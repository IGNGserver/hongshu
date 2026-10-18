// Optional real-browser integration test. All data/credentials are synthetic,
// generated in memory; only this test's random database is dropped afterwards.
const { spawn, execFileSync } = require("node:child_process");
const { randomBytes } = require("node:crypto");
const { createServer } = require("node:http");
const { resolve } = require("node:path");
const assert = require("node:assert/strict");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = resolve(__dirname, "..");
const db = "hongshu_browser_" + randomBytes(6).toString("hex");
const port = process.env.SMOKE_PORT || "18080";
const mysqlPort = process.env.TEST_MYSQL_PORT || "13306";
const origin = "http://localhost:" + port;
const password = randomBytes(32).toString("hex");
const mysql = process.env.MYSQL_BIN;
if (!mysql || !process.env.HONGSHU_BINARY)
  throw new Error("MYSQL_BIN and HONGSHU_BINARY required");
function sql(statement) {
  return execFileSync(
    mysql,
    [
      "--no-defaults",
      "-h",
      "127.0.0.1",
      "-P",
      mysqlPort,
      "-u",
      "root",
      "-e",
      statement,
    ],
    { env: process.env, stdio: ["ignore", "pipe", "pipe"] },
  );
}
async function request(path, token, method = "GET", body) {
  const res = await fetch(origin + "/api" + path, {
    method,
    headers: {
      Origin: origin,
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  assert.ok(res.ok, "fixture HTTP failed: " + res.status);
  return res.json();
}
(async () => {
  let server,
    browser,
    ai,
    created = false;
  try {
    let aiCalls = 0;
    ai = createServer(async (req, res) => {
      let raw = "";
      for await (const chunk of req) raw += chunk;
      const data = JSON.parse(raw);
      const body = data.messages[1].content;
      assert.ok(!body.includes("482913") && !body.includes("验证码"), "OTP leaked to classifier");
      aiCalls++;
      const category = body.includes("fixture-personal") ? "personal" : body.includes("fixture-promotion") ? "promotion" : body.includes("fixture-spam") ? "spam" : body.includes("fixture-unknown") ? "unknown" : "notification";
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({choices:[{message:{content:JSON.stringify({category})}}]}));
    });
    await new Promise((resolve) => ai.listen(0, "127.0.0.1", resolve));
    const aiEndpoint = `http://127.0.0.1:${ai.address().port}/v1/chat/completions`;
    sql("CREATE DATABASE " + db + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    created = true;
    server = spawn(process.env.HONGSHU_BINARY, [], {
      cwd: root,
      env: {
        ...process.env,
        MYSQL_DSN:
          "root@tcp(127.0.0.1:" + mysqlPort + ")/" + db + "?timeout=5s",
        HONGSHU_PASSWORD: password,
        PUBLIC_URL: origin,
        LISTEN_ADDR: ":" + port,
        WEB_DIR: process.env.WEB_BUILD_DIR || resolve(root, "web"),
        MIGRATIONS_DIR: resolve(root, "db/migrations"),
        HONGSHU_AI_ALLOWED_HOSTS: "127.0.0.1",
      },
      stdio: "ignore",
    });
    let ready = false;
    for (let i = 0; i < 100; i++) {
      try {
        ready = (await fetch(origin + "/healthz")).ok;
        if (ready) break;
      } catch {}
      await new Promise((r) => setTimeout(r, 100));
    }
    assert.ok(ready, "server did not become healthy");
    browser = await chromium.launch({
      headless: true,
      ...(process.env.CHROMIUM_EXECUTABLE
        ? { executablePath: process.env.CHROMIUM_EXECUTABLE }
        : {}),
      args: ["--no-sandbox", "--disable-dev-shm-usage"],
    });
    const page = await browser.newPage({
      viewport: { width: 1280, height: 900 },
    });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(origin);
    await page.getByRole("button", { name: "登录", exact: true }).waitFor();
    await page.getByLabel("中枢密码", { exact: true }).fill(password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.getByRole("heading", { name: "短信收件箱" }).waitFor();
    await page.getByText("实时连接", { exact: true }).waitFor({ timeout: 15000 });
    assert.equal(await page.locator(".rail").count(), 0, "navigation rail was removed");
    const phone = await request("/login", null, "POST", {
      password,
      name: "Synthetic Phone",
      kind: "android",
    });
    const token = phone.token,
      id = phone.device.id;
    await request("/devices/" + id, token, "PATCH", { upload: true });
    await request("/sims", token, "PUT", {
      phone: "+8613800000000",
      label: "Synthetic SIM",
      subscription_id: 1,
    });
    // A second SIM makes the combined SIM / device filter appear.
    await request("/sims", token, "PUT", {
      phone: "+8613900000000",
      label: "Synthetic SIM 2",
      subscription_id: 2,
    });
    const body = 'Synthetic SMS <img src=x onerror="window.injected=true">';
    await request("/messages", token, "POST", {
      messages: [
        {
          receiver: "+8613800000000",
          sender: "10086",
          body,
          timestamp: 1700000000000,
          subscription_id: 1,
        },
      ],
    });
    await page.locator(".conversation").first().waitFor({timeout:10000});
    await page.locator(".conversation").first().click();
    await page.locator(".bubble").first().waitFor();
    assert.equal(await page.locator(".conversation .avatar svg").count(), 1);
    assert.ok(
      (await page.locator(".bubble").first().textContent()).includes(body),
    );
    assert.equal(await page.evaluate(() => window.injected), undefined);
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(
      await page.getByRole("button", { name: "返回", exact: true }).isVisible(),
    );
    await page.getByRole("button", { name: "返回", exact: true }).click();
    await page.getByLabel("搜索短信").fill("no-match-fixture");
    await page
      .getByText("暂无短信。请配置 Android 采集设备及 SIM 号码。")
      .waitFor();
    await page.getByLabel("搜索短信").fill("Synthetic SMS");
    await page.locator(".conversation").first().waitFor();
    await page.setViewportSize({ width: 1280, height: 900 });
    assert.equal(await page.locator("select").count(), 0);
    await page.getByRole("button", { name: "筛选", exact: true }).click();
    assert.ok(await page.getByRole("listbox", { name: "筛选" }).isVisible());
    assert.ok(await page.getByRole("option", { name: "Synthetic SIM 2" }).isVisible());
    await page.keyboard.press("Escape");
    const revocable = await request("/login", null, "POST", {
      password,
      name: "Revocation fixture",
      kind: "web",
    });
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.getByRole("heading", { name: "设置", exact: true }).waitFor();
    await page.getByText("Synthetic Phone", { exact: true }).waitFor();
    assert.equal(await page.getByText("Web browser", { exact: true }).count(), 1, "administrator can see the current browser identity");
    assert.equal(await page.locator(".device-card button").count(), 3, "administrator can revoke Android and Web identities");
    const revocableCard = page.locator(".device-card").filter({hasText: "Revocation fixture"});
    await revocableCard.getByRole("button", {name: "撤销设备", exact: true}).click();
    await revocableCard.getByText("已撤销", {exact: true}).waitFor();
    const revokedSession = await fetch(origin + "/api/me", {
      headers: {Authorization: "Bearer " + revocable.token},
    });
    assert.equal(revokedSession.status, 401, "revoked browser credentials must stop authorizing requests");
    assert.equal(await revocableCard.getByRole("button", {name: "撤销设备", exact: true}).count(), 0, "revoked device no longer offers a revoke action");
    await page.getByRole("switch", { name: "浏览器通知", exact: true }).waitFor();
    await page.getByText("通知已关闭", { exact: true }).waitFor();
    assert.equal(await page.getByText("中枢名称", { exact: true }).count(), 0);
    assert.equal(await page.getByText("隐私与存储", { exact: true }).count(), 0);
    await page.getByRole("button", { name: "退出登录", exact: true }).waitFor();
    const cachedAPI = await page.evaluate(async () => {
      await navigator.serviceWorker.ready;
      for (const k of await caches.keys()) {
        for (const r of await (await caches.open(k)).keys()) {
          if (new URL(r.url).pathname.startsWith("/api")) return true;
        }
      }
      return false;
    });
    assert.equal(cachedAPI, false);
    // End-to-end grouping uses a local synthetic classifier, never a real AI provider.
    await request("/messages", token, "POST", {messages:[
      "验证码为 482913，5分钟内有效", "fixture-personal", "fixture-promotion", "fixture-spam", "fixture-unknown",
    ].map((text, i) => ({receiver:"+8613800000000",sender:"10086",body:text,timestamp:1700000000100+i,subscription_id:1}))});
    assert.equal(aiCalls, 0, "disabled AI made requests");
    await page.getByLabel("完整 Chat Completions 接口地址").fill(aiEndpoint);
    await page.getByLabel("模型", {exact:true}).fill("synthetic");
    await page.getByLabel("每分钟调用上限").fill("60");
    await page.getByLabel("启用 AI 分组", {exact:true}).check();
    await page.getByLabel("我了解开启后会自动分析历史与新增非验证码短信").check();
    await page.getByRole("button", {name:"保存 AI 设置",exact:true}).click();
    await page.getByText("AI 设置已保存", {exact:true}).waitFor();
    await page.getByRole("button", {name:"返回收件箱",exact:true}).click();
    await page.getByLabel("搜索短信").fill("");
    await page.getByRole("button", {name:"个人 1",exact:true}).waitFor({timeout:20000});
    await page.getByRole("button", {name:"推广 1",exact:true}).waitFor({timeout:20000});
    await page.getByRole("button", {name:"垃圾 1",exact:true}).waitFor({timeout:20000});
    await page.getByRole("button", {name:"通知 1",exact:true}).waitFor({timeout:20000});
    await page.getByRole("button", {name:"验证码 1",exact:true}).click();
    await page.locator(".conversation").first().click();
    await page.getByText("482913", {exact:true}).waitFor();
    assert.equal(await page.locator(".otp-code").count(), 1);
    await page.getByRole("button", {name:"个人 1",exact:true}).click();
    await page.locator(".conversation").filter({hasText:"fixture-personal"}).click();
    await page.getByTitle("调整分类", {exact:true}).click();
    await page.locator(".category-actions").getByRole("button", {name:"垃圾",exact:true}).click();
    await page.getByRole("button", {name:"垃圾 2",exact:true}).waitFor();
    await page.getByRole("button", {name:"待分析 1",exact:true}).click();
    await page.locator(".conversation").filter({hasText:"fixture-unknown"}).click();
    await page.getByText("待分析 · 待确认分类", {exact:true}).waitFor();
    await page.getByRole("button", {name:"设置",exact:true}).click();
    await page.getByLabel("启用 AI 分组", {exact:true}).uncheck();
    await page.getByRole("button", {name:"保存 AI 设置",exact:true}).click();
    await page.getByText("AI 设置已保存", {exact:true}).waitFor();
    await page.getByRole("button", {name:"返回收件箱",exact:true}).click();
    await page.getByRole("button", {name:"其他短信 5",exact:true}).waitFor();
    assert.equal(await page.locator(".group-chips button").count(), 3);
    assert.deepEqual(errors, []);
    await page.setViewportSize({ width: 390, height: 844 });
    if (process.env.SMOKE_SCREENSHOT)
      await page.screenshot({
        path: process.env.SMOKE_SCREENSHOT,
        fullPage: true,
      });
    console.log(
      "Browser smoke passed: login, inbox, XSS, mobile, search, devices, settings, private PWA cache, rule OTP, automatic historical AI queue, groups, manual correction, pending review, AI off",
    );
  } finally {
    if (browser) await browser.close();
    if (ai) await new Promise((resolve) => ai.close(resolve));
    if (server) {
      server.kill("SIGTERM");
      await new Promise((r) => server.once("exit", r));
    }
    if (created) sql("DROP DATABASE " + db);
  }
})().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
