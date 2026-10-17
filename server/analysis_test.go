package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestOTP(t *testing.T) {
	for _, v := range []struct {
		body, want string
		duration   int64
	}{
		{"【测试】验证码：482913，5分钟内有效。", "482913", 300000},
		{"Your verification code is A2B3C4. Valid for 10 minutes.", "A2B3C4", 600000},
		{"482913 is your verification code.", "482913", 0},
		{"订单 20231018 已支付，验证码 6604，客服 95588", "6604", 0},
		{"验证码 1234，验证码 5678", "", 0},
		{"您尾号 1234 的卡消费 5600 元", "", 0},
		{"验证码已发送，请联系 95588", "", 0},
		{"验证码：2026-10-16", "", 0},
		{"验证码：1234.50元", "", 0},
		{"验证码为 12345678901", "", 0},
		{"Your discount code is SAVE2026", "", 0},
		{"验证码 1234已失效，5分钟后可再次申请", "1234", 0},
		{"Your code is 1234. Wait 5 minutes before requesting a new valid code.", "1234", 0},
		{"验证码 1234，有效期：30秒", "1234", 30000},
		{"Your order code is 123456", "", 0},
		{"验证码 1234 或 5678", "", 0},
		{"Your code is 1234 or 5678", "", 0},
	} {
		t.Run(v.body, func(t *testing.T) {
			got, expires := extractOTP(v.body, 1000)
			if got != v.want {
				t.Fatalf("got %q want %q", got, v.want)
			}
			want := int64(0)
			if v.duration > 0 {
				want = 1000 + v.duration
			}
			if expires != want {
				t.Fatalf("expiry %d want %d", expires, want)
			}
		})
	}
}

func TestAnalysisWebSocketChangesWithoutNewSMS(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "synthetic", "", "")
	a.wsCheckInterval = 20 * time.Millisecond
	tx, e := db.Begin()
	if e != nil {
		t.Fatal(e)
	}
	_, token, e := insertDevice(context.Background(), tx, "Synthetic", "android", false)
	if e != nil {
		tx.Rollback()
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	srv := httptest.NewServer(a.routes("../web"))
	defer srv.Close()
	conn, _, e := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/api/ws", http.Header{"Authorization": []string{"Bearer " + token}})
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	if _, e = db.Exec("UPDATE ai_settings SET revision=revision+1 WHERE id=1"); e != nil {
		t.Fatal(e)
	}
	a.broadcastChange()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	var change map[string]any
	if e = conn.ReadJSON(&change); e != nil || change["type"] != "changed" || change["cursor"] != float64(0) || change["analysis_revision"] != float64(1) {
		t.Fatal("metadata-only event missing", e, change)
	}
}

func TestAnalysisUpgradeBackfillAndRollback(t *testing.T) {
	db := testDB(t)
	ctx := context.Background()
	rollback := func() {
		t.Helper()
		data, e := os.ReadFile("../db/migrations/006_message_analysis.down.sql")
		if e != nil {
			t.Fatal(e)
		}
		for _, statement := range strings.Split(string(data), ";") {
			if strings.TrimSpace(statement) != "" {
				if _, e = db.Exec(statement); e != nil {
					t.Fatal(e)
				}
			}
		}
		if _, e = db.Exec("DELETE FROM schema_migrations WHERE version='006_message_analysis.up.sql'"); e != nil {
			t.Fatal(e)
		}
	}
	rollback()
	if _, e := db.Exec("INSERT INTO devices (id,name,kind,token_hash,created_at) VALUES ('synthetic','Synthetic','android',?,0)", hash("synthetic")); e != nil {
		t.Fatal(e)
	}
	insert := func(id int) {
		t.Helper()
		if _, e := db.Exec("INSERT INTO messages VALUES (?,'synthetic',?,'+8613800000000','10086','验证码 482913',?,1,TRUE)", id, hash(strconv.Itoa(id)), id); e != nil {
			t.Fatal(e)
		}
	}
	for i := 1; i <= 205; i++ {
		insert(i)
	}
	if e := migrate(ctx, db, "../db/migrations"); e != nil {
		t.Fatal(e)
	}
	s, e := loadAI(ctx, db, false)
	if e != nil || s.Enabled {
		t.Fatal("upgrade enabled AI", e)
	}
	// An old writer adds a message after the migration snapshot.
	insert(206)
	a := &app{db: db}
	for batch := 1; batch <= 4; batch++ {
		if e = a.backfillAnalysis(ctx); e != nil {
			t.Fatal(e)
		}
		var count int
		if e = db.QueryRow("SELECT COUNT(*) FROM message_analysis").Scan(&count); e != nil {
			t.Fatal(e)
		}
		want := batch * 100
		if want > 205 {
			want = 205
		}
		if batch == 4 {
			want = 206
		}
		if count != want {
			t.Fatalf("batch %d got %d want %d", batch, count, want)
		}
		if batch == 1 {
			var oldest int
			if e = db.QueryRow("SELECT MIN(message_id) FROM message_analysis").Scan(&oldest); e != nil || oldest != 106 {
				t.Fatal("upgrade must backfill recent IDs first", e)
			}
		}
	}
	rollback()
	var count int
	if e = db.QueryRow("SELECT COUNT(*) FROM messages").Scan(&count); e != nil || count != 206 {
		t.Fatal("rollback changed raw SMS", e)
	}
	if e = migrate(ctx, db, "../db/migrations"); e != nil {
		t.Fatal("re-upgrade", e)
	}
}

