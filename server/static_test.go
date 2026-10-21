package main

import (
	"bytes"
	"encoding/json"
	"image"
	_ "image/png"
	"net/http/httptest"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

func TestManifestServedAsManifestJSON(t *testing.T) {
	a, e := newApp(nil, "https://sms.example.com", "1", "", "")
	if e != nil {
		t.Fatal(e)
	}
	w := httptest.NewRecorder()
	a.routes("../web").ServeHTTP(w, httptest.NewRequest("GET", "/manifest.webmanifest", nil))
	if w.Code != 200 {
		t.Fatalf("manifest returned %d", w.Code)
	}
	if got := w.Header().Get("Content-Type"); !strings.HasPrefix(got, "application/manifest+json") {
		t.Fatalf("manifest content type %q is not application/manifest+json", got)
	}
}

// macOS Safari builds the Dock/Launchpad icon from the manifest's opaque
// full-bleed maskable bitmap. A 512px ceiling produces the system monogram
// fallback on Retina displays, so a 1024px maskable icon must exist, and every
// declared icon must match its advertised dimensions.
func TestManifestIconsAreServedAtDeclaredSizes(t *testing.T) {
	raw, e := os.ReadFile("../web/manifest.webmanifest")
	if e != nil {
		t.Fatal(e)
	}
	var manifest struct {
		Icons []struct {
			Src     string `json:"src"`
			Sizes   string `json:"sizes"`
			Type    string `json:"type"`
			Purpose string `json:"purpose"`
		} `json:"icons"`
	}
	if e := json.Unmarshal(raw, &manifest); e != nil {
		t.Fatal(e)
	}
	if len(manifest.Icons) == 0 {
		t.Fatal("manifest has no icons")
	}
	a, e := newApp(nil, "https://sms.example.com", "1", "", "")
	if e != nil {
		t.Fatal(e)
	}
	routes := a.routes("../web")
	hasHighResolutionMaskable := false
	for _, icon := range manifest.Icons {
		if icon.Type != "image/png" {
			t.Errorf("%s has non-PNG type %q", icon.Src, icon.Type)
		}
		w := httptest.NewRecorder()
		routes.ServeHTTP(w, httptest.NewRequest("GET", icon.Src, nil))
		if w.Code != 200 {
			t.Errorf("%s returned %d", icon.Src, w.Code)
			continue
		}
		if got := w.Header().Get("Content-Type"); got != "image/png" {
			t.Errorf("%s content type %q is not image/png", icon.Src, got)
		}
		img, format, e := image.Decode(bytes.NewReader(w.Body.Bytes()))
		if e != nil || format != "png" {
			t.Errorf("%s is not a decodable PNG: %v", icon.Src, e)
			continue
		}
		widthText, heightText, ok := strings.Cut(icon.Sizes, "x")
		if !ok {
			t.Errorf("%s has invalid sizes %q", icon.Src, icon.Sizes)
			continue
		}
		width, widthErr := strconv.Atoi(widthText)
		height, heightErr := strconv.Atoi(heightText)
		if widthErr != nil || heightErr != nil {
			t.Errorf("%s has invalid sizes %q", icon.Src, icon.Sizes)
			continue
		}
		bounds := img.Bounds()
		if bounds.Dx() != width || bounds.Dy() != height {
			t.Errorf("%s is %dx%d but declares %s", icon.Src, bounds.Dx(), bounds.Dy(), icon.Sizes)
		}
		if icon.Purpose == "maskable" && width >= 1024 && height >= 1024 {
			hasHighResolutionMaskable = true
		}
	}
	if !hasHighResolutionMaskable {
		t.Fatal("manifest needs an opaque full-bleed maskable icon of at least 1024x1024 for macOS Safari web apps")
	}
	for _, legacy := range []string{"/favicon.ico", "/apple-touch-icon-precomposed.png"} {
		w := httptest.NewRecorder()
		routes.ServeHTTP(w, httptest.NewRequest("GET", legacy, nil))
		if w.Code != 200 {
			t.Errorf("Safari fallback %s returned %d", legacy, w.Code)
		}
	}
}

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
