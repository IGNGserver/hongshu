package main

import (
	"context"
	"crypto/ecdh"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	webpush "github.com/SherClockHolmes/webpush-go"
	"github.com/gorilla/websocket"
)

const (
	pushMessagePayload = `{"title":"鸿枢","body":"有新短信，打开应用查看","tag":"hongshu-new","url":"/"}`
	pushTestPayload    = `{"title":"鸿枢","body":"测试通知已送达","tag":"hongshu-test","url":"/"}`
)

func (a *app) ws(w http.ResponseWriter, r *http.Request) {
	up := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" || a.isHostAllowed(r) }, ReadBufferSize: 1024, WriteBufferSize: 1024}
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
	changeCh := make(chan struct{}, 1)
	a.subsMu.Lock()
	a.subscribers[changeCh] = struct{}{}
	a.subsMu.Unlock()
	defer func() {
		a.subsMu.Lock()
		delete(a.subscribers, changeCh)
		a.subsMu.Unlock()
	}()

	interval := a.wsCheckInterval
	if interval <= 0 {
		interval = 25 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	// Tests and small deployments may not have started the shared poller yet. Once-only
	// startup keeps one state query per process instead of one per socket.
	a.startStatePoller(context.Background())
	var observed hubState
	observedValid := false
	var lastPing time.Time

	checkChanges := func() bool {
		if _, err := a.lookup(r); err != nil {
			return false
		}
		state, ok := a.currentHubState()
		if !ok {
			// The shared poller is still warming up; a ticker/change event will retry.
			return true
		}
		// Include this device's own uploads. Other clients must see them; the source
		// device decides locally whether to notify itself.
		_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if !observedValid || state != observed {
			if conn.WriteJSON(map[string]any{"type": "changed", "cursor": state.cursor, "analysis_revision": state.analysis, "call_cursor": state.calls}) != nil {
				return false
			}
			observed = state
			observedValid = true
		}
		return true
	}

	for {
		select {
		case <-done:
			return
		case <-r.Context().Done():
			return
		case <-changeCh:
			if !checkChanges() {
				return
			}
		case <-ticker.C:
			if !checkChanges() {
				return
			}
			if time.Since(lastPing) >= 25*time.Second {
				if conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)) != nil {
					return
				}
				lastPing = time.Now()
			}
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
	for _, block := range []string{"100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4", "2001::/32", "2001:db8::/32", "64:ff9b::/96", "fec0::/10"} {
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

// Docker's default bridge has no IPv6 route even when the host does. Resolvers
// commonly return IPv6 first, so trying only ips[0] would silently fail APNs
// delivery on a working NAS. Prefer IPv4 and fall back to every other address.
func pushDialOrder(ips []net.IP) []net.IP {
	ordered := make([]net.IP, 0, len(ips))
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			ordered = append(ordered, v4)
		}
	}
	for _, ip := range ips {
		if ip.To4() == nil {
			ordered = append(ordered, ip)
		}
	}
	return ordered
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
		var lastErr error
		for _, ip := range pushDialOrder(ips) {
			conn, err := d.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
			if err == nil {
				return conn, nil
			}
			lastErr = err
		}
		if lastErr == nil {
			lastErr = errors.New("push DNS failed")
		}
		return nil, lastErr
	}}
	return &http.Client{Transport: tr, Timeout: 25 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("push redirects forbidden") }}
}

