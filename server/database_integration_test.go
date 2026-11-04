package main

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/go-sql-driver/mysql"
)

func rootTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := os.Getenv("TEST_MYSQL_DSN")
	if dsn == "" {
		t.Skip("TEST_MYSQL_DSN required for real MySQL tests")
	}
	cfg, err := mysql.ParseDSN(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.DBName = ""
	db, err := sql.Open("mysql", cfg.FormatDSN())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func createTestDatabase(t *testing.T, root *sql.DB, collation string) string {
	t.Helper()
	name := "hongshu_it_" + random(8)
	if _, err := root.Exec("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE " + collation); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = root.Exec("DROP DATABASE " + name) })
	return name
}

func questionCount(t *testing.T, db *sql.DB) int64 {
	t.Helper()
	var value string
	if err := db.QueryRow("SHOW GLOBAL STATUS LIKE 'Questions'").Scan(new(string), &value); err != nil {
		t.Fatal(err)
	}
	var n int64
	if _, err := fmt.Sscan(value, &n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestIntegrationBatchUploadQueryBudget(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/login", "", map[string]any{"password": "1"}, 200)
	me := c.call("GET", "/api/me", "", nil, 200)
	adminID := me["device"].(map[string]any)["id"].(string)
	if _, err := db.Exec("INSERT INTO push_subscriptions VALUES (?,?,?,?)", adminID, "https://push.invalid/synthetic", "p256dh", "auth"); err != nil {
		t.Fatal(err)
	}
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "name": "Phone", "kind": "android"}, 200)
	token := paired["token"].(string)
	deviceID := paired["device"].(map[string]any)["id"].(string)
	c.call("PATCH", "/api/devices/"+deviceID, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613800000000", "label": "SIM1", "subscription_id": 1}, 200)

	messages := make([]Incoming, 0, 100)
	for index := 0; index < 100; index++ {
		messages = append(messages, Incoming{
			Receiver:       "+8613800000000",
			Sender:         "10086",
			Body:           fmt.Sprintf("synthetic batch message %03d", index),
			Timestamp:      1700000000000 + int64(index),
			SubscriptionID: 1,
		})
	}
	before := questionCount(t, db)
	result := c.call("POST", "/api/messages", token, map[string]any{"messages": messages}, 200)
	after := questionCount(t, db)
	acks := result["acks"].([]any)
	if len(acks) != 100 {
		t.Fatalf("acks = %d", len(acks))
	}
	for _, raw := range acks {
		ack := raw.(map[string]any)
		if ack["error"] != nil || ack["id"].(float64) <= 0 {
			t.Fatalf("bad ack: %v", ack)
		}
	}
	var jobs int
	if err := db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&jobs); err != nil || jobs != 100 {
		t.Fatalf("push jobs = %d, %v", jobs, err)
	}
	if delta := after - before; delta > 40 {
		t.Fatalf("100-message upload used %d statements; batching regressed", delta)
	}
}

func TestIntegrationDatabaseURLAndMigrationModes(t *testing.T) {
	root := rootTestDB(t)
	database := createTestDatabase(t, root, "utf8mb4_bin")
	host, port := testMySQLAddress(t)
	t.Setenv("HONGSHU_DATABASE_URL", fmt.Sprintf("mysql://root@%s:%s/%s?tls=false", host, port, database))
	t.Setenv("MYSQL_DSN", "")
	t.Setenv("HONGSHU_DB_TLS_CA", "")
	t.Setenv("HONGSHU_DB_TLS_SERVER_NAME", "")
	t.Setenv("HONGSHU_MIGRATE", "auto")
	cfg, err := loadDatabaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	db, err := openDatabase(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = waitForDatabase(context.Background(), db, cfg.StartupTimeout); err != nil {
		t.Fatal(err)
	}
	if err = checkDatabaseCompatibility(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	if err = migrateWithOptions(context.Background(), db, "../db/migrations", "check", cfg.MigrationLockWait); err == nil {
		t.Fatal("check mode accepted an empty schema")
	}
	if err = migrateWithOptions(context.Background(), db, "../db/migrations", "auto", cfg.MigrationLockWait); err != nil {
		t.Fatal(err)
	}
	if err = migrateWithOptions(context.Background(), db, "../db/migrations", "check", cfg.MigrationLockWait); err != nil {
		t.Fatal(err)
	}
	if err = checkTableCollations(context.Background(), db); err != nil {
		t.Fatal(err)
	}
	// A future schema must be rejected rather than allowing an older binary to run.
	if _, err = db.Exec("INSERT INTO schema_migrations VALUES ('999_future.up.sql',?,FALSE)", strings.Repeat("0", 64)); err != nil {
		t.Fatal(err)
	}
	if err = migrateWithOptions(context.Background(), db, "../db/migrations", "check", cfg.MigrationLockWait); err == nil {
		t.Fatal("future schema was accepted")
	}
}

func TestIntegrationWrongCollationRejected(t *testing.T) {
	root := rootTestDB(t)
	database := createTestDatabase(t, root, "utf8mb4_0900_ai_ci")
	host, port := testMySQLAddress(t)
	cfg, err := mysql.ParseDSN("root@tcp(" + host + ":" + port + ")/" + database + "?timeout=5s")
	if err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("mysql", cfg.FormatDSN())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = checkDatabaseCompatibility(context.Background(), db); err == nil {
		t.Fatal("wrong database collation accepted")
	}
}

func testMySQLAddress(t *testing.T) (string, string) {
	t.Helper()
	dsn := os.Getenv("TEST_MYSQL_DSN")
	cfg, err := mysql.ParseDSN(dsn)
	if err != nil {
		t.Fatal(err)
	}
	host, port, err := splitHostPort(cfg.Addr)
	if err != nil {
		t.Fatal(err)
	}
	return host, port
}

func splitHostPort(address string) (string, string, error) {
	index := strings.LastIndex(address, ":")
	if index < 0 {
		return "", "", fmt.Errorf("address %q has no port", address)
	}
	return strings.Trim(address[:index], "[]"), address[index+1:], nil
}
