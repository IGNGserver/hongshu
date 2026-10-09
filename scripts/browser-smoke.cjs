// Optional real-browser integration test. All data/credentials are synthetic,
// generated in memory; only this test's random database is dropped afterwards.
const { spawn, execFileSync } = require("node:child_process");
const { randomBytes } = require("node:crypto");
const { resolve } = require("node:path");
const assert = require("node:assert/strict");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = resolve(__dirname, "..");
const db = "hongshu_browser_" + randomBytes(6).toString("hex");
const port = process.env.SMOKE_PORT || "18080";
const mysqlPort = process.env.TEST_MYSQL_PORT || "13306";
const origin = "http://localhost:" + port;
const secret = randomBytes(32).toString("hex");
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
    created = false;
  try {
    sql("CREATE DATABASE " + db + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    created = true;
    server = spawn(process.env.HONGSHU_BINARY, [], {
      cwd: root,
      env: {
        ...process.env,
        MYSQL_DSN:
          "root@tcp(127.0.0.1:" + mysqlPort + ")/" + db + "?timeout=5s",
        BOOTSTRAP_SECRET: secret,
        PUBLIC_URL: origin,
        LISTEN_ADDR: ":" + port,
        WEB_DIR: resolve(root, "web"),
        MIGRATIONS_DIR: resolve(root, "db/migrations"),
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
    await page.getByRole("button", { name: "安全连接", exact: true }).waitFor();
    await page.locator("select").selectOption("bootstrap");
    await page.getByLabel("设备名称", { exact: true }).fill("Synthetic Admin");
    await page.getByLabel("配对码 / 初始化密钥").fill(secret);
    await page.getByRole("button", { name: "安全连接", exact: true }).click();
    await page.getByRole("heading", { name: "短信收件箱" }).waitFor();
    await page.getByText("实时连接", { exact: true }).waitFor({ timeout: 15000 });
    const pairing = await page.evaluate(async () => {
      const r = await fetch("/api/pairings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
      return r.json();
    });
    const phone = await request("/pair", null, "POST", {
      code: pairing.code,
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
    await page.getByRole("button", { name: "设备", exact: true }).click();
    await page.getByText("Synthetic Phone", { exact: true }).waitFor();
    await page.getByRole("button", { name: "生成配对码", exact: true }).click();
    await page.locator(".code").waitFor();
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.getByLabel("中枢名称").fill("Synthetic Hub");
    await page.getByRole("button", { name: "保存名称" }).click();
    await page.getByText("Synthetic Hub", { exact: true }).first().waitFor();
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
    assert.deepEqual(errors, []);
    await page.setViewportSize({ width: 390, height: 844 });
    if (process.env.SMOKE_SCREENSHOT)
      await page.screenshot({
        path: process.env.SMOKE_SCREENSHOT,
        fullPage: true,
      });
    console.log(
      "Browser smoke passed: setup, inbox, XSS safety, mobile conversation, search, devices, settings, private PWA cache",
    );
  } finally {
    if (browser) await browser.close();
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
