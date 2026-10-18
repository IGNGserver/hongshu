package main

import (
	"testing"
)

// A malformed message must not roll back its valid neighbours: the batch returns per-item
// ACKs so a single bad row cannot repeatedly fail the whole upload.
func TestUploadIsolatesInvalidMessagesPerItem(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/login", "", map[string]any{"password": "1"}, 200)
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "name": "Phone", "kind": "android"}, 200)
	token := paired["token"].(string)
	id := paired["device"].(map[string]any)["id"].(string)
	c.call("PATCH", "/api/devices/"+id, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1}, 200)

	mixed := c.call("POST", "/api/messages", token, map[string]any{"messages": []map[string]any{
		{"receiver": "+8613800000000", "sender": "10086", "body": "synthetic valid", "timestamp": 1700000000000, "subscription_id": 1, "historical": false},
		{"receiver": "+8613800000000", "sender": "10086", "body": "", "timestamp": 1700000001000, "subscription_id": 1, "historical": false},
		{"receiver": "+8613800000000", "sender": "10086", "body": "synthetic valid two", "timestamp": 1700000002000, "subscription_id": 1, "historical": false},
	}}, 200)
	acks := mixed["acks"].([]any)
	if len(acks) != 3 {
		t.Fatalf("expected one ack per message: %v", mixed)
	}
	first := acks[0].(map[string]any)
	if first["id"].(float64) <= 0 || first["error"] != nil {
		t.Fatalf("valid neighbour rejected: %v", first)
	}
	if acks[1].(map[string]any)["error"] != "invalid_message" {
		t.Fatalf("invalid message not isolated: %v", acks[1])
	}
	if acks[2].(map[string]any)["id"].(float64) <= 0 {
		t.Fatalf("second valid neighbour rejected: %v", acks[2])
	}
	var messages int
	if err := db.QueryRow("SELECT COUNT(*) FROM messages").Scan(&messages); err != nil || messages != 2 {
		t.Fatalf("mixed batch stored wrong rows: %d %v", messages, err)
	}

	// A whole batch of malformed messages still commits nothing.
	all := c.call("POST", "/api/messages", token, map[string]any{"messages": []map[string]any{
		{"receiver": "not-a-number", "sender": "10086", "body": "synthetic", "timestamp": 1700000000000, "subscription_id": 1, "historical": false},
	}}, 200)
	if all["acks"].([]any)[0].(map[string]any)["error"] != "invalid_message" {
		t.Fatalf("invalid receiver accepted: %v", all)
	}
	if err := db.QueryRow("SELECT COUNT(*) FROM messages").Scan(&messages); err != nil || messages != 2 {
		t.Fatalf("invalid batch stored rows: %d %v", messages, err)
	}
}
