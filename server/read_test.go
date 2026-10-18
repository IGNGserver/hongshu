package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestReadWatermarksAndUnreadCounts(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}

	// Login web
	c.call("POST", "/api/login", "", map[string]any{"password": "1"}, 200)

	// Login android
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "name": "Phone", "kind": "android"}, 200)
	androidToken := paired["token"].(string)
	id := paired["device"].(map[string]any)["id"].(string)

	// Enable upload
	c.call("PATCH", "/api/devices/"+id, androidToken, map[string]bool{"upload": true}, 200)

	// Register SIM
	c.call("PUT", "/api/sims", androidToken, map[string]any{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1}, 200)

	// Initial me call should have unread_count = 0
	meResp := c.call("GET", "/api/me", "", nil, 200)
	if meResp["unread_count"].(float64) != 0 {
		t.Fatalf("expected initial unread_count 0, got %v", meResp["unread_count"])
	}

	// Upload messages: 2 from 10086, 1 from 10010, 1 historical from 10086
	_ = c.call("POST", "/api/messages", androidToken, map[string]any{
		"messages": []map[string]any{
			{"receiver": "+8613800000000", "sender": "10086", "body": "Msg 1", "timestamp": 1700000000000, "subscription_id": 1, "historical": false},
			{"receiver": "+8613800000000", "sender": "10086", "body": "Msg 2", "timestamp": 1700000001000, "subscription_id": 1, "historical": false},
			{"receiver": "+8613800000000", "sender": "10010", "body": "Msg 3", "timestamp": 1700000002000, "subscription_id": 1, "historical": false},
			{"receiver": "+8613800000000", "sender": "10086", "body": "Msg 4 historical", "timestamp": 1690000000000, "subscription_id": 1, "historical": true},
		},
	}, 200)

	// Check me: unread_count should be 3 (historical excluded)
	meResp = c.call("GET", "/api/me", "", nil, 200)
	if meResp["unread_count"].(float64) != 3 {
		t.Fatalf("expected unread_count 3, got %v", meResp["unread_count"])
	}

	// Check conversations: 10086 should have 2 unread, 10010 should have 1 unread
	convResp := c.call("GET", "/api/conversations", "", nil, 200)
	convs := convResp["conversations"].([]any)
	if len(convs) != 2 {
		t.Fatalf("expected 2 conversations, got %d", len(convs))
	}
	for _, raw := range convs {
		conv := raw.(map[string]any)
		sender := conv["sender"].(string)
		unread := conv["unread_count"].(float64)
		if sender == "10086" && unread != 2 {
			t.Fatalf("expected 10086 unread 2, got %v", unread)
		}
		if sender == "10010" && unread != 1 {
			t.Fatalf("expected 10010 unread 1, got %v", unread)
		}
	}

	// Mark 10086 as read
	readResp := c.call("POST", "/api/messages/read", "", map[string]any{"sender": "10086"}, 200)
	if readResp["unread_count"].(float64) != 1 {
		t.Fatalf("expected remaining unread 1, got %v", readResp["unread_count"])
	}

	// Recheck conversations: 10086 unread 0, 10010 unread 1
	convResp = c.call("GET", "/api/conversations", "", nil, 200)
	for _, raw := range convResp["conversations"].([]any) {
		conv := raw.(map[string]any)
		sender := conv["sender"].(string)
		unread := conv["unread_count"].(float64)
		if sender == "10086" && unread != 0 {
			t.Fatalf("expected 10086 unread 0, got %v", unread)
		}
		if sender == "10010" && unread != 1 {
			t.Fatalf("expected 10010 unread 1, got %v", unread)
		}
	}

	// Mark all read
	readAllResp := c.call("POST", "/api/messages/read", "", map[string]any{"all": true}, 200)
	if readAllResp["unread_count"].(float64) != 0 {
		t.Fatalf("expected unread_count 0 after mark all, got %v", readAllResp["unread_count"])
	}

	// Recheck me and conversations
	meResp = c.call("GET", "/api/me", "", nil, 200)
	if meResp["unread_count"].(float64) != 0 {
		t.Fatalf("expected me unread 0, got %v", meResp["unread_count"])
	}
	convResp = c.call("GET", "/api/conversations", "", nil, 200)
	for _, raw := range convResp["conversations"].([]any) {
		conv := raw.(map[string]any)
		if conv["unread_count"].(float64) != 0 {
			t.Fatalf("expected unread 0, got %v", conv["unread_count"])
		}
	}
}

