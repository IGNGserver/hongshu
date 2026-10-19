package main

import (
	"testing"
	"time"
)

func TestOutgoingFingerprint(t *testing.T) {
	in := Incoming{Receiver: "+8613800000000", Sender: "10086", Body: "测试", Timestamp: 1700000000000, SubscriptionID: 1}
	explicit := in
	explicit.Direction = directionIn
	if fingerprint(explicit) != fingerprint(in) {
		t.Fatal("an explicit \"in\" changed the received-message identity")
	}
	out := in
	out.Direction = directionOut
	if fingerprint(out) != hash("+8613800000000\x0010086\x001700000000000\x00测试\x00out") {
		t.Fatal("sent canonical hash changed")
	}
	// Shared with the Android client (ProtocolTest) so both sides keep one identity.
	if fingerprint(out) != "d56c4a02e82f207e4a4436d1c2ef37c27e942d3883e7232b770334b13bff0adc" {
		t.Fatal("cross-client sent vector mismatch")
	}
	if fingerprint(out) == fingerprint(in) {
		t.Fatal("a sent message must not deduplicate against a received one")
	}
}

func TestOutgoingValidation(t *testing.T) {
	m := Incoming{Receiver: "+123456789", Sender: "10086", Body: "hello", Timestamp: time.Now().UnixMilli()}
	for _, dir := range []string{"", directionIn, directionOut} {
		m.Direction = dir
		if !validMessage(m) {
			t.Fatalf("direction %q rejected", dir)
		}
	}
	for _, dir := range []string{"IN", "sent", "both", " out"} {
		m.Direction = dir
		if validMessage(m) {
			t.Fatalf("direction %q accepted", dir)
		}
	}
}

// A sent message joins its recipient's conversation but never counts as unread, never
// pushes, never yields a verification code, and keeps its direction through sync.
func TestIntegrationOutgoingMessages(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/login", "", map[string]any{"password": "1"}, 200)
	admin := c.call("GET", "/api/me", "", nil, 200)
	adminID := admin["device"].(map[string]any)["id"].(string)
	features, _ := admin["features"].([]any)
	if len(features) == 0 || features[0] != "outgoing_sms" {
		t.Fatalf("hub does not announce outgoing_sms: %v", admin["features"])
	}
	if _, e := db.Exec("INSERT INTO push_subscriptions VALUES (?,?,?,?)", adminID, "https://push.invalid/synthetic", "p256dh", "auth"); e != nil {
		t.Fatal(e)
	}
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "name": "Phone", "kind": "android"}, 200)
	token := paired["token"].(string)
	id := paired["device"].(map[string]any)["id"].(string)
	c.call("PATCH", "/api/devices/"+id, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1}, 200)

	received := map[string]any{"receiver": "+8613800000000", "sender": "13900000000", "body": "synthetic 验证码 482913", "timestamp": 1700000000000, "subscription_id": 1}
	sent := map[string]any{"receiver": "+8613800000000", "sender": "13900000000", "body": "synthetic 验证码 482913", "timestamp": 1700000000000, "subscription_id": 1, "direction": "out"}
	acks := c.call("POST", "/api/messages", token, map[string]any{"messages": []map[string]any{received, sent}}, 200)["acks"].([]any)
	for i, ack := range acks {
		if a := ack.(map[string]any); a["error"] != nil || a["duplicate"] != false {
			t.Fatalf("ack %d: same body and time in both directions must be two messages: %v", i, a)
		}
	}
	sentID := int64(acks[1].(map[string]any)["id"].(float64))

	var dir, otp, state string
	if e := db.QueryRow("SELECT m.direction,x.otp,x.state FROM messages m JOIN message_analysis x ON x.message_id=m.id WHERE m.id=?", sentID).Scan(&dir, &otp, &state); e != nil {
		t.Fatal(e)
	}
	if dir != directionOut || otp != "" || state != "complete" {
		t.Fatalf("sent message stored as %q otp=%q state=%q", dir, otp, state)
	}
	var jobs int
	if e := db.QueryRow("SELECT COUNT(*) FROM push_jobs WHERE message_id=?", sentID).Scan(&jobs); e != nil || jobs != 0 {
		t.Fatalf("sent message queued %d push jobs: %v", jobs, e)
	}
	me := c.call("GET", "/api/me", "", nil, 200)
	if me["unread_count"] != float64(1) {
		t.Fatalf("only the received message is unread: %v", me["unread_count"])
	}
	conversations := c.call("GET", "/api/conversations", "", nil, 200)["conversations"].([]any)
	if len(conversations) != 1 {
		t.Fatalf("both directions belong to one conversation: %v", conversations)
	}
	head := conversations[0].(map[string]any)
	if head["unread_count"] != float64(1) || head["direction"] != directionOut {
		t.Fatalf("conversation head: %v", head)
	}
	page := c.call("GET", "/api/sync?after=0&limit=10", token, nil, 200)["messages"].([]any)
	if len(page) != 2 || page[0].(map[string]any)["direction"] != directionIn || page[1].(map[string]any)["direction"] != directionOut {
		t.Fatalf("sync lost the direction: %v", page)
	}

	retry := c.call("POST", "/api/messages", token, map[string]any{"messages": []map[string]any{sent}}, 200)["acks"].([]any)[0].(map[string]any)
	if retry["duplicate"] != true || int64(retry["id"].(float64)) != sentID {
		t.Fatalf("sent retry not idempotent: %v", retry)
	}
	bad := map[string]any{}
	for k, v := range sent {
		bad[k] = v
	}
	bad["direction"] = "sideways"
	if c.call("POST", "/api/messages", token, map[string]any{"messages": []map[string]any{bad}}, 200)["acks"].([]any)[0].(map[string]any)["error"] != "invalid_message" {
		t.Fatal("unknown direction accepted")
	}
}
