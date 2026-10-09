package main

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

var buildVersion = "development"

func env(k, fallback string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return fallback
}

// DDL is not transactional in MySQL. Dirty state deliberately blocks startup
// after partial application; never silently continue a half-applied schema.
func migrate(ctx context.Context, db *sql.DB, dir string) error {
	conn, err := db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	var locked int
	if err = conn.QueryRowContext(ctx, "SELECT GET_LOCK('hongshu_migrations',30)").Scan(&locked); err != nil || locked != 1 {
		return fmt.Errorf("migration lock unavailable")
	}
	defer conn.ExecContext(context.Background(), "SELECT RELEASE_LOCK('hongshu_migrations')")
	if _, err = conn.ExecContext(ctx, "CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(100) PRIMARY KEY, checksum CHAR(64) NOT NULL, dirty BOOLEAN NOT NULL)"); err != nil {
		return err
	}
	files, err := filepath.Glob(filepath.Join(dir, "*.up.sql"))
	if err != nil {
		return err
	}
	if len(files) == 0 {
		return fmt.Errorf("no migrations")
	}
	for _, file := range files {
		data, e := os.ReadFile(file)
		if e != nil {
			return e
		}
		sum := sha256.Sum256(data)
		checksum := hex.EncodeToString(sum[:])
		version := filepath.Base(file)
		var old string
		var dirty bool
		e = conn.QueryRowContext(ctx, "SELECT checksum,dirty FROM schema_migrations WHERE version=?", version).Scan(&old, &dirty)
		if e == nil {
			if dirty || old != checksum {
				return fmt.Errorf("migration %s dirty or modified; restore/repair required", version)
			}
			continue
		}
		if e != sql.ErrNoRows {
			return e
		}
		if _, e = conn.ExecContext(ctx, "INSERT INTO schema_migrations VALUES (?,?,TRUE)", version, checksum); e != nil {
			return e
		}
		for _, stmt := range strings.Split(string(data), ";") {
			if strings.TrimSpace(stmt) != "" {
				if _, e = conn.ExecContext(ctx, stmt); e != nil {
					return fmt.Errorf("migration %s failed", version)
				}
			}
		}
		if _, e = conn.ExecContext(ctx, "UPDATE schema_migrations SET dirty=FALSE WHERE version=?", version); e != nil {
			return e
		}
	}
	return nil
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	dsn := os.Getenv("MYSQL_DSN")
	if dsn == "" {
		log.Fatal("MYSQL_DSN is required")
	}
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		log.Fatal("invalid database configuration")
	}
	defer db.Close()
	db.SetMaxOpenConns(16)
	db.SetMaxIdleConns(4)
	db.SetConnMaxLifetime(5 * time.Minute)
	for i := 0; i < 30; i++ {
		if err = db.PingContext(ctx); err == nil {
			break
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}
	}
	if err != nil {
		log.Fatal("database unavailable")
	}
	if err = migrate(ctx, db, env("MIGRATIONS_DIR", "../db/migrations")); err != nil {
		log.Fatal(err)
	}
	a, err := newApp(db, env("PUBLIC_URL", "http://localhost:8080"), os.Getenv("BOOTSTRAP_SECRET"), os.Getenv("VAPID_PUBLIC_KEY"), os.Getenv("VAPID_PRIVATE_KEY"))
	if err != nil {
		log.Fatal(err)
	}
	if len(a.bootstrap) < 32 {
		log.Fatal("BOOTSTRAP_SECRET must contain at least 32 characters")
	}
	go a.pushLoop(ctx)
	srv := &http.Server{Addr: env("LISTEN_ADDR", ":8080"), Handler: a.routes(env("WEB_DIR", "../web")), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 16 << 10}
	go func() {
		<-ctx.Done()
		c, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = srv.Shutdown(c)
	}()
	log.Print("Hongshu listening; sensitive request logging disabled")
	if err = srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal("HTTP server failed")
	}
}
