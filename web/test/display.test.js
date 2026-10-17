import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { linkSegments, isFreshArrival, freshConversationKeys, conversationKey } from "../app.js";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("links keep exact text and stop before sentence punctuation", () => {
  const body = "账单详情 https://h5.example.cn/bill?id=7，请查收。备用 http://a.example.com/x).";
  assert.deepEqual(linkSegments(body), [
    { text: "账单详情 " },
    { url: "https://h5.example.cn/bill?id=7" },
    { text: "，请查收。备用 " },
    { url: "http://a.example.com/x" },
    { text: ")." },
  ]);
  assert.equal(linkSegments(body).map((s) => s.text ?? s.url).join(""), body);
});

test("only http(s) URLs with a host become links", () => {
  for (const body of ["javascript:alert(1)", "data:text/html,x", "https://", "ftp://example.com", 'Synthetic SMS <img src=x onerror="window.injected=true">']) {
    assert.deepEqual(linkSegments(body), [{ text: body }], body);
  }
});

test("bubbles animate only for messages newer than the last render", () => {
  assert.equal(isFreshArrival(5, 0), false, "first render of a conversation shows history without animation");
  assert.equal(isFreshArrival(5, 5), false);
  assert.equal(isFreshArrival(4, 5), false, "older pages loaded by scrolling up never animate");
  assert.equal(isFreshArrival(6, 5), true);
});

test("rows animate only when new or moved forward since the last render", () => {
  const a = { sender: "10086", receiver: "+861", timestamp: 100 };
  const b = { sender: "95588", receiver: "+861", timestamp: 200 };
  assert.equal(freshConversationKeys(new Map(), [a, b]).size, 0, "first load is not an arrival");
  const previous = new Map([[conversationKey(a), 100], [conversationKey(b), 200]]);
  assert.deepEqual([...freshConversationKeys(previous, [a, b])], []);
  const moved = { ...a, timestamp: 300 };
  const added = { sender: "95338", receiver: "+862", timestamp: 310 };
  assert.deepEqual([...freshConversationKeys(previous, [added, moved, b])], [conversationKey(added), conversationKey(moved)]);
});

test("icon.svg is self-contained so <img> and favicon can render it", () => {
  const svg = read("icon.svg");
  const refs = [...svg.matchAll(/\bhref="([^"]*)"/g)].map((m) => m[1]);
  assert.ok(refs.length > 0);
  assert.ok(refs.every((ref) => ref.startsWith("data:image/png;base64,")), "no external file references");
});

test("manifest keeps any and maskable icons separate and the shell caches them", () => {
  const manifest = JSON.parse(read("manifest.webmanifest"));
  assert.ok(manifest.icons.every((icon) => icon.purpose === "any" || icon.purpose === "maskable"));
  const maskable = manifest.icons.filter((icon) => icon.purpose === "maskable");
  assert.deepEqual(maskable.map((icon) => icon.src), ["/icon-maskable-512.png"]);
  const worker = read("sw.js");
  const build = read("build.mjs");
  for (const file of ["icon-maskable-512.png", "apple-touch-icon.png"]) {
    assert.ok(worker.includes(`"/${file}"`), `sw.js precaches ${file}`);
    assert.ok(build.includes(`"${file}"`), `build.mjs ships ${file}`);
  }
  assert.ok(read("index.html").includes('rel="apple-touch-icon" href="/apple-touch-icon.png"'));
});

test("avatar initials match Android avatarInitial", async () => {
  const { avatarInitial } = await import("../app.js");
  assert.equal(avatarInitial("张三", "13912345678"), "张");
  assert.equal(avatarInitial("", "【京东】"), "京");
  assert.equal(avatarInitial("alice", "+8613300001111"), "A");
  assert.equal(avatarInitial("", "13912345678"), null, "plain numbers get the person icon");
  assert.equal(avatarInitial("", "+8613912345678"), null);
  assert.equal(avatarInitial("😀 Bob", "1"), "😀", "surrogate pairs stay whole");
});

test("service numbers match Android isServiceNumber", async () => {
  const { isServiceNumber } = await import("../app.js");
  for (const sender of ["10086", "95588", "106903", "1069012345678", "+8610086"]) assert.equal(isServiceNumber(sender), true, sender);
  for (const sender of ["13912345678", "12", "+8613912345678", "abc"]) assert.equal(isServiceNumber(sender), false, sender);
});

