package main

import (
	"context"
	"database/sql"
	"time"
)

// Analysis queue boundary: revisions, enqueueing, candidate selection, leases and the
// durable claim. Callers are the HTTP handlers and the worker loops in
// analysis_worker.go.

func loadAI(ctx context.Context, q rowQuerier, lock bool) (AISettings, error) {
	var s AISettings
	query := settingsSQL
	if lock {
		query += " FOR UPDATE"
	}
	e := q.QueryRowContext(ctx, query).Scan(&s.Enabled, &s.Paused, &s.Endpoint, &s.Protocol, &s.Model, &s.Secret, &s.Timeout, &s.Rate, &s.DailyLimit, &s.Redact, &s.Generation, &s.Revision, &s.UsageDay, &s.Usage, &s.LastError)
	s.Protocol = normalizeAIProtocol(s.Protocol)
	s.KeySet = s.Secret != ""
	if s.UsageDay != time.Now().UTC().Unix()/86400 {
		s.Usage = 0
	}
	return s, e
}

// Revision allocation and the metadata write share the settings-row transaction lock.
// Unlike AUTO_INCREMENT, readers cannot pass an uncommitted revision.
func analysisRevision(ctx context.Context, tx *sql.Tx) (int64, error) {
	return allocateAnalysisRevisions(ctx, tx, 1)
}

// A whole upload batch reserves a contiguous revision block once. This keeps the revision
// feed gap-safe while avoiding one ai_settings lock acquisition per SMS.
func allocateAnalysisRevisions(ctx context.Context, tx *sql.Tx, count int) (int64, error) {
	if count <= 0 {
		return 0, nil
	}
	var current int64
	if e := tx.QueryRowContext(ctx, "SELECT revision FROM ai_settings WHERE id=1 FOR UPDATE").Scan(&current); e != nil {
		return 0, e
	}
	if _, e := tx.ExecContext(ctx, "UPDATE ai_settings SET revision=revision+? WHERE id=1", count); e != nil {
		return 0, e
	}
	return current + 1, nil
}

// A sent message is never a code this user is waiting for, and classifying the user's own
// writing is not worth an AI call: it is stored as already analysed, with no OTP.
func insertAnalysis(ctx context.Context, tx *sql.Tx, id int64, body string, ts int64, sent bool) error {
	code, expires := "", int64(0)
	if !sent {
		code, expires = extractOTP(body, ts)
	}
	rev, e := analysisRevision(ctx, tx)
	if e != nil {
		return e
	}
	// Received OTPs stay pending so AI can confirm the category and code later;
	// the rule code is already stored and shown while that work waits.
	state := "pending"
	if sent {
		state = "complete"
	}
	_, e = tx.ExecContext(ctx, "INSERT IGNORE INTO message_analysis (message_id,otp,expires_at,rule_version,state,revision) VALUES (?,?,?,?,?,?)", id, code, expires, otpRuleVersion, state, rev)
	return e
}

