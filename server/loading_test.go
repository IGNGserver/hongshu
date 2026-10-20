package main

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestLoadingValidationBeforeDatabase(t *testing.T) {
	a := &app{}
	for _, path := range []string{"/api/recent?limit=0", "/api/recent?limit=201", "/api/recent?limit=x", "/api/metadata?since=-1"} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("GET", path, nil)
		if path[:11] == "/api/recent" {
			a.recent(w, r)
		} else {
			a.metadata(w, r)
		}
		if w.Code != 400 {
			t.Fatalf("invalid loading request status %d", w.Code)
		}
	}
}

func TestStaticCachePolicyNeverAppliesToAPI(t *testing.T) {
	dir := t.TempDir()
	if err := os.Mkdir(filepath.Join(dir, "assets"), 0700); err != nil {
		t.Fatal(err)
	}
	for _, file := range []string{"index.html", "sw.js", "app.js", "assets/0123456789abcdef-app.js"} {
		if err := os.WriteFile(filepath.Join(dir, file), []byte("synthetic"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	a, err := newApp(nil, "https://sms.example.com", "synthetic", "", "")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path, cache string
		status      int
	}{
		{"/", "no-cache", 200}, {"/app.js", "no-cache", 200}, {"/sw.js", "no-cache", 200},
		{"/assets/0123456789abcdef-app.js", "public, max-age=31536000, immutable", 200},
		{"/api/unknown", "no-store", 404}, {"/assets/0123456789abcdef-package.json", "no-store", 404},
	} {
		w := httptest.NewRecorder()
		a.routes(dir).ServeHTTP(w, httptest.NewRequest("GET", tc.path, nil))
		if w.Code != tc.status || w.Header().Get("Cache-Control") != tc.cache {
			t.Fatalf("%s: %d %s", tc.path, w.Code, w.Header().Get("Cache-Control"))
		}
	}
}

func TestIntegrationMetadataRevisionAndRevocation(t *testing.T) {
	_, c, token := unreadFixture(t)
	first := c.call("GET", "/api/metadata", token, nil, 200)
	revision := int64(first["revision"].(float64))
	unchanged := c.call("GET", fmt.Sprintf("/api/metadata?since=%d", revision), token, nil, 200)
	if unchanged["unchanged"] != true || unchanged["contacts"] != nil {
		t.Fatal("unchanged snapshot repeated sensitive tables")
	}
	c.call("PUT", "/api/contacts", token, map[string]string{"phone": "10086", "name": "Synthetic"}, 200)
	next := c.call("GET", fmt.Sprintf("/api/metadata?since=%d", revision), token, nil, 200)
	if next["unchanged"] != false || next["revision"].(float64) <= float64(revision) || next["contacts"].(map[string]any)["10086"] != "Synthetic" {
		t.Fatal("metadata mutation missing revision")
	}
	c.call("GET", "/api/metadata", "invalid", nil, 401)
	c.call("GET", "/api/recent", "invalid", nil, 401)
}

func TestIntegrationRecentDoesNotConsumeReliableHistory(t *testing.T) {
	_, c, token := unreadFixture(t)
	before := c.call("GET", "/api/me", token, nil, 200)
	recent := c.call("GET", "/api/recent?limit=1", token, nil, 200)
	if recent["cursor"] != nil || recent["head"] != before["cursor"] || recent["epoch"] != before["epoch"] {
		t.Fatal("display snapshot leaked a reliable cursor")
	}
	rows := recent["messages"].([]any)
	if len(rows) != 1 || rows[0].(map[string]any)["id"] != float64(3) {
		t.Fatal("snapshot not ordered by latest conversation")
	}
	all := c.call("GET", "/api/sync?after=0", token, nil, 200)
	if len(all["messages"].([]any)) != 4 {
		t.Fatal("snapshot consumed reliable history")
	}
	after := c.call("GET", "/api/me", token, nil, 200)
	if before["unread_count"] != after["unread_count"] {
		t.Fatal("snapshot marked messages read")
	}
}

func TestIntegrationUnreadCacheInvalidatesOnReadAndRestore(t *testing.T) {
	a, c, token := unreadFixture(t)
	for i := 0; i < 2; i++ {
		if c.call("GET", "/api/me", token, nil, 200)["unread_count"] != float64(3) {
			t.Fatal("bad cached unread")
		}
	}
	if !a.unreadSnapshot.valid {
		t.Fatal("unread cache unused")
	}
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 3}, 200)
	if c.call("GET", "/api/me", token, nil, 200)["unread_count"] != float64(1) {
		t.Fatal("read revision did not invalidate count")
	}
	if _, err := a.db.Exec("UPDATE sync_clock SET epoch=epoch+1 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	c.call("GET", "/api/me", token, nil, 200)
	if a.unreadSnapshot.epoch != 2 {
		t.Fatal("restored epoch reused stale count")
	}
}

func TestStaticCompressionNegotiatesWithoutWeakeningCacheIsolation(t *testing.T) {
	dir := t.TempDir()
	var compressed bytes.Buffer
	writer := gzip.NewWriter(&compressed)
	_, _ = writer.Write([]byte("synthetic javascript"))
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "app.js"), []byte("synthetic javascript"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "app.js.gz"), compressed.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(nil, "https://sms.example.com", "synthetic", "", "")
	if err != nil {
		t.Fatal(err)
	}
	for _, encoding := range []string{"gzip", "br, gzip;q=0.5", "gzip;q=0", ""} {
		r := httptest.NewRequest("GET", "/app.js", nil)
		r.Header.Set("Accept-Encoding", encoding)
		w := httptest.NewRecorder()
		a.routes(dir).ServeHTTP(w, r)
		want := encoding == "gzip" || encoding == "br, gzip;q=0.5"
		if (w.Header().Get("Content-Encoding") == "gzip") != want || w.Header().Get("Vary") != "Accept-Encoding" {
			t.Fatalf("wrong encoding negotiation %q", encoding)
		}
		if w.Header().Get("Cache-Control") != "no-cache" {
			t.Fatal("legacy name became immutable")
		}
	}
}

func TestIntegrationMetadataMarkerFailureRollsBackContact(t *testing.T) {
	a, c, token := unreadFixture(t)
	if _, err := a.db.Exec("CREATE TRIGGER reject_metadata BEFORE UPDATE ON metadata_clock FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic revision failure'"); err != nil {
		t.Fatal(err)
	}
	c.call("PUT", "/api/contacts", token, map[string]string{"phone": "10086", "name": "must rollback"}, 503)
	contacts := c.call("GET", "/api/contacts", token, nil, 200)["contacts"].(map[string]any)
	if contacts["10086"] != nil {
		t.Fatal("contact committed without its invalidation token")
	}
}
