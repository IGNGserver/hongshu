package main

import (
	"context"
	"database/sql"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Analysis types, shared SQL fragments and the HTTP handlers that expose AI settings,
// counts, classification changes and retries. Provider transport lives in
// analysis_provider.go, queue/lease work in analysis_queue.go, and the background
// workers in analysis_worker.go.

type Analysis struct {
	OTP       string `json:"otp"`
	ExpiresAt int64  `json:"expires_at"`
	Category  string `json:"category"`
	Manual    bool   `json:"manual"`
	State     string `json:"state"`
	Error     string `json:"error"`
	Revision  int64  `json:"revision"`
}

type AISettings struct {
	Enabled    bool             `json:"enabled"`
	Paused     bool             `json:"paused"`
	Endpoint   string           `json:"endpoint,omitempty"`
	Protocol   string           `json:"protocol"`
	Model      string           `json:"model,omitempty"`
	Secret     string           `json:"-"`
	KeySet     bool             `json:"key_set"`
	Timeout    int              `json:"timeout_seconds"`
	Rate       int              `json:"requests_per_minute"`
	DailyLimit int              `json:"daily_limit"`
	Redact     bool             `json:"redact"`
	Generation int64            `json:"-"`
	Revision   int64            `json:"revision"`
	UsageDay   int64            `json:"-"`
	Usage      int              `json:"usage_count"`
	LastError  string           `json:"last_error"`
	Queue      map[string]int64 `json:"queue"`
}

// A manual decision is final even when AI is off. Otherwise only an enabled hub
// treats a completed AI category as the final group; while AI is pending, failed
// or off, the rule result stays visible immediately.
const finalCategory = "(x.manual_override=TRUE OR (ai.enabled=TRUE AND x.state='complete')) AND COALESCE(x.category,'')<>''"

const analysisOtpField = "CASE WHEN " + finalCategory + " AND x.category<>'otp' THEN '' ELSE COALESCE(x.otp,'') END"

const analysisFields = analysisOtpField + ",COALESCE(x.expires_at,0),COALESCE(x.category,''),COALESCE(x.manual_override,FALSE),COALESCE(x.state,'pending'),COALESCE(x.error,''),COALESCE(x.revision,0)"

const messageJoins = " LEFT JOIN contacts c ON c.phone=m.sender LEFT JOIN message_analysis x ON x.message_id=m.id JOIN ai_settings ai ON ai.id=1"

const effectiveGroup = "CASE WHEN " + finalCategory + " THEN x.category WHEN COALESCE(x.otp,'')<>'' THEN 'otp' ELSE 'pending' END"

const settingsSQL = "SELECT enabled,paused,endpoint,protocol,model,secret,timeout_seconds,requests_per_minute,daily_limit,redact,generation,revision,usage_day,usage_count,last_error FROM ai_settings WHERE id=1"

const (
	aiProtocolResponses = "openai_responses"
	aiProtocolChat      = "openai_chat_completions"
	aiProtocolAnthropic = "anthropic_messages"
	aiProtocolGemini    = "gemini_generate_content"
)

type rowQuerier interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func normalizeAIProtocol(v string) string {
	if v == "" {
		return aiProtocolChat
	}
	return v
}

func validAIProtocol(v string) bool {
	switch normalizeAIProtocol(v) {
	case aiProtocolResponses, aiProtocolChat, aiProtocolAnthropic, aiProtocolGemini:
		return true
	default:
		return false
	}
}

func validCategory(v string) bool {
	return v == "otp" || v == "personal" || v == "notification" || v == "promotion" || v == "spam"
}

func (a *app) validGroup(w http.ResponseWriter, r *http.Request) bool {
	g := r.URL.Query().Get("group")
	if g == "" || g == "all" || g == "otp" || g == "other" {
		return true
	}
	if g != "pending" && !validCategory(g) {
		fail(w, 400, "invalid_group")
		return false
	}
	s, e := loadAI(r.Context(), a.db, false)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return false
	}
	if !s.Enabled {
		fail(w, 400, "ai_disabled")
		return false
	}
	return true
}

