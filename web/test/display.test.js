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
