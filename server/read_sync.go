package main

import (
	"database/sql"
	"net/http"
	"strings"
)

type readWatermark struct {
	Sender    string `json:"sender"`
	MessageID int64  `json:"message_id"`
}

// Each page observes one consistent snapshot. The revision/epoch fence prevents
// a client from declaring a multi-page snapshot complete across concurrent reads
// or a database restore. Read markers are shared by all authorized devices.
func (a *app) readState(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	after := q.Get("after")
	limit, ok := pageLimit(r)
	epoch, okEpoch := number(r, "epoch", 0)
	revision, okRevision := number(r, "revision", -1)
	since, okSince := number(r, "since", -1)
	head, okHead := number(r, "head", -1)
	if !ok || !okEpoch || !okRevision || !okSince || !okHead || len(after) > 100 || strings.ContainsRune(after, 0) ||
		(after != "" && (revision < 0 || epoch == 0)) || (revision >= 0 && head < 0) ||
		(since >= 0 && (epoch == 0 || head < 0 || after != "" || revision >= 0)) {
		fail(w, 400, "invalid_cursor")
		return
	}
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	if err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	defer tx.Rollback()
	var currentEpoch, currentRevision, currentHead, global int64
	if err = tx.QueryRowContext(r.Context(), "SELECT c.epoch,c.seq,r.updated_at,r.global_watermark_id FROM sync_clock c JOIN read_state r ON r.id=c.id WHERE c.id=1").Scan(&currentEpoch, &currentHead, &currentRevision, &global); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	if epoch != 0 && epoch != currentEpoch {
		fail(w, 409, "epoch_changed")
		return
	}
	if revision >= 0 && (revision != currentRevision || head != currentHead) {
		fail(w, 409, "read_state_changed")
		return
	}
	items := []readWatermark{}
	if since != currentRevision || head != currentHead {
		// Do not replicate legacy invalid boundaries into every client's cache.
		query := "SELECT cr.sender,IF(m.id IS NULL,0,cr.last_read_message_id) FROM conversation_reads cr LEFT JOIN messages m ON m.id=cr.last_read_message_id AND m.sender=cr.sender"
		args := []any{}
		if after != "" {
			query += " WHERE cr.sender>?"
			args = append(args, after)
		}
		args = append(args, limit+1)
		rows, err := tx.QueryContext(r.Context(), query+" ORDER BY cr.sender LIMIT ?", args...)
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
		for rows.Next() {
			var item readWatermark
			if err = rows.Scan(&item.Sender, &item.MessageID); err != nil {
				rows.Close()
				fail(w, 503, "database_unavailable")
				return
			}
			items = append(items, item)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			fail(w, 503, "database_unavailable")
			return
		}
	}
	more := int64(len(items)) > limit
	next := ""
	if more {
		items = items[:limit]
		next = items[len(items)-1].Sender
	}
	if err = tx.Commit(); err != nil {
		fail(w, 503, "database_unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{"items": items, "global_watermark_id": global, "revision": currentRevision, "head": currentHead, "epoch": currentEpoch, "more": more, "next": next})
}
