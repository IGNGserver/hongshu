package main

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"testing"
)

func TestIntegrationConversationIndexQueryPlan(t *testing.T) {
	db := testDB(t)
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	d, _, err := insertDevice(context.Background(), tx, "synthetic index fixture", "android", false)
	if err != nil {
		t.Fatal(err)
	}
	for id := 1; id <= 2000; id++ {
		if _, err = tx.Exec("INSERT INTO messages (id,device_id,fingerprint,receiver,sender,body,timestamp,subscription_id,historical) VALUES (?,?,?,?,?,?,?,?,TRUE)", id, d.ID, hash(fmt.Sprint(id)), "+123456789", fmt.Sprintf("synthetic-%d", id%10), "synthetic history", id, 7); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = tx.Exec("UPDATE sync_clock SET seq=2000 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	if err = reconcileConversationHeads(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	var plan string
	if err = db.QueryRow("EXPLAIN ANALYZE SELECT " + messageColumns + " FROM conversation_heads h JOIN messages m ON m.id=h.message_id" + messageJoins + " ORDER BY h.timestamp DESC,h.message_id DESC LIMIT 5").Scan(&plan); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(plan, "Table scan on m") || strings.Contains(plan, "Window aggregate") || !strings.Contains(plan, "index lookup on m using PRIMARY") {
		t.Fatal("summary query reverted to scanning message history", plan)
	}
	t.Log("2000-message/10-conversation fixture: summary query plan", plan)
}

func TestIntegrationConversationIndexBackfillsAndRepairsLegacyWriter(t *testing.T) {
	db := testDB(t)
	tx, err := db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	d, _, err := insertDevice(context.Background(), tx, "synthetic", "android", false)
	if err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	for _, stmt := range []string{"DROP TABLE conversation_heads", "DROP TABLE conversation_heads_state", "DELETE FROM schema_migrations WHERE version IN ('005_conversation_heads.up.sql','007_sender_conversation_heads.up.sql')"} {
		if _, err = db.Exec(stmt); err != nil {
			t.Fatal(err)
		}
	}
	for id, stamp := range []int64{3000, 1000, 3000} {
		if _, err = db.Exec("INSERT INTO messages (id,device_id,fingerprint,receiver,sender,body,timestamp,subscription_id,historical) VALUES (?,?,?,?,?,?,?,?,FALSE)", id+1, d.ID, strings.Repeat(string(rune('a'+id)), 64), "+123456789", "synthetic", "synthetic message", stamp, 7); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = db.Exec("UPDATE sync_clock SET seq=3 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	if err = migrate(context.Background(), db, "../db/migrations"); err != nil {
		t.Fatal(err)
	}
	check := func(want int) {
		t.Helper()
		var got int
		if err = db.QueryRow("SELECT message_id FROM conversation_heads").Scan(&got); err != nil || got != want {
			t.Fatalf("index got %d want %d: %v", got, want, err)
		}
	}
	check(3)
	// A legacy writer ignores the derived index during rollback.
	if _, err = db.Exec("INSERT INTO messages (id,device_id,fingerprint,receiver,sender,body,timestamp,subscription_id,historical) VALUES (4,?,?,?,?,?,?,?,FALSE)", d.ID, strings.Repeat("d", 64), "+123456789", "synthetic", "legacy writer", 4000, 7); err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("UPDATE sync_clock SET seq=4 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	if err = migrate(context.Background(), db, "../db/migrations"); err != nil {
		t.Fatal(err)
	}
	check(4)
	// Backfill-only down migration is safe for authoritative messages.
	if _, err = db.Exec("DROP TABLE conversation_heads"); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = db.QueryRow("SELECT COUNT(*) FROM messages").Scan(&count); err != nil || count != 4 {
		t.Fatal("derived index removal deleted history", err)
	}
}

func TestIntegrationAuthenticationOutageAndRecoveryKeepsSameIdentity(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "synthetic", "", "")
	c := apiTest{t: t, a: a}
	identity := c.call("POST", "/api/login", "", map[string]any{"password": "synthetic", "kind": "android"}, 200)
	token := identity["token"].(string)
	id := identity["device"].(map[string]any)["id"]
	c.call("GET", "/api/me", token, nil, 200)
	offline, err := sql.Open("mysql", "synthetic@tcp(127.0.0.1:1)/synthetic?timeout=1s")
	if err != nil {
		t.Fatal(err)
	}
	offline.Close()
	a.db = offline
	c.call("GET", "/api/me", token, nil, 503)
	a.db = db
	recovered := c.call("GET", "/api/me", token, nil, 200)
	if recovered["device"].(map[string]any)["id"] != id {
		t.Fatal("recovery replaced identity")
	}
}
