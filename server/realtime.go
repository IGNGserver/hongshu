package main

import (
	"context"
	"crypto/ecdh"
	"encoding/base64"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"time"

	webpush "github.com/SherClockHolmes/webpush-go"
	"github.com/gorilla/websocket"
)

func (a *app) ws(w http.ResponseWriter, r *http.Request) {
	up := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" || r.Header.Get("Origin") == a.origin }, ReadBufferSize: 1024, WriteBufferSize: 1024}
	conn, e := up.Upgrade(w, r, nil)
	if e != nil {
		return
	}
	defer conn.Close()
	conn.SetReadLimit(1024)
	_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(70 * time.Second)) })
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	var observed int64 = -1
	var lastPing time.Time
	for {
		select {
		case <-done:
			return
		case <-r.Context().Done():
			return
		case <-ticker.C:
			d, err := a.lookup(r)
			if err != nil {
				return
			}
			var seq int64
			var clock int64
			if a.db.QueryRowContext(r.Context(), "SELECT seq FROM sync_clock WHERE id=1").Scan(&clock) != nil {
				return
			}
			if clock != observed && a.db.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(id),0) FROM messages WHERE device_id<>? AND id>?", d.ID, observed).Scan(&seq) != nil {
				return
			}
			_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if seq > 0 {
				if conn.WriteJSON(map[string]any{"type": "changed", "cursor": seq}) != nil {
					return
				}
			} else if time.Since(lastPing) >= 25*time.Second {
				if conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)) != nil {
					return
				}
				lastPing = time.Now()
			}
			observed = clock
		}
	}
}
func publicIP(ip net.IP) bool {
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() {
		return false
	}
	addr, ok := netip.AddrFromSlice(ip)
	if !ok {
		return false
	}
	addr = addr.Unmap()
	for _, block := range []string{"100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4", "2001:db8::/32", "64:ff9b::/96"} {
		if netip.MustParsePrefix(block).Contains(addr) {
			return false
		}
	}
	return true
}
func validateEndpoint(ctx context.Context, s string) error {
	u, e := url.Parse(s)
	if e != nil || u.Scheme != "https" || u.User != nil || u.Hostname() == "" || u.Fragment != "" || (u.Port() != "" && u.Port() != "443") || len(s) > 4096 {
		return errors.New("invalid push endpoint")
	}
	ips, e := net.DefaultResolver.LookupIP(ctx, "ip", u.Hostname())
	if e != nil || len(ips) == 0 {
		return errors.New("unresolvable push endpoint")
	}
	for _, ip := range ips {
		if !publicIP(ip) {
			return errors.New("nonpublic push endpoint")
		}
	}
	return nil
}
func pushHTTPClient() *http.Client {
	tr := &http.Transport{TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 15 * time.Second, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, e := net.SplitHostPort(address)
		if e != nil || port != "443" {
			return nil, errors.New("invalid push address")
		}
		ips, e := net.DefaultResolver.LookupIP(ctx, "ip", host)
		if e != nil || len(ips) == 0 {
			return nil, errors.New("push DNS failed")
		}
		for _, ip := range ips {
			if !publicIP(ip) {
				return nil, errors.New("nonpublic push IP")
			}
		}
		d := net.Dialer{Timeout: 10 * time.Second}
		return d.DialContext(ctx, network, net.JoinHostPort(ips[0].String(), port))
	}}
	return &http.Client{Transport: tr, Timeout: 25 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("push redirects forbidden") }}
}
func (a *app) subscribe(w http.ResponseWriter, r *http.Request) {
	if a.vapidPublic == "" || a.vapidPrivate == "" {
		fail(w, 503, "push_not_configured")
		return
	}
	var in webpush.Subscription
	if !decode(w, r, &in) {
		return
	}
	key, e := base64.RawURLEncoding.DecodeString(in.Keys.P256dh)
	auth, e2 := base64.RawURLEncoding.DecodeString(in.Keys.Auth)
	if e != nil || e2 != nil || len(auth) != 16 {
		fail(w, 400, "invalid_push_key")
		return
	}
	if _, e = ecdh.P256().NewPublicKey(key); e != nil {
		fail(w, 400, "invalid_push_key")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if validateEndpoint(ctx, in.Endpoint) != nil {
		fail(w, 400, "invalid_push_endpoint")
		return
	}
	// An endpoint belongs to one device; replacing it never transfers another identity.
	var other string
	e = a.db.QueryRowContext(r.Context(), "SELECT device_id FROM push_subscriptions WHERE endpoint=? AND device_id<>? LIMIT 1", in.Endpoint, device(r).ID).Scan(&other)
	if e == nil {
		fail(w, 409, "subscription_in_use")
		return
	}
	_, e = a.db.ExecContext(r.Context(), "INSERT INTO push_subscriptions VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE endpoint=?,p256dh=?,auth=?", device(r).ID, in.Endpoint, in.Keys.P256dh, in.Keys.Auth, in.Endpoint, in.Keys.P256dh, in.Keys.Auth)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *app) unsubscribe(w http.ResponseWriter, r *http.Request) {
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM push_jobs WHERE device_id=?", device(r).ID); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM push_subscriptions WHERE device_id=?", device(r).ID); e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *app) pushLoop(ctx context.Context) {
	if a.vapidPublic == "" || a.vapidPrivate == "" {
		return
	}
	client := pushHTTPClient()
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// One middlebox process supported. Jobs survive restarts, with at-least-once
			// notification semantics; stable SW tag coalesces retries.
			rows, e := a.db.QueryContext(ctx, "SELECT j.id,j.device_id,j.attempts,p.endpoint,p.p256dh,p.auth FROM push_jobs j JOIN push_subscriptions p ON p.device_id=j.device_id JOIN devices d ON d.id=j.device_id WHERE j.next_at<=? AND d.revoked=FALSE AND d.notify=TRUE ORDER BY j.id LIMIT 20", time.Now().UnixMilli())
			if e != nil {
				continue
			}
			type job struct {
				id       int64
				device   string
				attempts int
				sub      webpush.Subscription
			}
			jobs := []job{}
			for rows.Next() {
				var j job
				if rows.Scan(&j.id, &j.device, &j.attempts, &j.sub.Endpoint, &j.sub.Keys.P256dh, &j.sub.Keys.Auth) == nil {
					jobs = append(jobs, j)
				}
			}
			rows.Close()
			for _, j := range jobs {
				if ctx.Err() != nil {
					return
				}
				res, e := webpush.SendNotificationWithContext(ctx, []byte(`{"title":"鸿枢","body":"有新短信，打开应用查看","tag":"hongshu-new","url":"/"}`), &j.sub, &webpush.Options{HTTPClient: client, Subscriber: env("VAPID_SUBJECT", "mailto:admin@example.invalid"), VAPIDPublicKey: a.vapidPublic, VAPIDPrivateKey: a.vapidPrivate, TTL: 86400})
				status := 0
				if res != nil {
					status = res.StatusCode
					res.Body.Close()
				}
				if e == nil && status >= 200 && status < 300 {
					_, _ = a.db.ExecContext(ctx, "DELETE FROM push_jobs WHERE id=?", j.id)
				} else if status == 404 || status == 410 {
					_, _ = a.db.ExecContext(ctx, "DELETE FROM push_jobs WHERE device_id=?", j.device)
					_, _ = a.db.ExecContext(ctx, "DELETE FROM push_subscriptions WHERE device_id=? AND endpoint=?", j.device, j.sub.Endpoint)
				} else {
					shift := j.attempts
					if shift > 10 {
						shift = 10
					}
					delay := time.Duration(1<<shift) * 30 * time.Second
					if delay > 6*time.Hour {
						delay = 6 * time.Hour
					}
					_, _ = a.db.ExecContext(ctx, "UPDATE push_jobs SET attempts=LEAST(attempts+1,100),next_at=? WHERE id=?", time.Now().Add(delay).UnixMilli(), j.id)
				}
			}
		}
	}
}
