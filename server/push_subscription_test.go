package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type pausedSubscriptionBody struct {
	io.Reader
	ctx     context.Context
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (b *pausedSubscriptionBody) Read(p []byte) (int, error) {
	b.once.Do(func() { close(b.entered) })
	select {
	case <-b.release:
		return b.Reader.Read(p)
	case <-b.ctx.Done():
		return 0, b.ctx.Err()
	}
}

func TestIntegrationSubscribeCannotResurrectRevokedIdentity(t *testing.T) {
	a, d := pushFixture(t)
	token := strings.Repeat("a", 64)
	if _, err := a.db.Exec("UPDATE devices SET token_hash=? WHERE id=?", hash(token), d.ID); err != nil {
		t.Fatal(err)
	}
	var key, auth string
	if err := a.db.QueryRow("SELECT p256dh,auth FROM push_subscriptions WHERE device_id=?", d.ID).Scan(&key, &auth); err != nil {
		t.Fatal(err)
	}
	// A public IP literal passes endpoint validation without external DNS or delivery.
	sub := map[string]any{"endpoint": "https://8.8.8.8/synthetic", "keys": map[string]string{"p256dh": key, "auth": auth}}
	encoded, err := json.Marshal(sub)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	body := &pausedSubscriptionBody{Reader: strings.NewReader(string(encoded)), ctx: ctx, entered: make(chan struct{}), release: make(chan struct{})}
	var release sync.Once
	defer release.Do(func() { close(body.release) })
	r := httptest.NewRequest("PUT", "/api/push", body).WithContext(ctx)
	r.Header.Set("Origin", a.origin)
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { defer close(done); a.routes("../web").ServeHTTP(w, r) }()
	select {
	case <-body.entered: // middleware authentication succeeded, handler body is paused
	case <-ctx.Done():
		t.Fatal("subscription did not reach authenticated handler")
	}
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/logout", token, nil, 200)
	release.Do(func() { close(body.release) })
	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal("subscription did not finish")
	}
	if w.Code != 401 {
		t.Fatalf("stale authenticated subscription returned %d", w.Code)
	}
	var count int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM push_subscriptions WHERE device_id=?", d.ID).Scan(&count); err != nil || count != 0 {
		t.Fatal("revoked subscription resurrected", count, err)
	}
	login := c.call("POST", "/api/login", "", map[string]string{"password": "synthetic", "kind": "web"}, 200)
	c.call("PUT", "/api/push", login["token"].(string), sub, 200)
}
