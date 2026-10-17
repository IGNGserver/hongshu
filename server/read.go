package main

import (
	"database/sql"
	"net/http"
	"time"
)

func (a *app) getUnreadState(r *http.Request) (int64, int64, error) {
	var watermark int64
	err := a.db.QueryRowContext(r.Context(), "SELECT COALESCE(global_watermark_id,0) FROM read_state WHERE id=1").Scan(&watermark)
	if err == sql.ErrNoRows {
		watermark = 0
		err = nil
	} else if err != nil {
		return 0, 0, err
	}

	var unreadCount int64
	query := `SELECT COUNT(*) FROM messages m
LEFT JOIN conversation_reads cr ON cr.sender=m.sender
WHERE m.historical=FALSE AND m.id>? AND m.id>COALESCE(cr.last_read_message_id,0)`
	err = a.db.QueryRowContext(r.Context(), query, watermark).Scan(&unreadCount)
	if err != nil {
		return watermark, 0, err
	}
	return watermark, unreadCount, nil
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

	now := time.Now().UnixMilli()
	if in.All {
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

		if _, err = tx.ExecContext(r.Context(), `INSERT INTO read_state (id, global_watermark_id, updated_at)
VALUES (1, ?, ?) ON DUPLICATE KEY UPDATE global_watermark_id=GREATEST(global_watermark_id, VALUES(global_watermark_id)), updated_at=VALUES(updated_at)`, seq, now); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}

		if err = tx.Commit(); err != nil {
			fail(w, 503, "database_unavailable")
			return
		}

		a.broadcastChange()
		writeJSON(w, 200, map[string]any{"ok": true, "global_watermark_id": seq, "unread_count": 0})
		return
	}

	if in.Sender == "" {
		fail(w, 400, "invalid_sender")
		return
	}

	targetMessageID := in.MessageID
	if targetMessageID <= 0 {
		err := a.db.QueryRowContext(r.Context(), "SELECT COALESCE(MAX(id),0) FROM messages WHERE sender=?", in.Sender).Scan(&targetMessageID)
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}

	if targetMessageID > 0 {
		_, err := a.db.ExecContext(r.Context(), `INSERT INTO conversation_reads (sender, last_read_message_id, read_at)
VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_read_message_id=GREATEST(last_read_message_id, VALUES(last_read_message_id)), read_at=VALUES(read_at)`, in.Sender, targetMessageID, now)
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}

	a.broadcastChange()
	watermark, unread, err := a.getUnreadState(r)
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "global_watermark_id": watermark, "unread_count": unread})
}

func (a *app) attachUnreadCounts(r *http.Request, ms []Message) ([]ConversationItem, error) {
	if len(ms) == 0 {
		return []ConversationItem{}, nil
	}

	var watermark int64
	err := a.db.QueryRowContext(r.Context(), "SELECT COALESCE(global_watermark_id,0) FROM read_state WHERE id=1").Scan(&watermark)
	if err != nil && err != sql.ErrNoRows {
		return nil, err
	}

	rows, err := a.db.QueryContext(r.Context(), `
SELECT m.sender, COUNT(*)
FROM messages m
LEFT JOIN conversation_reads cr ON cr.sender = m.sender
WHERE m.historical = FALSE
  AND m.id > ?
  AND m.id > COALESCE(cr.last_read_message_id, 0)
GROUP BY m.sender
`, watermark)
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
		res[i] = ConversationItem{
			Message:     m,
			UnreadCount: counts[m.Sender],
		}
	}
	return res, nil
}
