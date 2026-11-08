package main

import (
	"context"
	"net/http"
	"time"
)

// Operational snapshot for operators. Every field is a counter, duration or boolean:
// no SMS body, sender, device token, Push endpoint or AI secret can reach it, and the
// handler never reads message content, so it is safe to poll during an incident.
type opsSnapshot struct {
	Status   string         `json:"status"`
	Version  string         `json:"version"`
	UptimeMS int64          `json:"uptime_ms"`
	Database map[string]any `json:"database"`
	Schema   map[string]any `json:"schema"`
	Push     map[string]any `json:"push"`
	Analysis map[string]any `json:"analysis"`
	Workers  map[string]any `json:"workers"`
	Realtime map[string]any `json:"realtime"`
	Redis    map[string]any `json:"redis"`
}

func (a *app) opsStatus(w http.ResponseWriter, r *http.Request) {
	// The probe path stays cheap; this endpoint is polled rarely by an operator.
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	started := time.Now()
	pingErr := a.db.PingContext(ctx)
	stats := a.db.Stats()
	snapshot := opsSnapshot{
		Status:   "ok",
		Version:  buildVersion,
		UptimeMS: time.Since(a.startedAt).Milliseconds(),
		Database: map[string]any{
			"ok":         pingErr == nil,
			"latency_ms": float64(time.Since(started).Microseconds()) / 1000,
			"open":       stats.OpenConnections,
			"in_use":     stats.InUse,
			"idle":       stats.Idle,
			"max_open":   stats.MaxOpenConnections,
			"wait_count": stats.WaitCount,
			"wait_ms":    stats.WaitDuration.Milliseconds(),
		},
		Schema:   a.schemaStatus(ctx),
		Push:     a.pushStatus(ctx),
		Analysis: a.analysisStatus(ctx),
		Workers: map[string]any{
			"running": a.workersRunning.Load(),
			"exited":  a.workersExited.Load(),
		},
		Realtime: map[string]any{"subscribers": a.subscriberCount()},
		Redis:    map[string]any{"enabled": a.redis != nil},
	}
	if pingErr != nil {
		snapshot.Status = "unavailable"
		writeJSON(w, 503, snapshot)
		return
	}
	writeJSON(w, 200, snapshot)
}

// schemaStatus reports applied version and dirty rows so a failed migration is visible
// without shell access. A query error is reported as "unknown" instead of hiding it.
func (a *app) schemaStatus(ctx context.Context) map[string]any {
	var version string
	var dirty int
	if err := a.db.QueryRowContext(ctx, "SELECT COALESCE(MAX(version),''),(SELECT COUNT(*) FROM schema_migrations WHERE dirty=TRUE) FROM schema_migrations").Scan(&version, &dirty); err != nil {
		return map[string]any{"version": "unknown", "dirty": -1}
	}
	return map[string]any{"version": version, "dirty": dirty}
}

func (a *app) pushStatus(ctx context.Context) map[string]any {
	status := map[string]any{
		"enabled":           a.vapidPublic != "" && a.vapidPrivate != "",
		"claim_failures":    a.pushClaimFailures.Load(),
		"state_failures":    a.pushStateFailures.Load(),
		"delivery_failures": a.pushDeliveryFailures.Load(),
		"suppressed_read":   a.pushSuppressed.Load(),
		"dropped":           a.pushDropped.Load(),
		"last_success":      a.pushLastSuccess.Load(),
		"last_loop":         a.pushLastLoop.Load(),
	}
	// nil (not 0) means "could not be measured", so a stalled queue is not confused
	// with an empty one.
	var backlog int64
	if err := a.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM push_jobs").Scan(&backlog); err == nil {
		status["backlog"] = backlog
	} else {
		status["backlog"] = nil
	}
	return status
}

func (a *app) analysisStatus(ctx context.Context) map[string]any {
	status := map[string]any{
		"finish_failures": a.analysisFinishFailures.Load(),
		"last_success":    a.analysisLastSuccess.Load(),
		"last_loop":       a.analysisLastLoop.Load(),
		"backfill_done":   a.backfillDone.Load(),
	}
	var queued int64
	if err := a.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM message_analysis WHERE state='pending'").Scan(&queued); err == nil {
		status["queued"] = queued
	} else {
		status["queued"] = nil
	}
	return status
}

func (a *app) subscriberCount() int {
	a.subsMu.Lock()
	defer a.subsMu.Unlock()
	return len(a.subscribers)
}
