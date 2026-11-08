package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"database/sql"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/go-sql-driver/mysql"
)

// databaseConfig is the deployment-facing database contract. It deliberately keeps the
// driver config separate from pool policy so a remote MySQL can be tuned without changing
// SQL or handlers.
type databaseConfig struct {
	Driver *mysql.Config
	// Address is safe to log: it never contains the password.
	Address string
	Source  string

	MaxOpen             int
	MaxIdle             int
	ConnMaxLifetime     time.Duration
	ConnMaxIdleTime     time.Duration
	StartupTimeout      time.Duration
	MigrateMode         string
	MigrationLockWait   time.Duration
	Warnings            []string
	ConfiguredURLSource bool
}

func loadDatabaseConfig() (databaseConfig, error) {
	var cfg databaseConfig
	rawURL := strings.TrimSpace(os.Getenv("HONGSHU_DATABASE_URL"))
	rawDSN := strings.TrimSpace(os.Getenv("MYSQL_DSN"))
	if rawURL == "" && rawDSN == "" {
		return cfg, errors.New("HONGSHU_DATABASE_URL or MYSQL_DSN is required")
	}

	var driver *mysql.Config
	var err error
	if rawURL != "" {
		driver, err = parseDatabaseURL(rawURL)
		cfg.Source = "HONGSHU_DATABASE_URL"
		cfg.ConfiguredURLSource = true
		if rawDSN != "" {
			cfg.Warnings = append(cfg.Warnings, "both HONGSHU_DATABASE_URL and MYSQL_DSN are set; using HONGSHU_DATABASE_URL")
		}
	} else {
		driver, err = parseDatabaseDSN(rawDSN)
		cfg.Source = "MYSQL_DSN"
	}
	if err != nil {
		return cfg, err
	}

	if err = sanitizeDatabaseConfig(driver, cfg.ConfiguredURLSource); err != nil {
		return cfg, err
	}
	cfg.Driver = driver
	cfg.Address = redactedDatabaseAddress(driver)

	if cfg.MaxOpen, err = intEnv("HONGSHU_DB_MAX_OPEN", 16, 1, 256); err != nil {
		return cfg, err
	}
	if cfg.MaxIdle, err = intEnv("HONGSHU_DB_MAX_IDLE", cfg.MaxOpen, 0, cfg.MaxOpen); err != nil {
		return cfg, err
	}
	if cfg.ConnMaxLifetime, err = durationEnv("HONGSHU_DB_CONN_MAX_LIFETIME", 30*time.Minute, time.Minute, 24*time.Hour); err != nil {
		return cfg, err
	}
	if cfg.ConnMaxIdleTime, err = durationEnv("HONGSHU_DB_CONN_MAX_IDLE_TIME", 5*time.Minute, time.Second, 24*time.Hour); err != nil {
		return cfg, err
	}
	if cfg.StartupTimeout, err = durationEnv("HONGSHU_DB_STARTUP_TIMEOUT", 60*time.Second, 5*time.Second, 10*time.Minute); err != nil {
		return cfg, err
	}
	if cfg.MigrationLockWait, err = durationEnv("HONGSHU_MIGRATION_LOCK_TIMEOUT", 30*time.Second, time.Second, 10*time.Minute); err != nil {
		return cfg, err
	}
	cfg.MigrateMode = strings.ToLower(strings.TrimSpace(env("HONGSHU_MIGRATE", "auto")))
	switch cfg.MigrateMode {
	case "auto", "check", "off":
	default:
		return cfg, errors.New("HONGSHU_MIGRATE must be auto, check or off")
	}
	if driver.TLS == nil && !isLoopbackHost(driver.Addr) && cfg.ConfiguredURLSource {
		cfg.Warnings = append(cfg.Warnings, "database connection is not using TLS; use tls=true for remote MySQL or keep the network private")
	}
	if driver.TLS != nil && driver.TLS.InsecureSkipVerify {
		cfg.Warnings = append(cfg.Warnings, "database TLS certificate verification is disabled (tls=skip-verify/preferred); use tls=true for remote MySQL")
	}
	return cfg, nil
}

