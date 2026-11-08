package main

import (
	"context"
	"io"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"
)

func TestLifecycleDrainsHTTPBeforeStoppingWorkersAndClosing(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{})
	release := make(chan struct{})
	tracked := &trackedHTTPHandler{handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-release
		w.WriteHeader(http.StatusNoContent)
	})}
	srv := &http.Server{Handler: tracked}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	workerExited := make(chan struct{})
	var workers sync.WaitGroup
	var mu sync.Mutex
	events := []string{}
	record := func(event string) {
		mu.Lock()
		events = append(events, event)
		mu.Unlock()
	}
	done := make(chan error, 1)
	go func() {
		done <- runLifecycle(ctx, srv, func() error { return srv.Serve(listener) }, lifecycleHooks{
			start: func(workerCtx context.Context) error {
				workers.Add(1)
				go func() {
					defer workers.Done()
					<-workerCtx.Done()
					record("worker")
					close(workerExited)
				}()
				return nil
			},
			waitHTTP: tracked.wg.Wait,
			waitWork: workers.Wait,
			close: func() error {
				record("close")
				return nil
			},
		})
	}()
	response := make(chan error, 1)
	go func() {
		res, err := http.Get("http://" + listener.Addr().String() + "/")
		if err == nil {
			_, _ = io.Copy(io.Discard, res.Body)
			res.Body.Close()
			if res.StatusCode != http.StatusNoContent {
				t.Errorf("status = %d", res.StatusCode)
			}
		}
		response <- err
	}()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("request did not enter handler")
	}
	cancel()
	select {
	case <-workerExited:
		t.Fatal("worker stopped before the in-flight HTTP request drained")
	case <-time.After(75 * time.Millisecond):
	}
	close(release)
	select {
	case err := <-response:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("drained response did not finish")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("lifecycle did not finish")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(events) != 2 || events[0] != "worker" || events[1] != "close" {
		t.Fatalf("resource shutdown order = %v", events)
	}
}
