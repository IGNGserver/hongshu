import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { epochReset, mergeMessages, query, pushKey, restoreScrollPosition } from "../app.js";
test("history import cannot replace chronological latest",()=>assert.deepEqual(mergeMessages([{id:1,timestamp:200}],[{id:2,timestamp:100}]),[{id:2,timestamp:100},{id:1,timestamp:200}]));
test("incremental merge is idempotent and ordered", () =>
  assert.deepEqual(
    mergeMessages(
      [{ id: 2, body: "old" }],
      [{ id: 1 }, { id: 2, body: "new" }],
    ),
    [{ id: 1 }, { id: 2, body: "new" }],
  ));
test("filters encoded without corrupting numbers or unicode", () =>
  assert.equal(
    query({ q: "a&中", sim: "+123", offset: 0, empty: "" }),
    "q=a%26%E4%B8%AD&sim=%2B123&offset=0",
  ));
test("restored epoch drops a known cursor but first sight only records it", () => {
  assert.equal(epochReset(false, 0, 1), false);
  assert.equal(epochReset(true, 1, 1), false);
  assert.equal(epochReset(true, 1, 2), true);
  assert.equal(epochReset(true, 0, 1), true);
});
test("VAPID urlsafe decoding", () => {
  assert.deepEqual([...pushKey("_-8")], [255, 239]);
});
test("prepending history preserves the visible scroll offset", () => {
  assert.equal(restoreScrollPosition(240, 1200, 1680), 720);
  assert.equal(restoreScrollPosition(240, 1200, 1100), 240);
});
test("service worker never caches API or displays push body", async () => {
  const sw = await readFile(new URL("../sw.js", import.meta.url), "utf8");
  assert.ok(sw.includes("!ASSETS.includes"));
  assert.ok(!sw.includes("e.data.json"));
  assert.ok(sw.includes("有新短信，打开应用查看"));
});
test("no credential or SMS persistence in browser source", async () => {
  const source = await readFile(new URL("../app.js", import.meta.url), "utf8");
  assert.ok(!source.includes("indexedDB"));
  assert.ok(!source.includes("sessionStorage"));
  assert.ok(!source.includes("document.cookie"));
  // Only explicitly named, non-sensitive UI preferences may be persistent.
  assert.match(source, /const THEME_KEY = "hongshu-theme";/);
  const mentions = source.match(/localStorage/g) || [];
  assert.match(source, /const LOW_SPEED_KEY = "hongshu-low-speed";/);
  const allowed = [...source.matchAll(/localStorage\.(getItem|setItem)\((?:THEME_KEY|LOW_SPEED_KEY)[,)]/g)];
  assert.ok(allowed.length > 0);
  assert.equal(mentions.length, allowed.length, "localStorage used outside allowed UI preferences");
});

test("theme preference stores only system, light or dark", () => {
  const store = new Map();
  const ctx = vm.createContext({ URLSearchParams, Uint8Array, AbortController, DOMException, console });
  vm.runInContext(readFileSync(new URL("../app.js", import.meta.url), "utf8").replace(/^export /gm, ""), ctx);
  ctx.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)) };
  ctx.document = { documentElement: { dataset: {} }, querySelectorAll: () => [] };
  for (const [input, stored] of [["dark", "dark"], ["light", "light"], ["system", "system"], ["<img onerror=x>", "system"], [{ toString: () => "dark" }, "system"]]) {
    vm.runInContext("saveTheme(input)", Object.assign(ctx, { input }));
    assert.equal(store.get("hongshu-theme"), stored);
  }
  assert.deepEqual([...store.keys()], ["hongshu-theme"]);
  store.set("hongshu-theme", "anything-else");
  assert.equal(vm.runInContext("readTheme()", ctx), "system");
});
