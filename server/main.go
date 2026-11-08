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
	if err := run(ctx, os.Args[1:]); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	if len(args) > 0 {
		switch args[0] {
		case "migrate":
			if len(args) != 1 {
				return fmt.Errorf("migrate takes no arguments")
			}
			return runMigrateCommand(ctx)
		default:
			return fmt.Errorf("unknown command; supported: migrate")
		}
	}

	password := os.Getenv("HONGSHU_PASSWORD")
	if len(password) == 0 || len(password) > 1024 {
		return fmt.Errorf("HONGSHU_PASSWORD must contain 1 to 1024 bytes")
	}
	cfg, err := loadDatabaseConfig()
	if err != nil {
		return err
	}
	for _, warning := range cfg.Warnings {
		log.Print(warning)
	}
	log.Printf("database target %s (%s); migrations=%s", cfg.Address, cfg.Source, cfg.MigrateMode)
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
	migrationsDir := env("MIGRATIONS_DIR", "../db/migrations")
	switch cfg.MigrateMode {
	case "auto", "check":
		if err = migrateWithOptions(ctx, db, migrationsDir, cfg.MigrateMode, cfg.MigrationLockWait); err != nil {
			return err
		}
	case "off":
		log.Print("database migrations disabled by HONGSHU_MIGRATE=off")
	}
	if err = checkTableCollations(ctx, db); err != nil {
		return err
	}
	a, err := newApp(db, env("PUBLIC_URL", "http://localhost:8080"), password, os.Getenv("VAPID_PUBLIC_KEY"), os.Getenv("VAPID_PRIVATE_KEY"))
	if err != nil {
		return err
	}
	if err = a.configureTrustedProxies(os.Getenv("TRUSTED_PROXY_CIDRS")); err != nil {
		return err
	}
	tracked := &trackedHTTPHandler{handler: a.routes(env("WEB_DIR", "../web"))}
	srv := &http.Server{Addr: env("LISTEN_ADDR", ":8080"), Handler: tracked, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 16 << 10}
	log.Print("Hongshu listening; sensitive request logging disabled")
	return runLifecycle(ctx, srv, srv.ListenAndServe, lifecycleHooks{
		start: func(workerCtx context.Context) error {
			if err := a.configureRedisAt(workerCtx, cfg.Driver.DBName, cfg.Driver.Addr); err != nil {
				log.Printf("Redis disabled: %s", err)
			}
			a.startStatePoller(workerCtx)
			a.startWorker(workerCtx, a.pushLoop)
			a.startWorker(workerCtx, a.analysisLoop)
			a.startWorker(workerCtx, a.analysisBackfillLoop)
			return nil
		},
		waitHTTP: tracked.wg.Wait,
		waitWork: a.workers.Wait,
		close:    a.closeResources,
	})
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
