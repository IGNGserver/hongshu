package main

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

// fetchOps calls the real router so the route and the auth wrapper are covered too.
func fetchOps(t *testing.T, a *app, token string) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest("GET", "/api/ops", nil)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	a.routes("../web").ServeHTTP(w, r)
	return w
}

func decodeOps(t *testing.T, w *httptest.ResponseRecorder) opsSnapshot {
	t.Helper()
	if w.Code != 200 {
		t.Fatalf("ops status = %d, body = %s", w.Code, w.Body.String())
	}
	var snapshot opsSnapshot
	if err := json.Unmarshal(w.Body.Bytes(), &snapshot); err != nil {
		t.Fatalf("ops payload is not valid JSON: %v", err)
	}
	return snapshot
}

func TestOpsRequiresAuthentication(t *testing.T) {
	a, c, token := unreadFixture(t)
	if w := fetchOps(t, a, ""); w.Code != 401 {
		t.Fatalf("unauthenticated ops returned %d", w.Code)
	}
	if w := fetchOps(t, a, strings.Repeat("x", 64)); w.Code != 401 {
		t.Fatalf("bogus token accepted: %d", w.Code)
	}
	if snapshot := decodeOps(t, fetchOps(t, a, token)); snapshot.Status != "ok" {
		t.Fatalf("authenticated ops status = %q", snapshot.Status)
	}
	_ = c
}

func TestOpsReportsLivenessSchemaAndQueueCounts(t *testing.T) {
	a, _, token := unreadFixture(t)
	a.pushLastLoop.Store(1_700_000_000_000)
	a.analysisLastLoop.Store(1_700_000_000_001)
	a.analysisLastSuccess.Store(1_700_000_000_002)
	a.pushDropped.Store(3)
	a.analysisFinishFailures.Store(2)
	snapshot := decodeOps(t, fetchOps(t, a, token))

	if snapshot.Version == "" || snapshot.UptimeMS < 0 {
		t.Fatalf("version/uptime missing: %+v", snapshot)
	}
	if got := snapshot.Push["last_loop"]; got != float64(1_700_000_000_000) {
		t.Fatalf("push last_loop = %v", got)
	}
	if got := snapshot.Analysis["last_success"]; got != float64(1_700_000_000_002) {
		t.Fatalf("analysis last_success = %v", got)
	}
	if got := snapshot.Push["dropped"]; got != float64(3) {
		t.Fatalf("push dropped = %v", got)
	}
	if got := snapshot.Analysis["finish_failures"]; got != float64(2) {
		t.Fatalf("analysis finish_failures = %v", got)
	}
	// Counts are measured here because a real database is available.
	if _, ok := snapshot.Push["backlog"]; !ok {
		t.Fatal("push backlog missing")
	}
	if snapshot.Push["backlog"] == nil {
		t.Fatal("push backlog could not be measured")
	}
	if _, ok := snapshot.Analysis["queued"]; !ok {
		t.Fatal("analysis queued missing")
	}
	schemaVersion, ok := snapshot.Schema["version"].(string)
	if !ok || schemaVersion == "" || schemaVersion == "unknown" {
		t.Fatalf("schema version not reported: %v", snapshot.Schema["version"])
	}
	if snapshot.Schema["dirty"] != float64(0) {
		t.Fatalf("schema dirty = %v", snapshot.Schema["dirty"])
	}
	if enabled, ok := snapshot.Redis["enabled"].(bool); !ok || enabled {
		t.Fatalf("redis enabled = %v", snapshot.Redis["enabled"])
	}
	if _, ok := snapshot.Workers["running"]; !ok {
		t.Fatal("worker liveness missing from the ops snapshot")
	}
}

// An operator snapshot must never become a data-exfiltration path.
func TestOpsNeverLeaksContentCredentialsOrEndpoints(t *testing.T) {
	a, c, token := unreadFixture(t)
	body := "synthetic confidential body 987654321"
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{
		{Receiver: "+123456789", Sender: "10086", Body: body, Timestamp: 9000},
	}}, 200)
	w := fetchOps(t, a, token)
	if w.Code != 200 {
		t.Fatalf("ops status = %d", w.Code)
	}
	payload := w.Body.String()
	for _, secret := range []string{
		token,
		body,
		"10086",
		"+123456789",
		a.vapidPrivate,
		a.vapidPublic,
		a.origin,
	} {
		if secret == "" {
			continue
		}
		if strings.Contains(payload, secret) {
			t.Fatalf("ops payload leaked a sensitive value")
		}
	}
	for _, key := range []string{"body", "sender", "receiver", "token", "endpoint", "p256dh", "auth", "secret", "password"} {
		if strings.Contains(payload, `"`+key+`"`) {
			t.Fatalf("ops payload exposes a %q field", key)
		}
	}
}
