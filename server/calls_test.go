package main

import (
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestCallFingerprint(t *testing.T) {
	c := IncomingCall{Receiver: "+8613800000000", Number: "13900000000", Type: "incoming", Timestamp: 1700000000000, Duration: 75, SubscriptionID: 1}
	// Shared with the Android client (CallLogTest) so both sides keep one identity.
	if callFingerprint(c) != "bf4ef9880762b3b21e9f1cf296615e8af7492beea5aaa2020f18afceb543ae97" {
		t.Fatal("cross-client call vector mismatch")
	}
	unmapped := c
	unmapped.Receiver, unmapped.SubscriptionID = "", -1
	if callFingerprint(unmapped) != callFingerprint(c) {
		t.Fatal("learning the line later must not create a second identity")
	}
	missed := c
	missed.Type, missed.Duration = "missed", 0
	if callFingerprint(missed) == callFingerprint(c) {
		t.Fatal("different calls share an identity")
	}
}

func TestCallValidation(t *testing.T) {
	now := time.Now().UnixMilli()
	ok := IncomingCall{Receiver: "+8613800000000", Number: "13900000000", Type: "outgoing", Timestamp: now, Duration: 30}
	valid := []func(*IncomingCall){
		func(c *IncomingCall) {},
		func(c *IncomingCall) { c.Receiver = "" },
		func(c *IncomingCall) { c.Number, c.Presentation = "", "restricted" },
		func(c *IncomingCall) { c.Number, c.Presentation = "", "unknown" },
		func(c *IncomingCall) { c.Presentation = "payphone" },
		func(c *IncomingCall) { c.Location = strings.Repeat("北", 100) },
		func(c *IncomingCall) { c.Duration = maxCallDuration },
	}
	for _, typ := range []string{"incoming", "outgoing", "missed", "rejected", "blocked", "voicemail", "answered_externally", "unknown"} {
		typ := typ
		valid = append(valid, func(c *IncomingCall) { c.Type = typ })
	}
	for i, change := range valid {
		c := ok
		change(&c)
		if !validCall(c) {
			t.Fatalf("valid call %d rejected: %+v", i, c)
		}
	}
	invalid := []func(*IncomingCall){
		func(c *IncomingCall) { c.Type = "" },
		func(c *IncomingCall) { c.Type = "INCOMING" },
		func(c *IncomingCall) { c.Number = "" },
		func(c *IncomingCall) { c.Presentation = "hidden" },
		func(c *IncomingCall) { c.Receiver = "SIM1" },
		func(c *IncomingCall) { c.Number = "1\x002" },
		func(c *IncomingCall) { c.Number = strings.Repeat("1", 101) },
		func(c *IncomingCall) { c.Location = strings.Repeat("北", 101) },
		func(c *IncomingCall) { c.Duration = -1 },
		func(c *IncomingCall) { c.Duration = maxCallDuration + 1 },
		func(c *IncomingCall) { c.Timestamp = -1 },
		func(c *IncomingCall) { c.Timestamp = time.Now().Add(25 * time.Hour).UnixMilli() },
	}
	for i, change := range invalid {
		c := ok
		change(&c)
		if validCall(c) {
			t.Fatalf("invalid call %d accepted: %+v", i, c)
		}
	}
}

// Calls are their own stream: they never move the SMS clock, unread counts or push queue,
// keep a line only when the phone confirmed it, and page/sync in a stable order.
func TestIntegrationCallLog(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/login", "", map[string]any{"password": "1"}, 200)
	adminCookie := c.cookie
	before := c.call("GET", "/api/me", "", nil, 200)
	features := map[any]bool{}
	for _, f := range before["features"].([]any) {
		features[f] = true
	}
	if !features["call_log"] || before["call_cursor"] != float64(0) {
		t.Fatalf("hub does not announce call_log: %v %v", before["features"], before["call_cursor"])
	}
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "name": "Phone", "kind": "android"}, 200)
	token := paired["token"].(string)
	phoneID := paired["device"].(map[string]any)["id"].(string)
	c.cookie = adminCookie // calls without a token act as the admin browser
	batch := map[string]any{"calls": []map[string]any{{"number": "13900000000", "type": "incoming", "timestamp": 1700000000000, "duration": 75}}}
	c.call("POST", "/api/calls", token, batch, 403)
	c.call("POST", "/api/calls", "", batch, 403)
	c.call("PATCH", "/api/devices/"+phoneID, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1}, 200)
	c.call("PUT", "/api/contacts", "", map[string]any{"phone": "13900000000", "name": "合成联系人"}, 200)

	calls := []map[string]any{
		{"receiver": "+8613800000000", "number": "13900000000", "type": "incoming", "timestamp": 1700000000000, "duration": 75, "subscription_id": 1, "location": "合成归属地"},
		{"receiver": "", "number": "", "type": "missed", "timestamp": 1700000100000, "duration": 0, "subscription_id": -1, "presentation": "restricted"},
		{"receiver": "+8613800000000", "number": "13900000000", "type": "outgoing", "timestamp": 1700000100000, "duration": 12, "subscription_id": 1, "video": true},
		{"receiver": "+8619900000000", "number": "10086", "type": "incoming", "timestamp": 1700000200000, "duration": 5, "subscription_id": 2},
		{"receiver": "+8613800000000", "number": "", "type": "incoming", "timestamp": 1700000300000, "duration": 5, "subscription_id": 1},
		{"receiver": "+8613800000000", "number": "10010", "type": "outgoing", "timestamp": 1700000400000, "duration": 1, "subscription_id": 1, "historical": true},
	}
	uploaded := c.call("POST", "/api/calls", token, map[string]any{"calls": calls}, 200)
	acks := uploaded["acks"].([]any)
	if len(acks) != len(calls) {
		t.Fatalf("ack count %d", len(acks))
	}
	expectErr := []any{nil, nil, nil, "sim_not_confirmed", "invalid_call", nil}
	ids := []int64{}
	for i, raw := range acks {
		ack := raw.(map[string]any)
		if ack["error"] != expectErr[i] || ack["fingerprint"] == "" {
			t.Fatalf("ack %d: %v", i, ack)
		}
		if ack["error"] == nil {
			ids = append(ids, int64(ack["id"].(float64)))
		}
	}
	if ids[0] != 1 || ids[1] != 2 || ids[2] != 3 || ids[3] != 4 || uploaded["cursor"] != float64(4) {
		t.Fatalf("call ids are not commit-ordered: %v %v", ids, uploaded["cursor"])
	}
	if retry := c.call("POST", "/api/calls", token, map[string]any{"calls": calls[:1]}, 200)["acks"].([]any)[0].(map[string]any); retry["duplicate"] != true || retry["id"] != float64(1) {
		t.Fatalf("call retry not idempotent: %v", retry)
	}

	after := c.call("GET", "/api/me", "", nil, 200)
	if after["call_cursor"] != float64(4) || after["cursor"] != before["cursor"] || after["unread_count"] != float64(0) {
		t.Fatalf("calls leaked into the SMS stream: %v", after)
	}
	var jobs int
	if e := db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&jobs); e != nil || jobs != 0 {
		t.Fatalf("calls queued push jobs: %d %v", jobs, e)
	}

	// Newest call start first; equal start times fall back to the higher ID.
	page := c.call("GET", "/api/calls?limit=2", "", nil, 200)
	list := page["calls"].([]any)
	if page["more"] != true || page["head"] != float64(4) || len(list) != 2 || list[0].(map[string]any)["id"] != float64(4) || list[1].(map[string]any)["id"] != float64(3) {
		t.Fatalf("first call page: %v", page)
	}
	page = c.call("GET", "/api/calls?limit=2&before=3", "", nil, 200)
	list = page["calls"].([]any)
	if page["more"] != false || len(list) != 2 || list[0].(map[string]any)["id"] != float64(2) || list[1].(map[string]any)["id"] != float64(1) {
		t.Fatalf("second call page: %v", page)
	}
	c.call("GET", "/api/calls?before=999", "", nil, 400)
	c.call("GET", "/api/calls?limit=0", "", nil, 400)

	detail := c.call("GET", "/api/calls/1", token, nil, 200)["call"].(map[string]any)
	if detail["contact"] != "合成联系人" || detail["receiver"] != "+8613800000000" || detail["duration"] != float64(75) || detail["presentation"] != "allowed" || detail["location"] != "合成归属地" || detail["device_id"] != phoneID {
		t.Fatalf("call detail: %v", detail)
	}
	hidden := c.call("GET", "/api/calls/2", "", nil, 200)["call"].(map[string]any)
	if hidden["number"] != "" || hidden["contact"] != "" || hidden["receiver"] != "" || hidden["presentation"] != "restricted" {
		t.Fatalf("hidden call: %v", hidden)
	}
	if video := c.call("GET", "/api/calls/3", "", nil, 200)["call"].(map[string]any); video["video"] != true || video["type"] != "outgoing" {
		t.Fatalf("video call: %v", video)
	}
	if old := c.call("GET", "/api/calls/4", "", nil, 200)["call"].(map[string]any); old["historical"] != true {
		t.Fatalf("historical flag lost: %v", old)
	}
	c.call("GET", "/api/calls/999", "", nil, 404)
	c.call("GET", "/api/calls/abc", "", nil, 400)

	synced := c.call("GET", "/api/calls/sync?after=1&limit=2", token, nil, 200)
	if synced["cursor"] != float64(3) || synced["more"] != true || len(synced["calls"].([]any)) != 2 || synced["epoch"] != before["epoch"] {
		t.Fatalf("call sync page: %v", synced)
	}
	if tail := c.call("GET", "/api/calls/sync?after=3", token, nil, 200); tail["cursor"] != float64(4) || tail["more"] != false {
		t.Fatalf("call sync tail: %v", tail)
	}
	if empty := c.call("GET", "/api/calls/sync?after=4", token, nil, 200); empty["cursor"] != float64(4) || len(empty["calls"].([]any)) != 0 {
		t.Fatalf("caught-up call sync: %v", empty)
	}
	c.call("GET", "/api/calls/sync?after="+strconv.Itoa(5), token, nil, 409)

	// Revocation stops further uploads but keeps history.
	c.call("DELETE", "/api/devices/"+phoneID, "", nil, 200)
	if kept := c.call("GET", "/api/calls", "", nil, 200)["calls"].([]any); len(kept) != 4 {
		t.Fatalf("revocation removed call history: %d", len(kept))
	}
}
