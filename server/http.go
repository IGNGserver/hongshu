package main

import (
	"database/sql"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

func init() {
	// Go has no built-in .webmanifest type, and the distroless runtime has no
	// /etc/mime.types. Without this, ServeFile sniffs the JSON as text/plain,
	// which can make a Home Screen web app non-installable on iOS.
	_ = mime.AddExtensionType(".webmanifest", "application/manifest+json")
	// Safari probes the legacy favicon path even when <link rel="icon"> exists.
	_ = mime.AddExtensionType(".ico", "image/x-icon")
}

func (a *app) routes(web string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if a.db.PingContext(r.Context()) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		writeJSON(w, 200, map[string]any{"status": "ok", "push": map[string]any{"enabled": a.vapidPublic != "" && a.vapidPrivate != "", "claim_failures": a.pushClaimFailures.Load(), "state_failures": a.pushStateFailures.Load(), "delivery_failures": a.pushDeliveryFailures.Load(), "suppressed_read": a.pushSuppressed.Load(), "dropped": a.pushDropped.Load(), "last_success": a.pushLastSuccess.Load()}})
	})
	mux.HandleFunc("POST /api/login", a.loginHandler)
	mux.HandleFunc("POST /api/logout", a.auth(func(w http.ResponseWriter, r *http.Request) {
		a.revokeIdentity(w, r, device(r).ID, func() {
			http.SetCookie(w, &http.Cookie{Name: "hongshu", Path: "/", MaxAge: -1, HttpOnly: true, Secure: a.requestSecure(r), SameSite: http.SameSiteStrictMode})
		})
	}))
	mux.HandleFunc("GET /api/me", a.auth(a.me))
	mux.HandleFunc("GET /api/metadata", a.auth(a.metadata))
	mux.HandleFunc("GET /api/recent", a.auth(a.recent))
	mux.HandleFunc("GET /api/devices", a.auth(a.devices))
	mux.HandleFunc("PATCH /api/devices/{id}", a.auth(a.updateDevice))
	mux.HandleFunc("DELETE /api/devices/{id}", a.auth(a.revoke))
	mux.HandleFunc("GET /api/sims", a.auth(a.sims))
	mux.HandleFunc("PUT /api/sims", a.auth(a.saveSIM))
	mux.HandleFunc("PUT /api/contacts", a.auth(a.contact))
	mux.HandleFunc("GET /api/contacts", a.auth(a.contacts))
	mux.HandleFunc("POST /api/messages", a.auth(a.upload))
	mux.HandleFunc("GET /api/messages", a.auth(a.history))
	mux.HandleFunc("GET /api/analysis/settings", a.auth(a.analysisSettings))
	mux.HandleFunc("PUT /api/analysis/settings", a.auth(a.saveAISettings))
	mux.HandleFunc("POST /api/analysis/test", a.auth(a.testAI))
	mux.HandleFunc("POST /api/analysis/retry", a.auth(a.retryAnalysis))
	mux.HandleFunc("GET /api/analysis", a.auth(a.analysisSync))
	mux.HandleFunc("GET /api/analysis/counts", a.auth(a.analysisCounts))
	mux.HandleFunc("PATCH /api/messages/{id}/category", a.auth(a.changeCategory))
	mux.HandleFunc("POST /api/messages/read", a.auth(a.markRead))
	mux.HandleFunc("GET /api/read-state", a.auth(a.readState))
	mux.HandleFunc("GET /api/conversations", a.auth(a.conversations))
	mux.HandleFunc("GET /api/sync", a.auth(a.syncMessages))
	mux.HandleFunc("GET /api/ws", a.auth(a.ws))
	mux.HandleFunc("PUT /api/push", a.auth(a.subscribe))
	mux.HandleFunc("DELETE /api/push", a.auth(a.unsubscribe))
	mux.HandleFunc("POST /api/push/test", a.auth(a.testPush))
	mux.HandleFunc("GET /api/settings", a.auth(a.settings))
	mux.HandleFunc("PATCH /api/settings", a.auth(a.saveSettings))
	// Explicit allowlist: never expose package manifests, tests or source maps.
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		name := strings.TrimPrefix(r.URL.Path, "/")
		if name == "" {
			name = "index.html"
		}
		immutable := false
		if strings.HasPrefix(name, "assets/") {
			parts := immutableAsset.FindStringSubmatch(name)
			if len(parts) == 0 || !shellAsset(parts[1]) || parts[1] == "sw.js" || parts[1] == "index.html" {
				http.NotFound(w, r)
				return
			}
			immutable = true
		} else if !shellAsset(name) {
			http.NotFound(w, r)
			return
		}
		if _, e := os.Stat(filepath.Join(web, name)); e != nil {
			http.NotFound(w, r)
			return
		}
		if name == "sw.js" {
			w.Header().Set("Service-Worker-Allowed", "/")
		}
		w.Header().Set("Cache-Control", "no-cache")
		if immutable {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		w.Header().Set("Vary", "Accept-Encoding")
		if acceptsGzip(r.Header.Get("Accept-Encoding")) {
			if _, err := os.Stat(filepath.Join(web, name+".gz")); err == nil {
				w.Header().Set("Content-Type", mime.TypeByExtension(filepath.Ext(name)))
				w.Header().Set("Content-Encoding", "gzip")
				name += ".gz"
			}
		}
		http.ServeFile(w, r, filepath.Join(web, name))
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		if a.requestSecure(r) {
			w.Header().Set("Strict-Transport-Security", "max-age=31536000")
		}
		origin := r.Header.Get("Origin")
		_, cookieErr := r.Cookie("hongshu")
		if !a.isOriginAllowed(r, r.URL.Path == "/api/ws") {
			fail(w, 403, "origin_denied")
			return
		}
		if r.Method != "GET" && r.Method != "HEAD" && (cookieErr == nil || r.URL.Path == "/api/login") && origin == "" && r.Header.Get("Authorization") == "" {
			fail(w, 403, "origin_required")
			return
		}
		mux.ServeHTTP(w, r)
	})
}

