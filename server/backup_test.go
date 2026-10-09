package main

import (
	"context"
	"database/sql"
	"os"
	"os/exec"
	"strings"
	"testing"

	"github.com/go-sql-driver/mysql"
)

func TestIntegrationBackupRestore(t *testing.T) {
	dumpBinary, clientBinary := os.Getenv("MYSQLDUMP_BIN"), os.Getenv("MYSQL_BIN")
	if dumpBinary == "" || clientBinary == "" {
		t.Skip("MYSQLDUMP_BIN and MYSQL_BIN required for real logical restore")
	}
	source, target := testDB(t), testDB(t)
	a, _ := newApp(source, "http://localhost:8080", strings.Repeat("s", 32), "", "")
	tx, _ := source.Begin()
	d, token, e := insertDevice(context.Background(), tx, "backup source", "android", false)
	if e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	c := apiTest{t: t, a: a}
	c.call("PATCH", "/api/devices/"+d.ID, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+1234567890", "label": "Backup SIM", "subscription_id": 1}, 200)
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{{Receiver: "+1234567890", Sender: "backup sender", Body: "synthetic restore fixture", Timestamp: 1700000000000, SubscriptionID: 1}}}, 200)
	var sourceName, targetName string
	_ = source.QueryRow("SELECT DATABASE()").Scan(&sourceName)
	_ = target.QueryRow("SELECT DATABASE()").Scan(&targetName)
	cfg, e := mysql.ParseDSN(os.Getenv("TEST_MYSQL_DSN"))
	if e != nil {
		t.Fatal(e)
	}
	// Both databases were created by this test, never a production target.
	args := []string{"--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=13306", "--user=" + cfg.User}
	if cfg.Net == "tcp" {
		parts := strings.Split(cfg.Addr, ":")
		args[2] = "--host=" + strings.Join(parts[:len(parts)-1], ":")
		args[3] = "--port=" + parts[len(parts)-1]
	}
	dump := exec.Command(dumpBinary, append(args, "--single-transaction", "--quick", "--no-tablespaces", "--set-gtid-purged=OFF", sourceName)...)
	dump.Env = append(os.Environ(), "MYSQL_PWD="+cfg.Passwd)
	bytes, e := dump.Output()
	if e != nil {
		t.Fatal("consistent dump failed", e)
	}
	restore := exec.Command(clientBinary, append(args, targetName)...)
	restore.Env = dump.Env
	restore.Stdin = strings.NewReader(string(bytes))
	if e = restore.Run(); e != nil {
		t.Fatal("isolated restore failed", e)
	}
	for _, db := range []*sql.DB{source, target} {
		var seq, count int64
		if e = db.QueryRow("SELECT seq FROM sync_clock WHERE id=1").Scan(&seq); e != nil || seq != 1 {
			t.Fatal("restored cursor mismatch")
		}
		if e = db.QueryRow("SELECT COUNT(*) FROM messages WHERE body='synthetic restore fixture'").Scan(&count); e != nil || count != 1 {
			t.Fatal("restored message mismatch")
		}
		var name string
		if e = db.QueryRow("SELECT name FROM devices WHERE token_hash=?", hash(token)).Scan(&name); e != nil || name != "backup source" {
			t.Fatal("identity not restored")
		}
		if e = migrate(context.Background(), db, "../db/migrations"); e != nil {
			t.Fatal("restored migration audit invalid", e)
		}
	}
}
