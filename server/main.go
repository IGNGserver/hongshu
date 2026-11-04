package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
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

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "migrate":
			if err := runMigrateCommand(ctx); err != nil {
				log.Fatal(err)
			}
			return
		default:
			log.Fatal("unknown command; supported: migrate")
		}
	}

	password := os.Getenv("HONGSHU_PASSWORD")
	if len(password) == 0 || len(password) > 1024 {
		log.Fatal("HONGSHU_PASSWORD must contain 1 to 1024 bytes")
	}
	cfg, err := loadDatabaseConfig()
	if err != nil {
		log.Fatal(err)
	}
	for _, warning := range cfg.Warnings {
		log.Print(warning)
	}
	log.Printf("database target %s (%s); migrations=%s", cfg.Address, cfg.Source, cfg.MigrateMode)
	db, err := openDatabase(cfg)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	if err = waitForDatabase(ctx, db, cfg.StartupTimeout); err != nil {
		log.Fatalf("database unavailable: %s", databaseErrorCategory(err))
	}
	if err = checkDatabaseCompatibility(ctx, db); err != nil {
		log.Fatal(err)
	}
	if err = checkTableCollations(ctx, db); err != nil {
		log.Fatal(err)
	}
	migrationsDir := env("MIGRATIONS_DIR", "../db/migrations")
	switch cfg.MigrateMode {
	case "auto", "check":
		if err = migrateWithOptions(ctx, db, migrationsDir, cfg.MigrateMode, cfg.MigrationLockWait); err != nil {
			log.Fatal(err)
		}
	case "off":
		log.Print("database migrations disabled by HONGSHU_MIGRATE=off")
	}
	if err = checkTableCollations(ctx, db); err != nil {
		log.Fatal(err)
	}
	a, err := newApp(db, env("PUBLIC_URL", "http://localhost:8080"), password, os.Getenv("VAPID_PUBLIC_KEY"), os.Getenv("VAPID_PRIVATE_KEY"))
	if err != nil {
		log.Fatal(err)
	}
	if err = a.configureTrustedProxies(os.Getenv("TRUSTED_PROXY_CIDRS")); err != nil {
		log.Fatal(err)
	}
	if err = a.configureRedis(ctx, cfg.Driver.DBName); err != nil {
		log.Printf("Redis disabled: %s", err)
	}
	a.startStatePoller(ctx)
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

// runMigrateCommand applies schema changes with the operator-supplied account and exits.
// A shared MySQL can therefore use a DDL-capable migration account once, while the running
// hub uses HONGSHU_MIGRATE=check and a DML-only account.
func runMigrateCommand(ctx context.Context) error {
	cfg, err := loadDatabaseConfig()
	if err != nil {
		return err
	}
	for _, warning := range cfg.Warnings {
		log.Print(warning)
	}
	db, err := openDatabase(cfg)
	if err != nil {
		return err
	}
	defer db.Close()
	if err = waitForDatabase(ctx, db, cfg.StartupTimeout); err != nil {
		return fmt.Errorf("database unavailable: %s", databaseErrorCategory(err))
	}
	if err = checkDatabaseCompatibility(ctx, db); err != nil {
		return err
	}
	if err = checkTableCollations(ctx, db); err != nil {
		return err
	}
	if err = migrateWithOptions(ctx, db, env("MIGRATIONS_DIR", "../db/migrations"), "auto", cfg.MigrationLockWait); err != nil {
		return err
	}
	if err = checkTableCollations(ctx, db); err != nil {
		return err
	}
	log.Printf("database migrations applied to %s", cfg.Address)
	return nil
}
