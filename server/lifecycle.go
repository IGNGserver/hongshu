package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"sync"
	"time"
)

type lifecycleHooks struct {
	start    func(context.Context) error
	waitHTTP func()
	waitWork func()
	close    func() error
}

type trackedHTTPHandler struct {
	handler http.Handler
	wg      sync.WaitGroup
}

func (h *trackedHTTPHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.wg.Add(1)
	defer h.wg.Done()
	h.handler.ServeHTTP(w, r)
}

func (a *app) startWorker(ctx context.Context, worker func(context.Context)) {
	a.workers.Add(1)
	a.workersRunning.Add(1)
	go func() {
		defer a.workers.Done()
		defer a.workersRunning.Add(-1)
		defer a.workersExited.Add(1)
		worker(ctx)
	}()
}

func (a *app) closeResources() error {
	a.pushClientMu.Lock()
	if a.pushClient != nil {
		a.pushClient.CloseIdleConnections()
	}
	a.pushClientMu.Unlock()
	a.aiClientsMu.Lock()
	for key, client := range a.aiClients {
		client.CloseIdleConnections()
		delete(a.aiClients, key)
	}
	a.aiClientsMu.Unlock()
	if a.redis != nil {
		return a.redis.client.Close()
	}
	return nil
}

// runLifecycle owns the order that main previously left to racing goroutines and
// process exit. The listener stops first; workers and their base context remain
// alive during the bounded HTTP drain, then every handler and worker is joined
// before shared transports, Redis or the DB are closed.
func runLifecycle(ctx context.Context, srv *http.Server, serve func() error, hooks lifecycleHooks) error {
	workerCtx, stopWorkers := context.WithCancel(context.Background())
	defer stopWorkers()
	srv.BaseContext = func(net.Listener) context.Context { return workerCtx }
	if hooks.start != nil {
		if err := hooks.start(workerCtx); err != nil {
			stopWorkers()
			if hooks.waitWork != nil {
				hooks.waitWork()
			}
			if hooks.close != nil {
				return errors.Join(err, hooks.close())
			}
			return err
		}
	}
	serveResult := make(chan error, 1)
	go func() { serveResult <- serve() }()

	var runErr error
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		shutdownErr := srv.Shutdown(shutdownCtx)
		cancel()
		if shutdownErr != nil {
			// Shutdown does not close hijacked WebSockets and a deadline can leave
			// active handlers. Cancel their base context and force-close the rest.
			stopWorkers()
			_ = srv.Close()
			runErr = shutdownErr
		}
		if err := <-serveResult; err != nil && !errors.Is(err, http.ErrServerClosed) {
			runErr = errors.Join(runErr, err)
		}
	case err := <-serveResult:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			runErr = err
		}
	}

	stopWorkers()
	if hooks.waitHTTP != nil {
		hooks.waitHTTP()
	}
	if hooks.waitWork != nil {
		hooks.waitWork()
	}
	if hooks.close != nil {
		runErr = errors.Join(runErr, hooks.close())
	}
	return runErr
}