func unreadFixture(t *testing.T) (*app, *apiTest, string) {
	t.Helper()
	a, err := newApp(testDB(t), "http://localhost:8080", "synthetic", "", "")
	if err != nil {
		t.Fatal(err)
	}
	c := &apiTest{t: t, a: a}
	login := c.call("POST", "/api/login", "", map[string]any{"password": "synthetic", "kind": "android"}, 200)
	token := login["token"].(string)
	id := login["device"].(map[string]any)["id"].(string)
	c.call("PATCH", "/api/devices/"+id, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+123456789", "label": "Synthetic", "subscription_id": 1}, 200)
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{
		{Receiver: "+123456789", Sender: "10086", Body: "first", Timestamp: 1000},
		{Receiver: "+123456789", Sender: "10010", Body: "other sender", Timestamp: 2000},
		{Receiver: "+123456789", Sender: "10086", Body: "second", Timestamp: 3000},
		{Receiver: "+123456789", Sender: "10086", Body: "history", Timestamp: 500, Historical: true},
	}}, 200)
	return a, c, token
}

func TestReadRejectsMalformedBoundaryBeforeStore(t *testing.T) {
	a := &app{}
	for _, body := range []string{
		`{"sender":"10086","message_id":-1}`,
		`{"all":true,"message_id":-1}`,
		`{"sender":""}`,
		`{"sender":"bad\u0000sender"}`,
		`{"sender":"` + strings.Repeat("x", 101) + `"}`,
	} {
		r := httptest.NewRequest("POST", "/api/messages/read", strings.NewReader(body))
		w := httptest.NewRecorder()
		a.markRead(w, r)
		if w.Code != http.StatusBadRequest {
			t.Fatalf("malformed read boundary accepted: status=%d", w.Code)
		}
	}
}

func TestReadRejectsInvalidBoundaries(t *testing.T) {
	a, c, token := unreadFixture(t)
	for _, id := range []int64{-1, 2, 999, 9223372036854775807} {
		out := c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": id}, 400)
		if out["error"] != "invalid_message_id" {
			t.Fatalf("ID %d: unexpected error %v", id, out)
		}
	}
	c.call("POST", "/api/messages/read", token, map[string]any{"all": true, "message_id": -1}, 400)
	var rows int
	if err := a.db.QueryRow("SELECT COUNT(*) FROM conversation_reads").Scan(&rows); err != nil || rows != 0 {
		t.Fatal("invalid input changed read state", rows, err)
	}
	assertUnread := func(body map[string]any, want float64) {
		t.Helper()
		out := c.call("POST", "/api/messages/read", token, body, 200)
		if out["unread_count"] != want {
			t.Fatalf("read %v: got %v want %v", body, out, want)
		}
	}
	assertUnread(map[string]any{"sender": "10086", "message_id": 1}, 2)
	assertUnread(map[string]any{"sender": "10086", "message_id": 3}, 1)
	assertUnread(map[string]any{"sender": "10086", "message_id": 1}, 1) // No regression.
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{
		{Receiver: "+123456789", Sender: "10086", Body: "after read", Timestamp: 4000},
	}}, 200)
	if me := c.call("GET", "/api/me", token, nil, 200); me["unread_count"] != float64(2) {
		t.Fatal("new message was preemptively read", me)
	}
	assertUnread(map[string]any{"sender": "10086", "message_id": 0}, 1)
	assertUnread(map[string]any{"sender": "10010"}, 0)
	assertUnread(map[string]any{"sender": "unknown"}, 0) // Omitted/zero remains a no-op for an empty conversation.
}

