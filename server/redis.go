package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

type redisCache struct {
	client  *redis.Client
	prefix  string
	timeout time.Duration
}

var redisLeaderScript = redis.NewScript(`
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('pexpire', KEYS[1], ARGV[2])
end
if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
  return 1
end
return 0
`)

// configureRedis is optional. A cache outage must never block SMS writes or startup; when
// the URL is absent or the initial ping fails, the hub simply runs with in-process state.
func (a *app) configureRedis(ctx context.Context, databaseName string) error {
	return a.configureRedisAt(ctx, databaseName, "")
}

func defaultRedisPrefix(databaseName, databaseAddress, deploymentID string) string {
	// An explicit deployment ID lets replicas share a lease even when their DB
	// addresses differ (for example behind different local proxies). Without one,
	// include the normalized target as well as the schema to isolate same-name DBs.
	material := strings.TrimSpace(deploymentID)
	if material == "" {
		material = strings.ToLower(strings.TrimSpace(databaseAddress)) + "/" + databaseName
	}
	sum := sha256.Sum256([]byte(material))
	return "hongshu:" + hex.EncodeToString(sum[:12]) + ":"
}

func (a *app) configureRedisAt(ctx context.Context, databaseName, databaseAddress string) error {
	raw := strings.TrimSpace(os.Getenv("HONGSHU_REDIS_URL"))
	if raw == "" {
		return nil
	}
	options, err := redis.ParseURL(raw)
	if err != nil {
		return errors.New("HONGSHU_REDIS_URL is invalid")
	}
	options.DialTimeout = 3 * time.Second
	options.ReadTimeout = time.Second
	options.WriteTimeout = time.Second
	options.PoolSize = 8
	options.MinIdleConns = 1
	options.MaxRetries = 1
	client := redis.NewClient(options)
	pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err = client.Ping(pingCtx).Err(); err != nil {
		_ = client.Close()
		return fmt.Errorf("Redis unavailable: %s", redisErrorCategory(err))
	}
	prefix := strings.TrimSpace(os.Getenv("HONGSHU_REDIS_PREFIX"))
	if prefix == "" {
		prefix = defaultRedisPrefix(databaseName, databaseAddress, os.Getenv("HONGSHU_DEPLOYMENT_ID"))
	} else if !strings.HasSuffix(prefix, ":") {
		prefix += ":"
	}
	if len(prefix) > 160 {
		_ = client.Close()
		return errors.New("HONGSHU_REDIS_PREFIX is too long")
	}
	a.redis = &redisCache{client: client, prefix: prefix, timeout: 250 * time.Millisecond}
	a.startWorker(ctx, a.redisPublisher)
	a.startWorker(ctx, a.redisSubscriber)
	return nil
}

func (a *app) redisPublisher(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-a.redisEvents:
			publishCtx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
			_ = a.redis.publish(publishCtx, "changed")
			cancel()
		}
	}
}

func (a *app) redisSubscriber(ctx context.Context) {
	_ = a.redis.subscribe(ctx, func(string) {
		a.remoteChange()
	})
}

func (r *redisCache) key(parts ...string) string {
	return r.prefix + strings.Join(parts, ":")
}

func (r *redisCache) getInt(ctx context.Context, key string) (int64, bool) {
	callCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	value, err := r.client.Get(callCtx, key).Result()
	if err != nil {
		return 0, false
	}
	n, err := strconv.ParseInt(value, 10, 64)
	if err != nil || n < 0 {
		return 0, false
	}
	return n, true
}

func (r *redisCache) setInt(ctx context.Context, key string, value int64, ttl time.Duration) {
	callCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	_ = r.client.Set(callCtx, key, strconv.FormatInt(value, 10), ttl).Err()
}

func (r *redisCache) publish(ctx context.Context, event string) error {
	return r.client.Publish(ctx, r.key("events"), event).Err()
}

func (r *redisCache) subscribe(ctx context.Context, handler func(string)) error {
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		pubsub := r.client.Subscribe(ctx, r.key("events"))
		channel := pubsub.Channel()
		closed := false
		for !closed {
			select {
			case <-ctx.Done():
				_ = pubsub.Close()
				return ctx.Err()
			case message, ok := <-channel:
				if !ok {
					closed = true
					break
				}
				handler(message.Payload)
			}
		}
		_ = pubsub.Close()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
}

// leader returns true when this process owns the analysis/backfill lease. On Redis errors
// it fails open: duplicate work is bounded by database row locks, while refusing to run
// would silently stop AI classification.
func (a *app) analysisLeader(ctx context.Context) bool {
	if a.redis == nil {
		return true
	}
	callCtx, cancel := context.WithTimeout(ctx, 200*time.Millisecond)
	defer cancel()
	key := a.redis.key("leader:analysis")
	result, err := redisLeaderScript.Run(callCtx, a.redis.client, []string{key}, a.instanceID, int64(15*time.Second/time.Millisecond)).Int64()
	if err != nil {
		return true
	}
	return result == 1
}

func redisErrorCategory(err error) string {
	if err == nil {
		return "ok"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	message := strings.ToLower(err.Error())
	switch {
	case strings.Contains(message, "auth"):
		return "authentication failed"
	case strings.Contains(message, "refused"):
		return "connection refused"
	case strings.Contains(message, "timeout"):
		return "timeout"
	default:
		return "unavailable"
	}
}
