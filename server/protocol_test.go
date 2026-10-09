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
	r := httptest.NewRequest("POST", "/api/pair", strings.NewReader(`{}`))
	r.Header.Set("Origin", "https://evil.example")
	w = httptest.NewRecorder()
	a.routes("../web").ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("cross-origin mutation allowed")
	}
	if _, e = newApp(nil, "http://sms.example.com", "", "", ""); e == nil {
		t.Fatal("public cleartext allowed")
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
