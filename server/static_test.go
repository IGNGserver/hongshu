package main

import (
	"net/http/httptest"
	"os"
	"regexp"
	"strings"
	"testing"
)

// Every file the service worker precaches must be served, or cache.addAll fails and the
// offline shell never installs. Files outside the allowlist must stay hidden.
func TestShellAssetsServed(t *testing.T) {
	worker, e := os.ReadFile("../web/sw.js")
	if e != nil {
		t.Fatal(e)
	}
	assets := regexp.MustCompile(`"(/[^"]*)"`).FindAllStringSubmatch(string(regexp.MustCompile(`(?s)const ASSETS = \[(.*?)\];`).FindSubmatch(worker)[1]), -1)
	if len(assets) == 0 {
		t.Fatal("no precached assets found in sw.js")
	}
	a, e := newApp(nil, "https://sms.example.com", "1", "", "")
	if e != nil {
		t.Fatal(e)
	}
	routes := a.routes("../web")
	for _, asset := range assets {
		w := httptest.NewRecorder()
		routes.ServeHTTP(w, httptest.NewRequest("GET", asset[1], nil))
		if w.Code != 200 {
			t.Errorf("precached %s returned %d", asset[1], w.Code)
		}
		policy := w.Header().Get("Content-Security-Policy")
		if !strings.Contains(policy, "img-src 'self' data: blob:;") || !strings.Contains(policy, "connect-src 'self';") || !strings.Contains(policy, "script-src 'self';") {
			t.Errorf("local avatars must allow blob images without relaxing script/connect policy: %s", policy)
		}
	}
	for _, hidden := range []string{"/package.json", "/build.mjs", "/test/display.test.js"} {
		w := httptest.NewRecorder()
		routes.ServeHTTP(w, httptest.NewRequest("GET", hidden, nil))
		if w.Code != 404 {
			t.Errorf("%s exposed with %d", hidden, w.Code)
		}
	}
}