func (a *app) effectivePushClient() *http.Client {
	a.pushClientMu.Lock()
	defer a.pushClientMu.Unlock()
	if a.pushClient == nil {
		a.pushClient = pushHTTPClient()
	}
	return a.pushClient
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
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	// Authentication preceded decoding/DNS and may now be stale. Serialize
	// against logout's device -> subscription lock order before creating state.
	var revoked bool
	e = tx.QueryRowContext(r.Context(), "SELECT revoked FROM devices WHERE id=? FOR UPDATE", device(r).ID).Scan(&revoked)
	if e == sql.ErrNoRows || (e == nil && revoked) {
		fail(w, 401, "unauthorized")
		return
	}
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	var other string
	e = tx.QueryRowContext(r.Context(), "SELECT device_id FROM push_subscriptions WHERE endpoint=? AND device_id<>? LIMIT 1 FOR UPDATE", in.Endpoint, device(r).ID).Scan(&other)
	if e == nil {
		fail(w, 409, "subscription_in_use")
		return
	}
	if e != sql.ErrNoRows {
		fail(w, 503, "database_unavailable")
		return
	}
	_, e = tx.ExecContext(r.Context(), "INSERT INTO push_subscriptions VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE endpoint=?,p256dh=?,auth=?", device(r).ID, in.Endpoint, in.Keys.P256dh, in.Keys.Auth, in.Endpoint, in.Keys.P256dh, in.Keys.Auth)
	if e != nil || tx.Commit() != nil {
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

// testPush delivers one generic notification directly to the current browser.
// It deliberately bypasses push_jobs: a test must not depend on a real SMS and
// must not leave a queued alert if the browser is offline.
func (a *app) testPush(w http.ResponseWriter, r *http.Request) {
	if a.vapidPublic == "" || a.vapidPrivate == "" {
		fail(w, 503, "push_not_configured")
		return
	}
	var sub webpush.Subscription
	var notify bool
	err := a.db.QueryRowContext(r.Context(), "SELECT p.endpoint,p.p256dh,p.auth,d.notify FROM push_subscriptions p JOIN devices d ON d.id=p.device_id WHERE p.device_id=? AND d.revoked=FALSE", device(r).ID).Scan(&sub.Endpoint, &sub.Keys.P256dh, &sub.Keys.Auth, &notify)
	if err == sql.ErrNoRows {
		fail(w, 404, "push_not_subscribed")
		return
	}
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if !notify {
		fail(w, 409, "push_disabled")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	res, sendErr := webpush.SendNotificationWithContext(ctx, []byte(pushTestPayload), &sub, &webpush.Options{HTTPClient: a.effectivePushClient(), Subscriber: env("VAPID_SUBJECT", "mailto:admin@example.invalid"), VAPIDPublicKey: a.vapidPublic, VAPIDPrivateKey: a.vapidPrivate, TTL: 300})
	if sendErr != nil {
		if res != nil && res.Body != nil {
			res.Body.Close()
		}
		a.pushDeliveryFailures.Add(1)
		fail(w, 502, "push_delivery_failed")
		return
	}
	if res == nil {
		a.pushDeliveryFailures.Add(1)
		fail(w, 502, "push_delivery_failed")
		return
	}
	defer res.Body.Close()
	if res.StatusCode == 404 || res.StatusCode == 410 {
		if err = a.removeDeadPushSubscription(r.Context(), device(r).ID, sub); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		a.pushDeliveryFailures.Add(1)
		fail(w, 410, "push_subscription_expired")
		return
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		a.pushDeliveryFailures.Add(1)
		fail(w, 502, "push_delivery_failed")
		return
	}
	a.pushLastSuccess.Store(time.Now().UnixMilli())
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *app) removeDeadPushSubscription(ctx context.Context, id string, sub webpush.Subscription) error {
	// The response belongs to the subscription snapshot that was sent, not to a
	// replacement that may have been committed while the provider was thinking.
	// Lock and compare the complete key set before deleting either row type.
	tx, err := a.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var endpoint, p256dh, auth string
	err = tx.QueryRowContext(ctx, "SELECT endpoint,p256dh,auth FROM push_subscriptions WHERE device_id=? FOR UPDATE", id).Scan(&endpoint, &p256dh, &auth)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if endpoint != sub.Endpoint || p256dh != sub.Keys.P256dh || auth != sub.Keys.Auth {
		return nil
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM push_jobs WHERE device_id=?", id); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM push_subscriptions WHERE device_id=? AND endpoint=? AND p256dh=? AND auth=?", id, endpoint, p256dh, auth); err != nil {
		return err
	}
	return tx.Commit()
}

type claimedPush struct {
	id, lease, messageID int64
	device               string
	attempts             int
	sub                  webpush.Subscription
}

func (a *app) claimPushJobs(ctx context.Context, now time.Time) ([]claimedPush, error) {
	tx, err := a.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	// Claim immediately before delivery, rather than letting the tail of a
	// serial batch consume its entire lease while earlier requests run.
	rows, err := tx.QueryContext(ctx, "SELECT j.id,j.device_id,j.attempts,j.message_id,p.endpoint,p.p256dh,p.auth FROM push_jobs j JOIN push_subscriptions p ON p.device_id=j.device_id JOIN devices d ON d.id=j.device_id WHERE j.next_at<=? AND j.leased_until<=? AND d.revoked=FALSE AND d.notify=TRUE AND NOT EXISTS (SELECT 1 FROM push_jobs active WHERE active.device_id=j.device_id AND active.leased_until>?) ORDER BY j.id LIMIT 16 FOR UPDATE SKIP LOCKED", now.UnixMilli(), now.UnixMilli(), now.UnixMilli())
	if err != nil {
		return nil, err
	}
	jobs := []claimedPush{}
	seenDevices := make(map[string]struct{})
	for rows.Next() {
		j := claimedPush{lease: now.Add(60 * time.Second).UnixMilli()}
		if err = rows.Scan(&j.id, &j.device, &j.attempts, &j.messageID, &j.sub.Endpoint, &j.sub.Keys.P256dh, &j.sub.Keys.Auth); err != nil {
			rows.Close()
			return nil, err
		}
		if _, duplicate := seenDevices[j.device]; duplicate {
			continue
		}
		seenDevices[j.device] = struct{}{}
		jobs = append(jobs, j)
		if len(jobs) == 4 {
			break
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	for _, j := range jobs {
		if _, err = tx.ExecContext(ctx, "UPDATE push_jobs SET leased_until=? WHERE id=?", j.lease, j.id); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return jobs, nil
}

func pushRetryAfter(header http.Header, now time.Time) time.Duration {
	value := strings.TrimSpace(header.Get("Retry-After"))
	if value == "" {
		return 0
	}
	if seconds, err := strconv.Atoi(value); err == nil {
		if seconds > 0 {
			return time.Duration(seconds) * time.Second
		}
		return 0
	}
	if at, err := http.ParseTime(value); err == nil && at.After(now) {
		return at.Sub(now)
	}
	return 0
}

func retryablePushStatus(status int) bool {
	return status == 0 || status == 408 || status == 425 || status == 429 || status >= 500
}

func (a *app) deliverPushJob(ctx context.Context, client *http.Client, j claimedPush) error {
	leaseDeadline := time.UnixMilli(j.lease)
	if !time.Now().Before(leaseDeadline) {
		return nil
	}
	var active bool
	err := a.db.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM push_jobs j JOIN devices d ON d.id=j.device_id JOIN push_subscriptions p ON p.device_id=j.device_id WHERE j.id=? AND j.leased_until=? AND d.revoked=FALSE AND d.notify=TRUE AND p.endpoint=? AND p.p256dh=? AND p.auth=?)", j.id, j.lease, j.sub.Endpoint, j.sub.Keys.P256dh, j.sub.Keys.Auth).Scan(&active)
	if err != nil || !active || !time.Now().Before(leaseDeadline) {
		return err
	}
	// A message that was already read on any device must not produce a new alert.
	var read bool
	if err = a.db.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM messages m LEFT JOIN conversation_reads cr ON cr.sender=m.sender JOIN read_state rs ON rs.id=1 WHERE m.id=? AND (m.id<=rs.global_watermark_id OR m.id<=COALESCE(cr.last_read_message_id,0)))", j.messageID).Scan(&read); err != nil {
		return err
	}
	if read {
		if _, err = a.db.ExecContext(ctx, "DELETE FROM push_jobs WHERE id=? AND leased_until=?", j.id, j.lease); err == nil {
			a.pushSuppressed.Add(1)
		}
		return err
	}
	// Retries that exhausted the ceiling are noise, not a deliverable alert.
	if j.attempts >= 50 {
		if _, err = a.db.ExecContext(ctx, "DELETE FROM push_jobs WHERE id=? AND leased_until=?", j.id, j.lease); err == nil {
			a.pushDropped.Add(1)
		}
		return err
	}
	// Even if a DB stall consumed most of the lease, an HTTP request cannot
	// outlive it. Keep the parent context for the conditional state update.
	sendCtx, cancel := context.WithDeadline(ctx, leaseDeadline)
	defer cancel()
	res, sendErr := webpush.SendNotificationWithContext(sendCtx, []byte(pushMessagePayload), &j.sub, &webpush.Options{HTTPClient: client, Subscriber: env("VAPID_SUBJECT", "mailto:admin@example.invalid"), VAPIDPublicKey: a.vapidPublic, VAPIDPrivateKey: a.vapidPrivate, TTL: 86400})
	status := 0
	var retryAfter time.Duration
	if res != nil {
		status = res.StatusCode
		retryAfter = pushRetryAfter(res.Header, time.Now())
		// Drain only a bounded error body: enough for normal connection reuse without
		// letting an abusive provider turn a notification into an unbounded download.
		_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 32<<10))
		res.Body.Close()
	}
	if sendErr == nil && status >= 200 && status < 300 {
		_, err = a.db.ExecContext(ctx, "DELETE FROM push_jobs WHERE id=? AND leased_until=?", j.id, j.lease)
		if err == nil {
			a.pushLastSuccess.Store(time.Now().UnixMilli())
		}
		return err
	}
	a.pushDeliveryFailures.Add(1)
	if status == 404 || status == 410 {
		return a.removeDeadPushSubscription(ctx, j.device, j.sub)
	}
	if status >= 400 && status < 500 && !retryablePushStatus(status) {
		// Request/authentication failures will not heal by retrying the identical
		// ciphertext. Keep the message and subscription for diagnosis, drop only this
		// undeliverable job, and let later messages exercise repaired configuration.
		_, err = a.db.ExecContext(ctx, "DELETE FROM push_jobs WHERE id=? AND leased_until=?", j.id, j.lease)
		if err == nil {
			a.pushDropped.Add(1)
		}
		return err
	}
	if retryAfter <= 0 {
		shift := j.attempts
		if shift > 10 {
			shift = 10
		}
		retryAfter = time.Duration(1<<shift) * 30 * time.Second
	}
	if retryAfter > 6*time.Hour {
		retryAfter = 6 * time.Hour
	}
	_, err = a.db.ExecContext(ctx, "UPDATE push_jobs SET attempts=LEAST(attempts+1,100),next_at=?,leased_until=0 WHERE id=? AND leased_until=?", time.Now().Add(retryAfter).UnixMilli(), j.id, j.lease)
	return err
}

func (a *app) processPushBatch(ctx context.Context, client *http.Client, now time.Time) error {
	started := time.Now()
	a.pushMore.Store(false)
	processed := 0
	for processed < 20 {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		jobs, err := a.claimPushJobs(ctx, now.Add(time.Since(started)))
		if err != nil {
			a.pushClaimFailures.Add(1)
			return fmt.Errorf("push claim failed")
		}
		if len(jobs) == 0 {
			break
		}
		a.pushMore.Store(true)
		processed += len(jobs)
		var wg sync.WaitGroup
		errorsByJob := make([]error, len(jobs))
		for index, job := range jobs {
			wg.Add(1)
			go func() {
				defer wg.Done()
				errorsByJob[index] = a.deliverPushJob(ctx, client, job)
			}()
		}
		wg.Wait()
		for _, err = range errorsByJob {
			if err != nil {
				a.pushStateFailures.Add(1)
				return fmt.Errorf("push state update failed")
			}
		}
	}
	return nil
}

func (a *app) pushLoop(ctx context.Context) {
	if a.vapidPublic == "" || a.vapidPrivate == "" {
		return
	}
	client := a.effectivePushClient()
	for {
		if ctx.Err() != nil {
			return
		}
		err := a.processPushBatch(ctx, client, time.Now())
		a.pushLastLoop.Store(time.Now().UnixMilli())
		if err != nil && ctx.Err() == nil {
			failures := a.pushClaimFailures.Load() + a.pushStateFailures.Load()
			if failures == 1 || failures%20 == 0 {
				log.Print("push worker state failure; inspect healthz counters")
			}
			a.pushMore.Store(false)
			if !waitFor(ctx, a.pushWake, 5*time.Second) {
				return
			}
			continue
		}
		if a.pushMore.Load() {
			continue
		}
		if !waitFor(ctx, a.pushWake, 15*time.Second) {
			return
		}
	}
}