func (a *app) analysisSettings(w http.ResponseWriter, r *http.Request) {
	s, e := loadAI(r.Context(), a.db, false)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	// Rule-only OTP rows are claimable without a state rewrite; count them as
	// waiting work so the queue summary matches what the worker will actually take.
	rows, e := a.db.QueryContext(r.Context(), "SELECT CASE WHEN state='complete' AND manual_override=FALSE AND COALESCE(category,'')='' AND COALESCE(otp,'')<>'' THEN 'pending' ELSE state END bucket,COUNT(*) FROM message_analysis GROUP BY bucket")
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	s.Queue = map[string]int64{"pending": 0, "processing": 0, "failed": 0, "complete": 0}
	for rows.Next() {
		var state string
		var n int64
		if rows.Scan(&state, &n) != nil {
			rows.Close()
			fail(w, 503, "database_unavailable")
			return
		}
		s.Queue[state] = n
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	var missing int64
	if a.db.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM messages m LEFT JOIN message_analysis x ON x.message_id=m.id WHERE x.message_id IS NULL").Scan(&missing) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	s.Queue["backfill"] = missing
	if !device(r).Admin {
		s.Endpoint = ""
		s.Model = ""
		s.KeySet = false
	}
	writeJSON(w, 200, s)
}

func (a *app) saveAISettings(w http.ResponseWriter, r *http.Request) {
	if !device(r).Admin {
		fail(w, 403, "admin_required")
		return
	}
	var in struct {
		Enabled    bool   `json:"enabled"`
		Paused     bool   `json:"paused"`
		Endpoint   string `json:"endpoint"`
		Protocol   string `json:"protocol"`
		Model      string `json:"model"`
		Key        string `json:"api_key"`
		ClearKey   bool   `json:"clear_key"`
		Timeout    int    `json:"timeout_seconds"`
		Rate       int    `json:"requests_per_minute"`
		DailyLimit int    `json:"daily_limit"`
		Redact     bool   `json:"redact"`
		Consent    bool   `json:"consent"`
	}
	if !decode(w, r, &in) {
		return
	}
	in.Endpoint = strings.TrimSpace(in.Endpoint)
	in.Protocol = normalizeAIProtocol(strings.TrimSpace(in.Protocol))
	in.Model = strings.TrimSpace(in.Model)
	if !validAIProtocol(in.Protocol) {
		fail(w, 400, "invalid_ai_protocol")
		return
	}
	if in.Timeout < 2 || in.Timeout > 60 || in.Rate < 1 || in.Rate > 60 || in.DailyLimit < 1 || in.DailyLimit > 10000 || len(in.Model) > 200 || len(in.Key) > 4096 || strings.ContainsAny(in.Model, "\r\n\x00") || strings.ContainsAny(in.Key, "\r\n\x00") {
		fail(w, 400, "invalid_ai_settings")
		return
	}
	if in.Enabled && (!in.Consent || in.Model == "" || validateAIEndpoint(in.Endpoint) != nil) {
		fail(w, 400, "ai_consent_or_config_required")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	s, e := loadAI(r.Context(), tx, true)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	secret := s.Secret
	// Allow disabling an existing endpoint even if its deployment allowlist changed.
	// Never store a newly supplied URL containing credentials/query secrets.
	if in.Endpoint != "" && validateAIEndpoint(in.Endpoint) != nil && (in.Enabled || in.Endpoint != s.Endpoint) {
		fail(w, 400, "invalid_ai_endpoint")
		return
	}
	if in.ClearKey {
		secret = ""
	}
	if in.Key != "" {
		secret, e = sealAI(in.Key)
		if e != nil {
			fail(w, 400, "ai_secret_required")
			return
		}
	}
	if in.Enabled {
		if _, e = openAI(secret); e != nil {
			fail(w, 400, "ai_secret_required")
			return
		}
	}
	// Changing provider/privacy revokes in-flight leases but preserves completed classifications.
	changed := s.Endpoint != in.Endpoint || s.Protocol != in.Protocol || s.Model != in.Model || s.Secret != secret || s.Redact != in.Redact
	// Old in-flight results are rejected by generation below; their leases expire normally.
	rev, e := analysisRevision(r.Context(), tx)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	_, e = tx.ExecContext(r.Context(), "UPDATE ai_settings SET enabled=?,paused=?,endpoint=?,protocol=?,model=?,secret=?,timeout_seconds=?,requests_per_minute=?,daily_limit=?,redact=?,generation=generation+?,last_error='' WHERE id=1", in.Enabled, in.Paused, in.Endpoint, in.Protocol, in.Model, secret, in.Timeout, in.Rate, in.DailyLimit, in.Redact, changed)
	if e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	a.invalidateAIStatus()
	a.notifyChange()
	writeJSON(w, 200, map[string]any{"ok": true, "revision": rev})
}

func (a *app) analysisCounts(w http.ResponseWriter, r *http.Request) {
	// Counts span groups; UI uses the same search/SIM/device scope, not its selected group.
	r2 := r.Clone(r.Context())
	u := *r.URL
	q := u.Query()
	q.Del("group")
	u.RawQuery = q.Encode()
	r2.URL = &u
	where, args := filters(r2)
	rows, e := a.db.QueryContext(r.Context(), "SELECT "+effectiveGroup+",COUNT(*) FROM messages m"+messageJoins+where+" GROUP BY "+effectiveGroup, args...)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	counts := map[string]int64{"all": 0, "otp": 0, "other": 0, "personal": 0, "notification": 0, "promotion": 0, "spam": 0, "pending": 0}
	for rows.Next() {
		var g string
		var n int64
		if rows.Scan(&g, &n) != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		counts[g] = n
		counts["all"] += n
		if g != "otp" {
			counts["other"] += n
		}
	}
	if rows.Err() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"counts": counts})
}

