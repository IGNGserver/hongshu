package main

import (
	"context"
	"database/sql"
	"net/http"
	"strings"
)

type callUploadAck struct {
	ID          int64  `json:"id"`
	Duplicate   bool   `json:"duplicate"`
	Error       string `json:"error,omitempty"`
	Fingerprint string `json:"fingerprint"`
}

type callInsert struct {
	call     IncomingCall
	deviceID string
	id       int64
	fp       string
}

// uploadCalls mirrors the batched SMS path: one duplicate lookup, one confirmed-SIM read,
// one multi-row insert and one clock advance instead of four statements per call.
func (a *app) uploadCalls(w http.ResponseWriter, r *http.Request) {
	d := device(r)
	if d.Kind != "android" || !d.Upload {
		fail(w, 403, "upload_disabled")
		return
	}
	var in struct {
		Calls []IncomingCall `json:"calls"`
	}
	if !decode(w, r, &in) {
		return
	}
	if len(in.Calls) == 0 || len(in.Calls) > 100 {
		fail(w, 400, "invalid_batch")
		return
	}
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	// call_clock serializes call commits exactly like sync_clock does for messages, so the
	// /calls/sync cursor never skips a concurrently committing lower ID.
	var seq int64
	if err = tx.QueryRowContext(r.Context(), "SELECT seq FROM call_clock WHERE id=1 FOR UPDATE").Scan(&seq); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
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
	acks := make([]callUploadAck, len(in.Calls))
	var validFingerprints []string
	for index, call := range in.Calls {
		fp := callFingerprint(call)
		acks[index].Fingerprint = fp
		if !validCall(call) {
			acks[index].Error = "invalid_call"
			continue
		}
		// A committed retry must still deduplicate after its SIM registration was removed.
		validFingerprints = append(validFingerprints, fp)
	}
	existing, err := loadExistingCallFingerprints(r.Context(), tx, d.ID, validFingerprints)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	pending := make([]callInsert, 0, len(in.Calls))
	batchIDs := make(map[string]int64)
	for index, call := range in.Calls {
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
		// An unknown line is allowed, but a named one must be a line this phone confirmed.
		if call.Receiver != "" {
			if _, ok := sims[call.Receiver]; !ok {
				acks[index].Error = "sim_not_confirmed"
				continue
			}
		}
		seq++
		acks[index].ID = seq
		batchIDs[fp] = seq
		pending = append(pending, callInsert{call: call, deviceID: d.ID, id: seq, fp: fp})
	}
	if len(pending) > 0 {
		if err = insertCallsBatch(r.Context(), tx, pending); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}
	if _, err = tx.ExecContext(r.Context(), "UPDATE call_clock SET seq=? WHERE id=1", seq); err != nil || tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	a.notifyChange()
	writeJSON(w, 200, map[string]any{"acks": acks, "cursor": seq})
}

func loadExistingCallFingerprints(ctx context.Context, tx *sql.Tx, deviceID string, fingerprints []string) (map[string]int64, error) {
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
		query := "SELECT fingerprint,id FROM calls WHERE device_id=? AND fingerprint IN (?" + strings.Repeat(",?", end-start-1) + ")"
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

func insertCallsBatch(ctx context.Context, tx *sql.Tx, rows []callInsert) error {
	chunks := make([][]callInsert, 0, 1)
	start, size := 0, 0
	for index, row := range rows {
		size += len(row.call.Number) + len(row.call.Receiver) + len(row.call.Location) + 128
		if index > start && size > 1<<20 {
			chunks = append(chunks, rows[start:index])
			start = index
			size = len(row.call.Number) + len(row.call.Receiver) + len(row.call.Location) + 128
		}
	}
	chunks = append(chunks, rows[start:])
	for _, chunk := range chunks {
		var query strings.Builder
		query.WriteString("INSERT INTO calls (id,device_id,fingerprint,receiver,number,type,timestamp,duration,subscription_id,video,presentation,location,historical) VALUES ")
		args := make([]any, 0, len(chunk)*13)
		for index, row := range chunk {
			if index > 0 {
				query.WriteByte(',')
			}
			query.WriteString("(?,?,?,?,?,?,?,?,?,?,?,?,?)")
			args = append(args, row.id, row.deviceID, row.fp, row.call.Receiver, row.call.Number, row.call.Type, row.call.Timestamp, row.call.Duration, row.call.SubscriptionID, row.call.Video, presentation(row.call), row.call.Location, row.call.Historical)
		}
		if _, err := tx.ExecContext(ctx, query.String(), args...); err != nil {
			return err
		}
	}
	return nil
}
