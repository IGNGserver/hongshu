package main

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/go-sql-driver/mysql"
)

type migrationFile struct {
	Version  string
	Path     string
	Checksum string
	SQL      string
}

type appliedMigration struct {
	Checksum string
	Dirty    bool
}

// migrate is the test/backward-compatible entry point. Production selects a mode through
// HONGSHU_MIGRATE so a shared MySQL can use a DDL-capable migration account once and a
// DML-only runtime account afterwards.
func migrate(ctx context.Context, db *sql.DB, dir string) error {
	return migrateWithOptions(ctx, db, dir, "auto", 30*time.Second)
}

func migrateWithOptions(ctx context.Context, db *sql.DB, dir, mode string, lockWait time.Duration) error {
	switch mode {
	case "auto", "check":
	case "off":
		return nil
	default:
		return errors.New("invalid migration mode")
	}
	conn, err := db.Conn(ctx)
	if err != nil {
		return fmt.Errorf("database unavailable: %s", databaseErrorCategory(err))
	}
	defer conn.Close()

	var database string
	if err = conn.QueryRowContext(ctx, "SELECT DATABASE()").Scan(&database); err != nil || database == "" {
		return errors.New("database name is required for migrations")
	}
	release, err := acquireMigrationLock(ctx, conn, database, lockWait)
	if err != nil {
		return err
	}
	defer release()

	files, err := loadMigrationFiles(dir)
	if err != nil {
		return err
	}
	applied, err := readAppliedMigrations(ctx, conn, mode == "auto")
	if err != nil {
		return err
	}
	pending, err := inspectMigrations(files, applied)
	if err != nil {
		return err
	}
	if mode == "check" {
		if len(pending) > 0 {
			return fmt.Errorf("database schema has %d pending migration(s); run hongshu migrate", len(pending))
		}
		return nil
	}
	for _, file := range pending {
		if err = applyMigration(ctx, conn, file); err != nil {
			return err
		}
	}
	return reconcileConversationHeads(ctx, conn)
}

func loadMigrationFiles(dir string) ([]migrationFile, error) {
	files, err := filepath.Glob(filepath.Join(dir, "*.up.sql"))
	if err != nil {
		return nil, err
	}
	sort.Strings(files)
	if len(files) == 0 {
		return nil, errors.New("no migrations")
	}
	out := make([]migrationFile, 0, len(files))
	for _, path := range files {
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		sum := sha256.Sum256(data)
		out = append(out, migrationFile{
			Version:  filepath.Base(path),
			Path:     path,
			Checksum: hex.EncodeToString(sum[:]),
			SQL:      string(data),
		})
	}
	return out, nil
}

func readAppliedMigrations(ctx context.Context, conn *sql.Conn, create bool) (map[string]appliedMigration, error) {
	applied := map[string]appliedMigration{}
	rows, err := conn.QueryContext(ctx, "SELECT version,checksum,dirty FROM schema_migrations")
	if err != nil {
		if !isMissingTable(err) {
			return nil, fmt.Errorf("schema state unavailable: %s", databaseErrorCategory(err))
		}
		if !create {
			return nil, errors.New("schema is not initialized; run hongshu migrate with a DDL-capable account")
		}
		if _, err = conn.ExecContext(ctx, "CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(100) PRIMARY KEY, checksum CHAR(64) NOT NULL, dirty BOOLEAN NOT NULL)"); err != nil {
			return nil, fmt.Errorf("schema state initialization failed: %s", databaseErrorCategory(err))
		}
		return applied, nil
	}
	defer rows.Close()
	for rows.Next() {
		var version string
		var row appliedMigration
		if err = rows.Scan(&version, &row.Checksum, &row.Dirty); err != nil {
			return nil, err
		}
		applied[version] = row
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	return applied, nil
}

func inspectMigrations(files []migrationFile, applied map[string]appliedMigration) ([]migrationFile, error) {
	local := make(map[string]migrationFile, len(files))
	for _, file := range files {
		local[file.Version] = file
	}
	for version, row := range applied {
		file, ok := local[version]
		if !ok {
			return nil, fmt.Errorf("database schema contains migration %s that this hub does not know; upgrade the hub", version)
		}
		if row.Dirty {
			return nil, fmt.Errorf("migration %s is dirty; restore or repair required", version)
		}
		if row.Checksum != file.Checksum {
			return nil, fmt.Errorf("migration %s was modified; restore/repair required", version)
		}
	}
	pending := make([]migrationFile, 0, len(files))
	for _, file := range files {
		if _, ok := applied[file.Version]; !ok {
			pending = append(pending, file)
		}
	}
	return pending, nil
}

func applyMigration(ctx context.Context, conn *sql.Conn, file migrationFile) error {
	if _, err := conn.ExecContext(ctx, "INSERT INTO schema_migrations VALUES (?,?,TRUE)", file.Version, file.Checksum); err != nil {
		return fmt.Errorf("migration %s could not be recorded: %s", file.Version, databaseErrorCategory(err))
	}
	for index, statement := range splitSQL(file.SQL) {
		if _, err := conn.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("migration %s statement %d failed; inspect database availability/schema (sensitive details omitted)", file.Version, index+1)
		}
	}
	if _, err := conn.ExecContext(ctx, "UPDATE schema_migrations SET dirty=FALSE WHERE version=?", file.Version); err != nil {
		return fmt.Errorf("migration %s could not be finalized: %s", file.Version, databaseErrorCategory(err))
	}
	return nil
}

func acquireMigrationLock(ctx context.Context, conn *sql.Conn, database string, wait time.Duration) (func(), error) {
	seconds := int(math.Ceil(wait.Seconds()))
	if seconds < 1 {
		seconds = 1
	}
	name := migrationLockName(database)
	var got sql.NullInt64
	if err := conn.QueryRowContext(ctx, "SELECT GET_LOCK(?,?)", name, seconds).Scan(&got); err != nil {
		return nil, fmt.Errorf("migration lock unavailable: %s", databaseErrorCategory(err))
	}
	if !got.Valid || got.Int64 != 1 {
		return nil, errors.New("migration lock unavailable; another hub may be migrating this schema")
	}
	return func() {
		releaseCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = conn.ExecContext(releaseCtx, "SELECT RELEASE_LOCK(?)", name)
	}, nil
}

// The lock is schema-scoped so two independent hub databases on one shared MySQL server do
// not block each other's upgrades.
func migrationLockName(database string) string {
	sum := sha256.Sum256([]byte(database))
	return "hongshu:m:" + hex.EncodeToString(sum[:16])
}

func isMissingTable(err error) bool {
	var mysqlErr *mysql.MySQLError
	return errors.As(err, &mysqlErr) && mysqlErr.Number == 1146
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
