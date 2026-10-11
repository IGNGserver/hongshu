import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, cp, readFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import vm from "node:vm";

test("content-only upgrade versions the full shell and stays consistent offline", async () => {
  const temp = await mkdtemp(join(process.env.HONGSHU_TEST_TMP || tmpdir(), "hongshu-shell-"));
  try {
    const source = join(temp, "source");
    await cp(new URL("../", import.meta.url), source, { recursive: true });
    const build = out => execFileSync(process.execPath, [join(source, "build.mjs")], {
      env: { ...process.env, WEB_BUILD_DIR: join(temp, out) },
    });
    build("v1");
    const oldWorker = await readFile(join(temp, "v1/sw.js"), "utf8");
    await writeFile(join(source, "app.js"), (await readFile(join(source,"app.js"),"utf8"))+"\n// synthetic shell upgrade\n");
    build("v2");
    const worker = await readFile(join(temp, "v2/sw.js"), "utf8");
    assert.notEqual(worker, oldWorker);
    assert.ok(!worker.includes("__HONGSHU_SHELL_VERSION__"));
    const stores = new Map();
    const oldName = oldWorker.match(/const CACHE = "([^"]+)"/)[1];
    stores.set(oldName,new Map([["/app.js","old"]]));
    const handlers = {};
    const caches = {
      keys: async () => [...stores.keys()],
      delete: async key => stores.delete(key),
      open: async key => {
        if (!stores.has(key)) stores.set(key,new Map());
        const store = stores.get(key);
        return { addAll: async assets => {
          for (const path of assets) store.set(path,await readFile(join(temp,"v2",path==="/"?"index.html":path.slice(1)),"utf8"));
        } };
      },
      match: async request => [...stores.values()].map(store=>store.get(new URL(request.url).pathname)).find(Boolean),
    };
    const ctx = vm.createContext({ URL,caches,fetch:async()=>{throw Error("offline")},
      self:{location:{origin:"https://example.test"},clients:{claim:async()=>{}},skipWaiting:()=>{},addEventListener:(name,handler)=>handlers[name]=handler}});
    vm.runInContext(worker,ctx);
    let pending;
    handlers.install({waitUntil:promise=>pending=promise});await pending;
    handlers.activate({waitUntil:promise=>pending=promise});await pending;
    assert.equal(stores.size,1);
    handlers.fetch({request:{method:"GET",url:"https://example.test/app.js"},respondWith:promise=>pending=promise});
    assert.ok((await pending).includes("synthetic shell upgrade"));
    const shell = [...stores.values()][0];
    assert.ok(shell.has("/icon-192.png") && shell.has("/icon-512.png"));
    assert.ok([...shell.keys()].every(path=>!path.startsWith("/api")));
  } finally { await rm(temp,{recursive:true,force:true}); }
});
