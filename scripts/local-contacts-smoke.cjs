// Real Chromium/IndexedDB with synthetic API and Picker data, never a real address book.
const { createServer } = require("node:http");
const { readFile } = require("node:fs/promises");
const { resolve } = require("node:path");
const assert = require("node:assert/strict");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = process.env.WEB_BUILD_DIR || resolve(__dirname, "../web");
const sender = "+8613800000001";
const name = "合成本机朋友";
const calls = [];
let browser, server;

async function setup(context, supported = true) {
  await context.addInitScript(({ supported, name }) => {
    window.revokedContacts = [];
    const revoke = URL.revokeObjectURL.bind(URL);
    URL.revokeObjectURL = url => { window.revokedContacts.push(url); revoke(url); };
    const bytes = Uint8Array.from(atob("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="), c => c.charCodeAt(0));
    window.syntheticContacts = [{ name: [name], tel: ["13800000001"], icon: [new Blob([bytes], { type: "image/png" })] }];
    Object.defineProperty(navigator, "contacts", { configurable: true, value: supported ? {
      getProperties: async () => ["name", "tel", "icon"],
      select: async properties => {
        if (JSON.stringify(properties) !== '["name","tel","icon"]') throw Error("Unexpected properties");
        return window.syntheticContacts;
      },
    } : undefined });
  }, { supported, name });
  await context.route("**/api/**", route => {
    const request = route.request();
    calls.push({ url: request.url(), body: request.postData() || "" });
    const path = new URL(request.url()).pathname;
    const message = { id: 1, sender, receiver: "+8613900000000", device_id: "phone", body: "synthetic message", timestamp: 1700000000000, contact: "中枢别名" };
    const data = path === "/api/me" ? { device: { id: "browser-A", admin: false }, cursor: 1, epoch: 1, unread_count: 0, ai_enabled: false } :
      path === "/api/devices" ? { devices: [] } : path === "/api/sims" ? { sims: [] } :
      path === "/api/contacts" ? { contacts: { [sender]: "中枢别名" } } :
      path === "/api/analysis/settings" ? { enabled: false } :
      path === "/api/analysis/counts" ? { counts: { all: 1, other: 1 } } :
      path === "/api/conversations" ? { conversations: [message] } : path === "/api/messages" ? { messages: [message] } : {};
    return route.fulfill({ json: data });
  });
}

async function settings(page) {
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByRole("heading", { name: "本机联系人", exact: true }).waitFor();
}
async function back(page) {
  await page.getByRole("button", { name: "返回收件箱", exact: true }).click();
  await page.getByRole("heading", { name: "短信收件箱", exact: true }).waitFor();
}
async function saved(page) {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const request = indexedDB.open("hongshu-local-contacts", 1);
    request.onsuccess = () => {
      const db = request.result;
      const tx = db.transaction("settings");
      const read = tx.objectStore("settings").get("contacts");
      read.onsuccess = () => { resolve(read.result ? { enabled: read.result.enabled, count: read.result.records.length } : null); db.close(); };
      read.onerror = reject;
    };
    request.onerror = reject;
  }));
}

