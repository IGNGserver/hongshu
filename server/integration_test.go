package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/go-sql-driver/mysql"
	"github.com/gorilla/websocket"
)

func testDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := os.Getenv("TEST_MYSQL_DSN")
	if dsn == "" {
		t.Skip("TEST_MYSQL_DSN required for real MySQL tests")
	}
	cfg, e := mysql.ParseDSN(dsn)
	if e != nil {
		t.Fatal(e)
	}
	cfg.DBName = ""
	root, e := sql.Open("mysql", cfg.FormatDSN())
	if e != nil {
		t.Fatal(e)
	}
	name := "hongshu_it_" + random(8)
	if _, e = root.Exec("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"); e != nil {
		t.Fatal(e)
	}
	cfg.DBName = name
	db, e := sql.Open("mysql", cfg.FormatDSN())
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close(); _, _ = root.Exec("DROP DATABASE " + name); root.Close() })
	if e = migrate(context.Background(), db, "../db/migrations"); e != nil {
		t.Fatal(e)
	}
	return db
}

type apiTest struct {
	t      *testing.T
	a      *app
	cookie *http.Cookie
}

func (c *apiTest) call(method, path, token string, body any, status int) map[string]any {
	c.t.Helper()
	var b bytes.Buffer
	if body != nil {
		if e := json.NewEncoder(&b).Encode(body); e != nil {
			c.t.Fatal(e)
		}
	}
	r := httptest.NewRequest(method, path, &b)
	r.RemoteAddr = "127.0.0.1:1234"
	r.Header.Set("Origin", c.a.origin)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	} else if c.cookie != nil {
		r.AddCookie(c.cookie)
	}
	w := httptest.NewRecorder()
	c.a.routes("../web").ServeHTTP(w, r)
	if w.Code != status {
		c.t.Fatalf("%s %s: got %d %s expected %d", method, path, w.Code, w.Body.String(), status)
	}
	if cookies := w.Result().Cookies(); len(cookies) > 0 {
		c.cookie = cookies[0]
	}
	var out map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return out
}
func TestIntegrationWorkflow(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", strings.Repeat("s", 32), "", "")
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/bootstrap", "", map[string]any{"secret": a.bootstrap, "name": "Admin browser"}, 201)
	c.call("POST", "/api/bootstrap", "", map[string]any{"secret": a.bootstrap, "name": "Another"}, 409)
	me := c.call("GET", "/api/me", "", nil, 200)
	if me["epoch"] != float64(1) {
		t.Fatal("fresh database epoch")
	}
	admin := me["device"].(map[string]any)["id"].(string)
	code := c.call("POST", "/api/pairings", "", map[string]any{}, 201)["code"].(string)
	paired := c.call("POST", "/api/pair", "", map[string]any{"code": code, "name": "Phone", "kind": "android"}, 201)
	token := paired["token"].(string)
	id := paired["device"].(map[string]any)["id"].(string)
	c.call("POST", "/api/pair", "", map[string]any{"code": code, "name": "Replay", "kind": "android"}, 403)
	message := Incoming{Receiver: "+8613800000000", Sender: "10086", Body: "synthetic integration message <script>", Timestamp: 1700000000000, SubscriptionID: 1}
	batch := map[string]any{"messages": []Incoming{message}}
	c.call("POST", "/api/messages", token, batch, 403)
	c.call("PATCH", "/api/devices/"+id, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": message.Receiver, "label": "SIM 1", "subscription_id": 1}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613900000000", "label": "SIM 2", "subscription_id": 2}, 200)
	first := c.call("POST", "/api/messages", token, batch, 200)
	if first["acks"].([]any)[0].(map[string]any)["duplicate"] != false {
		t.Fatal("first message duplicate")
	}
	retry := c.call("POST", "/api/messages", token, batch, 200)
	if retry["acks"].([]any)[0].(map[string]any)["duplicate"] != true {
		t.Fatal("retry was not idempotent")
	}
	// An unconfirmed receiver must not discard the confirmed item in the same batch.
	second := message
	second.Timestamp++
	bad := message
	bad.Receiver = "+1234567890"
	partial := c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{second, bad}}, 200)
	acks := partial["acks"].([]any)
	if acks[0].(map[string]any)["duplicate"] != false || acks[1].(map[string]any)["error"] != "sim_not_confirmed" {
		t.Fatal("unconfirmed SIM discarded the rest of the batch")
	}
	syncPage := c.call("GET", "/api/sync?after=0&limit=2", token, nil, 200)
	if syncPage["cursor"] != float64(2) || syncPage["epoch"] != float64(1) || len(syncPage["messages"].([]any)) != 2 {
		t.Fatal("accepted item was not committed")
	}
	if _, e := db.Exec("UPDATE sync_clock SET seq=1,epoch=2 WHERE id=1"); e != nil {
		t.Fatal(e)
	}
	rewound := c.call("GET", "/api/sync?after=2&limit=1", token, nil, 409)
	if rewound["error"] != "epoch_changed" {
		t.Fatal("cursor past a restored clock was treated as caught up")
	}
	if _, e := db.Exec("UPDATE sync_clock SET seq=2,epoch=1 WHERE id=1"); e != nil {
		t.Fatal(e)
	}
	c.call("PUT", "/api/contacts", token, map[string]string{"phone": "10086", "name": "Carrier"}, 200)
	conversations := c.call("GET", "/api/conversations?q=Carrier", token, nil, 200)
	if len(conversations["conversations"].([]any)) != 1 {
		t.Fatal("contact search failed")
	}
	c.call("GET", "/api/conversations?q=%25%27%20OR%201%3D1", token, nil, 200)
	c.call("GET", "/api/messages?sender=10086&sim=%2B8613800000000", token, nil, 200)
	c.call("GET", "/api/sync?after=-1", token, nil, 400)
	c.call("GET", "/api/sync?limit=0", token, nil, 400)
	c.call("POST", "/api/pairings", token, map[string]any{}, 403)
	c.call("DELETE", "/api/devices/"+admin, "", nil, 409)
	// Concurrent writes use the clock lock; no cursor can skip a committed row.
	var wg sync.WaitGroup
	failures := make(chan string, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			m := message
			m.Timestamp += int64(i + 100)
			body, _ := json.Marshal(map[string]any{"messages": []Incoming{m}})
			r := httptest.NewRequest("POST", "/api/messages", bytes.NewReader(body))
			r.Header.Set("Authorization", "Bearer "+token)
			w := httptest.NewRecorder()
			a.routes("../web").ServeHTTP(w, r)
			if w.Code != 200 {
				failures <- w.Body.String()
			}
		}(i)
	}
	wg.Wait()
	close(failures)
	for f := range failures {
		t.Error(f)
	}
	all := c.call("GET", "/api/sync?after=0&limit=200", token, nil, 200)
	if len(all["messages"].([]any)) != 14 || all["cursor"] != float64(14) {
		t.Fatal("concurrent sequence gaps or lost messages")
	}
	// Durable notification queue exists in the same commit, excludes source.
	if _, e := db.Exec("INSERT INTO push_subscriptions VALUES (?,?,?,?)", admin, "https://example.com/push", "dummy", "dummy"); e != nil {
		t.Fatal(e)
	}
	if _, e := db.Exec("INSERT INTO push_subscriptions VALUES (?,?,?,?)", id, "https://example.com/source", "dummy", "dummy"); e != nil {
		t.Fatal(e)
	}
	message.Timestamp += 10000
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{message}}, 200)
	var count int
	if e := db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&count); e != nil || count != 1 {
		t.Fatal("push not durable or notified source")
	}
	message.Timestamp -= 3600000
	message.Historical = true
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{message}}, 200)
	if e := db.QueryRow("SELECT COUNT(*) FROM push_jobs").Scan(&count); e != nil || count != 1 {
		t.Fatal("historical import created notification storm")
	}
	latest := c.call("GET", "/api/messages?sender=10086&limit=1", token, nil, 200)
	if latest["messages"].([]any)[0].(map[string]any)["id"] != float64(15) {
		t.Fatal("history import displaced latest chronological message")
	}
	latestConversation := c.call("GET", "/api/conversations?limit=1", token, nil, 200)
	if latestConversation["conversations"].([]any)[0].(map[string]any)["id"] != float64(15) {
		t.Fatal("conversation sorted by ingestion instead of message time")
	}
	otherSIM := message
	otherSIM.Receiver = "+8613900000000"
	otherSIM.Historical = false
	otherSIM.Timestamp += 20000
	c.call("POST", "/api/messages", token, map[string]any{"messages": []Incoming{otherSIM}}, 200)
	split := c.call("GET", "/api/conversations?sender=10086", token, nil, 200)
	if len(split["conversations"].([]any)) != 2 {
		t.Fatal("same sender on two SIMs collapsed into one conversation")
	}
	c.call("GET", "/api/messages?sender=10086&before=14&limit=1", token, nil, 200)
	c.call("DELETE", "/api/devices/"+id, "", nil, 200)
	c.call("GET", "/api/me", token, nil, 401)
	retained := c.call("GET", "/api/sync?after=0", "", nil, 200)
	if len(retained["messages"].([]any)) != 17 {
		t.Fatal("revocation erased SMS history")
	}
	if e := migrate(context.Background(), db, "../db/migrations"); e != nil {
		t.Fatal("migration not repeatable", e)
	}
}
func TestIntegrationWebSocketRevocation(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", strings.Repeat("s", 32), "", "")
	tx, _ := db.Begin()
	d, token, e := insertDevice(context.Background(), tx, "test", "android", false)
	if e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	srv := httptest.NewServer(a.routes("../web"))
	defer srv.Close()
	h := http.Header{"Authorization": []string{"Bearer " + token}}
	conn, _, e := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/api/ws", h)
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	_, _ = db.Exec("UPDATE devices SET revoked=TRUE WHERE id=?", d.ID)
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, _, e = conn.ReadMessage(); e == nil {
		t.Fatal("revoked WS remained usable")
	}
	h.Set("Origin", "https://evil.example")
	_, res, e := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/api/ws", h)
	if e == nil || res.StatusCode != 403 {
		t.Fatal("WS allowed wrong origin")
	}
}
func TestIntegrationDirtyMigration(t *testing.T) {
	db := testDB(t)
	if _, e := db.Exec("UPDATE schema_migrations SET dirty=TRUE"); e != nil {
		t.Fatal(e)
	}
	if migrate(context.Background(), db, "../db/migrations") == nil {
		t.Fatal("dirty schema allowed")
	}
	if _, e := db.Exec("UPDATE schema_migrations SET dirty=FALSE,checksum=?", fmt.Sprintf("%064d", 0)); e != nil {
		t.Fatal(e)
	}
	if migrate(context.Background(), db, "../db/migrations") == nil {
		t.Fatal("changed migration allowed")
	}
}

func TestIntegrationLogoutRevokesIdentityAndProtectsRemainingAdmin(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", strings.Repeat("s", 32), "", "")
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/bootstrap", "", map[string]any{"secret": a.bootstrap, "name": "first admin"}, 201)
	original := c.cookie
	c.call("POST", "/api/logout", "", map[string]any{}, 409)
	code := c.call("POST", "/api/pairings", "", map[string]bool{"admin": true}, 201)["code"].(string)
	c.call("POST", "/api/pair", "", map[string]any{"code": code, "name": "wrong admin client", "kind": "android"}, 400)
	paired := c.call("POST", "/api/pair", "", map[string]any{"code": code, "name": "backup admin", "kind": "web"}, 201)
	if paired["device"].(map[string]any)["admin"] != true {
		t.Fatal("explicit admin pairing lost role")
	}
	token := c.cookie.Value
	c.call("POST", "/api/logout", "", map[string]any{}, 200)
	c.call("GET", "/api/me", token, nil, 401)
	c.cookie = original
	c.call("POST", "/api/logout", "", map[string]any{}, 409)
	c.call("GET", "/api/me", "", nil, 200)
}