func TestAISecretAndEndpoint(t *testing.T) {
	t.Setenv("HONGSHU_AI_SECRET", base64.StdEncoding.EncodeToString(make([]byte, 32)))
	secret, e := sealAI("synthetic-secret")
	if e != nil || strings.Contains(secret, "synthetic") {
		t.Fatal("seal")
	}
	if got, e := openAI(secret); e != nil || got != "synthetic-secret" {
		t.Fatal("open")
	}
	t.Setenv("HONGSHU_AI_SECRET", base64.StdEncoding.EncodeToString([]byte(strings.Repeat("x", 32))))
	if _, e = openAI(secret); e == nil {
		t.Fatal("wrong deployment key accepted")
	}
	t.Setenv("HONGSHU_AI_ALLOWED_HOSTS", "")
	for _, v := range []string{"http://127.0.0.1/api", "https://u:p@example.com/api", "https://example.com/api?key=x", "file:///etc/passwd"} {
		if validateAIEndpoint(v) == nil {
			t.Fatal(v)
		}
	}
	if validateAIEndpoint("https://example.com/v1/chat/completions") != nil {
		t.Fatal("public HTTPS")
	}
	t.Setenv("HONGSHU_AI_ALLOWED_HOSTS", "localhost")
	if validateAIEndpoint("http://localhost:11434/v1/chat/completions") != nil {
		t.Fatal("explicit local host")
	}
}

func TestOTPBoundedTokenWindows(t *testing.T) {
	code, _ := extractOTP(strings.Repeat("code 482913. ", 5000), 1000)
	if code != "482913" {
		t.Fatal("long synthetic input")
	}
}

func TestClassifierContract(t *testing.T) {
	content := `{"category":"notification"}`
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Messages []struct {
				Content string `json:"content"`
			} `json:"messages"`
		}
		if json.NewDecoder(r.Body).Decode(&in) != nil {
			t.Error("payload")
		}
		if len(in.Messages) != 2 || strings.Contains(in.Messages[1].Content, "13800138000") {
			t.Error("redaction")
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": content}}}})
	}))
	defer server.Close()
	u, _ := url.Parse(server.URL)
	t.Setenv("HONGSHU_AI_ALLOWED_HOSTS", u.Hostname())
	s := AISettings{Endpoint: server.URL, Model: "synthetic", Timeout: 2, Redact: true}
	got, code, _ := classifySMS(context.Background(), s, "联系电话 13800138000")
	if got != "notification" || code != "" {
		t.Fatal(got, code)
	}
	for _, bad := range []string{`{"category":"spam","action":"delete"}`, `{"category":"invented"}`, "```json\n{}\n```", `{"category":"unknown"}`} {
		content = bad
		if got, code, _ = classifySMS(context.Background(), s, "synthetic"); got != "" || code == "" {
			t.Fatal("accepted untrusted output")
		}
	}
	t.Setenv("HONGSHU_AI_ALLOWED_HOSTS", "")
	_, code, _ = classifySMS(context.Background(), AISettings{Endpoint: strings.Replace(server.URL, "http:", "https:", 1), Model: "synthetic", Timeout: 2}, "synthetic")
	if code == "" {
		t.Fatal("private destination accepted")
	}
}

