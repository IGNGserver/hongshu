package main

import (
	"context"
	"database/sql"
	"net/http"
	"sort"
	"strings"
	"time"
)

type messageUploadAck struct {
	ID          int64  `json:"id"`
	Duplicate   bool   `json:"duplicate"`
	Error       string `json:"error,omitempty"`
	Fingerprint string `json:"fingerprint"`
}

type messageInsert struct {
	msg      Incoming
	deviceID string
	id       int64
	fp       string
}

// upload commits a whole batch with a bounded number of statements instead of one
// prepare/execute cycle per SMS. At 20-50ms RTT the old loop spent most of its time in
// round trips while holding the sync_clock row lock, so every device queued behind it.
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
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var seq int64
	if err = tx.QueryRowContext(r.Context(), "SELECT seq FROM sync_clock WHERE id=1 FOR UPDATE").Scan(&seq); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	// Serialize revocation with writes and recheck policy inside the transaction. A failed
	// policy query is a database fault, not an administrator decision.
	var active bool
	if err = tx.QueryRowContext(r.Context(), "SELECT upload AND NOT revoked FROM devices WHERE id=? FOR UPDATE", d.ID).Scan(&active); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if !active {
		fail(w, 403, "upload_disabled")
		return
	}

	sims, err := loadConfirmedSIMs(r.Context(), tx, d.ID)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	acks := make([]messageUploadAck, len(in.Messages))
	batchIDs := make(map[string]int64)
	var validFingerprints []string
	for index, message := range in.Messages {
		fp := fingerprint(message)
		acks[index].Fingerprint = fp
		if !validMessage(message) {
			acks[index].Error = "invalid_message"
			continue
		}
		// Query duplicates for every valid item, even if its SIM is no longer registered.
		// The idempotency decision must win over the current SIM registry.
		validFingerprints = append(validFingerprints, fp)
	}
	existing, err := loadExistingFingerprints(r.Context(), tx, d.ID, validFingerprints)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}

	pending := make([]messageInsert, 0, len(in.Messages))
	for index, message := range in.Messages {
		fp := acks[index].Fingerprint
		if acks[index].Error != "" {
			continue
		}
		if id, ok := existing[fp]; ok {
			acks[index].ID, acks[index].Duplicate = id, true
			continue
		}
		if id, ok := batchIDs[fp]; ok {
			acks[index].ID, acks[index].Duplicate = id, true
			continue
		}
		if _, ok := sims[message.Receiver]; !ok {
			acks[index].Error = "sim_not_confirmed"
			continue
		}
		seq++
		acks[index].ID = seq
		batchIDs[fp] = seq
		pending = append(pending, messageInsert{msg: message, deviceID: d.ID, id: seq, fp: fp})
	}

	if len(pending) > 0 {
		if err = insertMessagesBatch(r.Context(), tx, pending); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if err = upsertConversationHeads(r.Context(), tx, pending); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if err = insertAnalysisBatch(r.Context(), tx, pending); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if err = insertPushJobsBatch(r.Context(), tx, pending, d.ID); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}
	if _, err = tx.ExecContext(r.Context(), "UPDATE sync_clock c, conversation_heads_state h SET c.seq=?,h.seq=? WHERE c.id=1 AND h.id=1", seq, seq); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if err = tx.Commit(); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	a.notifyChange()
	for _, row := range pending {
		if !row.msg.Historical && direction(row.msg) == directionIn {
			signalWork(a.pushWake)
			break
		}
	}
	writeJSON(w, 200, map[string]any{"acks": acks, "cursor": seq})
}

func loadConfirmedSIMs(ctx context.Context, tx *sql.Tx, deviceID string) (map[string]struct{}, error) {
	rows, err := tx.QueryContext(ctx, "SELECT phone FROM sims WHERE device_id=?", deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]struct{}{}
	for rows.Next() {
		var phone string
		if err = rows.Scan(&phone); err != nil {
			return nil, err
		}
		out[phone] = struct{}{}
	}
	return out, rows.Err()
}