func (a *app) backfillAnalysis(ctx context.Context) error {
	if a.backfillDone.Load() {
		return nil
	}
	tx, e := a.db.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var cursor, before int64
	if e = tx.QueryRowContext(ctx, "SELECT backfill_id,backfill_before FROM ai_settings WHERE id=1 FOR UPDATE").Scan(&cursor, &before); e != nil {
		return e
	}
	query := "SELECT m.id,m.body,m.timestamp,m.direction,x.message_id FROM messages m LEFT JOIN message_analysis x ON x.message_id=m.id WHERE m.id>? ORDER BY m.id LIMIT 100"
	bound := cursor
	if before > 0 {
		query = "SELECT m.id,m.body,m.timestamp,m.direction,x.message_id FROM messages m LEFT JOIN message_analysis x ON x.message_id=m.id WHERE m.id<? ORDER BY m.id DESC LIMIT 100"
		bound = before
	}
	rows, e := tx.QueryContext(ctx, query, bound)
	if e != nil {
		return e
	}
	type entry struct {
		id, ts    int64
		body, dir string
		exists    sql.NullInt64
	}
	items := []entry{}
	for rows.Next() {
		var m entry
		if e = rows.Scan(&m.id, &m.body, &m.ts, &m.dir, &m.exists); e != nil {
			rows.Close()
			return e
		}
		items = append(items, m)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return e
	}
	missing := make([]messageInsert, 0, len(items))
	for _, m := range items {
		if !m.exists.Valid {
			missing = append(missing, messageInsert{msg: Incoming{Body: m.body, Timestamp: m.ts, Direction: m.dir}, id: m.id})
		}
		if before > 0 {
			bound = m.id
		} else {
			cursor = m.id
		}
	}
	if e = insertAnalysisBatch(ctx, tx, missing); e != nil {
		return e
	}
	if before > 0 {
		before = bound
		if len(items) < 100 || before <= 1 {
			before = 0
		}
	}
	if _, e = tx.ExecContext(ctx, "UPDATE ai_settings SET backfill_id=?,backfill_before=? WHERE id=1", cursor, before); e != nil {
		return e
	}
	if e = tx.Commit(); e == nil && len(items) > 0 {
		a.notifyChange()
	}
	if e == nil && len(items) == 0 && before == 0 {
		a.backfillDone.Store(true)
	}
	return e
}

type analysisJob struct {
	ID               int64
	Body, OTP, Token string
	Settings         AISettings
}

// A rule-only OTP row is already complete but still needs AI review. Claiming it
// lazily avoids a bulk state rewrite during upgrade while keeping one code path.
const analysisClaimableSQL = "manual_override=FALSE AND category='' AND ((state='pending' AND next_at<=?) OR (state='processing' AND leased_until<=?) OR (state='complete' AND otp<>''))"

const analysisCandidateSQL = "SELECT x.message_id,m.body,x.revision,x.otp FROM message_analysis x JOIN messages m ON m.id=x.message_id WHERE " + analysisClaimableSQL + " ORDER BY m.historical ASC,m.timestamp DESC,m.id DESC LIMIT 1"

type analysisCandidate struct {
	ID, Revision, SelectedAt int64
	Body, OTP                string
}

func selectAnalysisCandidate(ctx context.Context, q rowQuerier) (*analysisCandidate, error) {
	c := &analysisCandidate{SelectedAt: time.Now().UnixMilli()}
	// One nonlocking statement selects the highest-priority eligible candidate
	// in its MVCC snapshot. Ingestion holds sync_clock before ai_settings;
	// holding settings during this unbounded join/sort would stall all uploads.
	e := q.QueryRowContext(ctx, analysisCandidateSQL, c.SelectedAt, c.SelectedAt).Scan(&c.ID, &c.Body, &c.Revision, &c.OTP)
	if e == sql.ErrNoRows {
		return nil, nil
	}
	return c, e
}

type analysisSchedule struct {
	reason     string
	retryAfter time.Duration
}

func (a *app) claimAnalysis(ctx context.Context) (*analysisJob, error) {
	job, _, err := a.claimAnalysisScheduled(ctx)
	return job, err
}

