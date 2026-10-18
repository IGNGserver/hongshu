package main

import (
	"fmt"
	"net/http/httptest"
	"net/url"
	"testing"
)

func TestReadStateRejectsMalformedPaginationBeforeStore(t *testing.T) {
	a := &app{}
	for _, query := range []string{"limit=201", "epoch=-1", "since=-1", "since=0", "revision=bad", "after=x", "after=x&revision=1", "after=x&revision=1&epoch=1&since=1"} {
		w := httptest.NewRecorder()
		a.readState(w, httptest.NewRequest("GET", "/api/read-state?"+query, nil))
		if w.Code != 400 {
			t.Fatalf("query %s returned %d", query, w.Code)
		}
	}
}

func TestIntegrationReadStateFencesMessageHeadChanges(t *testing.T) {
	a, c, token := unreadFixture(t)
	if _, err := a.db.Exec("INSERT INTO conversation_reads VALUES ('10010',5,1),('10086',6,1)"); err != nil {
		t.Fatal(err)
	}
	first := c.call("GET", "/api/read-state?limit=1&epoch=1", token, nil, 200)
	revision := int64(first["revision"].(float64))
	if first["items"].([]any)[0].(map[string]any)["message_id"] != float64(0) {
		t.Fatal("future ID was not sanitized", first)
	}
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{
		{Receiver: "+123456789", Sender: "10010", Body: "fifth", Timestamp: 5000},
		{Receiver: "+123456789", Sender: "10086", Body: "sixth", Timestamp: 6000},
	}}, 200)
	c.call("GET", fmt.Sprintf("/api/read-state?after=10010&revision=%d&head=4&epoch=1", revision), token, nil, 409)
	changed := c.call("GET", fmt.Sprintf("/api/read-state?since=%d&head=4&epoch=1", revision), token, nil, 200)
	if changed["head"] != float64(6) || changed["revision"] != float64(revision) || len(changed["items"].([]any)) != 2 {
		t.Fatal("message-only change incorrectly treated as unchanged", changed)
	}
}

func TestIntegrationReadStateIncludesSpaceSenderOnFirstPage(t *testing.T) {
	_, c, token := unreadFixture(t)
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{
		{Receiver: "+123456789", Sender: " ", Body: "synthetic space sender", Timestamp: 5000},
	}}, 200)
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": " ", "message_id": 5}, 200)
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10010", "message_id": 2}, 200)
	first := c.call("GET", "/api/read-state?limit=1&epoch=1", token, nil, 200)
	if first["next"] != " " || first["items"].([]any)[0].(map[string]any)["sender"] != " " {
		t.Fatal("space sender omitted", first)
	}
	last := c.call("GET", fmt.Sprintf("/api/read-state?limit=1&after=%s&revision=%.0f&head=5&epoch=1", url.QueryEscape(" "), first["revision"]), token, nil, 200)
	if last["more"] != false || last["items"].([]any)[0].(map[string]any)["sender"] != "10010" {
		t.Fatal("space sender cursor did not advance", last)
	}
}

func TestIntegrationReadStateDoesNotReplicateLegacyInvalidIDs(t *testing.T) {
	a, c, token := unreadFixture(t)
	if _, err := a.db.Exec("INSERT INTO conversation_reads VALUES ('10086',9223372036854775807,1),('10010',1,1)"); err != nil {
		t.Fatal(err)
	}
	page := c.call("GET", "/api/read-state?epoch=1", token, nil, 200)
	if len(page["items"].([]any)) != 2 {
		t.Fatal("missing read markers", page)
	}
	for _, raw := range page["items"].([]any) {
		if raw.(map[string]any)["message_id"] != float64(0) {
			t.Fatal("invalid marker replicated into client", raw)
		}
	}
}

func TestIntegrationReadStatePaginationAndRevisionFence(t *testing.T) {
	a, c, token := unreadFixture(t)
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10010", "message_id": 2}, 200)
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 1}, 200)
	first := c.call("GET", "/api/read-state?limit=1&epoch=1", token, nil, 200)
	if first["more"] != true || first["next"] != "10010" || first["items"].([]any)[0].(map[string]any)["message_id"] != float64(2) {
		t.Fatal("incorrect first read page", first)
	}
	revision := int64(first["revision"].(float64))
	next := fmt.Sprintf("/api/read-state?limit=1&after=10010&revision=%d&epoch=1&head=4", revision)
	last := c.call("GET", next, token, nil, 200)
	if last["more"] != false || len(last["items"].([]any)) != 1 || last["items"].([]any)[0].(map[string]any)["sender"] != "10086" {
		t.Fatal("incorrect final read page", last)
	}
	unchanged := c.call("GET", fmt.Sprintf("/api/read-state?since=%d&epoch=1&head=4", revision), token, nil, 200)
	if unchanged["more"] != false || len(unchanged["items"].([]any)) != 0 {
		t.Fatal("unchanged state was downloaded again", unchanged)
	}
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 3}, 200)
	c.call("GET", next, token, nil, 409)
	changed := c.call("GET", fmt.Sprintf("/api/read-state?since=%d&epoch=1&head=4", revision), token, nil, 200)
	if len(changed["items"].([]any)) != 2 {
		t.Fatal("changed state not refreshed", changed)
	}
	if _, err := a.db.Exec("UPDATE sync_clock SET epoch=epoch+1 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	c.call("GET", next, token, nil, 409)
	c.call("GET", "/api/read-state", "invalid", nil, 401)
}
