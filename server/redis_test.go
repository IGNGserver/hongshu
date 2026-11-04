package main

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
)

func TestRedisCacheAndLeadership(t *testing.T) {
	server := miniredis.RunT(t)
	t.Setenv("HONGSHU_REDIS_URL", "redis://"+server.Addr()+"/0")
	t.Setenv("HONGSHU_REDIS_PREFIX", "hongshu-test:")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	newTestCache := func(instance string) *app {
		return &app{
			instanceID:        instance,
			subscribers:       make(map[chan struct{}]struct{}),
			stateRefresh:      make(chan struct{}, 1),
			pushWake:          make(chan struct{}, 1),
			analysisWake:      make(chan struct{}, 1),
			redisEvents:       make(chan struct{}, 1),
			statePollInterval: time.Second,
		}
	}
	a := newTestCache("one")
	if err := a.configureRedis(ctx, "hongshu"); err != nil {
		t.Fatal(err)
	}
	defer a.redis.client.Close()
	a.redis.setInt(ctx, a.redis.key("count"), 42, time.Minute)
	if count, ok := a.redis.getInt(ctx, a.redis.key("count")); !ok || count != 42 {
		t.Fatalf("cache get = %d %v", count, ok)
	}
	if !a.analysisLeader(ctx) {
		t.Fatal("first instance did not acquire the analysis lease")
	}

	b := newTestCache("two")
	if err := b.configureRedis(ctx, "hongshu"); err != nil {
		t.Fatal(err)
	}
	defer b.redis.client.Close()
	if b.analysisLeader(ctx) {
		t.Fatal("second instance stole a live analysis lease")
	}
	server.FastForward(16 * time.Second)
	if !b.analysisLeader(ctx) {
		t.Fatal("expired analysis lease was not acquired")
	}

	// Pub/sub wakes local state and worker channels, which is how another hub process
	// reaches this process's WebSocket clients without polling the database.
	select {
	case <-a.stateRefresh:
	default:
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		if err := a.redis.publish(ctx, "changed"); err != nil {
			t.Fatal(err)
		}
		select {
		case <-a.stateRefresh:
			return
		case <-time.After(100 * time.Millisecond):
		}
		if time.Now().After(deadline) {
			t.Fatal("Redis change event did not reach the local state refresh")
		}
	}
}

func TestRedisInvalidURLDoesNotLeak(t *testing.T) {
	t.Setenv("HONGSHU_REDIS_URL", "postgres://user:secret@example/db")
	a := &app{}
	err := a.configureRedis(context.Background(), "hongshu")
	if err == nil {
		t.Fatal("invalid Redis URL accepted")
	}
	if a.redis != nil {
		t.Fatal("invalid Redis URL left a client configured")
	}
	if strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "postgres://") {
		t.Fatal("Redis error leaked the URL")
	}
}