func (a *app) claimAnalysisScheduled(ctx context.Context) (*analysisJob, analysisSchedule, error) {
	// Cheap, advisory reads avoid scanning the queue when work is disabled or
	// throttled. All policy and accounting is checked again under the lock.
	s, e := loadAI(ctx, a.db, false)
	if e != nil {
		return nil, analysisSchedule{reason: "error", retryAfter: 5 * time.Second}, e
	}
	if !s.Enabled || s.Paused {
		return nil, analysisSchedule{reason: "disabled", retryAfter: 30 * time.Second}, nil
	}
	if s.Usage >= s.DailyLimit {
		return nil, analysisSchedule{reason: "daily_limit", retryAfter: time.Minute}, nil
	}
	var next int64
	if e = a.db.QueryRowContext(ctx, "SELECT next_call FROM ai_settings WHERE id=1").Scan(&next); e != nil {
		return nil, analysisSchedule{reason: "error", retryAfter: 5 * time.Second}, e
	}
	if delay := time.Until(time.UnixMilli(next)); delay > 0 {
		return nil, analysisSchedule{reason: "rate_limit", retryAfter: delay}, nil
	}
	c, e := selectAnalysisCandidate(ctx, a.db)
	if e != nil {
		return nil, analysisSchedule{reason: "error", retryAfter: 5 * time.Second}, e
	}
	if c == nil {
		return nil, analysisSchedule{reason: "empty", retryAfter: 30 * time.Second}, nil
	}
	job, err := a.claimAnalysisCandidate(ctx, c)
	if err != nil {
		return nil, analysisSchedule{reason: "error", retryAfter: 5 * time.Second}, err
	}
	if job == nil {
		// Another hub won the candidate, or an exhausted row was finalized. Recheck
		// without sleeping but yield long enough to avoid a contended hot loop.
		return nil, analysisSchedule{reason: "competed", retryAfter: 50 * time.Millisecond}, nil
	}
	return job, analysisSchedule{}, nil
}

func (a *app) claimAnalysisCandidate(ctx context.Context, c *analysisCandidate) (*analysisJob, error) {
	tx, e := a.db.BeginTx(ctx, nil)
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	s, e := loadAI(ctx, tx, true)
	if e != nil {
		return nil, e
	}
	// Ordering belongs to the selection snapshot; concurrent arrivals join the
	// next selection. A global-revision guard would starve all work under even
	// unrelated OTP uploads. Recheck this candidate and use current settings,
	// without rescanning the queue under the settings lock.
	now := time.Now().UnixMilli()
	day := now / 86400000
	if s.UsageDay != day {
		s.Usage = 0
	}
	var next int64
	if e = tx.QueryRowContext(ctx, "SELECT next_call FROM ai_settings WHERE id=1").Scan(&next); e != nil {
		return nil, e
	}
	if !s.Enabled || s.Paused || now < next || s.Usage >= s.DailyLimit {
		return nil, nil
	}
	j := &analysisJob{ID: c.ID, Body: c.Body, OTP: c.OTP, Settings: s}
	var attempts int
	// Point lookup only; never lock messages in the reverse order to ingestion.
	// Keep the selection cutoff so newly due retries do not alter its ordering.
	where := " WHERE message_id=? AND revision=? AND " + analysisClaimableSQL
	e = tx.QueryRowContext(ctx, "SELECT attempts FROM message_analysis"+where+" FOR UPDATE", c.ID, c.Revision, c.SelectedAt, c.SelectedAt).Scan(&attempts)
	if e == sql.ErrNoRows {
		return nil, nil
	}
	if e != nil {
		return nil, e
	}
	rev, e := analysisRevision(ctx, tx)
	if e != nil {
		return nil, e
	}
	var result sql.Result
	if attempts >= 3 {
		result, e = tx.ExecContext(ctx, "UPDATE message_analysis SET state='failed',error='attempts_exhausted',lease_token='',leased_until=0,revision=?"+where, rev, c.ID, c.Revision, c.SelectedAt, c.SelectedAt)
	} else {
		j.Token = random(16)
		result, e = tx.ExecContext(ctx, "UPDATE message_analysis SET state='processing',attempts=attempts+1,lease_token=?,leased_until=?,revision=?"+where, j.Token, now+int64(s.Timeout+15)*1000, rev, c.ID, c.Revision, c.SelectedAt, c.SelectedAt)
	}
	if e != nil {
		return nil, e
	}
	if n, err := result.RowsAffected(); err != nil || n != 1 {
		return nil, err // rollback also discards the allocated revision
	}
	if attempts < 3 {
		_, e = tx.ExecContext(ctx, "UPDATE ai_settings SET usage_day=?,usage_count=?,next_call=? WHERE id=1", day, s.Usage+1, now+60000/int64(s.Rate))
	}
	if e != nil {
		return nil, e
	}
	if e = tx.Commit(); e != nil {
		return nil, e
	}
	a.notifyChange()
	if attempts >= 3 {
		return nil, nil
	}
	return j, nil
}
