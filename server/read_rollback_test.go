package main

import (
	"context"
	"os"
	"strings"
	"testing"
)

// Fail after the conversation write, not at request validation, to prove the
// durable notification marker and read boundary cannot commit independently.
func TestReadMarkerFailureRollsBackConversation(t *testing.T) {
	a, c, token := unreadFixture(t)
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 1}, 200)
	var before int64
	if err := a.db.QueryRow("SELECT updated_at FROM read_state WHERE id=1").Scan(&before); err != nil {
		t.Fatal(err)
	}
	if _, err := a.db.Exec(`CREATE TRIGGER fail_read_marker BEFORE UPDATE ON read_state FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic marker failure'`); err != nil {
		t.Fatal(err)
	}
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 3}, 503)
	var boundary, revision int64
	if err := a.db.QueryRow("SELECT cr.last_read_message_id,r.updated_at FROM conversation_reads cr JOIN read_state r ON r.id=1 WHERE cr.sender='10086'").Scan(&boundary, &revision); err != nil || boundary != 1 || revision != before {
		t.Fatal("failed marker committed a partial read", boundary, revision, err)
	}
	if _, err := a.db.Exec("DROP TRIGGER fail_read_marker"); err != nil {
		t.Fatal(err)
	}
	c.call("POST", "/api/messages/read", token, map[string]any{"sender": "10086", "message_id": 3}, 200)
}

func TestManualRepairCanRollBackBeforeCommit(t *testing.T) {
	a, _, _ := unreadFixture(t)
	if _, err := a.db.Exec("INSERT INTO conversation_reads VALUES ('10086',999,123)"); err != nil {
		t.Fatal(err)
	}
	var before int64
	if err := a.db.QueryRow("SELECT updated_at FROM read_state WHERE id=1").Scan(&before); err != nil {
		t.Fatal(err)
	}
	script, err := os.ReadFile("../scripts/repair-read-watermarks.sql")
	if err != nil {
		t.Fatal(err)
	}
	conn, err := a.db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	defer conn.ExecContext(context.Background(), "ROLLBACK")
	for _, statement := range splitSQL(string(script)) {
		if strings.EqualFold(strings.TrimSpace(statement), "COMMIT") {
			continue
		}
		if _, err := conn.ExecContext(context.Background(), statement); err != nil {
			t.Fatal(err)
		}
	}
	var provisional int64
	if err := conn.QueryRowContext(context.Background(), "SELECT last_read_message_id FROM conversation_reads WHERE sender='10086'").Scan(&provisional); err != nil || provisional != 0 {
		t.Fatal("repair never reached its update", provisional, err)
	}
	if _, err := conn.ExecContext(context.Background(), "ROLLBACK"); err != nil {
		t.Fatal(err)
	}
	var boundary, readAt, revision int64
	if err := a.db.QueryRow("SELECT cr.last_read_message_id,cr.read_at,r.updated_at FROM conversation_reads cr JOIN read_state r ON r.id=1 WHERE cr.sender='10086'").Scan(&boundary, &readAt, &revision); err != nil || boundary != 999 || readAt != 123 || revision != before {
		t.Fatal("repair rollback lost original state", boundary, readAt, revision, err)
	}
}