func TestClassifierProtocolAdapters(t *testing.T) {
	protocols := []struct {
		name string
		body map[string]any
	}{
		{aiProtocolResponses, map[string]any{"output": []any{map[string]any{"type": "message", "content": []any{map[string]any{"type": "output_text", "text": `{"category":"notification"}`}}}}}},
		{aiProtocolAnthropic, map[string]any{"content": []any{map[string]any{"type": "text", "text": `{"category":"notification"}`}}}},
		{aiProtocolGemini, map[string]any{"candidates": []any{map[string]any{"content": map[string]any{"parts": []any{map[string]any{"text": `{"category":"notification"}`}}}}}}},
	}
	for _, protocol := range protocols {
		t.Run(protocol.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodPost || r.Header.Get("Content-Type") != "application/json" {
					t.Error("invalid request envelope")
				}
				if protocol.name == aiProtocolAnthropic && r.Header.Get("anthropic-version") == "" {
					t.Error("missing Anthropic version")
				}
				if protocol.name == aiProtocolGemini && r.Header.Get("x-goog-api-key") != "" {
					t.Error("unexpected empty Gemini key header")
				}
				_ = json.NewEncoder(w).Encode(protocol.body)
			}))
			defer server.Close()
			u, _ := url.Parse(server.URL)
			t.Setenv("HONGSHU_AI_ALLOWED_HOSTS", u.Hostname())
			got, code, pause := classifySMS(context.Background(), AISettings{Protocol: protocol.name, Endpoint: server.URL, Model: "synthetic", Timeout: 2}, "synthetic")
			if got != "notification" || code != "" || pause {
				t.Fatalf("got=%q code=%q pause=%v", got, code, pause)
			}
		})
	}
}

