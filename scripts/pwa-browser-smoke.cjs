// Real service-worker/browser regression using synthetic, in-memory API responses.
// No database, user credentials, SMS records or external network are involved.
const { createServer } = require("node:http");
const { readFile, stat, mkdir } = require("node:fs/promises");
const { resolve, extname, basename } = require("node:path");
const assert = require("node:assert/strict");
const { chromium, webkit } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const web = resolve(process.env.WEB_BUILD_DIR || "");
if (!process.env.WEB_BUILD_DIR) throw Error("WEB_BUILD_DIR must contain the production Web build");
const mime = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".webmanifest": "application/manifest+json", ".ico": "image/x-icon", ".svg": "image/svg+xml" };
const now = Date.now();
const message = { id: 1, sender: "fixture-sender", receiver: "fixture-line", device_id: 1, timestamp: now, body: "Synthetic browser acceptance message", direction: "in", category: "notification" };
const call = { id: 1, timestamp: now, number: "fixture-sender", receiver: "fixture-line", device_id: 1, type: "incoming", duration: 65, presentation: "allowed", video: false };
const me = { device: { id: 2, name: "Browser fixture", admin: true }, cursor: 1, call_cursor: 0, epoch: 1, unread_count: 0, metadata_revision: 1, features: ["metadata_revision", "call_log"], ai_enabled: false };
let upgrade = false;
let staleShell = false;
let originUnavailable = false;
const initialHTML = readFile(resolve(web, "index.html"), "utf8");
let revision;
const nextRevision = "0123456789abcdef01234567";
const api = path => path === "/api/me" ? me
  : path === "/api/metadata" ? { epoch: 1, revision: 1, devices: [{ id: 1, name: "Fixture device" }], sims: [], contacts: {} }
  : path === "/api/conversations" ? { conversations: [message], has_more: false }
  : path === "/api/messages" ? { messages: [message], has_more: false }
  : path === "/api/calls" ? { calls: [call], has_more: false }
  : path === "/api/calls/1" ? { call }
  : path === "/api/analysis/counts" ? { counts: {} }
  : path === "/api/analysis/settings" ? { enabled: false, configured: false, provider: "openai", model: "" }
  : { ok: true, devices: [], sims: [], contacts: {}, states: [], messages: [], calls: [] };
