package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
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
}
