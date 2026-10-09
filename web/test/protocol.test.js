import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { epochReset, mergeMessages, query, pushKey } from "../app.js";
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
test("service worker never caches API or displays push body", async () => {
  const sw = await readFile(new URL("../sw.js", import.meta.url), "utf8");
  assert.ok(sw.includes("!ASSETS.includes"));
  assert.ok(!sw.includes("e.data.json"));
  assert.ok(sw.includes("有新短信，打开应用查看"));
});
test("no credential or SMS persistence in browser source", async () => {
  const source = await readFile(new URL("../app.js", import.meta.url), "utf8");
  assert.ok(!source.includes("localStorage"));
  assert.ok(!source.includes("indexedDB"));
});
