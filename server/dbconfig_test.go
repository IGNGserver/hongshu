package main

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/go-sql-driver/mysql"
)

func clearDatabaseEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{
		"HONGSHU_DATABASE_URL", "MYSQL_DSN", "HONGSHU_DB_MAX_OPEN", "HONGSHU_DB_MAX_IDLE",
		"HONGSHU_DB_CONN_MAX_LIFETIME", "HONGSHU_DB_CONN_MAX_IDLE_TIME", "HONGSHU_DB_STARTUP_TIMEOUT",
		"HONGSHU_MIGRATION_LOCK_TIMEOUT", "HONGSHU_MIGRATE", "HONGSHU_DB_TLS_CA", "HONGSHU_DB_TLS_SERVER_NAME",
	} {
		t.Setenv(key, "")
	}
}

func TestParseDatabaseURL(t *testing.T) {
	clearDatabaseEnv(t)
	driver, err := parseDatabaseURL("mysql://hongshu:p%40ss%2Fword@db.example:3307/hongshu?tls=false&timeout=7s&readTimeout=45s")
	if err != nil {
		t.Fatal(err)
	}
	if driver.User != "hongshu" || driver.Passwd != "p@ss/word" || driver.Addr != "db.example:3307" || driver.DBName != "hongshu" {
		t.Fatalf("unexpected driver config: %#v", driver)
	}
	if driver.TLS != nil {
		t.Fatal("tls=false still enabled TLS")
	}
	if driver.Timeout != 7*time.Second || driver.ReadTimeout != 45*time.Second {
		t.Fatal("timeout parameters were not applied")
	}
	if !driver.InterpolateParams || driver.MultiStatements || driver.ParseTime {
		t.Fatal("unsafe or unexpected driver defaults")
	}
	if driver.Collation != "utf8mb4_bin" {
		t.Fatalf("collation = %q", driver.Collation)
	}
}

func TestParseDatabaseURLIPV6AndDefaultTLS(t *testing.T) {
	clearDatabaseEnv(t)
	driver, err := parseDatabaseURL("mysql://user:pass@[2001:db8::1]:3306/hongshu")
	if err != nil {
		t.Fatal(err)
	}
	if driver.Addr != "[2001:db8::1]:3306" {
		t.Fatalf("addr = %q", driver.Addr)
	}
	if driver.TLS == nil || !driver.TLS.InsecureSkipVerify {
		t.Fatal("non-loopback URL should default to opportunistic TLS")
	}
}

