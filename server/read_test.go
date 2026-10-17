package main

import (
	"testing"
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
