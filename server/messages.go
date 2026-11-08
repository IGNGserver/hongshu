package main

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Direction is "in" when the uploading phone's SIM received the message and "out" when it
// was sent from that phone. Sender is the other party either way, so one conversation holds
// both directions, and Receiver is always this phone's own line.
type Message struct {
	ID             int64    `json:"id"`
	DeviceID       string   `json:"device_id"`
	Receiver       string   `json:"receiver"`
	Sender         string   `json:"sender"`
	Body           string   `json:"body"`
	Timestamp      int64    `json:"timestamp"`
	SubscriptionID int      `json:"subscription_id"`
	Contact        string   `json:"contact"`
	Historical     bool     `json:"historical"`
	Direction      string   `json:"direction"`
	Analysis       Analysis `json:"analysis"`
}

type ConversationItem struct {
	Message
	UnreadCount int64 `json:"unread_count"`
}
type Incoming struct {
	Receiver       string `json:"receiver"`
	Sender         string `json:"sender"`
	Body           string `json:"body"`
	Timestamp      int64  `json:"timestamp"`
	SubscriptionID int    `json:"subscription_id"`
	Historical     bool   `json:"historical"`
	Direction      string `json:"direction,omitempty"` // absent = "in": older phones keep their fingerprints
}

const (
	directionIn  = "in"
	directionOut = "out"
)

// Features this hub understands, announced by /me. A phone uploads sent messages only when
// "outgoing_sms" is present: an older hub ignores the direction field and would store them
// as messages received from their recipient. "call_log" gates call uploads the same way:
// an older hub has no /api/calls.
var hubFeatures = []string{"outgoing_sms", "metadata_revision", "recent_snapshot", "call_log", "conversation_cursor"}

func direction(m Incoming) string {
	if m.Direction == "" {
		return directionIn
	}
	return m.Direction
}

var phonePattern = regexp.MustCompile(`^\+?[0-9]{3,20}$`)

func fingerprint(m Incoming) string {
	base := fmt.Sprintf("%s\x00%s\x00%d\x00%s", m.Receiver, m.Sender, m.Timestamp, m.Body)
	// Received messages keep the original identity so existing queues still deduplicate.
	if direction(m) == directionOut {
		base += "\x00out"
	}
	return hash(base)
}
func validMessage(m Incoming) bool {
	return phonePattern.MatchString(m.Receiver) && len(m.Sender) > 0 && len(m.Sender) <= 100 && len(m.Body) > 0 && len(m.Body) <= 64000 && !strings.ContainsRune(m.Sender, 0) && !strings.ContainsRune(m.Body, 0) && (m.Direction == "" || m.Direction == directionIn || m.Direction == directionOut) && m.Timestamp >= 0 && m.Timestamp <= time.Now().Add(24*time.Hour).UnixMilli()
}

const messageColumns = "m.id,m.device_id,m.receiver,m.sender,m.body,m.timestamp,m.subscription_id,COALESCE(c.name,''),m.historical,m.direction," + analysisFields

