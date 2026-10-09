package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

type app struct {
	db                                           *sql.DB
	origin, bootstrap, vapidPublic, vapidPrivate string
	secure                                       bool
	limitMu                                      sync.Mutex
	limits                                       map[string]*attempt
	subsMu                                       sync.Mutex
	subscribers                                  map[chan struct{}]struct{}
}
type attempt struct {
	count int
	until time.Time
}
type Device struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Kind    string `json:"kind"`
	Admin   bool   `json:"admin"`
	Upload  bool   `json:"upload"`
	Notify  bool   `json:"notify"`
	Revoked bool   `json:"revoked"`
	Created int64  `json:"created_at"`
}
type deviceKey struct{}

func hash(s string) string { b := sha256.Sum256([]byte(s)); return hex.EncodeToString(b[:]) }
func random(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
func newApp(db *sql.DB, origin, secret, pub, priv string) (*app, error) {
	u, e := url.Parse(origin)
	if e != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("PUBLIC_URL must be an origin")
	}
	if u.Scheme == "http" && u.Hostname() != "localhost" && u.Hostname() != "127.0.0.1" {
		return nil, errors.New("HTTPS required except localhost")
	}
	return &app{db: db, origin: strings.TrimRight(origin, "/"), bootstrap: secret, vapidPublic: pub, vapidPrivate: priv, secure: u.Scheme == "https", limits: map[string]*attempt{}, subscribers: make(map[chan struct{}]struct{})}, nil
}
func fail(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if d.Decode(v) != nil {
		fail(w, 400, "invalid_json")
		return false
	}
	if d.Decode(&struct{}{}) != io.EOF {
		fail(w, 400, "invalid_json")
		return false
	}
	return true
}
func device(r *http.Request) Device { return r.Context().Value(deviceKey{}).(Device) }
func (a *app) lookup(r *http.Request) (Device, error) {
	token := ""
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		token = strings.TrimPrefix(h, "Bearer ")
	} else if c, e := r.Cookie("hongshu"); e == nil {
		token = c.Value
	}
	var d Device
	if len(token) != 64 {
		return d, errors.New("missing credential")
	}
	err := a.db.QueryRowContext(r.Context(), "SELECT id,name,kind,admin,upload,notify,revoked,created_at FROM devices WHERE token_hash=? AND revoked=FALSE", hash(token)).Scan(&d.ID, &d.Name, &d.Kind, &d.Admin, &d.Upload, &d.Notify, &d.Revoked, &d.Created)
	return d, err
}
func (a *app) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		d, e := a.lookup(r)
		if e != nil {
			fail(w, 401, "unauthorized")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), deviceKey{}, d)))
	}
}
func (a *app) cookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{Name: "hongshu", Value: token, Path: "/", HttpOnly: true, Secure: a.secure, SameSite: http.SameSiteStrictMode, MaxAge: 365 * 24 * 3600})
}
func (a *app) broadcastChange() {
	a.subsMu.Lock()
	defer a.subsMu.Unlock()
	for ch := range a.subscribers {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}
func (a *app) allowed(r *http.Request) bool {
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	now := time.Now()
	a.limitMu.Lock()
	defer a.limitMu.Unlock()
	for k, v := range a.limits {
		if now.After(v.until) {
			delete(a.limits, k)
		}
	}
	if len(a.limits) > 10000 {
		return false
	}
	v := a.limits[ip]
	if v == nil {
		v = &attempt{until: now.Add(time.Minute)}
		a.limits[ip] = v
	}
	v.count++
	return v.count <= 10
}
func insertDevice(ctx context.Context, tx *sql.Tx, name, kind string, admin bool) (Device, string, error) {
	d := Device{ID: random(16), Name: name, Kind: kind, Admin: admin, Notify: true, Created: time.Now().UnixMilli()}
	token := random(32)
	_, e := tx.ExecContext(ctx, "INSERT INTO devices (id,name,kind,token_hash,admin,notify,created_at) VALUES (?,?,?,?,?,TRUE,?)", d.ID, d.Name, d.Kind, hash(token), admin, d.Created)
	return d, token, e
}
func validName(s string) bool {
	return len(strings.TrimSpace(s)) > 0 && len(s) <= 100 && !strings.ContainsRune(s, 0)
}
func (a *app) bootstrapHandler(w http.ResponseWriter, r *http.Request) {
	if !a.allowed(r) {
		fail(w, 429, "rate_limited")
		return
	}
	var in struct {
		Secret string `json:"secret"`
		Name   string `json:"name"`
	}
	if !decode(w, r, &in) {
		return
	}
	if subtle.ConstantTimeCompare([]byte(hash(in.Secret)), []byte(hash(a.bootstrap))) != 1 {
		fail(w, 403, "invalid_secret")
		return
	}
	if !validName(in.Name) {
		fail(w, 400, "invalid_name")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var seq int64
	if e = tx.QueryRowContext(r.Context(), "SELECT seq FROM sync_clock WHERE id=1 FOR UPDATE").Scan(&seq); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	var n int
	if e = tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM devices").Scan(&n); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if n > 0 {
		fail(w, 409, "already_initialized")
		return
	}
	d, token, e := insertDevice(r.Context(), tx, in.Name, "web", true)
	if e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	a.cookie(w, token)
	writeJSON(w, 201, d)
}
func (a *app) pairing(w http.ResponseWriter, r *http.Request) {
	if !device(r).Admin {
		fail(w, 403, "admin_required")
		return
	}
	var in struct {
		Admin bool `json:"admin"`
	}
	if !decode(w, r, &in) {
		return
	}
	code := random(16)
	expires := time.Now().Add(10 * time.Minute).UnixMilli()
	_, e := a.db.ExecContext(r.Context(), "INSERT INTO pairings VALUES (?,?,?)", hash(code), expires, in.Admin)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 201, map[string]any{"code": code, "expires_at": expires})
}
func (a *app) pair(w http.ResponseWriter, r *http.Request) {
	if !a.allowed(r) {
		fail(w, 429, "rate_limited")
		return
	}
	var in struct {
		Code string `json:"code"`
		Name string `json:"name"`
		Kind string `json:"kind"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validName(in.Name) || (in.Kind != "web" && in.Kind != "android") {
		fail(w, 400, "invalid_device")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var admin bool
	e = tx.QueryRowContext(r.Context(), "SELECT admin FROM pairings WHERE code_hash=? AND expires_at>? FOR UPDATE", hash(in.Code), time.Now().UnixMilli()).Scan(&admin)
	if e == sql.ErrNoRows {
		fail(w, 403, "invalid_pairing")
		return
	}
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if admin && in.Kind != "web" {
		fail(w, 400, "admin_pairing_requires_web")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "DELETE FROM pairings WHERE code_hash=?", hash(in.Code)); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	d, token, e := insertDevice(r.Context(), tx, in.Name, in.Kind, admin)
	if e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if in.Kind == "web" {
		a.cookie(w, token)
		writeJSON(w, 201, map[string]any{"device": d})
	} else {
		writeJSON(w, 201, map[string]any{"device": d, "token": token})
	}
}