func (a *app) analysisSync(w http.ResponseWriter, r *http.Request) {
	after, ok := number(r, "after", 0)
	limit, ok2 := pageLimit(r)
	if !ok || !ok2 {
		fail(w, 400, "invalid_cursor")
		return
	}
	// Repeatable-read snapshot keeps rows and head consistent without locking writers.
	tx, e := a.db.BeginTx(r.Context(), &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var head, epoch int64
	if tx.QueryRowContext(r.Context(), "SELECT revision,(SELECT epoch FROM sync_clock WHERE id=1) FROM ai_settings WHERE id=1").Scan(&head, &epoch) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if after > head {
		fail(w, 409, "analysis_cursor_changed")
		return
	}
	rows, e := tx.QueryContext(r.Context(), "SELECT x.message_id,"+analysisFields+" FROM message_analysis x JOIN ai_settings ai ON ai.id=1 WHERE x.revision>? AND x.revision<=? ORDER BY x.revision,x.message_id LIMIT ?", after, head, limit+1)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	type item struct {
		ID       int64    `json:"id"`
		Analysis Analysis `json:"analysis"`
	}
	items := []item{}
	for rows.Next() {
		var v item
		b := &v.Analysis
		if rows.Scan(&v.ID, &b.OTP, &b.ExpiresAt, &b.Category, &b.Manual, &b.State, &b.Error, &b.Revision) != nil {
			rows.Close()
			fail(w, 503, "database_unavailable")
			return
		}
		items = append(items, v)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	more := len(items) > int(limit)
	cursor := head
	if more {
		items = items[:limit]
		cursor = items[len(items)-1].Analysis.Revision
	}
	writeJSON(w, 200, map[string]any{"items": items, "cursor": cursor, "more": more, "epoch": epoch})
}

func (a *app) changeCategory(w http.ResponseWriter, r *http.Request) {
	id, e := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if e != nil || id <= 0 {
		fail(w, 400, "invalid_message_id")
		return
	}
	var in struct {
		Category string `json:"category"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validCategory(in.Category) && in.Category != "auto" && in.Category != "retry" {
		fail(w, 400, "invalid_category")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	s, e := loadAI(r.Context(), tx, true)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if !s.Enabled {
		fail(w, 400, "ai_disabled")
		return
	}
	var body, dir string
	var ts int64
	if tx.QueryRowContext(r.Context(), "SELECT body,timestamp,direction FROM messages WHERE id=?", id).Scan(&body, &ts, &dir) != nil {
		fail(w, 404, "message_not_found")
		return
	}
	if e = insertAnalysis(r.Context(), tx, id, body, ts, dir == directionOut); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	var state string
	var manual bool
	if tx.QueryRowContext(r.Context(), "SELECT state,manual_override FROM message_analysis WHERE message_id=? FOR UPDATE", id).Scan(&state, &manual) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if in.Category == "retry" && (manual || state != "failed") {
		fail(w, 409, "not_retryable")
		return
	}
	category := ""
	state = "pending"
	manual = validCategory(in.Category)
	if manual {
		category = in.Category
		state = "complete"
	}
	rev, e := analysisRevision(r.Context(), tx)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	_, e = tx.ExecContext(r.Context(), "UPDATE message_analysis SET category=?,manual_override=?,state=?,error='',attempts=0,next_at=0,lease_token='',leased_until=0,revision=? WHERE message_id=?", category, manual, state, rev, id)
	if e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	a.notifyChange()
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *app) retryAnalysis(w http.ResponseWriter, r *http.Request) {
	if !device(r).Admin {
		fail(w, 403, "admin_required")
		return
	}
	// Bounded batch: subsequent presses can retry the next batch; no unbounded UPDATE lock.
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	s, e := loadAI(r.Context(), tx, true)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if !s.Enabled {
		fail(w, 400, "ai_disabled")
		return
	}
	rows, e := tx.QueryContext(r.Context(), "SELECT message_id FROM message_analysis WHERE state='failed' AND manual_override=FALSE ORDER BY message_id DESC LIMIT 100 FOR UPDATE")
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	ids := []int64{}
	for rows.Next() {
		var id int64
		if rows.Scan(&id) != nil {
			rows.Close()
			fail(w, 503, "database_unavailable")
			return
		}
		ids = append(ids, id)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	for _, id := range ids {
		rev, e := analysisRevision(r.Context(), tx)
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if _, e = tx.ExecContext(r.Context(), "UPDATE message_analysis SET state='pending',attempts=0,next_at=0,error='',revision=? WHERE message_id=?", rev, id); e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE ai_settings SET paused=FALSE,last_error='' WHERE id=1"); e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	a.invalidateAIStatus()
	a.notifyChange()
	writeJSON(w, 200, map[string]any{"retried": len(ids)})
}

func (a *app) testAI(w http.ResponseWriter, r *http.Request) {
	if !device(r).Admin {
		fail(w, 403, "admin_required")
		return
	}
	// Synthetic-only test shares rate/budget accounting and never reads messages.
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	s, e := loadAI(r.Context(), tx, true)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	var next int64
	if tx.QueryRowContext(r.Context(), "SELECT next_call FROM ai_settings WHERE id=1").Scan(&next) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	now := time.Now().UnixMilli()
	if now < next || s.Usage >= s.DailyLimit {
		fail(w, 429, "ai_budget_or_rate_limit")
		return
	}
	// This synchronous test can legitimately exceed the server's normal 30s
	// write timeout. Extend only this response, with a bounded flush margin.
	if s.Timeout < 2 || s.Timeout > 60 {
		fail(w, 503, "invalid_ai_settings")
		return
	}
	if e = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(time.Duration(s.Timeout)*time.Second + 5*time.Second)); e != nil {
		fail(w, 503, "response_deadline_unavailable")
		return
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE ai_settings SET usage_day=?,usage_count=?,next_call=? WHERE id=1", time.Now().UTC().Unix()/86400, s.Usage+1, now+60000/int64(s.Rate)); e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	category, _, code, _ := classifySMSWithClient(r.Context(), s, "Synthetic service maintenance notification. No action required.", "", a.effectiveAIClient(s))
	if code != "" {
		fail(w, 502, code)
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "category": category})
}