func TestConversationUnreadCountsWithPaginationAndWatermarks(t *testing.T) {
	_, c, token := unreadFixture(t)
	checkPage := func(offset int, sender string, want float64) {
		t.Helper()
		path := "/api/conversations?limit=1"
		if offset != 0 {
			path += "&offset=1"
		}
		items := c.call("GET", path, token, nil, 200)["conversations"].([]any)
		if len(items) != 1 {
			t.Fatal("unexpected page", items)
		}
		item := items[0].(map[string]any)
		if item["sender"] != sender || item["unread_count"] != want {
			t.Fatal("wrong per-page count", item)
		}
	}
	checkPage(0, "10086", 2)
	checkPage(1, "10010", 1)
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 1}, 200)
	checkPage(0, "10086", 1)
	checkPage(1, "10010", 1)
	c.call("POST", "/api/messages/read", token, map[string]any{"all": true}, 200)
	checkPage(0, "10086", 0)
	checkPage(1, "10010", 0)
}

func TestReadOnlyChangesReachWebSocket(t *testing.T) {
	a, c, token := unreadFixture(t)
	a.wsCheckInterval = 20 * time.Millisecond
	srv := httptest.NewServer(a.routes("../web"))
	defer srv.Close()
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/api/ws", http.Header{"Authorization": []string{"Bearer " + token}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	readChange := func(want float64) map[string]any {
		t.Helper()
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		var change map[string]any
		if err := conn.ReadJSON(&change); err != nil {
			t.Fatal("missing read-state event", err)
		}
		if change["type"] != "changed" {
			t.Fatal("unexpected change", change)
		}
		// Counts remain authoritative through /me, not recomputed per socket.
		if me := c.call("GET", "/api/me", token, nil, 200); me["unread_count"] != want {
			t.Fatal("read event did not expose the committed unread state", me)
		}
		return change
	}
	initial := readChange(3) // Establish the socket's observed clock/revision first.
	for _, step := range []struct {
		body map[string]any
		want float64
	}{
		{map[string]any{"sender": "10086", "message_id": 1}, 2},
		{map[string]any{"all": true}, 0},
	} {
		c.call("POST", "/api/messages/read", token, step.body, 200)
		change := readChange(step.want)
		if change["cursor"] != initial["cursor"] || change["analysis_revision"] != initial["analysis_revision"] {
			t.Fatal("read-only operation changed message/analysis cursors", change)
		}
	}
}

func TestReadRevisionIsStrictlyMonotonicAndTransactional(t *testing.T) {
	a, c, token := unreadFixture(t)
	revision := time.Now().Add(time.Hour).UnixMilli()
	if _, err := a.db.Exec("UPDATE read_state SET updated_at=? WHERE id=1", revision); err != nil {
		t.Fatal(err)
	}
	assertRevision := func(want int64) {
		t.Helper()
		var got int64
		if err := a.db.QueryRow("SELECT updated_at FROM read_state WHERE id=1").Scan(&got); err != nil || got != want {
			t.Fatalf("read revision got=%d want=%d err=%v", got, want, err)
		}
	}
	for i := 0; i < 2; i++ {
		c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 1}, 200)
		revision++
		assertRevision(revision)
	}
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 2}, 400)
	assertRevision(revision) // Rolled-back invalid request cannot advertise a change.
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "unknown"}, 200)
	assertRevision(revision)
	c.call("POST", "/api/messages/read", token, map[string]any{"all": true}, 200)
	assertRevision(revision + 1)
}

