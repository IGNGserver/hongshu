package main

import (
	"bytes"
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/go-sql-driver/mysql"
)

// All fixtures are synthetic and bounded; even the query-plan case has only
// 10,000 rows. These tests run against CI's isolated MySQL, never an AI provider.
func analysisClaimFixture(t *testing.T, count int) (*app, Device) {
	t.Helper()
	db := testDB(t)
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	d, _, err := insertDevice(context.Background(), tx, "synthetic claim fixture", "android", false)
	if err != nil {
		t.Fatal(err)
	}
	d.Upload = true
	if _, err = tx.Exec("UPDATE devices SET upload=TRUE WHERE id=?", d.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec("INSERT INTO sims (device_id,phone,label,subscription_id) VALUES (?,?,'synthetic',1)", d.ID, "+123456789"); err != nil {
		t.Fatal(err)
	}
	for id := 1; id <= count; id++ {
		if _, err = tx.Exec("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,TRUE)", id, d.ID, hash(fmt.Sprint(id)), "+123456789", "synthetic", "synthetic history", id, 1); err != nil {
			t.Fatal(err)
		}
		if err = insertAnalysis(context.Background(), tx, int64(id), "synthetic history", int64(id)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = tx.Exec("UPDATE sync_clock SET seq=? WHERE id=1", count); err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec("UPDATE ai_settings SET enabled=TRUE,requests_per_minute=60,daily_limit=10,timeout_seconds=2,usage_day=? WHERE id=1", time.Now().Unix()/86400); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	return &app{db: db}, d
}

func selectedAnalysis(t *testing.T, db *sql.DB) *analysisCandidate {
	t.Helper()
	c, err := selectAnalysisCandidate(context.Background(), db)
	if err != nil || c == nil {
		t.Fatalf("select candidate: missing=%v error=%v", c == nil, err)
	}
	return c
}

// Delay the actual MySQL candidate query's response, after its MVCC snapshot
// exists but before database/sql can return it to claimAnalysis. No production
// hooks, sleeps, or scheduler races determine when the concurrent upload runs.
type blockedAnalysisConnector struct {
	driver.Connector
	entered chan chan struct{}
}

func (c *blockedAnalysisConnector) Connect(ctx context.Context) (driver.Conn, error) {
	conn, err := c.Connector.Connect(ctx)
	if err != nil {
		return nil, err
	}
	return &blockedAnalysisConn{Conn: conn, gate: c}, nil
}

type blockedAnalysisConn struct {
	driver.Conn
	gate *blockedAnalysisConnector
}

func (c *blockedAnalysisConn) BeginTx(ctx context.Context, opts driver.TxOptions) (driver.Tx, error) {
	return c.Conn.(driver.ConnBeginTx).BeginTx(ctx, opts)
}

func (c *blockedAnalysisConn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	return c.Conn.(driver.ExecerContext).ExecContext(ctx, query, args)
}

func (c *blockedAnalysisConn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	rows, err := c.Conn.(driver.QueryerContext).QueryContext(ctx, query, args)
	if err != nil || query != analysisCandidateSQL {
		return rows, err
	}
	// MySQL can send column metadata before executing the full SELECT. Read
	// its first result too, then replay it after release: the candidate snapshot
	// is now established regardless of packet scheduling or query planning.
	values := make([]driver.Value, len(rows.Columns()))
	if err = rows.Next(values); err != nil {
		rows.Close()
		return nil, err
	}
	release := make(chan struct{})
	select {
	case c.gate.entered <- release:
	case <-ctx.Done():
		rows.Close()
		return nil, ctx.Err()
	}
	select {
	case <-release:
		return &prefetchedAnalysisRows{Rows: rows, values: values}, nil
	case <-ctx.Done():
		rows.Close()
		return nil, ctx.Err()
	}
}

type prefetchedAnalysisRows struct {
	driver.Rows
	values []driver.Value
}

func (r *prefetchedAnalysisRows) Next(dest []driver.Value) error {
	if r.values != nil {
		copy(dest, r.values)
		r.values = nil
		return nil
	}
	return r.Rows.Next(dest)
}

type analysisClaimResult struct {
	job *analysisJob
	err error
}

func blockedAnalysisDB(t *testing.T, db *sql.DB) (*sql.DB, *blockedAnalysisConnector) {
	t.Helper()
	cfg, err := mysql.ParseDSN(os.Getenv("TEST_MYSQL_DSN"))
	if err != nil {
		t.Fatal(err)
	}
	if err = db.QueryRow("SELECT DATABASE()").Scan(&cfg.DBName); err != nil {
		t.Fatal(err)
	}
	// Force QueryContext rather than the driver's prepared-statement fallback,
	// so the barrier wraps the real query including its two cutoff parameters.
	cfg.InterpolateParams = true
	connector, err := mysql.NewConnector(cfg)
	if err != nil {
		t.Fatal(err)
	}
	gate := &blockedAnalysisConnector{Connector: connector, entered: make(chan chan struct{})}
	return sql.OpenDB(gate), gate
}

func awaitAnalysisCandidate(t *testing.T, ctx context.Context, gate *blockedAnalysisConnector) func() {
	t.Helper()
	select {
	case release := <-gate.entered:
		var once sync.Once
		return func() { once.Do(func() { close(release) }) }
	case <-ctx.Done():
		t.Fatal("candidate query did not reach barrier")
	}
	return nil
}

func uploadAnalysisFixture(t *testing.T, a *app, d Device, messages ...Incoming) time.Duration {
	t.Helper()
	// Exercise the real upload handler, including sync_clock, message/FK and
	// analysis revision locks. The candidate response stays blocked throughout.
	body, err := json.Marshal(map[string]any{"messages": messages})
	if err != nil {
		t.Fatal(err)
	}
	uploadCtx, stopUpload := context.WithTimeout(context.Background(), 2*time.Second)
	defer stopUpload()
	r := httptest.NewRequest("POST", "/api/messages", bytes.NewReader(body)).WithContext(context.WithValue(uploadCtx, deviceKey{}, d))
	w := httptest.NewRecorder()
	started := time.Now()
	a.upload(w, r)
	uploadElapsed := time.Since(started)
	if w.Code != 200 {
		t.Fatalf("upload blocked by candidate selection: status=%d", w.Code)
	}
	return uploadElapsed
}

func TestAnalysisCandidateScanDoesNotBlockUpload(t *testing.T) {
	const queueRows = 10000
	a, d := analysisClaimFixture(t, queueRows)
	db := a.db
	now := time.Now().UnixMilli()
	var plan string
	if err := db.QueryRow("EXPLAIN ANALYZE "+analysisCandidateSQL, now, now).Scan(&plan); err != nil {
		t.Fatal(err)
	}
	t.Logf("synthetic %d-row queue; unlocked candidate EXPLAIN ANALYZE: %s", queueRows, plan)
	workerDB, gate := blockedAnalysisDB(t, db)
	defer workerDB.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	result := make(chan analysisClaimResult, 1)
	worker := &app{db: workerDB}
	go func() {
		job, err := worker.claimAnalysis(ctx)
		result <- analysisClaimResult{job, err}
	}()
	release := awaitAnalysisCandidate(t, ctx, gate)
	defer release()
	message := Incoming{Receiver: "+123456789", Sender: "synthetic", Body: "synthetic incoming", Timestamp: 1, SubscriptionID: 1}
	uploadElapsed := uploadAnalysisFixture(t, a, d, message)
	select {
	case <-result:
		t.Fatal("candidate barrier released before upload committed")
	default:
	}
	release()
	select {
	case got := <-result:
		if got.err != nil || got.job == nil || got.job.ID != queueRows {
			t.Fatal("valid snapshot candidate lost progress after concurrent upload", got.err)
		}
	case <-ctx.Done():
		t.Fatal("claim did not finish after barrier release")
	}
	var usage int
	if err := db.QueryRow("SELECT usage_count FROM ai_settings WHERE id=1").Scan(&usage); err != nil || usage != 1 {
		t.Fatal("snapshot claim must consume exactly one quota unit", usage, err)
	}
	if _, err := db.Exec("UPDATE ai_settings SET next_call=0 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	job, err := a.claimAnalysis(ctx)
	claimElapsed := time.Since(started)
	if err != nil || job == nil || job.ID != queueRows+1 {
		t.Fatal("new incoming did not outrank historical queue", err)
	}
	if _, err = db.Exec("UPDATE ai_settings SET next_call=0 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	job, err = a.claimAnalysis(ctx)
	if err != nil || job == nil || job.ID != queueRows-1 {
		t.Fatal("historical task lost or chronological priority changed", err)
	}
	t.Logf("synthetic queue_rows=%d blocked_scan_upload=%s subsequent_claim=%s (diagnostic timing, no throughput assertion)", queueRows, uploadElapsed, claimElapsed)
}

func TestAnalysisConcurrentCandidatesShareLeaseAndQuota(t *testing.T) {
	a, _ := analysisClaimFixture(t, 2)
	if _, err := a.db.Exec("UPDATE ai_settings SET daily_limit=1 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	first, second := selectedAnalysis(t, a.db), selectedAnalysis(t, a.db)
	if first.ID != second.ID || first.Revision != second.Revision {
		t.Fatal("workers did not start from the same snapshot")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := make(chan struct{})
	results := make(chan analysisClaimResult, 2)
	for _, candidate := range []*analysisCandidate{first, second} {
		go func(c *analysisCandidate) {
			<-start
			worker := &app{db: a.db}
			job, err := worker.claimAnalysisCandidate(ctx, c)
			results <- analysisClaimResult{job, err}
		}(candidate)
	}
	close(start)
	winners := 0
	for range 2 {
		select {
		case got := <-results:
			if got.err != nil {
				t.Fatal(got.err)
			}
			if got.job != nil {
				winners++
			}
		case <-ctx.Done():
			t.Fatal("concurrent claim timed out")
		}
	}
	var usage, attempts int
	if err := a.db.QueryRow("SELECT usage_count,(SELECT attempts FROM message_analysis WHERE message_id=2) FROM ai_settings WHERE id=1").Scan(&usage, &attempts); err != nil || winners != 1 || usage != 1 || attempts != 1 {
		t.Fatal("duplicate lease or accounting", winners, usage, attempts, err)
	}
	// Remove the rate gate: the remaining pending row must still be blocked by
	// the shared daily budget, including with a freshly selected candidate.
	if _, err := a.db.Exec("UPDATE ai_settings SET next_call=0 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	remaining := selectedAnalysis(t, a.db)
	if job, err := a.claimAnalysisCandidate(ctx, remaining); err != nil || job != nil {
		t.Fatal("daily quota exceeded", err)
	}
	if _, err := a.db.Exec("UPDATE ai_settings SET usage_day=? WHERE id=1", time.Now().Unix()/86400-1); err != nil {
		t.Fatal(err)
	}
	job, err := a.claimAnalysisCandidate(ctx, remaining)
	if err != nil || job == nil || job.ID != 1 {
		t.Fatal("next-day quota did not recover pending work", err)
	}
	if err = a.db.QueryRow("SELECT usage_count FROM ai_settings WHERE id=1").Scan(&usage); err != nil || usage != 1 {
		t.Fatal("daily quota did not reset atomically", usage, err)
	}
}

func TestAnalysisCandidateProgressDuringEveryScanUpload(t *testing.T) {
	const rounds = 3
	a, d := analysisClaimFixture(t, rounds)
	if _, err := a.db.Exec("UPDATE ai_settings SET daily_limit=? WHERE id=1", rounds); err != nil {
		t.Fatal(err)
	}
	workerDB, gate := blockedAnalysisDB(t, a.db)
	defer workerDB.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	worker := &app{db: workerDB}
	for round := 0; round < rounds; round++ {
		// Advance past the rate gate without sleeping; never reset daily usage.
		if _, err := a.db.Exec("UPDATE ai_settings SET next_call=0 WHERE id=1"); err != nil {
			t.Fatal(err)
		}
		result := make(chan analysisClaimResult, 1)
		go func() {
			job, err := worker.claimAnalysis(ctx)
			result <- analysisClaimResult{job, err}
		}()
		release := awaitAnalysisCandidate(t, ctx, gate)
		defer release()
		// Every selection sees an intervening committed upload, never a quiet
		// window. Both uploads advance the global head, neither should displace
		// the selected candidate: OTP is ineligible, timestamp=0 history is older.
		otp := Incoming{Receiver: "+123456789", Sender: "synthetic", Body: "验证码为 482913，5分钟内有效", Timestamp: int64(100 + round), SubscriptionID: 1}
		history := Incoming{Receiver: "+123456789", Sender: "synthetic", Body: fmt.Sprintf("synthetic older history %d", round), Timestamp: 0, SubscriptionID: 1, Historical: true}
		uploadAnalysisFixture(t, a, d, otp, history)
		release()
		select {
		case got := <-result:
			if got.err != nil || got.job == nil || got.job.ID != int64(rounds-round) {
				t.Fatalf("round %d: valid candidate must be claimed after one scan despite uploads: missing=%v error=%v", round, got.job == nil, got.err)
			}
		case releaseAgain := <-gate.entered:
			close(releaseAgain)
			t.Fatal("unrelated uploads forced another candidate scan instead of making progress")
		case <-ctx.Done():
			t.Fatal("claim made no progress during continuous unrelated uploads")
		}
	}
	var usage, messages, processing, pending, complete, attempts int
	if err := a.db.QueryRow("SELECT usage_count,(SELECT COUNT(*) FROM messages) FROM ai_settings WHERE id=1").Scan(&usage, &messages); err != nil || usage != rounds || messages != rounds*3 {
		t.Fatal("uploads or quota lost during concurrent progress", usage, messages, err)
	}
	if err := a.db.QueryRow("SELECT SUM(state='processing'),SUM(state='pending'),SUM(state='complete'),SUM(attempts) FROM message_analysis").Scan(&processing, &pending, &complete, &attempts); err != nil || processing != rounds || pending != rounds || complete != rounds || attempts != rounds {
		t.Fatal("task loss, duplicate attempt, or OTP entered AI queue", processing, pending, complete, attempts, err)
	}
	if _, err := a.db.Exec("UPDATE ai_settings SET next_call=0 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	remaining := selectedAnalysis(t, a.db)
	if remaining.ID != rounds*3 {
		t.Fatal("equal-timestamp history lost descending ID priority", remaining.ID)
	}
	if job, err := a.claimAnalysisCandidate(ctx, remaining); err != nil || job != nil {
		t.Fatal("continuous progress exceeded the shared daily quota", err)
	}
	// Exhausting the budget leaves the new historical work intact. It resumes
	// with a fresh quota, without replaying any of the three original leases.
	if _, err := a.db.Exec("UPDATE ai_settings SET daily_limit=daily_limit+1 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	job, err := a.claimAnalysisCandidate(ctx, remaining)
	if err != nil || job == nil || job.ID != remaining.ID {
		t.Fatal("pending history did not survive quota exhaustion", err)
	}
	if err = a.db.QueryRow("SELECT usage_count FROM ai_settings WHERE id=1").Scan(&usage); err != nil || usage != rounds+1 {
		t.Fatal("resumed history charged incorrectly", usage, err)
	}
}

func TestAnalysisCandidateUsesLatestConfiguration(t *testing.T) {
	a, _ := analysisClaimFixture(t, 1)
	c := selectedAnalysis(t, a.db)
	if _, err := a.db.Exec("UPDATE ai_settings SET revision=revision+1,generation=generation+1,model='synthetic replacement',redact=FALSE,timeout_seconds=3 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	job, err := a.claimAnalysisCandidate(context.Background(), c)
	if err != nil || job == nil || job.Settings.Generation != 2 || job.Settings.Model != "synthetic replacement" || job.Settings.Redact || job.Settings.Timeout != 3 {
		t.Fatal("snapshot candidate did not use latest locked configuration", err)
	}
}

func TestAnalysisCandidateRevalidation(t *testing.T) {
	// Each task and policy guard must be rechecked under the settings lock.
	cases := []struct{ name, mutation string }{
		{"disabled", "UPDATE ai_settings SET enabled=FALSE WHERE id=1"},
		{"paused", "UPDATE ai_settings SET paused=TRUE WHERE id=1"},
		{"rate", "UPDATE ai_settings SET next_call=9223372036854775807 WHERE id=1"},
		{"quota", "UPDATE ai_settings SET usage_count=daily_limit WHERE id=1"},
		{"task_revision", "UPDATE message_analysis SET revision=revision+1 WHERE message_id=1"},
		{"manual", "UPDATE message_analysis SET manual_override=TRUE WHERE message_id=1"},
		{"otp", "UPDATE message_analysis SET otp='123456' WHERE message_id=1"},
		{"category", "UPDATE message_analysis SET category='personal' WHERE message_id=1"},
		{"state", "UPDATE message_analysis SET state='failed' WHERE message_id=1"},
		{"backoff", "UPDATE message_analysis SET next_at=9223372036854775807 WHERE message_id=1"},
		{"live_lease", "UPDATE message_analysis SET state='processing',leased_until=9223372036854775807 WHERE message_id=1"},
		{"deleted", "DELETE FROM messages WHERE id=1"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			a, _ := analysisClaimFixture(t, 1)
			c := selectedAnalysis(t, a.db)
			if _, err := a.db.Exec(tc.mutation); err != nil {
				t.Fatal(err)
			}
			var head, usage int64
			if err := a.db.QueryRow("SELECT revision,usage_count FROM ai_settings WHERE id=1").Scan(&head, &usage); err != nil {
				t.Fatal(err)
			}
			if job, err := a.claimAnalysisCandidate(context.Background(), c); err != nil || job != nil {
				t.Fatal("invalid candidate claimed", err)
			}
			var gotHead, gotUsage, attempts int64
			if err := a.db.QueryRow("SELECT revision,usage_count,(SELECT COALESCE(SUM(attempts),0) FROM message_analysis) FROM ai_settings WHERE id=1").Scan(&gotHead, &gotUsage, &attempts); err != nil || gotHead != head || gotUsage != usage || attempts != 0 {
				t.Fatal("rejection changed revision/quota/attempts", err)
			}
		})
	}
}

func TestAnalysisExpiredLeaseAttemptLimit(t *testing.T) {
	a, _ := analysisClaimFixture(t, 1)
	if _, err := a.db.Exec("UPDATE message_analysis SET state='processing',attempts=3,lease_token='synthetic-old-lease',leased_until=1 WHERE message_id=1"); err != nil {
		t.Fatal(err)
	}
	if job, err := a.claimAnalysis(context.Background()); err != nil || job != nil {
		t.Fatal("exhausted lease was retried", err)
	}
	var state, code, token string
	var usage, attempts, until int64
	if err := a.db.QueryRow("SELECT state,error,lease_token,attempts,leased_until,(SELECT usage_count FROM ai_settings WHERE id=1) FROM message_analysis WHERE message_id=1").Scan(&state, &code, &token, &attempts, &until, &usage); err != nil || state != "failed" || code != "attempts_exhausted" || token != "" || attempts != 3 || until != 0 || usage != 0 {
		t.Fatal("exhausted task not retained without another charge", err)
	}
}