// parseDatabaseURL accepts mysql://user:password@host:port/database?params. Passwords must
// be URL-encoded in the URL form; the raw MYSQL_DSN form keeps its historical syntax.
func parseDatabaseURL(raw string) (*mysql.Config, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, errors.New("invalid HONGSHU_DATABASE_URL")
	}
	if u.Scheme != "mysql" {
		return nil, errors.New("HONGSHU_DATABASE_URL must use the mysql:// scheme")
	}
	if u.Fragment != "" {
		return nil, errors.New("HONGSHU_DATABASE_URL must not contain a fragment")
	}
	if u.User == nil || u.User.Username() == "" {
		return nil, errors.New("HONGSHU_DATABASE_URL must include a user")
	}
	password, _ := u.User.Password()
	host := u.Hostname()
	if host == "" {
		return nil, errors.New("HONGSHU_DATABASE_URL must include a host")
	}
	port := u.Port()
	if port == "" {
		port = "3306"
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		return nil, errors.New("HONGSHU_DATABASE_URL has an invalid port")
	}
	database, err := url.PathUnescape(strings.TrimPrefix(u.EscapedPath(), "/"))
	if err != nil || database == "" || strings.Contains(database, "/") {
		return nil, errors.New("HONGSHU_DATABASE_URL must include one database name")
	}

	driver := mysql.NewConfig()
	driver.User = u.User.Username()
	driver.Passwd = password
	driver.Net = "tcp"
	driver.Addr = net.JoinHostPort(host, port)
	driver.DBName = database
	driver.AllowNativePasswords = true
	driver.CheckConnLiveness = true
	driver.ParseTime = false
	driver.Loc = time.UTC
	driver.Collation = "utf8mb4_bin"
	driver.Timeout = 10 * time.Second
	driver.ReadTimeout = 60 * time.Second
	driver.WriteTimeout = 60 * time.Second
	driver.InterpolateParams = true

	values, err := url.ParseQuery(u.RawQuery)
	if err != nil {
		return nil, errors.New("HONGSHU_DATABASE_URL has invalid query parameters")
	}
	tlsMode := "preferred"
	if isLoopbackHost(driver.Addr) {
		tlsMode = "false"
	}
	for key, list := range values {
		if len(list) != 1 {
			return nil, fmt.Errorf("HONGSHU_DATABASE_URL parameter %q is repeated", key)
		}
		value := list[0]
		switch key {
		case "tls":
			tlsMode = strings.ToLower(value)
		case "timeout":
			driver.Timeout, err = parseDSNDuration(key, value)
		case "readTimeout":
			driver.ReadTimeout, err = parseDSNDuration(key, value)
		case "writeTimeout":
			driver.WriteTimeout, err = parseDSNDuration(key, value)
		case "maxAllowedPacket":
			n, e := strconv.Atoi(value)
			if e != nil || n < 0 || n > 1<<30 {
				return nil, errors.New("HONGSHU_DATABASE_URL maxAllowedPacket is invalid")
			}
			driver.MaxAllowedPacket = n
		case "interpolateParams":
			driver.InterpolateParams, err = parseBoolParam(key, value)
		case "charset":
			if value != "utf8mb4" {
				return nil, errors.New("HONGSHU_DATABASE_URL charset must be utf8mb4")
			}
		case "collation":
			if value != "utf8mb4_bin" {
				return nil, errors.New("HONGSHU_DATABASE_URL collation must be utf8mb4_bin")
			}
			driver.Collation = value
		case "parseTime":
			b, e := parseBoolParam(key, value)
			if e != nil || b {
				return nil, errors.New("HONGSHU_DATABASE_URL does not support parseTime=true")
			}
		case "multiStatements":
			b, e := parseBoolParam(key, value)
			if e != nil || b {
				return nil, errors.New("HONGSHU_DATABASE_URL does not allow multiStatements")
			}
		case "allowAllFiles", "allowCleartextPasswords", "allowFallbackToPlaintext", "allowOldPasswords":
			b, e := parseBoolParam(key, value)
			if e != nil || b {
				return nil, fmt.Errorf("HONGSHU_DATABASE_URL does not allow %s=true", key)
			}
		default:
			return nil, fmt.Errorf("HONGSHU_DATABASE_URL has unsupported parameter %q", key)
		}
		if err != nil {
			return nil, err
		}
	}
	if err := applyDatabaseTLS(driver, tlsMode, host); err != nil {
		return nil, err
	}
	return driver, nil
}

