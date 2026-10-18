package main

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
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

func addPushJobs(t *testing.T, a *app, d Device, total int) {
	t.Helper()
	for id := 2; id <= total; id++ {
		if _, err := a.db.Exec("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,FALSE)", id, d.ID, hash(fmt.Sprint(id)), "+123456789", "synthetic", "synthetic SMS", id, 1); err != nil {
			t.Fatal(err)
		}
		if _, err := a.db.Exec("INSERT INTO push_jobs (message_id,device_id,next_at,leased_until) VALUES (?,?,0,0)", id, d.ID); err != nil {
			t.Fatal(err)
		}
	}
}

func TestIntegrationPushClaimsJustInTimeAndBoundsBatch(t *testing.T) {
	a, d := pushFixture(t)
	addPushJobs(t, a, d, 21)
	sent := 0
	client := &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		sent++
		var leased int
		if err := a.db.QueryRow("SELECT COUNT(*) FROM push_jobs WHERE leased_until>0").Scan(&leased); err != nil || leased != 1 {
			t.Errorf("queued jobs leased before their send: count=%d err=%v", leased, err)
		}
		if deadline, ok := r.Context().Deadline(); !ok || time.Until(deadline) > 60*time.Second {
			t.Error("send missing bounded lease deadline")
		}
		return &http.Response{StatusCode: 202, Body: io.NopCloser(strings.NewReader(""))}, nil
	})}
	if err := a.processPushBatch(context.Background(), client, time.Now()); err != nil {
		t.Fatal(err)
	}
	var pending, leased int
	if err := a.db.QueryRow("SELECT COUNT(*),COALESCE(SUM(leased_until>0),0) FROM push_jobs").Scan(&pending, &leased); err != nil || pending != 1 || leased != 0 || sent != 20 {
		t.Fatalf("batch sent=%d pending=%d leased=%d err=%v", sent, pending, leased, err)
	}
}

func TestIntegrationPushConcurrentWorkersDoNotPreleaseTail(t *testing.T) {
	a, d := pushFixture(t)
	addPushJobs(t, a, d, 2)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	entered := make(chan struct{})
	release := make(chan struct{})
	var sent atomic.Int32
	client := &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		if sent.Add(1) == 1 {
			close(entered)
			select {
			case <-release:
			case <-r.Context().Done():
				return nil, r.Context().Err()
			}
		}
		return &http.Response{StatusCode: 202, Body: io.NopCloser(strings.NewReader(""))}, nil
	})}
	first := make(chan error, 1)
	go func() { first <- a.processPushBatch(ctx, client, time.Now()) }()
	select {
	case <-entered:
	case <-ctx.Done():
		t.Fatal("first worker did not start")
	}
	if err := a.processPushBatch(ctx, client, time.Now()); err != nil {
		t.Error(err)
	}
	if sent.Load() != 2 {
		t.Error("slow first delivery prevented another worker from claiming the unstarted tail")
	}
	close(release)
	if err := <-first; err != nil {
		t.Fatal(err)
	}
	var pending int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&pending); err != nil || pending != 0 || sent.Load() != 2 {
		t.Fatalf("pending=%d sends=%d err=%v", pending, sent.Load(), err)
	}
}

func TestIntegrationPushSendCannotOutliveLease(t *testing.T) {
	a, _ := pushFixture(t)
	jobs, err := a.claimPushJobs(context.Background(), time.Now())
	if err != nil || len(jobs) != 1 {
		t.Fatal("claim", err)
	}
	j := jobs[0]
	j.lease = time.Now().Add(time.Second).UnixMilli()
	if _, err := a.db.Exec("UPDATE push_jobs SET leased_until=? WHERE id=?", j.lease, j.id); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client := &http.Client{Transport: transportFunc(func(r *http.Request) (*http.Response, error) {
		deadline, ok := r.Context().Deadline()
		if !ok || deadline.After(time.UnixMilli(j.lease)) {
			t.Error("HTTP call can outlive ownership")
		}
		<-r.Context().Done()
		return nil, r.Context().Err()
	})}
	if err := a.deliverPushJob(ctx, client, j); err != nil {
		t.Fatal("failed to persist retry after send deadline", err)
	}
	if ctx.Err() != nil {
		t.Fatal("send waited for parent timeout instead of lease expiry")
	}
	var attempts int
	var lease int64
	if err := a.db.QueryRow("SELECT attempts,leased_until FROM push_jobs WHERE id=?", j.id).Scan(&attempts, &lease); err != nil || attempts != 1 || lease != 0 {
		t.Fatal("expired send not left retryable", attempts, lease, err)
	}
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

func TestIntegrationPushSuppressesAlreadyReadMessages(t *testing.T) {
	a, _ := pushFixture(t)
	if _, err := a.db.Exec("UPDATE read_state SET global_watermark_id=1 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Transport: transportFunc(func(*http.Request) (*http.Response, error) {
		t.Fatal("a read message must not be pushed")
		return nil, nil
	})}
	if err := a.processPushBatch(context.Background(), client, time.Now()); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&count); err != nil || count != 0 {
		t.Fatalf("read message job retained: %d %v", count, err)
	}
	if a.pushSuppressed.Load() != 1 {
		t.Fatalf("suppression not counted: %d", a.pushSuppressed.Load())
	}
}

func TestIntegrationPushHonoursRetryAfterAndDropsExhaustedJobs(t *testing.T) {
	a, d := pushFixture(t)
	client := &http.Client{Transport: transportFunc(func(*http.Request) (*http.Response, error) {
		header := make(http.Header)
		header.Set("Retry-After", "120")
		return &http.Response{StatusCode: 429, Body: io.NopCloser(strings.NewReader("")), Header: header}, nil
	})}
	before := time.Now()
	if err := a.processPushBatch(context.Background(), client, before); err != nil {
		t.Fatal(err)
	}
	var next int64
	if err := a.db.QueryRow("SELECT next_at FROM push_jobs").Scan(&next); err != nil {
		t.Fatal(err)
	}
	if next < before.Add(119*time.Second).UnixMilli() {
		t.Fatalf("Retry-After ignored: next_at=%d", next)
	}
	// An exhausted job is dropped instead of retried forever.
	if _, err := a.db.Exec("UPDATE push_jobs SET attempts=50,next_at=0,leased_until=0 WHERE device_id=?", d.ID); err != nil {
		t.Fatal(err)
	}
	client.Transport = transportFunc(func(*http.Request) (*http.Response, error) {
		t.Fatal("exhausted job was sent")
		return nil, nil
	})
	if err := a.processPushBatch(context.Background(), client, time.Now()); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&count); err != nil || count != 0 || a.pushDropped.Load() != 1 {
		t.Fatalf("exhausted job retained: count=%d dropped=%d err=%v", count, a.pushDropped.Load(), err)
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
	// Every job that could only be retried through the dead endpoint is gone too.
	var jobs int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&jobs); err != nil || jobs != 0 {
		t.Fatal("jobs for the dead subscription retained", jobs, err)
	}
}
