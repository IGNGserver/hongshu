package main

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	webpush "github.com/SherClockHolmes/webpush-go"
)

func pushFixture(t *testing.T) (*app, Device) {
	t.Helper()
	db := testDB(t)
	private, public, err := webpush.GenerateVAPIDKeys()
	if err != nil {
		t.Fatal(err)
	}
	a, err := newApp(db, "http://localhost:8080", "synthetic", public, private)
	if err != nil {
		t.Fatal(err)
	}
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	d, _, err := insertDevice(context.Background(), tx, "synthetic browser", "web", true)
	if err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	recipient, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	auth := make([]byte, 16)
	if _, err = rand.Read(auth); err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec("INSERT INTO push_subscriptions VALUES (?,?,?,?)", d.ID, "https://push.example.invalid/synthetic", base64.RawURLEncoding.EncodeToString(recipient.PublicKey().Bytes()), base64.RawURLEncoding.EncodeToString(auth))
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec("INSERT INTO messages VALUES (1,?,?,?,?,?,?,?,FALSE)", d.ID, strings.Repeat("0", 64), "+123456789", "synthetic", "synthetic SMS", time.Now().UnixMilli(), 1)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec("INSERT INTO push_jobs (message_id,device_id,next_at,leased_until) VALUES (1,?,0,0)", d.ID)
	if err != nil {
		t.Fatal(err)
	}
	return a, d
}

func TestIntegrationPushWorkerClaimsAndDelivers(t *testing.T) {
	a, _ := pushFixture(t)
	sent := 0
	client := &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		sent++
		if r.Header.Get("Content-Encoding") != "aes128gcm" {
			t.Fatal("worker bypassed encryption")
		}
		return &http.Response{StatusCode: 202, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}, nil
	})}
	if err := a.processPushBatch(context.Background(), client, time.Now()); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if sent != 1 || count != 0 || a.pushLastSuccess.Load() == 0 {
		t.Fatal("actual worker did not drain queue")
	}
}

func TestIntegrationPushLeaseAndRevocation(t *testing.T) {
	a, d := pushFixture(t)
	jobs, err := a.claimPushJobs(context.Background(), time.Now())
	if err != nil || len(jobs) != 1 {
		t.Fatalf("claim: %v", err)
	}
	second, err := a.claimPushJobs(context.Background(), time.Now())
	if err != nil || len(second) != 0 {
		t.Fatal("live lease claimed twice", err)
	}
	expired, err := a.claimPushJobs(context.Background(), time.Now().Add(61*time.Second))
	if err != nil || len(expired) != 1 {
		t.Fatal("expired lease not recoverable", err)
	}
	if _, err = a.db.Exec("UPDATE devices SET revoked=TRUE WHERE id=?", d.ID); err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Transport: transportFunc(func(*http.Request) (*http.Response, error) {
		t.Fatal("claimed revoked recipient sent")
		return nil, nil
	})}
	if err = a.deliverPushJob(context.Background(), client, expired[0]); err != nil {
		t.Fatal(err)
	}
}

func TestIntegrationPushRetryAndExpiredEndpoint(t *testing.T) {
	a, _ := pushFixture(t)
	client := &http.Client{Transport: transportFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 500, Body: io.NopCloser(strings.NewReader(""))}, nil
	})}
	before := time.Now()
	if err := a.processPushBatch(context.Background(), client, before); err != nil {
		t.Fatal(err)
	}
	var attempts int
	var next, lease int64
	if err := a.db.QueryRow("SELECT attempts,next_at,leased_until FROM push_jobs").Scan(&attempts, &next, &lease); err != nil {
		t.Fatal(err)
	}
	if attempts != 1 || next <= before.UnixMilli() || lease != 0 {
		t.Fatal("retry state not persisted")
	}
	client.Transport = transportFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 410, Body: io.NopCloser(strings.NewReader(""))}, nil
	})
	if err := a.processPushBatch(context.Background(), client, time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM push_subscriptions").Scan(&count); err != nil || count != 0 {
		t.Fatal("expired endpoint retained", err)
	}
}
