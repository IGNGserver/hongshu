package main

import (
	"database/sql"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type Message struct {
	ID             int64  `json:"id"`
	DeviceID       string `json:"device_id"`
	Receiver       string `json:"receiver"`
	Sender         string `json:"sender"`
	Body           string `json:"body"`
	Timestamp      int64  `json:"timestamp"`
	SubscriptionID int    `json:"subscription_id"`
	Contact        string `json:"contact"`
	Historical     bool   `json:"historical"`
}
type Incoming struct {
	Receiver       string `json:"receiver"`
	Sender         string `json:"sender"`
	Body           string `json:"body"`
	Timestamp      int64  `json:"timestamp"`
	SubscriptionID int    `json:"subscription_id"`
	Historical     bool   `json:"historical"`
}

var phonePattern = regexp.MustCompile(`^\+?[0-9]{3,20}$`)

func fingerprint(m Incoming) string {
	return hash(fmt.Sprintf("%s\x00%s\x00%d\x00%s", m.Receiver, m.Sender, m.Timestamp, m.Body))
}
func validMessage(m Incoming) bool {
	return phonePattern.MatchString(m.Receiver) && len(m.Sender) > 0 && len(m.Sender) <= 100 && len(m.Body) > 0 && len(m.Body) <= 64000 && !strings.ContainsRune(m.Sender, 0) && !strings.ContainsRune(m.Body, 0) && m.Timestamp >= 0 && m.Timestamp <= time.Now().Add(24*time.Hour).UnixMilli()
}
func (a *app) upload(w http.ResponseWriter, r *http.Request) {
	d := device(r)
	if d.Kind != "android" || !d.Upload {
		fail(w, 403, "upload_disabled")
		return
	}
	var in struct {
		Messages []Incoming `json:"messages"`
	}
	if !decode(w, r, &in) {
		return
	}
	if len(in.Messages) == 0 || len(in.Messages) > 100 {
		fail(w, 400, "invalid_batch")
		return
	}
	for _, m := range in.Messages {
		if !validMessage(m) {
			fail(w, 400, "invalid_message")
			return
		}
	}
	tx, e := a.db.BeginTx(r.Context(), nil)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var seq int64
	if e = tx.QueryRowContext(r.Context(), "SELECT seq FROM sync_clock WHERE id=1 FOR UPDATE").Scan(&seq); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	// Serialize revocation with writes and recheck policy inside the transaction.
	var active bool
	if e = tx.QueryRowContext(r.Context(), "SELECT upload AND NOT revoked FROM devices WHERE id=? FOR UPDATE", d.ID).Scan(&active); e != nil || !active {
		fail(w, 403, "upload_disabled")
		return
	}
	type ack struct {
		ID        int64 `json:"id"`
		Duplicate bool  `json:"duplicate"`
	}
	acks := make([]ack, 0, len(in.Messages))
	for _, m := range in.Messages {
		var confirmed int
		e = tx.QueryRowContext(r.Context(), "SELECT COUNT(*) FROM sims WHERE device_id=? AND phone=?", d.ID, m.Receiver).Scan(&confirmed)
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if confirmed == 0 {
			fail(w, 409, "sim_not_confirmed")
			return
		}
		fp := fingerprint(m)
		var id int64
		e = tx.QueryRowContext(r.Context(), "SELECT id FROM messages WHERE device_id=? AND fingerprint=?", d.ID, fp).Scan(&id)
		if e == nil {
			acks = append(acks, ack{id, true})
			continue
		}
		if e != sql.ErrNoRows {
			fail(w, 503, "database_unavailable")
			return
		}
		seq++
		_, e = tx.ExecContext(r.Context(), "INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,?)", seq, d.ID, fp, m.Receiver, m.Sender, m.Body, m.Timestamp, m.SubscriptionID, m.Historical)
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if !m.Historical {
			_, e = tx.ExecContext(r.Context(), "INSERT INTO push_jobs (message_id,device_id,next_at) SELECT ?,d.id,? FROM devices d JOIN push_subscriptions p ON p.device_id=d.id WHERE d.id<>? AND d.notify=TRUE AND d.revoked=FALSE", seq, time.Now().UnixMilli(), d.ID)
		}
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		acks = append(acks, ack{seq, false})
	}
	if _, e = tx.ExecContext(r.Context(), "UPDATE sync_clock SET seq=? WHERE id=1", seq); e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"acks": acks, "cursor": seq})
}

const messageColumns = "m.id,m.device_id,m.receiver,m.sender,m.body,m.timestamp,m.subscription_id,COALESCE(c.name,''),m.historical"

