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
		for index, stmt := range splitSQL(string(data)) {
			if _, e = conn.ExecContext(ctx, stmt); e != nil {
				return fmt.Errorf("migration %s statement %d failed; inspect database availability/schema (sensitive details omitted)", version, index+1)
			}
		}
		if _, e = conn.ExecContext(ctx, "UPDATE schema_migrations SET dirty=FALSE WHERE version=?", version); e != nil {
			return e
		}
	}
	return reconcileConversationHeads(ctx, conn)
}

// A rollback to a legacy writer can leave this derived index behind the clock.
// Rebuild only then, under the same lock ordering used by uploads.
func reconcileConversationHeads(ctx context.Context, db interface {
	BeginTx(context.Context, *sql.TxOptions) (*sql.Tx, error)
}) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("conversation index transaction unavailable")
	}
	defer tx.Rollback()
	var head, indexed int64
	if err = tx.QueryRowContext(ctx, "SELECT seq FROM sync_clock WHERE id=1 FOR UPDATE").Scan(&head); err != nil {
		return fmt.Errorf("conversation index clock unavailable")
	}
	if err = tx.QueryRowContext(ctx, "SELECT seq FROM conversation_heads_state WHERE id=1 FOR UPDATE").Scan(&indexed); err != nil {
		return fmt.Errorf("conversation index state unavailable")
	}
	if head != indexed {
		if _, err = tx.ExecContext(ctx, "DELETE FROM conversation_heads"); err != nil {
			return fmt.Errorf("conversation index reset failed")
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO conversation_heads (sender,message_id,timestamp) SELECT sender,id,timestamp FROM (SELECT sender,id,timestamp,ROW_NUMBER() OVER (PARTITION BY sender ORDER BY timestamp DESC,id DESC) rn FROM messages) ranked WHERE rn=1"); err != nil {
			return fmt.Errorf("conversation index rebuild failed")
		}
		if _, err = tx.ExecContext(ctx, "UPDATE conversation_heads_state SET seq=? WHERE id=1", head); err != nil {
			return fmt.Errorf("conversation index checkpoint failed")
		}
	}
	return tx.Commit()
}

// splitSQL separates statements without treating a semicolon inside a quoted
// string or line comment as a boundary. Migrations are not allowed to use
// routine bodies, so dollar-quoting is intentionally unsupported.
func splitSQL(script string) []string {
	var out []string
	var b strings.Builder
	inSingle, inDouble, inLine := false, false, false
	for i := 0; i < len(script); i++ {
		c := script[i]
		if inLine {
			if c == '\n' {
				inLine = false
			}
			continue
		}
		if inSingle {
			b.WriteByte(c)
			if c == '\\' && i+1 < len(script) {
				i++
				b.WriteByte(script[i])
				continue
			}
			if c == '\'' {
				if i+1 < len(script) && script[i+1] == '\'' {
					i++
					b.WriteByte('\'')
					continue
				}
				inSingle = false
			}
			continue
		}
		if inDouble {
			b.WriteByte(c)
			if c == '"' {
				inDouble = false
			}
			continue
		}
		if c == '-' && i+1 < len(script) && script[i+1] == '-' {
			inLine = true
			i++
			continue
		}
		if c == '\'' {
			inSingle = true
			b.WriteByte(c)
			continue
		}
		if c == '"' {
			inDouble = true
			b.WriteByte(c)
			continue
		}
		if c == ';' {
			if stmt := strings.TrimSpace(b.String()); stmt != "" {
				out = append(out, stmt)
			}
			b.Reset()
			continue
		}
		b.WriteByte(c)
	}
	if stmt := strings.TrimSpace(b.String()); stmt != "" {
		out = append(out, stmt)
	}
	return out
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	password := os.Getenv("HONGSHU_PASSWORD")
	if len(password) == 0 || len(password) > 1024 {
		log.Fatal("HONGSHU_PASSWORD must contain 1 to 1024 bytes")
	}
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
	a, err := newApp(db, env("PUBLIC_URL", "http://localhost:8080"), password, os.Getenv("VAPID_PUBLIC_KEY"), os.Getenv("VAPID_PRIVATE_KEY"))
	if err != nil {
		log.Fatal(err)
	}
	if err = a.configureTrustedProxies(os.Getenv("TRUSTED_PROXY_CIDRS")); err != nil {
		log.Fatal(err)
	}
	go a.pushLoop(ctx)
	go a.analysisLoop(ctx)
	go a.analysisBackfillLoop(ctx)
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
