package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// Benchmarks for the queries that dominate hub latency. They need a real MySQL
// (TEST_MYSQL_DSN) because a fake driver would measure nothing meaningful, and they only
// run with -bench, so a normal `go test` stays unaffected.
//
// Fixture sizes are part of the result: a benchmark without its scale proves nothing.
const (
	benchMessages = 100_000
	benchSenders  = 200
	benchPushJobs = 20_000
)

func benchFixture(b *testing.B) (*app, string) {
	b.Helper()
	if os.Getenv("TEST_MYSQL_DSN") == "" {
		b.Skip("TEST_MYSQL_DSN required for real MySQL benchmarks")
	}
	db := testDBFor(b)
	a, err := newApp(db, "http://localhost:8080", "synthetic", "", "")
	if err != nil {
		b.Fatal(err)
	}
	token, deviceID := benchLogin(b, a)
	seedBenchData(b, a, deviceID)
	// The login device is the one the token belongs to, so enable upload on it.
	if _, err = db.Exec("UPDATE devices SET upload=TRUE WHERE id=?", deviceID); err != nil {
		b.Fatal(err)
	}
	return a, token
}

// seedBenchData inserts the fixture in batched statements: one round trip per 1000 rows
// instead of one per row, so setup costs seconds rather than minutes.
func seedBenchData(b *testing.B, a *app, deviceID string) {
	b.Helper()
	ctx := context.Background()
	tx, err := a.db.BeginTx(ctx, nil)
	if err != nil {
		b.Fatal(err)
	}
	defer tx.Rollback()
	if _, err = tx.Exec("INSERT INTO sims (device_id,phone,label,subscription_id) VALUES (?,?,?,1)", deviceID, "+123456789", "Bench"); err != nil {
		b.Fatal(err)
	}
	// claimPushJobs joins the live subscription, so the queue needs one.
	if _, err = tx.Exec("INSERT INTO push_subscriptions (device_id,endpoint,p256dh,auth) VALUES (?,?,?,?)",
		deviceID, "https://push.example.invalid/bench",
		"BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQl7rV_2G8w", "dGVzdC1hdXRoLXNlY3JldA"); err != nil {
		b.Fatal(err)
	}
	const batch = 1000
	for start := 1; start <= benchMessages; start += batch {
		var values []string
		var args []any
		for id := start; id < start+batch && id <= benchMessages; id++ {
			values = append(values, "(?,?,?,?,?,?,?,?,FALSE,'in')")
			args = append(args,
				id, deviceID, fmt.Sprintf("%064d", id), "+123456789",
				fmt.Sprintf("bench-%03d", id%benchSenders), "synthetic benchmark message",
				int64(id)*1000, 1)
		}
		if _, err = tx.Exec("INSERT INTO messages (id,device_id,fingerprint,receiver,sender,body,timestamp,subscription_id,historical,direction) VALUES "+strings.Join(values, ","), args...); err != nil {
			b.Fatal(err)
		}
	}
	if _, err = tx.Exec("UPDATE sync_clock SET seq=? WHERE id=1", benchMessages); err != nil {
		b.Fatal(err)
	}
	for start := 1; start <= benchPushJobs; start += batch {
		var values []string
		var args []any
		for id := start; id < start+batch && id <= benchPushJobs; id++ {
			values = append(values, "(?,?,0,0)")
			args = append(args, id, deviceID)
		}
		if _, err = tx.Exec("INSERT INTO push_jobs (message_id,device_id,next_at,leased_until) VALUES "+strings.Join(values, ","), args...); err != nil {
			b.Fatal(err)
		}
	}
	if err = tx.Commit(); err != nil {
		b.Fatal(err)
	}
}