func loadExistingFingerprints(ctx context.Context, tx *sql.Tx, deviceID string, fingerprints []string) (map[string]int64, error) {
	out := map[string]int64{}
	if len(fingerprints) == 0 {
		return out, nil
	}
	unique := make([]string, 0, len(fingerprints))
	seen := map[string]struct{}{}
	for _, fp := range fingerprints {
		if _, ok := seen[fp]; ok {
			continue
		}
		seen[fp] = struct{}{}
		unique = append(unique, fp)
	}
	for start := 0; start < len(unique); start += 500 {
		end := start + 500
		if end > len(unique) {
			end = len(unique)
		}
		args := make([]any, 0, end-start+1)
		args = append(args, deviceID)
		for _, fp := range unique[start:end] {
			args = append(args, fp)
		}
		query := "SELECT fingerprint,id FROM messages WHERE device_id=? AND fingerprint IN (?" + strings.Repeat(",?", end-start-1) + ")"
		rows, err := tx.QueryContext(ctx, query, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var fp string
			var id int64
			if err = rows.Scan(&fp, &id); err != nil {
				rows.Close()
				return nil, err
			}
			out[fp] = id
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
	}
	return out, nil
}

func insertMessagesBatch(ctx context.Context, tx *sql.Tx, rows []messageInsert) error {
	for _, chunk := range chunkMessageRows(rows, 1<<20) {
		var query strings.Builder
		query.WriteString("INSERT INTO messages (id,device_id,fingerprint,receiver,sender,body,timestamp,subscription_id,historical,direction) VALUES ")
		args := make([]any, 0, len(chunk)*10)
		for index, row := range chunk {
			if index > 0 {
				query.WriteByte(',')
			}
			query.WriteString("(?,?,?,?,?,?,?,?,?,?)")
			args = append(args, row.id, row.deviceID, row.fp, row.msg.Receiver, row.msg.Sender, row.msg.Body, row.msg.Timestamp, row.msg.SubscriptionID, row.msg.Historical, direction(row.msg))
		}
		if _, err := tx.ExecContext(ctx, query.String(), args...); err != nil {
			return err
		}
	}
	return nil
}

func upsertConversationHeads(ctx context.Context, tx *sql.Tx, rows []messageInsert) error {
	best := map[string]messageInsert{}
	for _, row := range rows {
		current, ok := best[row.msg.Sender]
		if !ok || row.msg.Timestamp > current.msg.Timestamp || (row.msg.Timestamp == current.msg.Timestamp && row.id > current.id) {
			best[row.msg.Sender] = row
		}
	}
	senders := make([]string, 0, len(best))
	for sender := range best {
		senders = append(senders, sender)
	}
	sort.Strings(senders)
	for start := 0; start < len(senders); start += 100 {
		end := start + 100
		if end > len(senders) {
			end = len(senders)
		}
		var query strings.Builder
		query.WriteString("INSERT INTO conversation_heads (sender,message_id,timestamp) VALUES ")
		args := make([]any, 0, (end-start)*3)
		for index, sender := range senders[start:end] {
			if index > 0 {
				query.WriteByte(',')
			}
			query.WriteString("(?,?,?)")
			row := best[sender]
			args = append(args, sender, row.id, row.msg.Timestamp)
		}
		query.WriteString(" ON DUPLICATE KEY UPDATE message_id=IF(VALUES(timestamp)>timestamp OR (VALUES(timestamp)=timestamp AND VALUES(message_id)>message_id),VALUES(message_id),message_id),timestamp=GREATEST(timestamp,VALUES(timestamp))")
		if _, err := tx.ExecContext(ctx, query.String(), args...); err != nil {
			return err
		}
	}
	return nil
}

func insertAnalysisBatch(ctx context.Context, tx *sql.Tx, rows []messageInsert) error {
	if len(rows) == 0 {
		return nil
	}
	base, err := allocateAnalysisRevisions(ctx, tx, len(rows))
	if err != nil {
		return err
	}
	for start := 0; start < len(rows); start += 100 {
		end := start + 100
		if end > len(rows) {
			end = len(rows)
		}
		var query strings.Builder
		query.WriteString("INSERT IGNORE INTO message_analysis (message_id,otp,expires_at,rule_version,state,revision) VALUES ")
		args := make([]any, 0, (end-start)*6)
		for index, row := range rows[start:end] {
			if index > 0 {
				query.WriteByte(',')
			}
			query.WriteString("(?,?,?,?,?,?)")
			sent := direction(row.msg) == directionOut
			code, expires := "", int64(0)
			if !sent {
				code, expires = extractOTP(row.msg.Body, row.msg.Timestamp)
			}
			state := "pending"
			if sent {
				state = "complete"
			}
			args = append(args, row.id, code, expires, otpRuleVersion, state, base+int64(start+index))
		}
		if _, err = tx.ExecContext(ctx, query.String(), args...); err != nil {
			return err
		}
	}
	return nil
}

func insertPushJobsBatch(ctx context.Context, tx *sql.Tx, rows []messageInsert, sourceDevice string) error {
	messageIDs := make([]int64, 0, len(rows))
	for _, row := range rows {
		if !row.msg.Historical && direction(row.msg) == directionIn {
			messageIDs = append(messageIDs, row.id)
		}
	}
	if len(messageIDs) == 0 {
		return nil
	}
	rowsDevices, err := tx.QueryContext(ctx, "SELECT d.id FROM devices d JOIN push_subscriptions p ON p.device_id=d.id WHERE d.id<>? AND d.notify=TRUE AND d.revoked=FALSE", sourceDevice)
	if err != nil {
		return err
	}
	var devices []string
	for rowsDevices.Next() {
		var id string
		if err = rowsDevices.Scan(&id); err != nil {
			rowsDevices.Close()
			return err
		}
		devices = append(devices, id)
	}
	err = rowsDevices.Err()
	rowsDevices.Close()
	if err != nil || len(devices) == 0 {
		return err
	}
	now := time.Now().UnixMilli()
	type pair struct {
		messageID int64
		deviceID  string
	}
	pairs := make([]pair, 0, len(messageIDs)*len(devices))
	for _, messageID := range messageIDs {
		for _, deviceID := range devices {
			pairs = append(pairs, pair{messageID, deviceID})
		}
	}
	for start := 0; start < len(pairs); start += 500 {
		end := start + 500
		if end > len(pairs) {
			end = len(pairs)
		}
		var query strings.Builder
		query.WriteString("INSERT IGNORE INTO push_jobs (message_id,device_id,next_at,leased_until) VALUES ")
		args := make([]any, 0, (end-start)*4)
		for index, pair := range pairs[start:end] {
			if index > 0 {
				query.WriteByte(',')
			}
			query.WriteString("(?,?,?,0)")
			args = append(args, pair.messageID, pair.deviceID, now)
		}
		if _, err = tx.ExecContext(ctx, query.String(), args...); err != nil {
			return err
		}
	}
	return nil
}

func chunkMessageRows(rows []messageInsert, maxBytes int) [][]messageInsert {
	if len(rows) == 0 {
		return nil
	}
	chunks := make([][]messageInsert, 0, 1)
	start, size := 0, 0
	for index, row := range rows {
		size += len(row.msg.Body) + len(row.msg.Sender) + len(row.msg.Receiver) + 128
		if index > start && size > maxBytes {
			chunks = append(chunks, rows[start:index])
			start = index
			size = len(row.msg.Body) + len(row.msg.Sender) + len(row.msg.Receiver) + 128
		}
	}
	chunks = append(chunks, rows[start:])
	return chunks
}
