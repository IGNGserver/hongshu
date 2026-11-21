package main

import "testing"

func hasFeature(me map[string]any, feature string) bool {
	features, ok := me["features"].([]any)
	if !ok {
		return false
	}
	for _, value := range features {
		if value == feature {
			return true
		}
	}
	return false
}

func simPhones(c *apiTest, token string) []string {
	c.t.Helper()
	body := c.call("GET", "/api/sims", token, nil, 200)
	rows := body["sims"].([]any)
	out := make([]string, 0, len(rows))
	for _, row := range rows {
		out = append(out, row.(map[string]any)["phone"].(string))
	}
	return out
}

// A client that supports sim_inventory replaces the whole set after uploads. The removed
// line must disappear so the Web inventory and SIM filter stop claiming it still exists.
func TestSimInventoryReplacesDeviceSet(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "kind": "android", "name": "Phone"}, 200)
	token := paired["token"].(string)
	if !hasFeature(c.call("GET", "/api/me", token, nil, 200), "sim_inventory") {
		t.Fatal("hub does not advertise sim_inventory")
	}

	// The legacy incremental shape is still how released clients register a line.
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613900000000", "label": "SIM2", "subscription_id": 2}, 200)
	if got := simPhones(&c, token); len(got) != 2 {
		t.Fatalf("legacy registrations missing: %v", got)
	}

	before := c.call("GET", "/api/me", token, nil, 200)["metadata_revision"].(float64)
	c.call("PUT", "/api/sims", token, map[string]any{"sims": []map[string]any{
		{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1},
	}}, 200)
	after := c.call("GET", "/api/me", token, nil, 200)["metadata_revision"].(float64)
	if after <= before {
		t.Fatal("replacing the SIM set must advance the metadata revision")
	}
	got := simPhones(&c, token)
	if len(got) != 1 || got[0] != "+8613800000000" {
		t.Fatalf("removed SIM survived the full-set update: %v", got)
	}

	// A legacy upsert after a replace adds a line back instead of replacing the set.
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613900000000", "label": "SIM2", "subscription_id": 2}, 200)
	if got = simPhones(&c, token); len(got) != 2 {
		t.Fatalf("legacy upsert after replace was not additive: %v", got)
	}

	// An empty set means the phone has no confirmed lines left.
	c.call("PUT", "/api/sims", token, map[string]any{"sims": []map[string]any{}}, 200)
	if got = simPhones(&c, token); len(got) != 0 {
		t.Fatalf("empty full-set update left stale rows: %v", got)
	}
}

// Replacing one device's set must never delete another device's registrations.
func TestSimInventoryIsScopedToTheRequestingDevice(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	first := c.call("POST", "/api/login", "", map[string]any{"password": "1", "kind": "android", "name": "Phone A"}, 200)
	second := c.call("POST", "/api/login", "", map[string]any{"password": "1", "kind": "android", "name": "Phone B"}, 200)
	tokenA := first["token"].(string)
	tokenB := second["token"].(string)
	c.call("PUT", "/api/sims", tokenA, map[string]any{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1}, 200)
	c.call("PUT", "/api/sims", tokenB, map[string]any{"phone": "+8613900000000", "label": "SIM1", "subscription_id": 1}, 200)

	c.call("PUT", "/api/sims", tokenA, map[string]any{"sims": []map[string]any{}}, 200)
	got := simPhones(&c, tokenB)
	if len(got) != 1 || got[0] != "+8613900000000" {
		t.Fatalf("device A wiped device B's SIM inventory: %v", got)
	}
}

func TestSimInventoryRejectsMalformedSets(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "kind": "android", "name": "Phone"}, 200)
	token := paired["token"].(string)

	c.call("PUT", "/api/sims", token, map[string]any{"sims": []map[string]any{
		{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1},
		{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1},
	}}, 400)
	c.call("PUT", "/api/sims", token, map[string]any{"sims": []map[string]any{
		{"phone": "not-a-number", "label": "SIM1", "subscription_id": 1},
	}}, 400)
	c.call("PUT", "/api/sims", token, map[string]any{"sims": "SIM1"}, 400)
	if got := simPhones(&c, token); len(got) != 0 {
		t.Fatalf("malformed full set changed the inventory: %v", got)
	}
}