func scanMessages(rows *sql.Rows) ([]Message, error) {
	out := []Message{}
	for rows.Next() {
		var m Message
		if e := rows.Scan(&m.ID, &m.DeviceID, &m.Receiver, &m.Sender, &m.Body, &m.Timestamp, &m.SubscriptionID, &m.Contact, &m.Historical); e != nil {
			return nil, e
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
func number(r *http.Request, key string, fallback int64) (int64, bool) {
	s := r.URL.Query().Get(key)
	if s == "" {
		return fallback, true
	}
	v, e := strconv.ParseInt(s, 10, 64)
	return v, e == nil && v >= 0
}
func pageLimit(r *http.Request) (int64, bool) {
	n, ok := number(r, "limit", 100)
	return n, ok && n > 0 && n <= 200
}
func (a *app) syncMessages(w http.ResponseWriter, r *http.Request) {
	after, ok := number(r, "after", 0)
	limit, ok2 := pageLimit(r)
	if !ok || !ok2 {
		fail(w, 400, "invalid_cursor")
		return
	}
	rows, e := a.db.QueryContext(r.Context(), "SELECT "+messageColumns+" FROM messages m LEFT JOIN contacts c ON c.phone=m.sender WHERE m.id>? ORDER BY m.id LIMIT ?", after, limit+1)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	ms, e := scanMessages(rows)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	more := len(ms) > int(limit)
	if more {
		ms = ms[:limit]
	}
	cursor := after
	if len(ms) > 0 {
		cursor = ms[len(ms)-1].ID
	}
	writeJSON(w, 200, map[string]any{"messages": ms, "cursor": cursor, "more": more})
}
func filters(r *http.Request) (string, []any) {
	where := " WHERE 1=1"
	args := []any{}
	for k, col := range map[string]string{"sim": "m.receiver", "device": "m.device_id", "sender": "m.sender"} {
		if v := r.URL.Query().Get(k); v != "" {
			where += " AND " + col + "=?"
			args = append(args, v)
		}
	}
	if q := r.URL.Query().Get("q"); q != "" {
		q = strings.ReplaceAll(strings.ReplaceAll(strings.ReplaceAll(q, "!", "!!"), "%", "!%"), "_", "!_")
		where += " AND (LOWER(m.sender) LIKE LOWER(?) ESCAPE '!' OR LOWER(m.body) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.name) LIKE LOWER(?) ESCAPE '!')"
		args = append(args, "%"+q+"%", "%"+q+"%", "%"+q+"%")
	}
	return where, args
}
func (a *app) history(w http.ResponseWriter, r *http.Request) {
	limit, ok := pageLimit(r)
	before, ok2 := number(r, "before", 0)
	if !ok || !ok2 {
		fail(w, 400, "invalid_page")
		return
	}
	where, args := filters(r)
	if before > 0 {
		var timestamp int64
		if e := a.db.QueryRowContext(r.Context(), "SELECT timestamp FROM messages WHERE id=?", before).Scan(&timestamp); e != nil {
			fail(w, 400, "invalid_cursor")
			return
		}
		where += " AND (m.timestamp<? OR (m.timestamp=? AND m.id<?))"
		args = append(args, timestamp, timestamp, before)
	}
	args = append(args, limit)
	rows, e := a.db.QueryContext(r.Context(), "SELECT "+messageColumns+" FROM messages m LEFT JOIN contacts c ON c.phone=m.sender"+where+" ORDER BY m.timestamp DESC,m.id DESC LIMIT ?", args...)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	ms, e := scanMessages(rows)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"messages": ms})
}
func (a *app) conversations(w http.ResponseWriter, r *http.Request) {
	limit, ok := pageLimit(r)
	offset, ok2 := number(r, "offset", 0)
	if !ok || !ok2 {
		fail(w, 400, "invalid_page")
		return
	}
	where, args := filters(r)
	args = append(args, limit, offset)
	rows, e := a.db.QueryContext(r.Context(), "SELECT "+messageColumns+" FROM messages m LEFT JOIN contacts c ON c.phone=m.sender JOIN (SELECT m.id,ROW_NUMBER() OVER (PARTITION BY m.sender ORDER BY m.timestamp DESC,m.id DESC) rn FROM messages m LEFT JOIN contacts c ON c.phone=m.sender"+where+") t ON t.id=m.id AND t.rn=1 ORDER BY m.timestamp DESC,m.id DESC LIMIT ? OFFSET ?", args...)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer rows.Close()
	ms, e := scanMessages(rows)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"conversations": ms})
}