var immutableAsset = regexp.MustCompile(`^assets/[a-f0-9]{16}-([^/]+)$`)

func acceptsGzip(header string) bool {
	for _, encoding := range strings.Split(header, ",") {
		parts := strings.Split(encoding, ";")
		if strings.TrimSpace(parts[0]) != "gzip" {
			continue
		}
		quality := 1.0
		for _, option := range parts[1:] {
			if key, value, ok := strings.Cut(strings.TrimSpace(option), "="); ok && key == "q" {
				var err error
				quality, err = strconv.ParseFloat(value, 64)
				if err != nil {
					quality = 0
				}
			}
		}
		if quality > 0 && quality <= 1 {
			return true
		}
	}
	return false
}

func shellAsset(name string) bool {
	switch name {
	case "index.html", "app.js", "local-contacts.js", "style.css", "sw.js", "manifest.webmanifest", "icon.svg",
		"icon-192.png", "icon-512.png", "icon-1024.png",
		"icon-maskable-192.png", "icon-maskable-512.png", "icon-maskable-1024.png",
		"apple-touch-icon.png", "apple-touch-icon-167.png", "apple-touch-icon-152.png", "apple-touch-icon-precomposed.png",
		"favicon-32.png", "favicon-16.png", "favicon.ico":
		return true
	}
	return false
}
func (a *app) me(w http.ResponseWriter, r *http.Request) {
	started := time.Now()
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var seq, epoch, metadataRevision int64
	var title string
	e := tx.QueryRowContext(r.Context(), "SELECT seq,epoch,title,revision FROM sync_clock JOIN settings ON settings.id=sync_clock.id JOIN metadata_clock ON metadata_clock.id=sync_clock.id WHERE sync_clock.id=1").Scan(&seq, &epoch, &title, &metadataRevision)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	s, err := loadAI(r.Context(), tx, false)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	watermark, unread, err := a.snapshotUnread(r.Context(), tx, epoch, seq)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	var pushSubscribed bool
	if err = tx.QueryRowContext(r.Context(), "SELECT EXISTS(SELECT 1 FROM push_subscriptions WHERE device_id=?)", device(r).ID).Scan(&pushSubscribed); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	serverTiming(w, started)
	writeJSON(w, 200, map[string]any{"device": device(r), "cursor": seq, "epoch": epoch, "title": title, "vapid_public_key": a.vapidPublic, "push_subscribed": pushSubscribed, "version": buildVersion, "ai_enabled": s.Enabled, "ai_paused": s.Paused, "analysis_revision": s.Revision, "metadata_revision": metadataRevision, "global_watermark_id": watermark, "unread_count": unread, "features": hubFeatures})
}
func timeDurationMS(d time.Duration) string {
	return strconv.FormatFloat(float64(d)/float64(time.Millisecond), 'f', 2, 64)
}
func (a *app) devices(w http.ResponseWriter, r *http.Request) {
	rows, e := a.db.QueryContext(r.Context(), "SELECT id,name,kind,admin,upload,notify,revoked,created_at FROM devices ORDER BY created_at")
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		var d Device
		if rows.Scan(&d.ID, &d.Name, &d.Kind, &d.Admin, &d.Upload, &d.Notify, &d.Revoked, &d.Created) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		out = append(out, d)
	}
	if rows.Err() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"devices": out})
}
func (a *app) updateDevice(w http.ResponseWriter, r *http.Request) {
	d := device(r)
	id := r.PathValue("id")
	if id != d.ID && !d.Admin {
		fail(w, 403, "forbidden")
		return
	}
	var in struct {
		Name   *string `json:"name"`
		Upload *bool   `json:"upload"`
		Notify *bool   `json:"notify"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Name != nil && !validName(*in.Name) {
		fail(w, 400, "invalid_name")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	_, e = tx.ExecContext(r.Context(), "UPDATE devices SET name=COALESCE(?,name),upload=IF(kind='android',COALESCE(?,upload),FALSE),notify=COALESCE(?,notify) WHERE id=? AND revoked=FALSE", in.Name, in.Upload, in.Notify, id)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if a.commitMetadata(r.Context(), tx) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *app) revoke(w http.ResponseWriter, r *http.Request) {
	if !device(r).Admin {
		fail(w, 403, "admin_required")
		return
	}
	a.revokeIdentity(w, r, r.PathValue("id"), nil)
}
func (a *app) revokeIdentity(w http.ResponseWriter, r *http.Request, id string, afterCommit func()) {
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), "UPDATE devices SET revoked=TRUE WHERE id=?", id); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM push_jobs WHERE device_id=?", id); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM push_subscriptions WHERE device_id=?", id); e != nil || a.commitMetadata(r.Context(), tx) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if afterCommit != nil {
		afterCommit()
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

type SIM struct {
	DeviceID       string `json:"device_id"`
	Phone          string `json:"phone"`
	Label          string `json:"label"`
	SubscriptionID int    `json:"subscription_id"`
}

func (a *app) sims(w http.ResponseWriter, r *http.Request) {
	rows, e := a.db.QueryContext(r.Context(), "SELECT device_id,phone,label,subscription_id FROM sims ORDER BY device_id,phone")
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	out := []SIM{}
	for rows.Next() {
		var s SIM
		if rows.Scan(&s.DeviceID, &s.Phone, &s.Label, &s.SubscriptionID) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		out = append(out, s)
	}
	if rows.Err() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"sims": out})
}
func (a *app) saveSIM(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone          string `json:"phone"`
		Label          string `json:"label"`
		SubscriptionID int    `json:"subscription_id"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !phonePattern.MatchString(in.Phone) || len(in.Label) > 100 {
		fail(w, 400, "invalid_sim")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO sims VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE label=?,subscription_id=?", device(r).ID, in.Phone, in.Label, in.SubscriptionID, in.Label, in.SubscriptionID); e != nil || a.commitMetadata(r.Context(), tx) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *app) contact(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone    string `json:"phone"`
		Name     string `json:"name"`
		Contacts []struct {
			Phone string `json:"phone"`
			Name  string `json:"name"`
		} `json:"contacts"`
	}
	if !decode(w, r, &in) {
		return
	}
	if len(in.Contacts) > 0 {
		if len(in.Contacts) > 500 {
			fail(w, 400, "invalid_batch")
			return
		}
		for _, c := range in.Contacts {
			if len(c.Phone) == 0 || len(c.Phone) > 100 || len(c.Name) > 100 {
				fail(w, 400, "invalid_contact")
				return
			}
		}
		tx, e := a.db.BeginTx(r.Context(), nil)
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		defer tx.Rollback()
		for _, c := range in.Contacts {
			if c.Name == "" {
				_, e = tx.ExecContext(r.Context(), "DELETE FROM contacts WHERE phone=?", c.Phone)
			} else {
				_, e = tx.ExecContext(r.Context(), "INSERT INTO contacts VALUES (?,?) ON DUPLICATE KEY UPDATE name=?", c.Phone, c.Name, c.Name)
			}
			if e != nil {
				fail(w, 503, "database_unavailable")
				return
			}
		}
		if a.commitMetadata(r.Context(), tx) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		writeJSON(w, 200, map[string]bool{"ok": true})
		return
	}
	if len(in.Phone) == 0 || len(in.Phone) > 100 || len(in.Name) > 100 {
		fail(w, 400, "invalid_contact")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	if in.Name == "" {
		_, e = tx.ExecContext(r.Context(), "DELETE FROM contacts WHERE phone=?", in.Phone)
	} else {
		_, e = tx.ExecContext(r.Context(), "INSERT INTO contacts VALUES (?,?) ON DUPLICATE KEY UPDATE name=?", in.Phone, in.Name, in.Name)
	}
	if e != nil || a.commitMetadata(r.Context(), tx) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *app) contacts(w http.ResponseWriter, r *http.Request) {
	rows, e := a.db.QueryContext(r.Context(), "SELECT phone,name FROM contacts ORDER BY phone")
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var phone, name string
		if rows.Scan(&phone, &name) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		out[phone] = name
	}
	if rows.Err() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"contacts": out})
}
func (a *app) settings(w http.ResponseWriter, r *http.Request) {
	var title string
	if a.db.QueryRowContext(r.Context(), "SELECT title FROM settings WHERE id=1").Scan(&title) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"title": title, "retention": "forever", "server_time": time.Now().UnixMilli()})
}
func (a *app) saveSettings(w http.ResponseWriter, r *http.Request) {
	if !device(r).Admin {
		fail(w, 403, "admin_required")
		return
	}
	var in struct {
		Title string `json:"title"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validName(in.Title) {
		fail(w, 400, "invalid_title")
		return
	}
	if _, e := a.db.ExecContext(r.Context(), "UPDATE settings SET title=? WHERE id=1", in.Title); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *app) isOriginAllowed(r *http.Request, isWS bool) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	if !isWS && strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") {
		return true
	}
	return a.isHostAllowed(r)
}

func (a *app) isHostAllowed(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	if a.origin != "" && strings.EqualFold(origin, a.origin) {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" {
		return false
	}
	hosts := []string{r.Host, r.URL.Host}
	// A forwarded host is only meaningful behind a trusted edge; anyone else can set the
	// header themselves and would otherwise bypass the same-origin check.
	if a.isTrustedProxy(r) {
		hosts = append([]string{r.Header.Get("X-Forwarded-Host")}, hosts...)
	}
	for _, h := range hosts {
		if h == "" {
			continue
		}
		hostPart := strings.TrimSpace(strings.Split(h, ",")[0])
		if hostPart != "" && strings.EqualFold(u.Host, hostPart) {
			return true
		}
	}
	return false
}

func (a *app) isTrustedProxy(r *http.Request) bool {
	if len(a.trustedProxies) == 0 {
		return false
	}
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		ip = r.RemoteAddr
	}
	peer, err := netip.ParseAddr(ip)
	if err != nil {
		return false
	}
	for _, prefix := range a.trustedProxies {
		if prefix.Contains(peer.Unmap()) {
			return true
		}
	}
	return false
}
