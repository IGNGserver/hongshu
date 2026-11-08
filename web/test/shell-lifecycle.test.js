import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

async function lifecycleRuntime(windows) {
  const stores = new Map();
  const caches = {
    keys: async () => [...stores.keys()],
    delete: async name => stores.delete(name),
    open: async name => {
      if (!stores.has(name)) stores.set(name, new Map());
      const store = stores.get(name);
      return {
        match: async key => store.get(key)?.clone(),
        put: async (key, response) => { store.set(key, response.clone()); },
      };
    },
  };
  const self = {
    __WB_MANIFEST: [], location: { origin: "https://example.test" },
    clients: { matchAll: async () => windows, claim: async () => {} },
    addEventListener() {}, skipWaiting: async () => {},
  };
  const context = vm.createContext({
    self, caches, Response, URL, console,
    setCacheNameDetails() {}, precacheAndRoute() {}, createHandlerBoundToURL() {}, addPlugins() {},
    registerRoute() {}, CacheFirst: class {}, ExpirationPlugin: class {},
  });
  const source = (await readFile(new URL("../sw.js", import.meta.url), "utf8")).replace(/^import .*;\n/gm, "");
  vm.runInContext(source, context);
  return { context, stores };
}

test("shell cleanup honors unknown clients, active leases and a post-lease grace period", async () => {
  const oldRevision = "a".repeat(24);
  const currentRevision = "b".repeat(24);
  const oldCache = "hongshu-shell-" + oldRevision;
  const current = "hongshu-shell-" + currentRevision;
  const windows = [{ id: "legacy-window" }];
  const { context, stores } = await lifecycleRuntime(windows);
  stores.set(oldCache, new Map()); stores.set(current, new Map());
  stores.set("hongshu-shell-lifecycle-v1", new Map());
  const metadata = stores.get("hongshu-shell-lifecycle-v1");
  const old = Date.now() - 30 * 24 * 60 * 60 * 1000;
  metadata.set(`/shell/${oldRevision}`, new Response(JSON.stringify({ firstSeen: old, lastSeen: old })));
  await vm.runInContext(`cleanupShells("${current}")`, context);
  assert.equal(stores.has(oldCache), true, "unknown open documents fail closed");
  await vm.runInContext(`rememberShellLease("legacy-window", "${oldRevision}")`, context);
  await vm.runInContext(`cleanupShells("${current}")`, context);
  assert.equal(stores.has(oldCache), true, "an active old document retains its shell");
  windows.length = 0;
  await vm.runInContext(`cleanupShells("${current}")`, context);
  assert.equal(stores.has(oldCache), true, "closing a document starts a grace period, not immediate deletion");
  const lease = await metadata.get(`/shell/${oldRevision}`).json();
  metadata.set(`/shell/${oldRevision}`, new Response(JSON.stringify({ ...lease, lastSeen: old })));
  await vm.runInContext(`cleanupShells("${current}")`, context);
  assert.equal(stores.has(oldCache), false, "an unreferenced shell is collected after grace");
  assert.equal(stores.has(current), true, "the current shell is never collected");
});
