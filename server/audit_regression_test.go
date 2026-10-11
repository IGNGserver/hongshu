package main

import (
	"context"
	"database/sql"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAuthenticationStoreFailurePreservesIdentityContract(t *testing.T) {
	db, err := sql.Open("mysql", "synthetic@tcp(127.0.0.1:1)/synthetic")
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	a, _ := newApp(db, "http://localhost:8080", "synthetic", "", "")
	if err := a.processPushBatch(context.Background(), nil, time.Now()); err == nil || a.pushClaimFailures.Load() != 1 {
		t.Fatal("worker claim error is not observable")
	}
	for _, test := range []struct {
		token string
		want  int
	}{{strings.Repeat("0", 64), 503}, {"short", 401}} {
		r := httptest.NewRequest("GET", "/api/me", nil)
		r.Header.Set("Authorization", "Bearer "+test.token)
		w := httptest.NewRecorder()
		a.auth(func(http.ResponseWriter, *http.Request) { t.Fatal("invalid authentication succeeded") })(w, r)
		if w.Code != test.want {
			t.Fatalf("got %d want %d", w.Code, test.want)
		}
	}
}

func TestHTTPSOriginCannotDowngradeCookie(t *testing.T) {
	a, _ := newApp(nil, "http://127.0.0.1:18473", "synthetic", "", "")
	r := httptest.NewRequest("POST", "http://example.test/api/login", nil)
	r.Header.Set("Origin", "https://example.test")
	if !a.isOriginAllowed(r, false) {
		t.Fatal("adaptive host rejected")
	}
	w := httptest.NewRecorder()
	a.cookieForRequest(w, r, strings.Repeat("0", 64))
	if !w.Result().Cookies()[0].Secure {
		t.Fatal("HTTPS cookie downgraded")
	}
}

func TestTrustedProxyRateLimitAndUntrustedSpoofing(t *testing.T) {
	a, _ := newApp(nil, "http://localhost:8080", "synthetic", "", "")
	if err := a.configureTrustedProxies("127.0.0.1/32"); err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", "/api/login", nil)
	r.RemoteAddr = "127.0.0.1:10000"
	r.Header.Set("X-Forwarded-For", "192.0.2.1")
	for i := 0; i < 10; i++ {
		if !a.allowed(r) {
			t.Fatal("early rate limit")
		}
	}
	if a.allowed(r) {
		t.Fatal("rate limit missing")
	}
	r.Header.Set("X-Forwarded-For", "192.0.2.2")
	if !a.allowed(r) {
		t.Fatal("distinct trusted clients share bucket")
	}
	r.RemoteAddr = "192.0.2.3:10000"
	if a.clientAddress(r) != "192.0.2.3" {
		t.Fatal("untrusted forwarding header accepted")
	}
	r.RemoteAddr = "127.0.0.1:10000"
	r.Header.Set("X-Forwarded-For", "192.0.2.1, 192.0.2.2")
	if a.clientAddress(r) != "127.0.0.1" {
		t.Fatal("appended forwarding chain accepted")
	}
}

func TestLoginHashAdmissionIsBounded(t *testing.T) {
	a, _ := newApp(nil, "http://localhost:8080", "synthetic", "", "")
	for i := 0; i < cap(a.passwordSlots); i++ {
		a.passwordSlots <- struct{}{}
	}
	r := httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"wrong"}`))
	w := httptest.NewRecorder()
	a.loginHandler(w, r)
	if w.Code != 503 || !strings.Contains(w.Body.String(), "authentication_busy") {
		t.Fatal("hash admission was not bounded")
	}
}

func TestPushRejectsSpecialPurposeAddresses(t *testing.T) {
	for _, s := range []string{"fec0::1", "192.88.99.1", "2001::1"} {
		if publicIP(net.ParseIP(s)) {
			t.Fatalf("special-purpose IP accepted: %s", s)
		}
	}
}
