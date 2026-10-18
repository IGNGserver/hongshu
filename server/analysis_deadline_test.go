package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestAITestUsesRouteSpecificWriteDeadline(t *testing.T) {
	db := testDB(t)
	model := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(700 * time.Millisecond)
		writeJSON(w, 200, map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": `{"category":"notification"}`}}}})
	}))
	defer model.Close()
	u, _ := url.Parse(model.URL)
	t.Setenv("HONGSHU_AI_ALLOWED_HOSTS", u.Hostname())
	if _, err := db.Exec("UPDATE ai_settings SET endpoint=?,timeout_seconds=2 WHERE id=1", model.URL); err != nil {
		t.Fatal(err)
	}
	a, err := newApp(db, "http://localhost:8080", "synthetic", "", "")
	if err != nil {
		t.Fatal(err)
	}
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	_, token, err := insertDevice(context.Background(), tx, "Synthetic", "web", true)
	if err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	routes := a.routes("../web")
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/slow-control" {
			time.Sleep(700 * time.Millisecond)
			writeJSON(w, 200, map[string]bool{"ok": true})
			return
		}
		routes.ServeHTTP(w, r)
	}))
	// Scale down the normal server timeout, keeping the actual test route's
	// model timeout within its production 2..60 second validation range.
	srv.Config.WriteTimeout = 500 * time.Millisecond
	srv.Start()
	defer srv.Close()
	client := &http.Client{Timeout: 5 * time.Second}
	req, _ := http.NewRequest("POST", srv.URL+"/api/analysis/test", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := client.Do(req)
	if err != nil {
		t.Fatal("successful slow model response lost to HTTP write timeout", err)
	}
	defer res.Body.Close()
	var result map[string]any
	if err = json.NewDecoder(res.Body).Decode(&result); err != nil || res.StatusCode != 200 || result["category"] != "notification" {
		t.Fatal("unexpected test result", res.StatusCode, result, err)
	}
	var usage int
	if err = db.QueryRow("SELECT usage_count FROM ai_settings WHERE id=1").Scan(&usage); err != nil || usage != 1 {
		t.Fatal("test bypassed budget accounting", usage, err)
	}
	res, err = client.Get(srv.URL + "/slow-control")
	if err == nil {
		_, readErr := io.ReadAll(res.Body)
		res.Body.Close()
		if readErr == nil {
			t.Fatal("test route relaxed the default deadline for unrelated requests")
		}
	}
}