func scanMessages(rows *sql.Rows) ([]Message, error) {
	out := []Message{}
	for rows.Next() {
		var m Message
		if e := rows.Scan(&m.ID, &m.DeviceID, &m.Receiver, &m.Sender, &m.Body, &m.Timestamp, &m.SubscriptionID, &m.Contact, &m.Historical, &m.Direction, &m.Analysis.OTP, &m.Analysis.ExpiresAt, &m.Analysis.Category, &m.Analysis.Manual, &m.Analysis.State, &m.Analysis.Error, &m.Analysis.Revision); e != nil {
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
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var epoch, head int64
	if err = tx.QueryRowContext(r.Context(), "SELECT epoch,seq FROM sync_clock WHERE id=1").Scan(&epoch, &head); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	// A cursor past the restored clock would otherwise return an empty page and
	// look caught up, hiding every reused message ID. Refuse it instead.
	if after > head {
		fail(w, 409, "epoch_changed")
		return
	}
	rows, err := tx.QueryContext(r.Context(), "SELECT "+messageColumns+" FROM messages m"+messageJoins+" WHERE m.id>? ORDER BY m.id LIMIT ?", after, limit+1)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	ms, err := scanMessages(rows)
	rows.Close()
	if err != nil || tx.Commit() != nil {
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
	writeJSON(w, 200, map[string]any{"messages": ms, "cursor": cursor, "more": more, "epoch": epoch})
}
func filters(r *http.Request) (string, []any) {
	where := " WHERE 1=1"
	args := []any{}
	if g := r.URL.Query().Get("group"); g != "" && g != "all" {
		if g == "other" {
			where += " AND " + effectiveGroup + "<>'otp'"
		} else {
			where += " AND " + effectiveGroup + "=?"
			args = append(args, g)
		}
	}
	keys := []string{"sim", "device", "sender"}
	cols := map[string]string{"sim": "m.receiver", "device": "m.device_id", "sender": "m.sender"}
	for _, k := range keys {
		col := cols[k]
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
	if !a.validGroup(w, r) {
		return
	}
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
	rows, e := a.db.QueryContext(r.Context(), "SELECT "+messageColumns+" FROM messages m"+messageJoins+where+" ORDER BY m.timestamp DESC,m.id DESC LIMIT ?", args...)
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

type conversationCursor struct {
	Version   int    `json:"v"`
	Timestamp int64  `json:"timestamp"`
	MessageID int64  `json:"message_id"`
	Head      int64  `json:"head"`
	Epoch     int64  `json:"epoch"`
	Filter    string `json:"filter"`
}

func conversationFilterFingerprint(r *http.Request) string {
	values := []string{}
	for _, key := range []string{"group", "q", "sim", "device", "sender"} {
		values = append(values, r.URL.Query().Get(key))
	}
	return hash(strings.Join(values, "\x00"))
}

func encodeConversationCursor(cursor conversationCursor) string {
	raw, _ := json.Marshal(cursor)
	return base64.RawURLEncoding.EncodeToString(raw)
}

func decodeConversationCursor(raw string) (conversationCursor, error) {
	var cursor conversationCursor
	if len(raw) == 0 || len(raw) > 2048 {
		return cursor, fmt.Errorf("invalid cursor")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil || json.Unmarshal(decoded, &cursor) != nil || cursor.Version != 1 || cursor.Timestamp < 0 || cursor.MessageID <= 0 || cursor.Head < 0 || cursor.Epoch < 0 || len(cursor.Filter) != 64 {
		return conversationCursor{}, fmt.Errorf("invalid cursor")
	}
	return cursor, nil
}

func (a *app) conversations(w http.ResponseWriter, r *http.Request) {
	if !a.validGroup(w, r) {
		return
	}
	limit, ok := pageLimit(r)
	offset, ok2 := number(r, "offset", 0)
	if !ok || !ok2 || (r.URL.Query().Has("offset") && r.URL.Query().Has("cursor")) {
		fail(w, 400, "invalid_page")
		return
	}
	keyset := !r.URL.Query().Has("offset")
	filterKey := conversationFilterFingerprint(r)
	var previous conversationCursor
	var err error
	if raw := r.URL.Query().Get("cursor"); raw != "" {
		previous, err = decodeConversationCursor(raw)
		if err != nil || previous.Filter != filterKey {
			fail(w, 400, "invalid_cursor")
			return
		}
	}
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var head, epoch int64
	if err = tx.QueryRowContext(r.Context(), "SELECT epoch,seq FROM sync_clock WHERE id=1").Scan(&epoch, &head); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if previous.MessageID > 0 && (previous.Head != head || previous.Epoch != epoch) {
		fail(w, 409, "cursor_reset")
		return
	}
	where, args := filters(r)
	unfiltered := r.URL.Query().Get("q") == "" && r.URL.Query().Get("device") == "" && r.URL.Query().Get("sim") == "" && (r.URL.Query().Get("group") == "" || r.URL.Query().Get("group") == "all") && r.URL.Query().Get("sender") == ""
	query := "SELECT " + messageColumns + " FROM messages m" + messageJoins + " JOIN (SELECT m.id,ROW_NUMBER() OVER (PARTITION BY m.sender ORDER BY m.timestamp DESC,m.id DESC) rn FROM messages m" + messageJoins + where + ") t ON t.id=m.id AND t.rn=1"
	keyCondition := ""
	if previous.MessageID > 0 {
		keyCondition = " WHERE (m.timestamp<? OR (m.timestamp=? AND m.id<?))"
	}
	if unfiltered {
		query = "SELECT " + messageColumns + " FROM conversation_heads h JOIN messages m ON m.id=h.message_id" + messageJoins
		if previous.MessageID > 0 {
			keyCondition = " WHERE (h.timestamp<? OR (h.timestamp=? AND h.message_id<?))"
		}
	}
	if previous.MessageID > 0 {
		args = append(args, previous.Timestamp, previous.Timestamp, previous.MessageID)
	}
	query += keyCondition + " ORDER BY "
	if unfiltered {
		query += "h.timestamp DESC,h.message_id DESC"
	} else {
		query += "m.timestamp DESC,m.id DESC"
	}
	query += " LIMIT ?"
	if keyset {
		args = append(args, limit+1)
	} else {
		query += " OFFSET ?"
		args = append(args, limit, offset)
	}
	rows, err := tx.QueryContext(r.Context(), query, args...)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	ms, err := scanMessages(rows)
	rows.Close()
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	more := keyset && len(ms) > int(limit)
	if more {
		ms = ms[:limit]
	}
	items, err := a.attachUnreadCounts(r.Context(), tx, ms)
	if err != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	response := map[string]any{"conversations": items}
	if keyset {
		next := ""
		if more && len(ms) > 0 {
			last := ms[len(ms)-1]
			next = encodeConversationCursor(conversationCursor{Version: 1, Timestamp: last.Timestamp, MessageID: last.ID, Head: head, Epoch: epoch, Filter: filterKey})
		}
		response["next_cursor"] = next
		response["more"] = more
		response["head"] = head
		response["epoch"] = epoch
	}
	writeJSON(w, 200, response)
}