func parseDatabaseDSN(raw string) (*mysql.Config, error) {
	driver, err := mysql.ParseDSN(raw)
	if err != nil {
		return nil, errors.New("MYSQL_DSN is invalid")
	}
	if driver.DBName == "" {
		return nil, errors.New("MYSQL_DSN must include a database name")
	}
	return driver, nil
}

func sanitizeDatabaseConfig(driver *mysql.Config, fromURL bool) error {
	if driver.MultiStatements {
		return errors.New("multiple SQL statements are not allowed")
	}
	if driver.AllowAllFiles || driver.AllowCleartextPasswords || driver.AllowOldPasswords {
		return errors.New("unsafe MySQL DSN option is not allowed")
	}
	if !fromURL && driver.AllowFallbackToPlaintext {
		return errors.New("AllowFallbackToPlaintext is not allowed in MYSQL_DSN; use an explicit mysql:// TLS mode")
	}
	if driver.DBName == "" {
		return errors.New("database name is required")
	}
	if strings.Contains(driver.DBName, " ") {
		return errors.New("database name must not contain spaces")
	}
	driver.MultiStatements = false
	driver.ParseTime = false
	driver.Loc = time.UTC
	driver.AllowNativePasswords = true
	driver.CheckConnLiveness = true
	// Interpolation removes prepare/execute/close round trips. The hub only binds strings,
	// integers and booleans, so the driver's escaping rules are sufficient; multiStatements
	// stays disabled.
	driver.InterpolateParams = true
	if driver.Collation == "" {
		driver.Collation = "utf8mb4_bin"
	} else if driver.Collation != "utf8mb4_bin" {
		return fmt.Errorf("database collation must be utf8mb4_bin, got %q", driver.Collation)
	}
	if driver.Timeout == 0 {
		driver.Timeout = 10 * time.Second
	}
	if driver.ReadTimeout == 0 {
		driver.ReadTimeout = 60 * time.Second
	}
	if driver.WriteTimeout == 0 {
		driver.WriteTimeout = 60 * time.Second
	}
	if !fromURL && driver.TLS == nil && os.Getenv("HONGSHU_DB_TLS_CA") != "" {
		return errors.New("HONGSHU_DB_TLS_CA requires a TLS-enabled database connection")
	}
	return nil
}