func benchLogin(b *testing.B, a *app) (string, string) {
	b.Helper()
	request := httptest.NewRequest("POST", "/api/login",
		strings.NewReader(`{"password":"synthetic","name":"bench device","kind":"android"}`))
	request.Header.Set("Origin", a.origin)
	recorder := httptest.NewRecorder()
	a.routes("../web").ServeHTTP(recorder, request)
	if recorder.Code != 200 {
		b.Fatalf("benchmark login -> %d %s", recorder.Code, recorder.Body.String())
	}
	var payload struct {
		Token  string `json:"token"`
		Device struct {
			ID string `json:"id"`
		} `json:"device"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil || payload.Token == "" || payload.Device.ID == "" {
		b.Fatalf("benchmark login returned no identity: %v", err)
	}
	return payload.Token, payload.Device.ID
}

func benchGet(b *testing.B, a *app, token, path string) {
	b.Helper()
	handler := a.routes("../web")
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		request := httptest.NewRequest("GET", path, nil)
		request.Header.Set("Origin", a.origin)
		request.Header.Set("Authorization", "Bearer "+token)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != 200 {
			b.Fatalf("%s -> %d %s", path, recorder.Code, recorder.Body.String())
		}
	}
}

// BenchmarkSnapshotUnreadCold advances the revision each iteration so the in-process
// cache misses and the aggregate itself is measured.
func BenchmarkSnapshotUnreadCold(b *testing.B) {
	a, _ := benchFixture(b)
	ctx := context.Background()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		tx, err := a.db.BeginTx(ctx, nil)
		if err != nil {
			b.Fatal(err)
		}
		if _, err = a.snapshotUnread(ctx, tx, 1, benchMessages, 0, int64(i+1)); err != nil {
			b.Fatal(err)
		}
		if err = tx.Commit(); err != nil {
			b.Fatal(err)
		}
	}
	b.ReportMetric(benchMessages, "messages")
}

// BenchmarkSnapshotUnreadCached is the steady-state cost: repeated page loads hit the
// cache, so this must not touch message rows at all.
func BenchmarkSnapshotUnreadCached(b *testing.B) {
	a, _ := benchFixture(b)
	ctx := context.Background()
	tx, err := a.db.BeginTx(ctx, nil)
	if err != nil {
		b.Fatal(err)
	}
	if _, err = a.snapshotUnread(ctx, tx, 1, benchMessages, 0, 1); err != nil {
		b.Fatal(err)
	}
	tx.Commit()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		tx, err := a.db.BeginTx(ctx, nil)
		if err != nil {
			b.Fatal(err)
		}
		if _, err = a.snapshotUnread(ctx, tx, 1, benchMessages, 0, 1); err != nil {
			b.Fatal(err)
		}
		tx.Commit()
	}
}

// BenchmarkConversationPage is the summary page the inbox loads first (indexed path).
func BenchmarkConversationPage(b *testing.B) {
	a, token := benchFixture(b)
	benchGet(b, a, token, "/api/conversations?limit=50")
}

// BenchmarkConversationPageFiltered takes the non-indexed path (ROW_NUMBER over the
// filtered join), which is the expensive variant.
func BenchmarkConversationPageFiltered(b *testing.B) {
	a, token := benchFixture(b)
	benchGet(b, a, token, "/api/conversations?limit=50&q=bench-0")
}

func BenchmarkHistoryPage(b *testing.B) {
	a, token := benchFixture(b)
	benchGet(b, a, token, "/api/messages?limit=50")
}

// BenchmarkSyncPage is the reliable catch-up path used after a reconnect.
func BenchmarkSyncPage(b *testing.B) {
	a, token := benchFixture(b)
	benchGet(b, a, token, "/api/sync?after=0&limit=200")
}

// BenchmarkClaimPushJobs measures one claim against a deep queue, including the
// per-device ordering guard that keeps one device from receiving two alerts at once.
func BenchmarkClaimPushJobs(b *testing.B) {
	a, _ := benchFixture(b)
	ctx := context.Background()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		b.StopTimer()
		if _, err := a.db.Exec("UPDATE push_jobs SET leased_until=0 WHERE leased_until>0"); err != nil {
			b.Fatal(err)
		}
		b.StartTimer()
		jobs, err := a.claimPushJobs(ctx, time.Now())
		if err != nil {
			b.Fatal(err)
		}
		if len(jobs) == 0 {
			b.Fatal("claim returned no job from a full queue")
		}
	}
	b.ReportMetric(benchPushJobs, "queued_jobs")
}

// BenchmarkUploadBatch measures one full 100-message Android batch: the write path most
// sensitive to how many indexes a table carries.
func BenchmarkUploadBatch(b *testing.B) {
	a, token := benchFixture(b)
	records := make([]map[string]any, 0, 100)
	for i := 0; i < 100; i++ {
		records = append(records, map[string]any{
			"receiver":  "+123456789",
			"sender":    "10086",
			"body":      fmt.Sprintf("bench upload %d", i),
			"timestamp": 9_000_000 + i,
		})
	}
	body := map[string]any{"messages": records}
	handler := a.routes("../web")
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		// Unique timestamps per iteration keep the idempotency fingerprint distinct.
		for index := range records {
			records[index]["timestamp"] = 10_000_000 + i*100 + index
		}
		payload, err := json.Marshal(body)
		if err != nil {
			b.Fatal(err)
		}
		request := httptest.NewRequest("POST", "/api/messages", strings.NewReader(string(payload)))
		request.Header.Set("Origin", a.origin)
		request.Header.Set("Authorization", "Bearer "+token)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != 200 {
			b.Fatalf("upload -> %d %s", recorder.Code, recorder.Body.String())
		}
	}
}