const server = createServer(async (req, res) => {
  try {
    if (originUnavailable) { req.socket.destroy(); return; }
    const path = new URL(req.url, "http://localhost").pathname;
    if (path.startsWith("/api/")) {
      if (path === "/api/me" && req.headers["x-pwa-fixture-anonymous"] === "1") { res.writeHead(401, { "Content-Type": "application/json" }); res.end(JSON.stringify({ error: "unauthorized" })); return; }
      if (path === "/api/ws") { res.writeHead(426); res.end(); return; }
      res.setHeader("Content-Type", "application/json"); res.setHeader("Cache-Control", "private, no-store");
      res.end(JSON.stringify(api(path))); return;
    }
    const file = resolve(web, path === "/" ? "index.html" : "." + path);
    if (!file.startsWith(web + "/") || !(await stat(file)).isFile()) { res.writeHead(404); res.end(); return; }
    let bytes = await readFile(file);
    if (upgrade && (basename(file) === "sw.js" || (basename(file) === "index.html" && !staleShell))) bytes = Buffer.from(bytes.toString().replaceAll(revision, nextRevision));
    res.setHeader("Content-Type", mime[extname(file)] || "application/octet-stream");
    res.setHeader("Cache-Control", path.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-store, no-cache, max-age=0, must-revalidate");
    if (path === "/sw.js") res.setHeader("Service-Worker-Allowed", "/");
    res.end(bytes);
  } catch { res.writeHead(404); res.end(); }
});
(async () => {
  let browser;
  try {
    revision = (await initialHTML).match(/name="hongshu-shell-revision" content="([^"]+)"/)[1];
    assert.notEqual(revision, "development");
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    browser = await (process.env.PWA_BROWSER === "webkit" ? webkit : chromium).launch({ headless: true,
      ...(process.env.PWA_BROWSER === "webkit" && process.env.WEBKIT_EXECUTABLE_PATH ? { executablePath: process.env.WEBKIT_EXECUTABLE_PATH } : {}) });
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    // WebKit automation cannot reliably emulate offline SW navigation (#42775).
    // Refuse real origin connections there and prove an uncontrolled page fails.
    const setUnavailable = async value => {
      if (process.env.PWA_BROWSER === "webkit") originUnavailable = value;
      else await context.setOffline(value);
    };
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.goto(origin);
    await page.locator(".inbox-header").waitFor();
    await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller), { timeout: 20000 });
    assert.equal(await page.locator('link[rel="manifest"]').getAttribute("href"), "/manifest.webmanifest");
    const response = await page.request.get(origin + "/manifest.webmanifest");
    assert.match(response.headers()["content-type"], /application\/manifest\+json/);
    const manifest = await response.json();
    assert.equal(manifest.id, "/"); assert.equal(manifest.scope, "/"); assert.equal(manifest.display, "standalone");
    for (const icon of manifest.icons) {
      const image = await page.request.get(origin + icon.src);
      assert.equal(image.status(), 200); assert.match(image.headers()["content-type"], /image\/png/);
      const bytes = await image.body();
      assert.equal(`${bytes.readUInt32BE(16)}x${bytes.readUInt32BE(20)}`, icon.sizes);
    }
    for (const link of await page.locator('link[rel="apple-touch-icon"]').evaluateAll(nodes => nodes.map(node => ({ href: node.getAttribute("href"), sizes: node.getAttribute("sizes") })))) {
      const image = await page.request.get(origin + link.href); const bytes = await image.body();
      assert.equal(image.status(), 200);
      if (link.sizes) assert.equal(`${bytes.readUInt32BE(16)}x${bytes.readUInt32BE(20)}`, link.sizes);
    }
    for (const width of [390, 600, 768, 1024, 1366]) {
      console.log(`Checking adaptive window ${width}`);
      await page.setViewportSize({ width, height: width > 900 ? 768 : 844 });
      await page.locator(".conversation").first().click();
      await page.locator(".messages").waitFor();
      const bounds = await page.evaluate(() => ({
        width: innerWidth, app: document.querySelector("#app").getBoundingClientRect().width,
        overflow: document.documentElement.scrollWidth > innerWidth,
        inbox: document.querySelector(".inbox").getBoundingClientRect().width,
        detail: document.querySelector(".detail").getBoundingClientRect().width,
      }));
      assert.equal(bounds.overflow, false, `page overflow at ${width}`);
      assert.ok(bounds.app <= width + 1);
      if (process.env.PWA_SCREENSHOT_DIR) { await mkdir(process.env.PWA_SCREENSHOT_DIR, { recursive: true }); await page.screenshot({ path: resolve(process.env.PWA_SCREENSHOT_DIR, `hongshu-${width}.png`) }); }
      if (width < 600) { assert.equal(bounds.inbox, 0); assert.ok(bounds.detail >= width - 2); await page.getByRole("button", { name: "返回", exact: true }).click(); }
      else { assert.ok(bounds.inbox >= 240); assert.ok(bounds.detail >= 300, `usable detail at ${width}`); }
      await page.getByRole("button", { name: "设置", exact: true }).click();
      await page.getByRole("button", { name: "安装说明", exact: true }).click();
      await page.getByRole("dialog", { name: "安装鸿枢" }).waitFor();
      const dialog = await page.locator(".pwa-help-dialog").boundingBox();
      assert.ok(dialog.x >= 0 && dialog.x + dialog.width <= width + 1);
      await page.getByRole("button", { name: "完成", exact: true }).click();
      await page.getByRole("button", { name: "返回收件箱", exact: true }).click();
      await page.locator(".inbox-header").waitFor();
    }
    await page.getByRole("tab", { name: "通话", exact: true }).click();
    await page.locator(".call-row").first().click();
    await page.locator(".call-facts").waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, "call detail fits a tablet");
    await page.getByRole("tab", { name: "短信", exact: true }).click();
    await page.locator(".list").waitFor();
    await page.setViewportSize({ width: 390, height: 360 });
    if (await page.getByRole("button", { name: "返回", exact: true }).isVisible()) await page.getByRole("button", { name: "返回", exact: true }).click();
    await page.locator(".inbox").waitFor({ state: "visible" });
    await page.getByRole("button", { name: "设置", exact: true }).click();
    assert.ok(await page.locator(".page-container").evaluate(node => node.clientHeight > 0 && node.scrollHeight > node.clientHeight), "short-window settings can scroll");
    await page.getByRole("button", { name: "安装说明", exact: true }).click();
    await page.evaluate(() => {
      const event = new Event("beforeinstallprompt", { cancelable: true });
      event.prompt = async () => { window.promptWasCalled = true; };
      event.userChoice = Promise.resolve({ outcome: "dismissed" });
      window.dispatchEvent(event);
    });
    await page.locator(".pwa-install-action").click();
    assert.equal(await page.evaluate(() => window.promptWasCalled), true);
    await page.getByRole("button", { name: "完成", exact: true }).click();
    await page.getByRole("button", { name: "返回收件箱", exact: true }).click();
    const oldApp = await page.locator('script[type="module"]').getAttribute("src");
    await setUnavailable(true);
    if (process.env.PWA_BROWSER === "webkit") {
      const control = await browser.newContext({ serviceWorkers: "block" });
      const uncontrolled = await control.newPage();
      await assert.rejects(() => uncontrolled.goto(origin), "an uncontrolled page must fail while the origin refuses connections");
      await control.close();
    }
    await page.reload();
    await page.getByRole("heading", { name: "暂时无法连接中枢" }).waitFor();
    assert.equal(await page.evaluate(() => fetch("/api/me").then(() => false, () => true)), true, "API cannot be answered from a shell cache");
    assert.equal(await page.evaluate(async () => (await Promise.all((await caches.keys()).map(async key => (await (await caches.open(key)).keys()).some(req => new URL(req.url).pathname.startsWith("/api/"))))).some(Boolean)), false);
    await setUnavailable(false);
    await page.reload();
    await page.locator(".inbox-header").waitFor();
    upgrade = true;
    staleShell = true;
    const failedCandidate = await page.evaluate(async () => {
      const reg = await navigator.serviceWorker.getRegistration();
      const outcome = new Promise(resolve => reg.addEventListener("updatefound", () => {
        const candidate = reg.installing;
        candidate.addEventListener("statechange", () => { if (["installed", "redundant"].includes(candidate.state)) resolve(candidate.state); });
      }, { once: true }));
      await reg.update();
      return outcome;
    });
    assert.equal(failedCandidate, "redundant", "stale HTML must reject a new candidate without replacing the active shell");
    assert.equal(await page.locator('meta[name="hongshu-shell-revision"]').getAttribute("content"), revision);
    staleShell = false;
    await page.evaluate(async () => (await navigator.serviceWorker.getRegistration()).update());
    await page.getByRole("button", { name: "更新并打开", exact: true }).waitFor();
    assert.equal(await page.locator('meta[name="hongshu-shell-revision"]').getAttribute("content"), revision, "update waits for consent");
    await page.getByRole("searchbox", { name: "搜索短信", exact: true }).fill("unsaved fixture");
    page.once("dialog", dialog => dialog.dismiss());
    await page.getByRole("button", { name: "更新并打开", exact: true }).click();
    assert.equal(await page.locator('meta[name="hongshu-shell-revision"]').getAttribute("content"), revision);
    page.once("dialog", dialog => dialog.accept());
    await page.getByRole("button", { name: "更新并打开", exact: true }).click();
    await page.waitForFunction(expected => document.querySelector('meta[name="hongshu-shell-revision"]')?.content === expected, nextRevision);
    await setUnavailable(true);
    assert.equal(await page.evaluate(async path => (await fetch(path)).ok, oldApp), true, "old immutable imports remain available offline");
    await setUnavailable(false);
    assert.deepEqual(errors, []);
    const anonymous = await browser.newContext({ viewport: { width: 320, height: 360 }, isMobile: true, hasTouch: true,
      userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 27_0 like Mac OS X) AppleWebKit/605.1.15 Version/27.0 Mobile/15E148 Safari/604.1",
      extraHTTPHeaders: { "X-Pwa-Fixture-Anonymous": "1" } });
    const login = await anonymous.newPage();
    await login.goto(origin);
    await login.locator(".auth").waitFor();
    await login.locator(".pwa-install-entry").click();
    await login.getByRole("dialog", { name: "安装鸿枢" }).waitFor();
    assert.match(await login.locator(".pwa-install-status").innerText(), /Safari.*添加到主屏幕/);
    await login.getByRole("button", { name: "完成", exact: true }).click();
    await login.locator(".pwa-help-dialog").waitFor({ state: "detached" });
    await login.evaluate(() => { Object.defineProperty(navigator, "standalone", { configurable: true, value: true }); window.dispatchEvent(new Event("appinstalled")); });
    assert.equal(await login.locator(".pwa-install-entry").isHidden(), true);
    assert.equal(await login.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, "short-window login stays within the screen");
    await anonymous.close();
    if (process.env.PWA_SCREENSHOT_DIR) { await mkdir(process.env.PWA_SCREENSHOT_DIR, { recursive: true }); await page.screenshot({ path: resolve(process.env.PWA_SCREENSHOT_DIR, "hongshu-phone.png"), fullPage: true }); }
    console.log(`Transport acceptance: ${process.env.PWA_BROWSER === "webkit" ? "origin unreachable, with failing uncontrolled-page comparison" : "Chromium offline network context"}`);
    console.log("PWA browser acceptance passed: icons, 5 window widths, short-window settings, install event, offline shell, API isolation, stale-shell rejection, consented upgrade and old imports");
    await context.close();
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });