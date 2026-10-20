package main

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// Exercise the real websocket handler with a query-recording store. Unlike a
// source-text assertion, this also catches expensive queries added by helpers.
type wsQueryStore struct {
	mu       sync.Mutex
	revision int64
	metadata int64
	epoch    int64
	states   int
	denied   []string
}

func (s *wsQueryStore) Connect(context.Context) (driver.Conn, error) { return &wsQueryConn{s}, nil }
func (s *wsQueryStore) Driver() driver.Driver                        { return wsQueryDriver{s} }

type wsQueryDriver struct{ store *wsQueryStore }

func (d wsQueryDriver) Open(string) (driver.Conn, error) { return &wsQueryConn{d.store}, nil }

type wsQueryConn struct{ store *wsQueryStore }

func (*wsQueryConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("unexpected prepare")
}
func (*wsQueryConn) Close() error              { return nil }
func (*wsQueryConn) Begin() (driver.Tx, error) { return nil, errors.New("unexpected transaction") }
func (c *wsQueryConn) QueryContext(_ context.Context, query string, _ []driver.NamedValue) (driver.Rows, error) {
	c.store.mu.Lock()
	defer c.store.mu.Unlock()
	if strings.HasPrefix(query, "SELECT id,name,kind,admin,upload,notify,revoked,created_at FROM devices WHERE token_hash=") {
		return &wsQueryRows{columns: []string{"id", "name", "kind", "admin", "upload", "notify", "revoked", "created_at"}, values: []driver.Value{"synthetic", "Synthetic", "web", true, false, true, false, int64(0)}}, nil
	}
	lower := strings.ToLower(query)
	if strings.Contains(lower, "messages") || strings.Contains(lower, "conversation_reads") || strings.Contains(lower, "count(") || !strings.Contains(lower, "read_state") || !strings.Contains(lower, "updated_at") || !strings.Contains(lower, "sync_clock") || !strings.Contains(lower, "ai_settings") || !strings.Contains(lower, "metadata_clock") {
		c.store.denied = append(c.store.denied, query)
		return nil, errors.New("websocket must query singleton state only")
	}
	c.store.states++
	return &wsQueryRows{columns: []string{"seq", "revision", "updated_at", "metadata_revision", "epoch"}, values: []driver.Value{int64(10), int64(20), c.store.revision, c.store.metadata, c.store.epoch}}, nil
}

type wsQueryRows struct {
	columns []string
	values  []driver.Value
}

func (r *wsQueryRows) Columns() []string { return r.columns }
func (*wsQueryRows) Close() error        { return nil }
func (r *wsQueryRows) Next(dest []driver.Value) error {
	if r.values == nil {
		return io.EOF
	}
	copy(dest, r.values)
	r.values = nil
	return nil
}

func TestWebSocketReadHintsNeverScanMessages(t *testing.T) {
	store := &wsQueryStore{revision: 1000}
	db := sql.OpenDB(store)
	defer db.Close()
	a := &app{db: db, subscribers: make(map[chan struct{}]struct{}), wsCheckInterval: 10 * time.Millisecond}
	srv := httptest.NewServer(a.routes("../web"))
	defer srv.Close()
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/api/ws", http.Header{"Authorization": []string{"Bearer " + strings.Repeat("a", 64)}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	readHint := func() {
		t.Helper()
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		var change map[string]any
		if err := conn.ReadJSON(&change); err != nil {
			t.Fatal("singleton-only websocket hint missing", err)
		}
		if change["type"] != "changed" || change["cursor"] != float64(10) || change["analysis_revision"] != float64(20) {
			t.Fatal("read-only hint altered message/analysis cursors", change)
		}
		if _, exposed := change["read_revision"]; exposed {
			t.Fatal("internal read marker leaked into client protocol")
		}
		if _, counted := change["unread_count"]; counted {
			t.Fatal("websocket computed unread count instead of a change hint")
		}
	}
	readHint()
	// Cosmetic changes and restores must invalidate clients without changing the
	// SMS head, and without introducing scans into each socket's polling loop.
	store.mu.Lock()
	store.metadata++
	store.mu.Unlock()
	a.broadcastChange()
	readHint()
	store.mu.Lock()
	store.epoch++
	store.mu.Unlock()
	readHint()
	store.mu.Lock()
	store.revision++
	store.mu.Unlock()
	a.broadcastChange()
	readHint()
	// A durable read marker also reaches sockets without an in-process broadcast
	// (e.g. another hub process, or the manual repair SQL).
	store.mu.Lock()
	store.revision++
	store.mu.Unlock()
	readHint()
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.states < 3 || len(store.denied) != 0 {
		t.Fatal("unexpected websocket queries", store.states, store.denied)
	}
}
