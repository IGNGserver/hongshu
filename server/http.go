package main

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func (a *app) routes(web string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if a.db.PingContext(r.Context()) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /api/bootstrap", a.bootstrapHandler)
	mux.HandleFunc("POST /api/pair", a.pair)
	mux.HandleFunc("POST /api/logout", a.auth(func(w http.ResponseWriter, r *http.Request) {
		a.revokeIdentity(w, r, device(r).ID, func() {
			http.SetCookie(w, &http.Cookie{Name: "hongshu", Path: "/", MaxAge: -1, HttpOnly: true, Secure: a.secure, SameSite: http.SameSiteStrictMode})
		})
	}))
	mux.HandleFunc("GET /api/me", a.auth(a.me))
	mux.HandleFunc("GET /api/devices", a.auth(a.devices))
	mux.HandleFunc("PATCH /api/devices/{id}", a.auth(a.updateDevice))
	mux.HandleFunc("DELETE /api/devices/{id}", a.auth(a.revoke))
	mux.HandleFunc("POST /api/pairings", a.auth(a.pairing))
	mux.HandleFunc("GET /api/sims", a.auth(a.sims))
	mux.HandleFunc("PUT /api/sims", a.auth(a.saveSIM))
	mux.HandleFunc("PUT /api/contacts", a.auth(a.contact))
	mux.HandleFunc("GET /api/contacts", a.auth(a.contacts))
	mux.HandleFunc("POST /api/messages", a.auth(a.upload))
	mux.HandleFunc("GET /api/messages", a.auth(a.history))
	mux.HandleFunc("GET /api/conversations", a.auth(a.conversations))
	mux.HandleFunc("GET /api/sync", a.auth(a.syncMessages))
	mux.HandleFunc("GET /api/ws", a.auth(a.ws))
	mux.HandleFunc("PUT /api/push", a.auth(a.subscribe))
	mux.HandleFunc("DELETE /api/push", a.auth(a.unsubscribe))
	mux.HandleFunc("GET /api/settings", a.auth(a.settings))
	mux.HandleFunc("PATCH /api/settings", a.auth(a.saveSettings))
	// Explicit allowlist: never expose package manifests, tests or source maps.
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		name := strings.TrimPrefix(r.URL.Path, "/")
		if name == "" {
			name = "index.html"
		}
		switch name {
		case "index.html", "app.js", "style.css", "sw.js", "manifest.webmanifest", "icon.svg", "icon-192.png", "icon-512.png":
		default:
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
		http.ServeFile(w, r, filepath.Join(web, name))
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		if a.secure {
			w.Header().Set("Strict-Transport-Security", "max-age=31536000")
		}
		origin := r.Header.Get("Origin")
		_, cookieErr := r.Cookie("hongshu")
		if origin != "" && origin != a.origin {
			fail(w, 403, "origin_denied")
			return
		}
		if r.Method != "GET" && r.Method != "HEAD" && (cookieErr == nil || r.URL.Path == "/api/bootstrap" || r.URL.Path == "/api/pair") && origin == "" && r.Header.Get("Authorization") == "" {
			fail(w, 403, "origin_required")
			return
		}
		mux.ServeHTTP(w, r)
	})
}
func (a *app) me(w http.ResponseWriter, r *http.Request) {
	var seq int64
	var title string
	e := a.db.QueryRowContext(r.Context(), "SELECT seq,title FROM sync_clock JOIN settings ON settings.id=sync_clock.id WHERE sync_clock.id=1").Scan(&seq, &title)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"device": device(r), "cursor": seq, "title": title, "vapid_public_key": a.vapidPublic, "version": buildVersion})
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
	res, e := a.db.ExecContext(r.Context(), "UPDATE devices SET name=COALESCE(?,name),upload=IF(kind='android',COALESCE(?,upload),FALSE),notify=COALESCE(?,notify) WHERE id=? AND revoked=FALSE", in.Name, in.Upload, in.Notify, id)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	n, _ := res.RowsAffected()
	_ = n
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
	// Lock all admin rows to serialize last-admin protection.
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	rows, e := tx.QueryContext(r.Context(), "SELECT id FROM devices WHERE admin=TRUE AND revoked=FALSE ORDER BY id FOR UPDATE")
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	admins := []string{}
	for rows.Next() {
		var s string
		if rows.Scan(&s) != nil {
			rows.Close()
			fail(w, 503, "database_unavailable")
			return
		}
		admins = append(admins, s)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if len(admins) == 1 && admins[0] == id {
		fail(w, 409, "last_admin")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE devices SET revoked=TRUE WHERE id=?", id); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM push_jobs WHERE device_id=?", id); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM push_subscriptions WHERE device_id=?", id); e != nil || tx.Commit() != nil {
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
	if _, e = tx.ExecContext(r.Context(), "INSERT INTO sims VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE label=?,subscription_id=?", device(r).ID, in.Phone, in.Label, in.SubscriptionID, in.Label, in.SubscriptionID); e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *app) contact(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone string `json:"phone"`
		Name  string `json:"name"`
	}
	if !decode(w, r, &in) {
		return
	}
	if len(in.Phone) == 0 || len(in.Phone) > 100 || len(in.Name) > 100 {
		fail(w, 400, "invalid_contact")
		return
	}
	var e error
	if in.Name == "" {
		_, e = a.db.ExecContext(r.Context(), "DELETE FROM contacts WHERE phone=?", in.Phone)
	} else {
		_, e = a.db.ExecContext(r.Context(), "INSERT INTO contacts VALUES (?,?) ON DUPLICATE KEY UPDATE name=?", in.Phone, in.Name, in.Name)
	}
	if e != nil {
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
