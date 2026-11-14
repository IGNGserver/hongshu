package main

import (
	"context"
	"database/sql"
	"net/http"
	"strings"
	"time"
)

func (a *app) unreadWithinTx(ctx context.Context, tx *sql.Tx, watermark int64, excludeDevice string) (int64, error) {
	var epoch, unreadHead, revision int64
	if err := tx.QueryRowContext(ctx, "SELECT c.epoch,(SELECT COALESCE(MAX(id),0) FROM messages WHERE historical=FALSE AND direction='in'),r.updated_at FROM sync_clock c JOIN read_state r ON r.id=c.id WHERE c.id=1").Scan(&epoch, &unreadHead, &revision); err != nil {
		return 0, err
	}
	return a.snapshotUnreadForDevice(ctx, tx, epoch, unreadHead, watermark, revision, excludeDevice)
}

func (a *app) markRead(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Sender    string `json:"sender"`
		MessageID int64  `json:"message_id"`
		All       bool   `json:"all"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.MessageID < 0 {
		fail(w, 400, "invalid_message_id")
		return
	}
	if !in.All && (in.Sender == "" || len(in.Sender) > 100 || strings.ContainsRune(in.Sender, 0)) {
		fail(w, 400, "invalid_sender")
		return
	}
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{Isolation: sql.LevelRepeatableRead})
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var seq int64
	if in.All {
		// Global reads share ingestion's ordering: clock, then read_state.
		if err = tx.QueryRowContext(r.Context(), "SELECT seq FROM sync_clock WHERE id=1 FOR UPDATE").Scan(&seq); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if in.MessageID > 0 {
			seq = min(seq, in.MessageID)
		}
	}
	// Serialize all read mutations (including manual repairs) through this row.
	// Conversation reads must never acquire sync_clock after this lock.
	var watermark int64
	if err = tx.QueryRowContext(r.Context(), "SELECT global_watermark_id FROM read_state WHERE id=1 FOR UPDATE").Scan(&watermark); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	now := time.Now().UnixMilli()
	if in.All {
		changed := seq > watermark
		if changed {
			if _, err = tx.ExecContext(r.Context(), "UPDATE read_state SET global_watermark_id=?,updated_at=GREATEST(updated_at+1,?) WHERE id=1", seq, now); err != nil {
				fail(w, 503, "database_unavailable")
				return
			}
		}
		unread, unreadErr := a.unreadWithinTx(r.Context(), tx, max(watermark, seq), unreadExcludeDevice(device(r)))
		if unreadErr != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if err = tx.Commit(); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if changed {
			a.notifyChange()
		}
		writeJSON(w, 200, map[string]any{"ok": true, "global_watermark_id": max(watermark, seq), "unread_count": unread})
		return
	}

	targetMessageID := in.MessageID
	if targetMessageID == 0 {
		err = tx.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(id),0) FROM messages WHERE sender=?", in.Sender).Scan(&targetMessageID)
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	} else {
		// Messages are immutable and cannot be deleted through the API. Never
		// accept a future ID or an ID belonging to another conversation.
		var exists bool
		if err = tx.QueryRowContext(r.Context(), "SELECT EXISTS(SELECT 1 FROM messages WHERE id=? AND sender=?)", targetMessageID, in.Sender).Scan(&exists); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		if !exists {
			fail(w, 400, "invalid_message_id")
			return
		}
	}

	var previous int64
	err = tx.QueryRowContext(r.Context(), "SELECT last_read_message_id FROM conversation_reads WHERE sender=? FOR UPDATE", in.Sender).Scan(&previous)
	if err == sql.ErrNoRows {
		previous = 0
	} else if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	changed := targetMessageID > previous
	if changed {
		_, err = tx.ExecContext(r.Context(), `INSERT INTO conversation_reads (sender, last_read_message_id, read_at)
VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_read_message_id=VALUES(last_read_message_id),read_at=VALUES(read_at)`, in.Sender, targetMessageID, now)
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		// Only an effective watermark advance invalidates other clients and caches.
		if _, err = tx.ExecContext(r.Context(), "UPDATE read_state SET updated_at=GREATEST(updated_at+1,?) WHERE id=1", now); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}
	// Derive the response from the same RR transaction as the mutation. A commit
	// followed by independent queries could combine different global watermarks.
	unread, err := a.unreadWithinTx(r.Context(), tx, watermark, unreadExcludeDevice(device(r)))
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if err = tx.Commit(); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if changed {
		a.notifyChange()
	}
	writeJSON(w, 200, map[string]any{"ok": true, "global_watermark_id": watermark, "unread_count": unread})
}

type contextQueryer interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}

func (a *app) attachUnreadCounts(ctx context.Context, q contextQueryer, ms []Message, excludeDevice string) ([]ConversationItem, error) {
	if len(ms) == 0 {
		return []ConversationItem{}, nil
	}
	args := make([]any, 0, len(ms)+1)
	for _, m := range ms {
		args = append(args, m.Sender)
	}
	deviceFilter := ""
	if excludeDevice != "" {
		deviceFilter = "\n  AND m.device_id <> ?"
		args = append(args, excludeDevice)
	}
	// Fold the singleton watermark into the aggregate so a conversation page needs one
	// query after the page query, not two.
	rows, err := q.QueryContext(ctx, `
SELECT m.sender, COUNT(*)
FROM messages m
LEFT JOIN conversation_reads cr ON cr.sender = m.sender
WHERE m.historical = FALSE
  AND m.direction = 'in'
  AND m.id > COALESCE((SELECT global_watermark_id FROM read_state WHERE id=1), 0)
  AND m.id > COALESCE(cr.last_read_message_id, 0)
  AND m.sender IN (?`+strings.Repeat(",?", len(ms)-1)+`)`+deviceFilter+`
GROUP BY m.sender
`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	counts := make(map[string]int64)
	for rows.Next() {
		var s string
		var c int64
		if err := rows.Scan(&s, &c); err != nil {
			return nil, err
		}
		counts[s] = c
	}
	if rows.Err() != nil {
		return nil, rows.Err()
	}
	res := make([]ConversationItem, len(ms))
	for i, m := range ms {
		res[i] = ConversationItem{Message: m, UnreadCount: counts[m.Sender]}
	}
	return res, nil
}
