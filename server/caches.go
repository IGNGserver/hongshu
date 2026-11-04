package main

import (
	"context"
	"time"
)

type metadataSnapshot struct {
	epoch    int64
	revision int64
	devices  []Device
	sims     []SIM
	contacts map[string]string
	valid    bool
}

type aiStatusSnapshot struct {
	enabled  bool
	paused   bool
	revision int64
	checked  time.Time
	valid    bool
}

func (a *app) cachedMetadata(epoch, revision int64) (metadataSnapshot, bool) {
	a.metadataMu.Lock()
	defer a.metadataMu.Unlock()
	if a.metadataCache.valid && a.metadataCache.epoch == epoch && a.metadataCache.revision == revision {
		return a.metadataCache, true
	}
	return metadataSnapshot{}, false
}

func (a *app) storeMetadata(snapshot metadataSnapshot) {
	a.metadataMu.Lock()
	a.metadataCache = snapshot
	a.metadataMu.Unlock()
}

// cachedAIStatus is advisory only: claimAnalysis still reloads and rechecks settings under
// the row lock. The short TTL keeps a second hub's settings change visible without making
// every idle worker do a SELECT per second.
func (a *app) cachedAIStatus(ctx context.Context) (aiStatusSnapshot, error) {
	a.aiMu.Lock()
	cached := a.aiStatusCache
	a.aiMu.Unlock()
	if cached.valid && time.Since(cached.checked) < 5*time.Second {
		return cached, nil
	}
	var fresh aiStatusSnapshot
	fresh.checked = time.Now()
	err := a.db.QueryRowContext(ctx, "SELECT enabled,paused,revision FROM ai_settings WHERE id=1").Scan(&fresh.enabled, &fresh.paused, &fresh.revision)
	if err != nil {
		return fresh, err
	}
	fresh.valid = true
	a.aiMu.Lock()
	a.aiStatusCache = fresh
	a.aiMu.Unlock()
	return fresh, nil
}

func (a *app) invalidateAIStatus() {
	a.aiMu.Lock()
	a.aiStatusCache.valid = false
	a.aiMu.Unlock()
}
