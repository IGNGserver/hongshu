package main

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestFingerprint(t *testing.T) {
	m := Incoming{Receiver: "+8613800000000", Sender: "10086", Body: "测试", Timestamp: 1700000000000, SubscriptionID: 1}
	if fingerprint(m) != hash("+8613800000000\x0010086\x001700000000000\x00测试") {
		t.Fatal("canonical hash changed")
	}
	if fingerprint(m) != "40315f7bdb01179c52c2db83ef04c8ec9f3957d384188ff8901f4115252fa41a" {
		t.Fatal("cross-client canonical vector mismatch")
	}
	b := m
	b.SubscriptionID = 2
	if fingerprint(b) != fingerprint(m) {
		t.Fatal("subscription IDs are not stable business IDs")
	}
	b.Receiver = "+8613900000000"
	if fingerprint(b) == fingerprint(m) {
		t.Fatal("receiver missing from dedup")
	}
}
func TestValidation(t *testing.T) {
	m := Incoming{Receiver: "+123456789", Sender: "10086", Body: "hello", Timestamp: time.Now().UnixMilli()}
	if !validMessage(m) {
		t.Fatal("valid message rejected")
	}
	for _, bad := range []Incoming{{Receiver: "SIM1"}, {Receiver: "+123456789", Sender: "bad\x00sender", Body: "x", Timestamp: m.Timestamp}, {Receiver: "+123456789", Sender: "a", Body: strings.Repeat("a", 64001), Timestamp: m.Timestamp}} {
		if validMessage(bad) {
			t.Fatal("bad message accepted")
		}
	}
}
func TestSSRF(t *testing.T) {
	for _, s := range []string{"127.0.0.1", "::1", "10.0.0.1", "192.168.5.17", "169.254.169.254", "100.64.0.1", "0.0.0.0"} {
		if publicIP(net.ParseIP(s)) {
			t.Errorf("nonpublic %s allowed", s)
		}
	}
	for _, s := range []string{"http://push.example/", "https://127.0.0.1/", "https://[::1]/", "https://user:pass@example.com/", "https://example.com:8080/"} {
		if validateEndpoint(context.Background(), s) == nil {
			t.Errorf("SSRF endpoint accepted: %s", s)
		}
	}
}
func TestOriginAndCookie(t *testing.T) {
	a, e := newApp(nil, "https://sms.example.com", strings.Repeat("x", 32), "", "")
	if e != nil {
		t.Fatal(e)
	}
	w := httptest.NewRecorder()
	a.cookie(w, strings.Repeat("a", 64))
	c := w.Result().Cookies()[0]
	if !c.Secure || !c.HttpOnly || c.SameSite != http.SameSiteStrictMode {
		t.Fatal("insecure browser credential")
	}
	insecure, e := newApp(nil, "http://sms.example.com:8080", "1", "", "")
	if e != nil {
		t.Fatal("public HTTP origin rejected", e)
	}
	w = httptest.NewRecorder()
	insecure.cookie(w, strings.Repeat("a", 64))
	c = w.Result().Cookies()[0]
	if c.Secure || !c.HttpOnly || c.SameSite != http.SameSiteStrictMode {
		t.Fatal("HTTP session cookie flags changed unexpectedly")
	}
	r := httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"1"}`))
	r.Header.Set("Origin", "https://evil.example")
	w = httptest.NewRecorder()
	a.routes("../web").ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("cross-origin mutation allowed")
	}
	if !insecure.passwordMatches("1") || insecure.passwordMatches("2") {
		t.Fatal("weak configured password was not verified correctly")
	}
	if _, e = newApp(nil, "http://sms.example.com", "", "", ""); e == nil {
		t.Fatal("empty password accepted")
	}
	if _, e = newApp(nil, "http://sms.example.com", strings.Repeat("x", 1025), "", ""); e == nil {
		t.Fatal("oversized password accepted")
	}
	if _, e = newApp(nil, "http://:8080", "1", "", ""); e == nil {
		t.Fatal("origin without a hostname accepted")
	}
	r = httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"1"}`))
	w = httptest.NewRecorder()
	insecure.routes("../web").ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatal("login without Origin was allowed")
	}
	r = httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"wrong"}`))
	r.Header.Set("Origin", insecure.origin)
	w = httptest.NewRecorder()
	insecure.routes("../web").ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized || !strings.Contains(w.Body.String(), "invalid_password") {
		t.Fatal("invalid password was not rejected")
	}
	r = httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"username":"ignored","password":"1"}`))
	r.Header.Set("Origin", insecure.origin)
	w = httptest.NewRecorder()
	insecure.routes("../web").ServeHTTP(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatal("login unexpectedly accepted a username field")
	}

	// Dynamic host / LAN origin matching when server origin is 127.0.0.1
	lanServer, err := newApp(nil, "http://127.0.0.1:18473", "1", "", "")
	if err != nil {
		t.Fatal(err)
	}
	r = httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"wrong"}`))
	r.Host = "192.168.5.17:18473"
	r.Header.Set("Origin", "http://192.168.5.17:18473")
	w = httptest.NewRecorder()
	lanServer.routes("../web").ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized || !strings.Contains(w.Body.String(), "invalid_password") {
		t.Fatalf("LAN host origin matching failed: got %d %s", w.Code, w.Body.String())
	}

	// Reverse proxy with X-Forwarded-Host
	r = httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"wrong"}`))
	r.Host = "127.0.0.1:18473"
	r.Header.Set("X-Forwarded-Host", "sms.example.com")
	r.Header.Set("Origin", "https://sms.example.com")
	w = httptest.NewRecorder()
	lanServer.routes("../web").ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized || !strings.Contains(w.Body.String(), "invalid_password") {
		t.Fatalf("X-Forwarded-Host origin matching failed: got %d %s", w.Code, w.Body.String())
	}

	// Bearer token request bypasses Origin check
	r = httptest.NewRequest("POST", "/api/messages", strings.NewReader(`{}`))
	r.Host = "192.168.5.17:18473"
	r.Header.Set("Authorization", "Bearer " + strings.Repeat("a", 64))
	r.Header.Set("Origin", "https://evil.example")
	w = httptest.NewRecorder()
	lanServer.routes("../web").ServeHTTP(w, r)
	// Should fail with 401 (unauthorized because token not in DB) or 503 (database_unavailable), NOT 403 origin_denied
	if w.Code == http.StatusForbidden && strings.Contains(w.Body.String(), "origin_denied") {
		t.Fatal("Bearer token request was blocked by Origin check")
	}
}
func TestSplitSQLKeepsSemicolonInsideStrings(t *testing.T) {
	parts := splitSQL("INSERT INTO settings VALUES (1, 'a;b');\n-- comment; ignored\nUPDATE settings SET title='c' WHERE id=1;")
	if len(parts) != 2 || parts[0] != "INSERT INTO settings VALUES (1, 'a;b')" || parts[1] != "UPDATE settings SET title='c' WHERE id=1" {
		t.Fatalf("sql split changed statement boundaries: %#v", parts)
	}
}
func TestDecode(t *testing.T) {
	for _, body := range []string{`{"extra":true}`, `{} {}`, strings.Repeat("x", 1<<20+1)} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("POST", "/", strings.NewReader(body))
		if decode(w, r, &struct{}{}) {
			t.Fatal("invalid body accepted")
		}
	}
}
func TestBatchContacts(t *testing.T) {
	var in struct {
		Phone    string `json:"phone"`
		Name     string `json:"name"`
		Contacts []struct {
			Phone string `json:"phone"`
			Name  string `json:"name"`
		} `json:"contacts"`
	}
	body := `{"contacts":[{"phone":"+8613800000000","name":"Alice"},{"phone":"+8613900000000","name":"Bob"}]}`
	r := httptest.NewRequest("PUT", "/api/contacts", strings.NewReader(body))
	w := httptest.NewRecorder()
	if !decode(w, r, &in) {
		t.Fatal("decode failed")
	}
	if len(in.Contacts) != 2 || in.Contacts[0].Name != "Alice" {
		t.Fatal("batch contacts parsing failed")
	}
}