test("avatar colour is a stable Java hashCode pick from the Android palette", async () => {
  const { avatarColor, AVATAR_COLORS } = await import("../app.js");
  assert.equal(avatarColor(""), AVATAR_COLORS[0]);
  assert.equal(avatarColor("a"), AVATAR_COLORS[97 % 8]);
  assert.equal(avatarColor("ab"), AVATAR_COLORS[(97 * 31 + 98) % 8]);
  assert.equal(avatarColor("10086"), avatarColor("10086"));
  assert.ok(AVATAR_COLORS.includes(avatarColor("13912345678")));
});

test("messages cluster by device, five-minute gap and day", async () => {
  const { clusterMessages, CLUSTER_GAP_MS } = await import("../app.js");
  const base = new Date(2026, 9, 17, 12, 0).getTime();
  const m = (id, t, device_id = 1) => ({ id, timestamp: base + t, device_id });
  const clusters = clusterMessages([
    m(1, 0), m(2, 60e3), m(3, 60e3 + CLUSTER_GAP_MS), // within the gap → one cluster
    m(4, 60e3 + CLUSTER_GAP_MS + 1, 2),             // another device
    m(5, 60e3 + 2 * CLUSTER_GAP_MS + 2, 2),         // more than five minutes later
    m(6, 24 * 3600e3),                              // next day
  ]);
  assert.deepEqual(clusters.map((c) => c.messages.map((x) => x.id)), [[1, 2, 3], [4], [5], [6]]);
  assert.equal(clusters[0].end, base + 60e3 + CLUSTER_GAP_MS);
  assert.deepEqual(clusterMessages([]), []);
});

test("theme choice falls back to system and picks the matching theme-color", async () => {
  const { normalizeTheme, themeColorFor, THEME_COLORS } = await import("../app.js");
  assert.equal(normalizeTheme("dark"), "dark");
  assert.equal(normalizeTheme("light"), "light");
  for (const bad of [null, undefined, "", "blue", "DARK"]) assert.equal(normalizeTheme(bad), "system");
  assert.equal(themeColorFor("system", "(prefers-color-scheme: dark)"), THEME_COLORS.dark);
  assert.equal(themeColorFor("system", "(prefers-color-scheme: light)"), THEME_COLORS.light);
  assert.equal(themeColorFor("dark", "(prefers-color-scheme: light)"), THEME_COLORS.dark, "a forced theme wins over the media query");
  assert.equal(themeColorFor("light", "(prefers-color-scheme: dark)"), THEME_COLORS.light);
});

test("theme-color values match the app background tokens in style.css", () => {
  const css = read("style.css");
  assert.ok(/--md-sys-color-surface-container-low: #F3F3FA;/.test(css), "light app background");
  assert.ok(/--md-sys-color-background: #111318;/.test(css), "dark app background");
  const html = read("index.html");
  assert.ok(html.includes('content="#F3F3FA" media="(prefers-color-scheme: light)"'));
  assert.ok(html.includes('content="#111318" media="(prefers-color-scheme: dark)"'));
});

test("a realtime refresh keeps scrolled-in pages without duplicates or gaps", async () => {
  const { mergeConversationPages, conversationKey } = await import("../app.js");
  const c = (sender, timestamp) => ({ sender, receiver: "+861", timestamp });
  const loaded = [c("a", 100), c("b", 90), c("c", 80), c("d", 70)];
  // Head page not full: it is the whole list, nothing else to keep.
  assert.deepEqual(mergeConversationPages([c("x", 200), c("a", 100)], loaded, false).map((r) => r.sender), ["x", "a"]);
  // Full head: "c" moved to the top, so its older copy is dropped; rows below the head's floor are kept.
  const head = [c("c", 300), c("a", 100)];
  const merged = mergeConversationPages(head, loaded, true);
  assert.deepEqual(merged.map((r) => r.sender), ["c", "a", "b", "d"]);
  assert.equal(new Set(merged.map(conversationKey)).size, merged.length);
  assert.equal(merged[0].timestamp, 300, "the fresh copy wins");
});