func TestParseDatabaseURLRejectsUnsafeSettings(t *testing.T) {
	clearDatabaseEnv(t)
	cases := []string{
		"postgres://user:pass@host/db",
		"mysql://host/db",
		"mysql://user@host/",
		"mysql://user:pass@host:70000/db",
		"mysql://user:pass@host/db?multiStatements=true",
		"mysql://user:pass@host/db?allowAllFiles=true",
		"mysql://user:pass@host/db?collation=utf8mb4_unicode_ci",
		"mysql://user:pass@host/db?unknown=1",
	}
	for _, raw := range cases {
		if _, err := parseDatabaseURL(raw); err == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
}

func TestDatabaseConfigDefaultTLSForRemoteURL(t *testing.T) {
	clearDatabaseEnv(t)
	t.Setenv("HONGSHU_DATABASE_URL", "mysql://user:pass@remote.example:3306/hongshu")
	cfg, err := loadDatabaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Driver.TLS == nil {
		t.Fatal("remote URL did not enable default TLS")
	}
	found := false
	for _, warning := range cfg.Warnings {
		if strings.Contains(warning, "verification is disabled") {
			found = true
		}
	}
	if !found {
		t.Fatalf("default preferred TLS should warn about verification: %v", cfg.Warnings)
	}
}

func TestDatabaseConfigPrecedenceAndRedaction(t *testing.T) {
	clearDatabaseEnv(t)
	t.Setenv("HONGSHU_DATABASE_URL", "mysql://urluser:urlpass@remote.example:3306/hongshu?tls=false")
	t.Setenv("MYSQL_DSN", "dsnuser:dsnpass@tcp(127.0.0.1:3306)/other")
	cfg, err := loadDatabaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Source != "HONGSHU_DATABASE_URL" || cfg.Driver.User != "urluser" || cfg.Driver.DBName != "hongshu" {
		t.Fatalf("URL did not win: %#v", cfg)
	}
	if len(cfg.Warnings) == 0 {
		t.Fatal("both configured but no warning")
	}
	if strings.Contains(cfg.Address, "urlpass") || strings.Contains(cfg.Address, "dsnpass") {
		t.Fatalf("address leaked a password: %s", cfg.Address)
	}
}

func TestDatabaseConfigFromDSNAppliesSafeDefaults(t *testing.T) {
	clearDatabaseEnv(t)
	t.Setenv("MYSQL_DSN", "user:pass@tcp(127.0.0.1:3306)/hongshu?charset=utf8mb4")
	cfg, err := loadDatabaseConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Source != "MYSQL_DSN" || !cfg.Driver.InterpolateParams || cfg.Driver.Collation != "utf8mb4_bin" {
		t.Fatalf("safe defaults missing: %#v", cfg.Driver)
	}
	if cfg.MaxIdle != cfg.MaxOpen {
		t.Fatalf("max idle %d should default to max open %d", cfg.MaxIdle, cfg.MaxOpen)
	}
}

func TestDatabaseConfigRejectsRawDSNPlaintextFallback(t *testing.T) {
	clearDatabaseEnv(t)
	t.Setenv("MYSQL_DSN", "user:pass@tcp(127.0.0.1:3306)/hongshu?tls=preferred&allowFallbackToPlaintext=true")
	if _, err := loadDatabaseConfig(); err == nil || !strings.Contains(err.Error(), "AllowFallbackToPlaintext") {
		t.Fatalf("raw DSN plaintext fallback was not rejected clearly: %v", err)
	}
}

func TestDatabaseConfigRejectsInvalidPool(t *testing.T) {
	clearDatabaseEnv(t)
	t.Setenv("HONGSHU_DATABASE_URL", "mysql://user:pass@host/db?tls=false")
	t.Setenv("HONGSHU_DB_MAX_OPEN", "0")
	if _, err := loadDatabaseConfig(); err == nil {
		t.Fatal("invalid pool size accepted")
	}
}

func TestDatabaseErrorCategoryDoesNotLeakDSN(t *testing.T) {
	cases := []struct {
		err  error
		want string
	}{
		{&mysql.MySQLError{Number: 1045, Message: "Access denied for user 'x'@'y' (using password: YES)"}, "authentication failed"},
		{&mysql.MySQLError{Number: 1049, Message: "Unknown database 'hongshu'"}, "unknown database"},
		{context.DeadlineExceeded, "timeout"},
		{errors.New("dial tcp 10.0.0.1:3306: connect: connection refused"), "connection refused"},
	}
	for _, tc := range cases {
		got := databaseErrorCategory(tc.err)
		if got != tc.want {
			t.Fatalf("category(%v) = %q want %q", tc.err, got, tc.want)
		}
		if strings.Contains(got, "password") || strings.Contains(got, "10.0.0.1") {
			t.Fatalf("category leaked details: %q", got)
		}
	}
}

func TestMySQLVersion(t *testing.T) {
	if major, minor := mysqlVersion("8.4.5"); major != 8 || minor != 4 {
		t.Fatalf("got %d.%d", major, minor)
	}
	if major, _ := mysqlVersion("10.11.6-MariaDB"); major != 10 {
		t.Fatalf("got %d", major)
	}
}

func TestMigrationLockNameIsSchemaScoped(t *testing.T) {
	a := migrationLockName("hongshu")
	b := migrationLockName("another")
	if a == b {
		t.Fatal("different schemas share a migration lock")
	}
	if len(a) > 64 {
		t.Fatalf("lock name too long: %d", len(a))
	}
	if a != migrationLockName("hongshu") {
		t.Fatal("lock name is not stable")
	}
}
