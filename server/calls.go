package main

import (
	"database/sql"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// A call log entry uploaded by an Android phone. Number is the other party (empty only when
// the network withheld it) and Receiver is this phone's confirmed line, empty when the
// system did not say which SIM carried the call. Duration is in seconds.
type Call struct {
	ID             int64  `json:"id"`
	DeviceID       string `json:"device_id"`
	Receiver       string `json:"receiver"`
	Number         string `json:"number"`
	Contact        string `json:"contact"`
	Type           string `json:"type"`
	Timestamp      int64  `json:"timestamp"`
	Duration       int64  `json:"duration"`
	SubscriptionID int    `json:"subscription_id"`
	Video          bool   `json:"video"`
	Presentation   string `json:"presentation"`
	Location       string `json:"location"`
	Historical     bool   `json:"historical"`
}

type IncomingCall struct {
	Receiver       string `json:"receiver"`
	Number         string `json:"number"`
	Type           string `json:"type"`
	Timestamp      int64  `json:"timestamp"`
	Duration       int64  `json:"duration"`
	SubscriptionID int    `json:"subscription_id"`
	Video          bool   `json:"video"`
	Presentation   string `json:"presentation"`
	Location       string `json:"location"`
	Historical     bool   `json:"historical"`
}

var callTypes = map[string]bool{"incoming": true, "outgoing": true, "missed": true, "rejected": true, "blocked": true, "voicemail": true, "answered_externally": true, "unknown": true}
var callPresentations = map[string]bool{"allowed": true, "restricted": true, "unknown": true, "payphone": true}

const maxCallDuration = 30 * 24 * 3600

func presentation(c IncomingCall) string {
	if c.Presentation == "" {
		return "allowed"
	}
	return c.Presentation
}

// The line is deliberately not part of the identity: a phone may only learn which SIM a call
// used after the user maps it, and one device cannot have two calls with the same party,
// start millisecond, type and duration.
func callFingerprint(c IncomingCall) string {
	return hash(fmt.Sprintf("call\x00%s\x00%d\x00%s\x00%d", c.Number, c.Timestamp, c.Type, c.Duration))
}

func validCall(c IncomingCall) bool {
	p := presentation(c)
	if !callPresentations[p] || !callTypes[c.Type] {
		return false
	}
	if c.Receiver != "" && !phonePattern.MatchString(c.Receiver) {
		return false
	}
	// Only a withheld/unknown/payphone presentation may lack a number.
	if c.Number == "" && p == "allowed" {
		return false
	}
	return len(c.Number) <= 100 && !strings.ContainsRune(c.Number, 0) &&
		utf8.RuneCountInString(c.Location) <= 100 && !strings.ContainsRune(c.Location, 0) &&
		c.Duration >= 0 && c.Duration <= maxCallDuration &&
		c.Timestamp >= 0 && c.Timestamp <= time.Now().Add(24*time.Hour).UnixMilli()
}

const callColumns = "k.id,k.device_id,k.receiver,k.number,COALESCE(c.name,''),k.type,k.timestamp,k.duration,k.subscription_id,k.video,k.presentation,k.location,k.historical"
const callJoins = " LEFT JOIN contacts c ON k.number<>'' AND c.phone=k.number"

func scanCalls(rows *sql.Rows) ([]Call, error) {
	out := []Call{}
	for rows.Next() {
		var c Call
		if e := rows.Scan(&c.ID, &c.DeviceID, &c.Receiver, &c.Number, &c.Contact, &c.Type, &c.Timestamp, &c.Duration, &c.SubscriptionID, &c.Video, &c.Presentation, &c.Location, &c.Historical); e != nil {
			return nil, e
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// GET /api/calls lists newest first by call start time. before is the ID of the oldest call
// on the previous page, read in the same snapshot as the clock so head and page agree.
func (a *app) calls(w http.ResponseWriter, r *http.Request) {
	limit, ok := pageLimit(r)
	before, ok2 := number(r, "before", 0)
	if !ok || !ok2 {
		fail(w, 400, "invalid_page")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var head, epoch int64
	if e = tx.QueryRowContext(r.Context(), "SELECT k.seq,s.epoch FROM call_clock k JOIN sync_clock s ON s.id=k.id WHERE k.id=1").Scan(&head, &epoch); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	where, args := "", []any{}
	if before > 0 {
		var timestamp int64
		if e = tx.QueryRowContext(r.Context(), "SELECT timestamp FROM calls WHERE id=?", before).Scan(&timestamp); e == sql.ErrNoRows {
			fail(w, 400, "invalid_cursor")
			return
		} else if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		where = " WHERE k.timestamp<? OR (k.timestamp=? AND k.id<?)"
		args = append(args, timestamp, timestamp, before)
	}
	args = append(args, limit+1)
	rows, e := tx.QueryContext(r.Context(), "SELECT "+callColumns+" FROM calls k"+callJoins+where+" ORDER BY k.timestamp DESC,k.id DESC LIMIT ?", args...)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	cs, e := scanCalls(rows)
	rows.Close()
	if e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	more := len(cs) > int(limit)
	if more {
		cs = cs[:limit]
	}
	writeJSON(w, 200, map[string]any{"calls": cs, "more": more, "head": head, "epoch": epoch})
}

func (a *app) callDetail(w http.ResponseWriter, r *http.Request) {
	id, e := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if e != nil || id <= 0 {
		fail(w, 400, "invalid_call")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var epoch int64
	if e = tx.QueryRowContext(r.Context(), "SELECT epoch FROM sync_clock WHERE id=1").Scan(&epoch); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	rows, e := tx.QueryContext(r.Context(), "SELECT "+callColumns+" FROM calls k"+callJoins+" WHERE k.id=?", id)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	cs, e := scanCalls(rows)
	rows.Close()
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if len(cs) == 0 {
		fail(w, 404, "not_found")
		return
	}
	if e = tx.Commit(); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"call": cs[0], "epoch": epoch})
}

// GET /api/calls/sync mirrors /sync: ascending commit order for clients that keep a local
// copy. The epoch is the shared database restore generation.
func (a *app) syncCalls(w http.ResponseWriter, r *http.Request) {
	after, ok := number(r, "after", 0)
	limit, ok2 := pageLimit(r)
	if !ok || !ok2 {
		fail(w, 400, "invalid_cursor")
		return
	}
	tx, e := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var head, epoch int64
	if e = tx.QueryRowContext(r.Context(), "SELECT k.seq,s.epoch FROM call_clock k JOIN sync_clock s ON s.id=k.id WHERE k.id=1").Scan(&head, &epoch); e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	// A cursor past the restored clock would look caught up and hide reused IDs.
	if after > head {
		fail(w, 409, "epoch_changed")
		return
	}
	rows, e := tx.QueryContext(r.Context(), "SELECT "+callColumns+" FROM calls k"+callJoins+" WHERE k.id>? ORDER BY k.id LIMIT ?", after, limit+1)
	if e != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	cs, e := scanCalls(rows)
	rows.Close()
	if e != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	more := len(cs) > int(limit)
	if more {
		cs = cs[:limit]
	}
	cursor := after
	if len(cs) > 0 {
		cursor = cs[len(cs)-1].ID
	}
	writeJSON(w, 200, map[string]any{"calls": cs, "cursor": cursor, "more": more, "epoch": epoch})
}
