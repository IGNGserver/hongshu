package main

import (
	"context"
	"database/sql"
	"net/http"
	"time"
)

// Mutations and their invalidation token commit together. Never acknowledge a token
// before the data it describes is visible, including across multiple hub processes.
func (a *app) commitMetadata(ctx context.Context, tx *sql.Tx) error {
	if _, err := tx.ExecContext(ctx, "UPDATE metadata_clock SET revision=revision+1 WHERE id=1"); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	a.broadcastChange()
	return nil
}

func (a *app) metadata(w http.ResponseWriter, r *http.Request) {
	since, ok := number(r, "since", 0)
	if !ok {
		fail(w, 400, "invalid_revision")
		return
	}
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var revision, epoch int64
	if err = tx.QueryRowContext(r.Context(), "SELECT revision,epoch FROM metadata_clock JOIN sync_clock ON sync_clock.id=metadata_clock.id WHERE metadata_clock.id=1").Scan(&revision, &epoch); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	out := map[string]any{"revision": revision, "epoch": epoch, "unchanged": revision == since}
	if revision != since {
		devices := []Device{}
		rows, e := tx.QueryContext(r.Context(), "SELECT id,name,kind,admin,upload,notify,revoked,created_at FROM devices ORDER BY created_at")
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		for rows.Next() {
			var d Device
			if e = rows.Scan(&d.ID, &d.Name, &d.Kind, &d.Admin, &d.Upload, &d.Notify, &d.Revoked, &d.Created); e != nil {
				break
			}
			devices = append(devices, d)
		}
		err = rows.Err()
		rows.Close()
		if e != nil || err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		sims := []SIM{}
		rows, e = tx.QueryContext(r.Context(), "SELECT device_id,phone,label,subscription_id FROM sims ORDER BY device_id,phone")
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		for rows.Next() {
			var s SIM
			if e = rows.Scan(&s.DeviceID, &s.Phone, &s.Label, &s.SubscriptionID); e != nil {
				break
			}
			sims = append(sims, s)
		}
		err = rows.Err()
		rows.Close()
		if e != nil || err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		contacts := map[string]string{}
		rows, e = tx.QueryContext(r.Context(), "SELECT phone,name FROM contacts ORDER BY phone")
		if e != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		for rows.Next() {
			var phone, name string
			if e = rows.Scan(&phone, &name); e != nil {
				break
			}
			contacts[phone] = name
		}
		err = rows.Err()
		rows.Close()
		if e != nil || err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		out["devices"], out["sims"], out["contacts"] = devices, sims, contacts
	}
	if tx.Commit() != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, out)
}

// A display snapshot is deliberately not named cursor: it must never acknowledge
// the reliable ascending log, even if the history has millions of old messages.
func (a *app) recent(w http.ResponseWriter, r *http.Request) {
	limit, ok := number(r, "limit", 50)
	if !ok || limit < 1 || limit > 200 {
		fail(w, 400, "invalid_page")
		return
	}
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var head, epoch int64
	if tx.QueryRowContext(r.Context(), "SELECT seq,epoch FROM sync_clock WHERE id=1").Scan(&head, &epoch) != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	rows, err := tx.QueryContext(r.Context(), "SELECT "+messageColumns+" FROM conversation_heads h JOIN messages m ON m.id=h.message_id"+messageJoins+" ORDER BY h.timestamp DESC,h.message_id DESC LIMIT ?", limit)
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
	writeJSON(w, 200, map[string]any{"messages": ms, "head": head, "epoch": epoch})
}

type unreadSnapshot struct {
	epoch, head, watermark, revision, count int64
	valid                                   bool
}

// Callers supply a repeatable-read transaction. Cache keys are durable state,
// not wall-clock TTLs, so revocation and restored IDs cannot hit stale counts.
func (a *app) snapshotUnread(ctx context.Context, tx *sql.Tx, epoch, head int64) (int64, int64, error) {
	var watermark, revision int64
	if err := tx.QueryRowContext(ctx, "SELECT global_watermark_id,updated_at FROM read_state WHERE id=1").Scan(&watermark, &revision); err != nil {
		return 0, 0, err
	}
	a.unreadMu.Lock()
	defer a.unreadMu.Unlock()
	c := a.unreadSnapshot
	if c.valid && c.epoch == epoch && c.head == head && c.watermark == watermark && c.revision == revision {
		return watermark, c.count, nil
	}
	var count int64
	err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM messages m
LEFT JOIN conversation_reads cr ON cr.sender=m.sender
WHERE m.historical=FALSE AND m.direction='in' AND m.id>? AND m.id>COALESCE(cr.last_read_message_id,0)`, watermark).Scan(&count)
	if err == nil {
		a.unreadSnapshot = unreadSnapshot{epoch, head, watermark, revision, count, true}
	}
	return watermark, count, err
}

// Safe aggregate only: never expose a request path, query, identity or payload.
func serverTiming(w http.ResponseWriter, started time.Time) {
	w.Header().Set("Server-Timing", "app;dur="+timeDurationMS(time.Since(started)))
}