(async () => {
  const errors = [];
  try {
    server = createServer(async (req, res) => {
      const path = new URL(req.url, "http://localhost").pathname;
      const file = path === "/" ? "index.html" : path.slice(1);
      if (!["index.html", "app.js", "local-contacts.js", "style.css", "sw.js", "manifest.webmanifest"].includes(file) && !/^(?:icon|favicon|apple-touch-icon)[a-z0-9.-]*\.(?:svg|png)$/.test(file)) { res.writeHead(404).end(); return; }
      try {
        res.setHeader("Content-Type", file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : file.endsWith(".html") ? "text/html" : "application/octet-stream");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; img-src 'self' data: blob:; connect-src 'self'; style-src 'self'");
        res.end(await readFile(resolve(root, file)));
      } catch { res.writeHead(404).end(); }
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://localhost:${server.address().port}`;
    browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}), args: ["--no-sandbox"] });
    const context = await browser.newContext({ serviceWorkers: "block" });
    await setup(context);
    const page = await context.newPage();
    page.on("pageerror", error => errors.push(error.message));
    await page.goto(origin);
    await page.locator(".conversation strong").filter({ hasText: "中枢别名" }).waitFor();
    await settings(page);
    await page.getByRole("button", { name: "选择或更新联系人", exact: true }).click();
    await page.getByText("已选择 1 个号码。", { exact: false }).waitFor();
    assert.deepEqual(await saved(page), { enabled: true, count: 1 });
    await back(page);
    await page.locator(".conversation strong").filter({ hasText: name }).waitFor();
    await page.waitForFunction(() => document.querySelector(".contact-photo")?.naturalWidth === 1);
    await page.reload();
    await page.locator(".conversation strong").filter({ hasText: name }).waitFor();
    await page.locator(".local-contact-search summary").click();
    const before = calls.length;
    await page.getByRole("searchbox", { name: "搜索本机联系人", exact: true }).fill("本机朋友");
    await page.getByRole("button", { name: `${name} · 13800000001`, exact: true }).waitFor();
    assert.equal(calls.length, before, "typing a local name must not request anything");
    await page.getByRole("button", { name: `${name} · 13800000001`, exact: true }).click();
    await page.locator(".detail-head strong").filter({ hasText: name }).waitFor();
    assert.ok((await page.locator(".detail-head .sub-info").textContent()).includes(sender), "details retain original sender");
    assert.ok(!calls.some(call => decodeURIComponent(call.url).includes(name) || call.body.includes(name)), "local contact leaked into API traffic");

    // A second tab uses the same IndexedDB, and clearing updates the first tab as well.
    const other = await context.newPage();
    other.on("pageerror", error => errors.push(error.message));
    await other.goto(origin);
    await other.locator(".conversation strong").filter({ hasText: name }).waitFor();
    await settings(other);
    await other.getByRole("switch", { name: "使用本机联系人", exact: true }).click();
    await other.waitForFunction(() => document.querySelector('.local-contacts-settings [role="switch"]')?.getAttribute("aria-checked") === "false");
    await page.locator(".detail-head strong").filter({ hasText: "中枢别名" }).waitFor();
    assert.ok(await page.evaluate(() => window.revokedContacts.length > 0));
    await other.getByRole("button", { name: "清除本机联系人", exact: true }).click();
    await other.getByText("已选择 0 个号码。", { exact: false }).waitFor();
    assert.equal(await saved(other), null);
    // A real IndexedDB transaction abort is reported, without displaying an unsaved name.
    await other.evaluate(() => {
      window.originalContactPut = IDBObjectStore.prototype.put;
      IDBObjectStore.prototype.put = function(value, key) {
        if (this.name === "settings" && key === "contacts") throw new DOMException("synthetic quota", "QuotaExceededError");
        return window.originalContactPut.call(this, value, key);
      };
    });
    await other.getByRole("button", { name: "选择或更新联系人", exact: true }).click();
    await other.locator("#toast").filter({ hasText: "联系人未保存" }).waitFor();
    assert.equal(await saved(other), null);
    await other.evaluate(() => { IDBObjectStore.prototype.put = window.originalContactPut; });
    await other.getByRole("button", { name: "选择或更新联系人", exact: true }).click();
    await other.getByText("已选择 1 个号码。", { exact: false }).waitFor();
    await other.getByRole("button", { name: "退出登录", exact: true }).click();
    await other.getByRole("button", { name: "登录", exact: true }).waitFor();
    assert.equal(await saved(other), null);
    const staleWriteBlocked = await other.evaluate(async () => {
      const { BrowserContacts } = await import("/local-contacts.js");
      const a = new BrowserContacts(), b = new BrowserContacts();
      await a.load("race-test");
      await a.select([{ name: ["合成竞态"], tel: ["13800000001"] }]);
      await b.load("race-test");
      await a.clear();
      try { await b.select([{ name: ["合成旧任务"], tel: ["13800000001"] }]); return false; }
      catch { await b.load("race-test"); return !b.enabled && b.records.length === 0; }
    });
    assert.equal(staleWriteBlocked, true, "a late write must not resurrect another tab's cleared contacts");
    await context.close();

    const unsupported = await browser.newContext({ serviceWorkers: "block" });
    await setup(unsupported, false);
    const fallback = await unsupported.newPage();
    fallback.on("pageerror", error => errors.push(error.message));
    await fallback.goto(origin);
    await fallback.getByRole("heading", { name: "短信收件箱" }).waitFor();
    await settings(fallback);
    assert.equal(await fallback.getByRole("button", { name: "选择或更新联系人", exact: true }).isDisabled(), true);
    assert.ok((await fallback.locator(".local-contacts-settings").textContent()).includes("当前浏览器不支持"));
    assert.deepEqual(errors, []);
    console.log("local contacts browser smoke passed: Picker, IndexedDB/reload/quota, avatar CSP, private search, cross-tab disable/clear/stale-write fence, logout, unsupported browser");
  } finally {
    await browser?.close();
    if (server) await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
