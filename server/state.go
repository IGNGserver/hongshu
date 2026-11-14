package main

import (
	"context"
	"time"
)

type hubState struct {
	cursor   int64
	epoch    int64
	analysis int64
	read     int64
	metadata int64
	calls    int64
}

func (a *app) readHubState(ctx context.Context) (hubState, error) {
	var state hubState
	err := a.db.QueryRowContext(ctx, `SELECT c.seq,ai.revision,r.updated_at,m.revision,c.epoch,k.seq
FROM sync_clock c
JOIN ai_settings ai ON ai.id=c.id
JOIN read_state r ON r.id=c.id
JOIN metadata_clock m ON m.id=c.id
JOIN call_clock k ON k.id=c.id
WHERE c.id=1`).Scan(&state.cursor, &state.analysis, &state.read, &state.metadata, &state.epoch, &state.calls)
	return state, err
}

func (a *app) refreshHubState(ctx context.Context) {
	state, err := a.readHubState(ctx)
	if err != nil {
		return
	}
	a.stateMu.Lock()
	changed := !a.stateValid || state != a.state
	a.state = state
	a.stateValid = true
	a.stateMu.Unlock()
	if changed {
		a.broadcastChange()
	}
}

func (a *app) currentHubState() (hubState, bool) {
	a.stateMu.RLock()
	defer a.stateMu.RUnlock()
	return a.state, a.stateValid
}

// One poller per process replaces a database query per WebSocket connection. Local writes
// still trigger an immediate refresh; the interval catches direct/external writers.
func (a *app) startStatePoller(ctx context.Context) {
	a.stateOnce.Do(func() {
		interval := a.statePollInterval
		if interval <= 0 {
			interval = time.Second
		}
		a.startWorker(ctx, func(ctx context.Context) {
			a.refreshHubState(ctx)
			ticker := time.NewTicker(interval)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					a.refreshHubState(ctx)
				case <-a.stateRefresh:
					a.refreshHubState(ctx)
				}
			}
		})
	})
}

func (a *app) requestStateRefresh() {
	if a.stateRefresh == nil {
		return
	}
	select {
	case a.stateRefresh <- struct{}{}:
	default:
	}
}

func signalWork(ch chan struct{}) {
	if ch == nil {
		return
	}
	select {
	case ch <- struct{}{}:
	default:
	}
}

func (a *app) wakeWork() {
	signalWork(a.analysisWake)
	signalWork(a.backfillWake)
}

// notifyChange is the post-commit path: local subscribers, one state refresh, local worker
// wakeups, and (when configured) a Redis event for other hub processes.
func (a *app) notifyChange() {
	a.requestStateRefresh()
	a.broadcastChange()
	a.wakeWork()
	if a.redis != nil && a.redisEvents != nil {
		select {
		case a.redisEvents <- struct{}{}:
		default:
		}
	}
}

func (a *app) remoteChange() {
	a.requestStateRefresh()
	a.broadcastChange()
	signalWork(a.pushWake)
	a.wakeWork()
}

func waitFor(ctx context.Context, ch <-chan struct{}, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-ch:
		return true
	case <-timer.C:
		return true
	}
}
