package main

import (
	"context"
	"database/sql"
	"fmt"
	"net/http/httptest"
	"sync"
	"testing"
)

func TestConversationCursorValidation(t *testing.T) {
	r := httptest.NewRequest("GET", "/api/conversations?q=bank&limit=2", nil)
	cursor := conversationCursor{Version: 1, Timestamp: 1234, MessageID: 9, Head: 20, Epoch: 2, Filter: conversationFilterFingerprint(r)}
	encoded := encodeConversationCursor(cursor)
	decoded, err := decodeConversationCursor(encoded)
	if err != nil || decoded != cursor {
		t.Fatalf("cursor round trip: %+v, %v", decoded, err)
	}
	for _, invalid := range []string{"", "not-base64", encoded + "x"} {
		if _, err := decodeConversationCursor(invalid); err == nil {
			t.Fatalf("accepted invalid cursor %q", invalid)
		}
	}
	other := httptest.NewRequest("GET", "/api/conversations?q=other&limit=2", nil)
	if conversationFilterFingerprint(other) == cursor.Filter {
		t.Fatal("cursor did not bind the filter")
	}
}

func TestIntegrationConversationKeysetAndHeadFencing(t *testing.T) {
	a, c, token := unreadFixture(t)
	first := c.call("GET", "/api/conversations?limit=1", token, nil, 200)
	if first["more"] != true || first["next_cursor"] == "" || first["head"] == nil || first["epoch"] == nil {
		t.Fatalf("missing keyset metadata: %v", first)
	}
	second := c.call("GET", "/api/conversations?limit=1&cursor="+first["next_cursor"].(string), token, nil, 200)
	if second["more"] != false || len(second["conversations"].([]any)) != 1 {
		t.Fatalf("bad second page: %v", second)
	}
	firstSender := first["conversations"].([]any)[0].(map[string]any)["sender"]
	secondSender := second["conversations"].([]any)[0].(map[string]any)["sender"]
	if firstSender == secondSender {
		t.Fatal("keyset returned a duplicate conversation")
	}
	legacy := c.call("GET", "/api/conversations?offset=0&limit=1", token, nil, 200)
	if legacy["next_cursor"] != nil || legacy["more"] != nil {
		t.Fatal("legacy offset response changed shape")
	}
	c.call("GET", "/api/conversations?offset=0&cursor="+first["next_cursor"].(string), token, nil, 400)
	c.call("GET", "/api/conversations?cursor="+first["next_cursor"].(string)+"x", token, nil, 400)

	// A committed new head cannot be combined with the old first page. The client
	// must restart rather than silently skipping a moved conversation.
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{{Receiver: "+123456789", Sender: "10000", Body: "new head", Timestamp: 4000}}}, 200)
	reset := c.call("GET", "/api/conversations?limit=1&cursor="+first["next_cursor"].(string), token, nil, 409)
	if reset["error"] != "cursor_reset" {
		t.Fatalf("stale head was accepted: %v", reset)
	}
	if me := c.call("GET", "/api/me", token, nil, 200); !containsString(me["features"].([]any), "conversation_cursor") {
		t.Fatal("hub did not advertise cursor support")
	}
	_ = a
}

func TestIntegrationNoOpReadAndUnreadSingleflight(t *testing.T) {
	a, c, token := unreadFixture(t)
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 3}, 200)
	var revision int64
	if err := a.db.QueryRow("SELECT updated_at FROM read_state WHERE id=1").Scan(&revision); err != nil {
		t.Fatal(err)
	}
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 1}, 200)
	var after int64
	if err := a.db.QueryRow("SELECT updated_at FROM read_state WHERE id=1").Scan(&after); err != nil {
		t.Fatal(err)
	}
	if after != revision {
		t.Fatalf("no-op read advanced revision %d -> %d", revision, after)
	}

	// Use one durable state key and an independent RR transaction per caller.
	var epoch, head, watermark int64
	if err := a.db.QueryRow("SELECT epoch,seq,(SELECT global_watermark_id FROM read_state WHERE id=1) FROM sync_clock WHERE id=1").Scan(&epoch, &head, &watermark); err != nil {
		t.Fatal(err)
	}
	a.unreadSnapshot = unreadSnapshot{}
	a.unreadQueries.Store(0)
	const callers = 16
	start := make(chan struct{})
	errs := make(chan error, callers)
	var wg sync.WaitGroup
	for range callers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			tx, err := a.db.BeginTx(context.Background(), &sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead})
			if err != nil {
				errs <- err
				return
			}
			defer tx.Rollback()
			count, err := a.snapshotUnread(context.Background(), tx, epoch, head, watermark, after)
			if err == nil && count != 1 {
				err = fmt.Errorf("unread=%d, want 1", count)
			}
			errs <- err
		}()
	}
	close(start)
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	if got := a.unreadQueries.Load(); got != 1 {
		t.Fatalf("same-key miss executed %d COUNT queries, want 1", got)
	}
}

func containsString(values []any, wanted string) bool {
	for _, value := range values {
		if value == wanted {
			return true
		}
	}
	return false
}