func TestAnalysisIntegration(t *testing.T) {
	db := testDB(t)
	a, _ := newApp(db, "http://localhost:8080", "1", "", "")
	c := apiTest{t: t, a: a}
	c.call("POST", "/api/login", "", map[string]any{"password": "1"}, 200)
	paired := c.call("POST", "/api/login", "", map[string]any{"password": "1", "kind": "android", "name": "Phone"}, 200)
	token := paired["token"].(string)
	id := paired["device"].(map[string]any)["id"].(string)
	// Keep a separate web administrator after the Android login.
	c.call("POST", "/api/login", "", map[string]any{"password": "1"}, 200)
	c.call("PATCH", "/api/devices/"+id, token, map[string]bool{"upload": true}, 200)
	c.call("PUT", "/api/sims", token, map[string]any{"phone": "+8613800000000", "label": "SIM", "subscription_id": 1}, 200)
	messages := []Incoming{
		{Receiver: "+8613800000000", Sender: "10086", Body: "验证码为 482913，5分钟内有效", Timestamp: 1700000000000, SubscriptionID: 1},
		{Receiver: "+8613800000000", Sender: "10086", Body: "synthetic maintenance notice", Timestamp: 1700000000001, SubscriptionID: 1},
	}
	c.call("POST", "/api/messages", token, map[string]any{"messages": messages}, 200)
	c.call("GET", "/api/messages?group=personal", token, nil, 400)
	otp := c.call("GET", "/api/messages?group=otp", token, nil, 200)["messages"].([]any)
	if len(otp) != 1 || otp[0].(map[string]any)["analysis"].(map[string]any)["otp"] != "482913" {
		t.Fatal("OTP group")
	}
	// Same sender has distinct messages in different groups.
	if len(c.call("GET", "/api/conversations?group=other", token, nil, 200)["conversations"].([]any)) != 1 {
		t.Fatal("other")
	}
	cfg := map[string]any{"enabled": true, "consent": true, "endpoint": "https://example.com/chat/completions", "model": "synthetic", "timeout_seconds": 2, "requests_per_minute": 60, "daily_limit": 10, "redact": true}
	c.call("PUT", "/api/analysis/settings", token, cfg, 403)
	c.call("PUT", "/api/analysis/settings", "", cfg, 200)
	j, e := a.claimAnalysis(context.Background())
	if e != nil || j == nil || j.ID != 2 {
		t.Fatal("claim", e)
	}
	c.call("PATCH", "/api/messages/2/category", token, map[string]string{"category": "spam"}, 200)
	if e = a.finishAnalysis(context.Background(), j, "notification", "", false); e != nil {
		t.Fatal(e)
	}
	ms := c.call("GET", "/api/messages?group=spam", token, nil, 200)["messages"].([]any)
	if len(ms) != 1 {
		t.Fatal("manual overwritten")
	}
	delta := c.call("GET", "/api/analysis?after=0&limit=1", token, nil, 200)
	if !delta["more"].(bool) {
		t.Fatal("metadata pagination")
	}
	counts := c.call("GET", "/api/analysis/counts?group=spam", token, nil, 200)["counts"].(map[string]any)
	if counts["all"] != float64(2) || counts["otp"] != float64(1) || counts["spam"] != float64(1) {
		t.Fatal(counts)
	}
	c.call("PATCH", "/api/messages/1/category", token, map[string]string{"category": "spam"}, 400)
	c.call("PATCH", "/api/messages/2/category", token, map[string]string{"category": "auto"}, 200)
	if _, e = db.Exec("UPDATE ai_settings SET next_call=0"); e != nil {
		t.Fatal(e)
	}
	j, e = a.claimAnalysis(context.Background())
	if e != nil || j == nil {
		t.Fatal("reclaim", e)
	}
	if e = a.finishAnalysis(context.Background(), j, "", "ai_http_429", true); e != nil {
		t.Fatal(e)
	}
	if j, e = a.claimAnalysis(context.Background()); e != nil || j != nil {
		t.Fatal("paused claim")
	}
	c.call("POST", "/api/analysis/retry", "", map[string]any{}, 200)
	if _, e = db.Exec("UPDATE ai_settings SET next_call=0"); e != nil {
		t.Fatal(e)
	}
	j, e = a.claimAnalysis(context.Background())
	if e != nil || j == nil {
		t.Fatal("retry", e)
	}
	// A restarted worker cannot claim while lease is still live.
	if other, e := a.claimAnalysis(context.Background()); e != nil || other != nil {
		t.Fatal("duplicate lease")
	}
	if _, e = db.Exec("UPDATE message_analysis SET leased_until=? WHERE message_id=2", time.Now().Add(-time.Minute).UnixMilli()); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("UPDATE ai_settings SET next_call=0"); e != nil {
		t.Fatal(e)
	}
	next, e := a.claimAnalysis(context.Background())
	if e != nil || next == nil || next.Token == j.Token {
		t.Fatal("lease recovery", e)
	}
	if e = a.finishAnalysis(context.Background(), j, "spam", "", false); e != nil {
		t.Fatal(e)
	}
	if e = a.finishAnalysis(context.Background(), next, "notification", "", false); e != nil {
		t.Fatal(e)
	}
	cfg["enabled"] = false
	c.call("PUT", "/api/analysis/settings", "", cfg, 200)
	if len(c.call("GET", "/api/messages?group=other", token, nil, 200)["messages"].([]any)) != 1 {
		t.Fatal("disabled mode")
	}
	if j, e = a.claimAnalysis(context.Background()); e != nil || j != nil {
		t.Fatal("disabled claim")
	}
	if _, e = db.Exec("DELETE FROM message_analysis"); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("UPDATE ai_settings SET backfill_id=0,backfill_before=0"); e != nil {
		t.Fatal(e)
	}
	if e = a.backfillAnalysis(context.Background()); e != nil {
		t.Fatal(e)
	}
	if len(c.call("GET", "/api/messages?group=otp", token, nil, 200)["messages"].([]any)) != 1 {
		t.Fatal("historical backfill")
	}
	// Re-enabling preserves results; incoming messages outrank historical work,
	// while the historical queue is newest first, independently of insertion ID.
	cfg["enabled"] = true
	cfg["daily_limit"] = 1
	c.call("PUT", "/api/analysis/settings", "", cfg, 200)
	c.call("PATCH", "/api/messages/2/category", token, map[string]string{"category": "notification"}, 200)
	more := []Incoming{
		{Receiver: "+8613800000000", Sender: "10010", Body: "older synthetic history", Timestamp: 1700000000100, SubscriptionID: 1, Historical: true},
		{Receiver: "+8613800000000", Sender: "10010", Body: "newer synthetic history", Timestamp: 1700000000200, SubscriptionID: 1, Historical: true},
		{Receiver: "+8613800000000", Sender: "10010", Body: "new incoming synthetic", Timestamp: 1700000000000, SubscriptionID: 1},
	}
	c.call("POST", "/api/messages", token, map[string]any{"messages": more}, 200)
	resetRate := func() {
		t.Helper()
		if _, e := db.Exec("UPDATE ai_settings SET next_call=0,usage_count=0,usage_day=?", time.Now().UTC().Unix()/86400); e != nil {
			t.Fatal(e)
		}
	}
	resetRate()
	j, e = a.claimAnalysis(context.Background())
	if e != nil || j == nil || j.ID != 5 {
		t.Fatal("new incoming priority", e)
	}
	if _, e = db.Exec("UPDATE ai_settings SET next_call=0"); e != nil {
		t.Fatal(e)
	}
	if other, e := a.claimAnalysis(context.Background()); e != nil || other != nil {
		t.Fatal("daily budget not enforced")
	}
	if e = a.finishAnalysis(context.Background(), j, "personal", "", false); e != nil {
		t.Fatal(e)
	}
	resetRate()
	j, e = a.claimAnalysis(context.Background())
	if e != nil || j == nil || j.ID != 4 {
		t.Fatal("newest history priority", e)
	}
	// Changing provider invalidates the old result, and lease recovery uses new settings.
	cfg["model"] = "replacement"
	c.call("PUT", "/api/analysis/settings", "", cfg, 200)
	if e = a.finishAnalysis(context.Background(), j, "spam", "", false); e != nil {
		t.Fatal(e)
	}
	var category string
	if e = db.QueryRow("SELECT category FROM message_analysis WHERE message_id=4").Scan(&category); e != nil || category != "" {
		t.Fatal("stale config result persisted")
	}
	if _, e = db.Exec("UPDATE message_analysis SET leased_until=0 WHERE message_id=4"); e != nil {
		t.Fatal(e)
	}
	resetRate()
	j, e = a.claimAnalysis(context.Background())
	if e != nil || j == nil || j.ID != 4 || j.Settings.Model != "replacement" {
		t.Fatal("new provider lease", e)
	}
	if e = a.finishAnalysis(context.Background(), j, "notification", "", false); e != nil {
		t.Fatal(e)
	}
	resetRate()
	j, e = a.claimAnalysis(context.Background())
	if e != nil || j == nil || j.ID != 3 {
		t.Fatal("remaining history", e)
	}
	if e = a.finishAnalysis(context.Background(), j, "promotion", "", false); e != nil {
		t.Fatal(e)
	}
	cfg["enabled"] = false
	c.call("PUT", "/api/analysis/settings", "", cfg, 200)
	cfg["enabled"] = true
	c.call("PUT", "/api/analysis/settings", "", cfg, 200)
	resetRate()
	if other, e := a.claimAnalysis(context.Background()); e != nil || other != nil {
		t.Fatal("completed messages requeued")
	}
	// Classifier selection must not acquire source-message locks in the reverse
	// order to ingestion (message first, analysis settings second).
	c.call("PATCH", "/api/messages/3/category", token, map[string]string{"category": "auto"}, 200)
	resetRate()
	writer, e := db.Begin()
	if e != nil {
		t.Fatal(e)
	}
	defer writer.Rollback()
	var body string
	if e = writer.QueryRow("SELECT body FROM messages WHERE id=3 FOR UPDATE").Scan(&body); e != nil {
		t.Fatal(e)
	}
	claimCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	j, e = a.claimAnalysis(claimCtx)
	if e != nil || j == nil || j.ID != 3 {
		t.Fatal("classifier locked source messages", e)
	}
}