func TestConcurrentReadAndUploadLockOrdering(t *testing.T) {
	a, _, token := unreadFixture(t)
	initialRevision := time.Now().Add(time.Hour).UnixMilli()
	if _, err := a.db.Exec("UPDATE read_state SET updated_at=? WHERE id=1", initialRevision); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var wg sync.WaitGroup
	errors := make(chan string, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			path, body := "/api/messages/read", `{"sender":"10086","message_id":1}`
			switch i % 3 {
			case 1:
				body = `{"all":true}`
			case 2:
				path = "/api/messages"
				body = fmt.Sprintf(`{"messages":[{"receiver":"+123456789","sender":"10086","body":"concurrent synthetic","timestamp":%d}]}`, 5000+i)
			}
			r := httptest.NewRequest("POST", path, strings.NewReader(body)).WithContext(ctx)
			r.Header.Set("Authorization", "Bearer "+token)
			w := httptest.NewRecorder()
			a.routes("../web").ServeHTTP(w, r)
			if w.Code != 200 {
				errors <- fmt.Sprintf("%s: status=%d", path, w.Code)
			}
		}(i)
	}
	wg.Wait()
	close(errors)
	for err := range errors {
		t.Error(err)
	}
	var revision, seq int64
	if err := a.db.QueryRow("SELECT updated_at,(SELECT seq FROM sync_clock WHERE id=1) FROM read_state WHERE id=1").Scan(&revision, &seq); err != nil || revision != initialRevision+8 || seq != 8 {
		t.Fatalf("lost read/upload commit: revision=%d seq=%d err=%v", revision, seq, err)
	}
}

func TestManualRepairReadWatermarks(t *testing.T) {
	a, c, token := unreadFixture(t)
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{
		{Receiver: "+123456789", Sender: "10000", Body: "valid read boundary", Timestamp: 5000},
	}}, 200)
	if _, err := a.db.Exec("INSERT INTO conversation_reads (sender,last_read_message_id,read_at) VALUES ('10086',9223372036854775807,111),('10010',1,222),('10000',5,777),('empty',0,888)"); err != nil {
		t.Fatal(err)
	}
	initialRevision := time.Now().Add(time.Hour).UnixMilli()
	if _, err := a.db.Exec("UPDATE read_state SET updated_at=? WHERE id=1", initialRevision); err != nil {
		t.Fatal(err)
	}
	script, err := os.ReadFile("../scripts/repair-read-watermarks.sql")
	if err != nil {
		t.Fatal(err)
	}
	runRepair := func() int {
		t.Helper()
		conn, err := a.db.Conn(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		defer conn.ExecContext(context.Background(), "ROLLBACK")
		for _, statement := range splitSQL(string(script)) {
			if _, err := conn.ExecContext(context.Background(), statement); err != nil {
				t.Fatal("manual repair failed", err)
			}
		}
		var repaired int
		if err := conn.QueryRowContext(context.Background(), "SELECT @hongshu_repaired_read_watermarks").Scan(&repaired); err != nil {
			t.Fatal(err)
		}
		return repaired
	}
	if repaired := runRepair(); repaired != 2 {
		t.Fatal("repair changed the wrong number of rows", repaired)
	}
	for sender, want := range map[string]int64{"10086": 0, "10010": 0, "10000": 5, "empty": 0} {
		var id, readAt int64
		if err := a.db.QueryRow("SELECT last_read_message_id,read_at FROM conversation_reads WHERE sender=?", sender).Scan(&id, &readAt); err != nil || id != want {
			t.Fatal("wrong repaired boundary", sender, id, err)
		}
		if sender == "10000" && readAt != 777 || sender == "empty" && readAt != 888 {
			t.Fatal("repair modified an already valid read row", sender)
		}
	}
	if repaired := runRepair(); repaired != 0 {
		t.Fatal("repair was not idempotent", repaired)
	}
	var revision, watermark, count int64
	if err := a.db.QueryRow("SELECT updated_at,global_watermark_id,(SELECT COUNT(*) FROM messages) FROM read_state WHERE id=1").Scan(&revision, &watermark, &count); err != nil || revision != initialRevision+1 || watermark != 0 || count != 5 {
		t.Fatal("repair lost data or changed unrelated state", revision, watermark, count, err)
	}
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{
		{Receiver: "+123456789", Sender: "10086", Body: "new after repair A", Timestamp: 6000},
		{Receiver: "+123456789", Sender: "10010", Body: "new after repair B", Timestamp: 7000},
	}}, 200)
	if me := c.call("GET", "/api/me", token, nil, 200); me["unread_count"] != float64(5) {
		t.Fatal("new messages remained preemptively read after repair", me)
	}
}