func applyDatabaseTLS(driver *mysql.Config, mode, host string) error {
	caPath := strings.TrimSpace(os.Getenv("HONGSHU_DB_TLS_CA"))
	serverName := strings.TrimSpace(os.Getenv("HONGSHU_DB_TLS_SERVER_NAME"))
	if serverName == "" {
		serverName = host
	}
	switch strings.ToLower(mode) {
	case "false", "disable", "disabled":
		if caPath != "" {
			return errors.New("HONGSHU_DB_TLS_CA requires tls=true")
		}
		driver.TLS = nil
		driver.AllowFallbackToPlaintext = false
	case "true", "verify", "verify-full":
		tlsConfig := &tls.Config{ServerName: serverName, MinVersion: tls.VersionTLS12}
		if caPath != "" {
			roots, err := loadCertPool(caPath)
			if err != nil {
				return err
			}
			tlsConfig.RootCAs = roots
		}
		driver.TLS = tlsConfig
	case "skip-verify":
		if caPath != "" {
			return errors.New("HONGSHU_DB_TLS_CA requires tls=true")
		}
		driver.TLS = &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS12} //nolint:gosec // explicit opt-out
	case "preferred":
		if caPath != "" {
			return errors.New("HONGSHU_DB_TLS_CA requires tls=true")
		}
		// Matches MySQL's PREFERRED mode: opportunistic encryption, no certificate
		// authentication. It is documented as weaker than tls=true.
		driver.TLS = &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS12} //nolint:gosec // explicit opt-out
		driver.AllowFallbackToPlaintext = true
	default:
		return errors.New("database tls must be true, false, skip-verify or preferred")
	}
	return nil
}

func loadCertPool(path string) (*x509.CertPool, error) {
	pem, err := os.ReadFile(path)
	if err != nil {
		return nil, errors.New("cannot read HONGSHU_DB_TLS_CA")
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		return nil, errors.New("HONGSHU_DB_TLS_CA contains no certificates")
	}
	return pool, nil
}

func parseDSNDuration(key, value string) (time.Duration, error) {
	d, err := time.ParseDuration(value)
	if err != nil || d < 0 || d > 10*time.Minute {
		return 0, fmt.Errorf("HONGSHU_DATABASE_URL parameter %s is invalid", key)
	}
	return d, nil
}

func parseBoolParam(key, value string) (bool, error) {
	switch strings.ToLower(value) {
	case "1", "true":
		return true, nil
	case "0", "false":
		return false, nil
	default:
		return false, fmt.Errorf("HONGSHU_DATABASE_URL parameter %s must be true or false", key)
	}
}

func redactedDatabaseAddress(driver *mysql.Config) string {
	user := driver.User
	if user == "" {
		user = "(anonymous)"
	}
	return fmt.Sprintf("%s@%s/%s", user, driver.Addr, driver.DBName)
}

func isLoopbackHost(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	return ip != nil && ip.IsLoopback()
}

func openDatabase(cfg databaseConfig) (*sql.DB, error) {
	connector, err := mysql.NewConnector(cfg.Driver)
	if err != nil {
		return nil, errors.New("invalid database configuration")
	}
	db := sql.OpenDB(connector)
	db.SetMaxOpenConns(cfg.MaxOpen)
	db.SetMaxIdleConns(cfg.MaxIdle)
	db.SetConnMaxLifetime(cfg.ConnMaxLifetime)
	db.SetConnMaxIdleTime(cfg.ConnMaxIdleTime)
	return db, nil
}

func waitForDatabase(ctx context.Context, db *sql.DB, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	backoff := 500 * time.Millisecond
	for {
		pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		err := db.PingContext(pingCtx)
		cancel()
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if permanentDatabaseError(err) || time.Now().After(deadline) {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}
		if backoff < 5*time.Second {
			backoff *= 2
			if backoff > 5*time.Second {
				backoff = 5 * time.Second
			}
		}
	}
}

func permanentDatabaseError(err error) bool {
	var mysqlErr *mysql.MySQLError
	if !errors.As(err, &mysqlErr) {
		return false
	}
	switch mysqlErr.Number {
	case 1044, 1045, 1049, 1130, 1251, 2026:
		return true
	default:
		return false
	}
}

