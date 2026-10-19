package main

import (
	"context"
	"database/sql"
	"os"
	"os/exec"
	"strconv"
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
		var epoch int64
		if e = db.QueryRow("SELECT seq,epoch FROM sync_clock WHERE id=1").Scan(&seq, &epoch); e != nil || seq != 1 || epoch != 1 {
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
	// Repeated restore of the SAME dump must not reuse a namespace, even when
	// newly ingested IDs have already caught up with a stale client's cursor.
	lastEpoch := int64(1)
	for generation := 2; generation <= 3; generation++ {
		var liveEpoch int64
		if e = target.QueryRow("SELECT epoch FROM sync_clock WHERE id=1").Scan(&liveEpoch); e != nil {
			t.Fatal(e)
		}
		repeat := exec.Command(clientBinary, append(args, targetName)...)
		repeat.Env = dump.Env
		repeat.Stdin = strings.NewReader(string(bytes))
		if e = repeat.Run(); e != nil {
			t.Fatal("repeated isolated restore failed", e)
		}
		var restoredEpoch int64
		if e = target.QueryRow("SELECT epoch FROM sync_clock WHERE id=1").Scan(&restoredEpoch); e != nil {
			t.Fatal(e)
		}
		next, e := exec.Command("bash", "../scripts/next-restore-epoch.sh", strconv.FormatInt(liveEpoch, 10), strconv.FormatInt(restoredEpoch, 10)).Output()
		if e != nil {
			t.Fatal("generation helper failed", e)
		}
		epoch, e := strconv.ParseInt(strings.TrimSpace(string(next)), 10, 64)
		if e != nil || epoch <= lastEpoch || epoch != int64(generation) {
			t.Fatal("restore reused generation", e)
		}
		if _, e = target.Exec("UPDATE sync_clock SET epoch=?,seq=3 WHERE id=1", epoch); e != nil {
			t.Fatal(e)
		}
		for id := 2; id <= 3; id++ {
			if _, e = target.Exec("INSERT INTO messages (id,device_id,fingerprint,receiver,sender,body,timestamp,subscription_id,historical) VALUES (?,?,?,?,?,?,?,?,FALSE)", id, d.ID, hash(strconv.Itoa(id)+strconv.Itoa(generation)), "+1234567890", "synthetic", "new restore namespace", 1700000000000+id, 1); e != nil {
				t.Fatal(e)
			}
		}
		restoredApp, e := newApp(target, "http://localhost:8080", "synthetic", "", "")
		if e != nil {
			t.Fatal(e)
		}
		restoredClient := apiTest{t: t, a: restoredApp}
		page := restoredClient.call("GET", "/api/sync?after=2", token, nil, 200)
		if page["epoch"] != float64(epoch) || page["epoch"] == float64(lastEpoch) {
			t.Fatal("client cannot distinguish reused IDs")
		}
		lastEpoch = epoch
	}
}
