package main

import (
	"context"
	"database/sql"
	"fmt"
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
	a.notifyChange()
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
		if cached, ok := a.cachedMetadata(epoch, revision); ok {
			out["devices"], out["sims"], out["contacts"] = cached.devices, cached.sims, cached.contacts
			if tx.Commit() != nil {
				fail(w, 503, "database_unavailable")
				return
			}
			writeJSON(w, 200, out)
			return
		}
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
		a.storeMetadata(metadataSnapshot{epoch: epoch, revision: revision, devices: devices, sims: sims, contacts: contacts, valid: true})
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

type unreadCall struct {
	done  chan struct{}
	count int64
	err   error
}

// Callers supply a repeatable-read transaction and durable state read from that
// snapshot. Equal keys share one in-flight COUNT per process; waiters never hold
// the cache mutex or issue a duplicate query during a cache miss.
func (a *app) snapshotUnread(ctx context.Context, tx *sql.Tx, epoch, head, watermark, revision int64) (int64, error) {
	key := fmt.Sprintf("%d/%d/%d/%d", epoch, head, watermark, revision)
	a.unreadMu.Lock()
	cached := a.unreadSnapshot
	a.unreadMu.Unlock()
	if cached.valid && cached.epoch == epoch && cached.head == head && cached.watermark == watermark && cached.revision == revision {
		return cached.count, nil
	}
	redisKey := ""
	if a.redis != nil {
		redisKey = a.redis.key("unread", fmt.Sprintf("%d", epoch), fmt.Sprintf("%d", head), fmt.Sprintf("%d", watermark), fmt.Sprintf("%d", revision))
		if count, ok := a.redis.getInt(ctx, redisKey); ok {
			return count, nil
		}
	}

	a.unreadMu.Lock()
	cached = a.unreadSnapshot
	if cached.valid && cached.epoch == epoch && cached.head == head && cached.watermark == watermark && cached.revision == revision {
		a.unreadMu.Unlock()
		return cached.count, nil
	}
	if a.unreadFlights == nil {
		a.unreadFlights = make(map[string]*unreadCall)
	}
	if existing := a.unreadFlights[key]; existing != nil {
		a.unreadMu.Unlock()
		select {
		case <-existing.done:
			return existing.count, existing.err
		case <-ctx.Done():
			return 0, ctx.Err()
		}
	}
	flight := &unreadCall{done: make(chan struct{})}
	a.unreadFlights[key] = flight
	a.unreadMu.Unlock()

	var count int64
	a.unreadQueries.Add(1)
	err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM messages m
LEFT JOIN conversation_reads cr ON cr.sender=m.sender
WHERE m.historical=FALSE AND m.direction='in' AND m.id>? AND m.id>COALESCE(cr.last_read_message_id,0)`, watermark).Scan(&count)
	flight.count, flight.err = count, err
	if err == nil {
		a.unreadMu.Lock()
		a.unreadSnapshot = unreadSnapshot{epoch, head, watermark, revision, count, true}
		a.unreadMu.Unlock()
		if a.redis != nil {
			a.redis.setInt(ctx, redisKey, count, 30*time.Second)
		}
	}
	a.unreadMu.Lock()
	delete(a.unreadFlights, key)
	close(flight.done)
	a.unreadMu.Unlock()
	return count, err
}

// Safe aggregate only: never expose a request path, query, identity or payload.
func serverTiming(w http.ResponseWriter, started time.Time) {
	w.Header().Set("Server-Timing", "app;dur="+timeDurationMS(time.Since(started)))
}