// databaseErrorCategory keeps credentials and SQL out of startup logs while still telling
// an operator whether to check DNS, credentials, grants, TLS or the server itself.
func databaseErrorCategory(err error) string {
	if err == nil {
		return "ok"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	var mysqlErr *mysql.MySQLError
	if errors.As(err, &mysqlErr) {
		switch mysqlErr.Number {
		case 1044:
			return "database permission denied"
		case 1045:
			return "authentication failed"
		case 1049:
			return "unknown database"
		case 1130:
			return "host not allowed"
		case 1142:
			return "schema permission denied"
		case 1146:
			return "schema not initialized"
		case 1251:
			return "unsupported authentication plugin"
		case 2002, 2003, 2005:
			return "connection failed"
		case 2026:
			return "TLS negotiation failed"
		default:
			return fmt.Sprintf("MySQL error %d", mysqlErr.Number)
		}
	}
	message := strings.ToLower(err.Error())
	switch {
	case strings.Contains(message, "tls"), strings.Contains(message, "certificate"):
		return "TLS negotiation failed"
	case strings.Contains(message, "timeout"), strings.Contains(message, "deadline"):
		return "timeout"
	case strings.Contains(message, "refused"):
		return "connection refused"
	default:
		return "database unavailable"
	}
}

func checkDatabaseCompatibility(ctx context.Context, db *sql.DB) error {
	var version string
	if err := db.QueryRowContext(ctx, "SELECT VERSION()").Scan(&version); err != nil {
		return fmt.Errorf("database version unavailable: %s", databaseErrorCategory(err))
	}
	if strings.Contains(strings.ToLower(version), "mariadb") {
		return errors.New("MariaDB is not supported; use MySQL 8.0 or newer")
	}
	major, _ := mysqlVersion(version)
	if major < 8 {
		return fmt.Errorf("MySQL 8.0 or newer is required (found %s)", version)
	}

	var charset, collation string
	if err := db.QueryRowContext(ctx, "SELECT @@character_set_database, @@collation_database").Scan(&charset, &collation); err != nil {
		return fmt.Errorf("database charset check failed: %s", databaseErrorCategory(err))
	}
	if charset != "utf8mb4" || collation != "utf8mb4_bin" {
		return fmt.Errorf("database must use utf8mb4/utf8mb4_bin (found %s/%s); recreate it with CREATE DATABASE ... CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", charset, collation)
	}
	if err := db.QueryRowContext(ctx, "SELECT @@character_set_connection, @@collation_connection").Scan(&charset, &collation); err != nil {
		return fmt.Errorf("connection charset check failed: %s", databaseErrorCategory(err))
	}
	if charset != "utf8mb4" || collation != "utf8mb4_bin" {
		return fmt.Errorf("connection must use utf8mb4/utf8mb4_bin (found %s/%s)", charset, collation)
	}
	return nil
}

func checkTableCollations(ctx context.Context, db *sql.DB) error {
	rows, err := db.QueryContext(ctx, "SELECT table_name,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_collation IS NOT NULL AND table_collation<>'utf8mb4_bin' ORDER BY table_name")
	if err != nil {
		return fmt.Errorf("table collation check failed: %s", databaseErrorCategory(err))
	}
	defer rows.Close()
	var bad []string
	for rows.Next() {
		var table, collation string
		if err := rows.Scan(&table, &collation); err != nil {
			return err
		}
		bad = append(bad, table+"="+collation)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if len(bad) > 0 {
		return fmt.Errorf("tables must use utf8mb4_bin; found %s", strings.Join(bad, ", "))
	}
	return nil
}

func mysqlVersion(version string) (int, int) {
	parts := strings.SplitN(version, ".", 3)
	if len(parts) < 1 {
		return 0, 0
	}
	major, _ := strconv.Atoi(parts[0])
	minor := 0
	if len(parts) > 1 {
		minor, _ = strconv.Atoi(parts[1])
	}
	return major, minor
}

func intEnv(name string, fallback, min, max int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < min || value > max {
		return 0, fmt.Errorf("%s must be an integer between %d and %d", name, min, max)
	}
	return value, nil
}

func durationEnv(name string, fallback, min, max time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value < min || value > max {
		return 0, fmt.Errorf("%s must be a duration between %s and %s", name, min, max)
	}
	return value, nil
}
