package main

import (
	"context"
	"database/sql"
	"log"
	"time"
)

// Background workers for analysis: the scheduled claim loop, result persistence with
// retry, and the historical backfill loop. Provider calls go through
// analysis_provider.go; queue state stays in analysis_queue.go.

func (a *app) finishAnalysis(ctx context.Context, j *analysisJob, category, otp, errCode string, pause bool) error {
	tx, e := a.db.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	s, e := loadAI(ctx, tx, true)
	if e != nil {
		return e
	}
	if s.Generation != j.Settings.Generation {
		return nil
	}
	var token string
	var attempts int
	e = tx.QueryRowContext(ctx, "SELECT lease_token,attempts FROM message_analysis WHERE message_id=? AND manual_override=FALSE FOR UPDATE", j.ID).Scan(&token, &attempts)
	if e == sql.ErrNoRows {
		return nil
	}
	if e != nil {
		return e
	}
	if token != j.Token {
		return nil
	}
	if errCode != "" || category != "otp" {
		otp = ""
	}
	state := "complete"
	next := int64(0)
	if errCode != "" {
		state = "pending"
		next = time.Now().Add(time.Duration(attempts*attempts) * 10 * time.Second).UnixMilli()
		if attempts >= 3 || pause || permanentMessageAIError(errCode) {
			state = "failed"
		}
	}
	rev, e := analysisRevision(ctx, tx)
	if e != nil {
		return e
	}
	// An empty AI code keeps the rule result, which is then hidden from the API
	// when the final category is not otp. A non-empty code is validated against
	// the original body before it gets here.
	if _, e = tx.ExecContext(ctx, "UPDATE message_analysis SET category=?,otp=CASE WHEN ?<>'' THEN ? ELSE otp END,state=?,error=?,next_at=?,lease_token='',leased_until=0,revision=? WHERE message_id=?", category, otp, otp, state, errCode, next, rev, j.ID); e != nil {
		return e
	}
	if pause {
		if _, e = tx.ExecContext(ctx, "UPDATE ai_settings SET paused=TRUE,last_error=? WHERE id=1", errCode); e != nil {
			return e
		}
	}
	if e = tx.Commit(); e == nil {
		a.invalidateAIStatus()
		a.notifyChange()
	}
	return e
}

func (a *app) analysisBackfillLoop(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		if !a.analysisLeader(ctx) {
			if !waitFor(ctx, a.backfillWake, 5*time.Second) {
				return
			}
			continue
		}
		if a.backfillDone.Load() {
			if !waitFor(ctx, a.backfillWake, 30*time.Second) {
				return
			}
			continue
		}
		if err := a.backfillAnalysis(ctx); err != nil {
			if !waitFor(ctx, a.backfillWake, 2*time.Second) {
				return
			}
			continue
		}
		if a.backfillDone.Load() {
			if !waitFor(ctx, a.backfillWake, 30*time.Second) {
				return
			}
		}
	}
}

func (a *app) finishAnalysisWithRetry(ctx context.Context, j *analysisJob, category, otp, code string, pause bool) error {
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		if err = a.finishAnalysis(ctx, j, category, otp, code, pause); err == nil || ctx.Err() != nil {
			return err
		}
		a.analysisFinishFailures.Add(1)
		timer := time.NewTimer(time.Duration(100*(1<<attempt)) * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
	return err
}

func (a *app) analysisLoop(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		if !a.analysisLeader(ctx) {
			if !waitFor(ctx, a.analysisWake, 5*time.Second) {
				return
			}
			continue
		}
		status, err := a.cachedAIStatus(ctx)
		if err != nil {
			if !waitFor(ctx, a.analysisWake, 5*time.Second) {
				return
			}
			continue
		}
		if !status.enabled || status.paused {
			if !waitFor(ctx, a.analysisWake, 30*time.Second) {
				return
			}
			continue
		}
		a.analysisLastLoop.Store(time.Now().UnixMilli())
		j, schedule, err := a.claimAnalysisScheduled(ctx)
		if err != nil {
			if !waitFor(ctx, a.analysisWake, schedule.retryAfter) {
				return
			}
			continue
		}
		if j == nil {
			if !waitFor(ctx, a.analysisWake, schedule.retryAfter) {
				return
			}
			continue
		}
		category, otp, code, pause := classifySMSWithClient(ctx, j.Settings, j.Body, j.OTP, a.effectiveAIClient(j.Settings))
		if ctx.Err() != nil {
			return // leave the durable lease for restart
		}
		if a.noteAIResult(j.Settings.Generation, code) {
			pause = true
		}
		finishErr := a.finishAnalysisWithRetry(ctx, j, category, otp, code, pause)
		if finishErr == nil {
			a.analysisLastSuccess.Store(time.Now().UnixMilli())
		}
		if finishErr != nil && ctx.Err() == nil {
			failures := a.analysisFinishFailures.Load()
			if failures <= 3 || failures%20 == 0 {
				log.Printf("analysis result persistence failed %d times; durable lease retained", failures)
			}
			if !waitFor(ctx, a.analysisWake, 2*time.Second) {
				return
			}
		}
	}
}
