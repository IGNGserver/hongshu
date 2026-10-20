import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, cp, readFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { workerRuntime } from "./sw-harness.js";

test("content-only upgrade pins immutable dependencies, keeps old tabs and works offline", async () => {
  const temp = await mkdtemp(join(process.env.HONGSHU_TEST_TMP || tmpdir(), "hongshu-shell-"));
  try {
    const source = join(temp, "source");
    await cp(new URL("../", import.meta.url), source, { recursive: true });
    const build = out => execFileSync(process.execPath, [join(source, "build.mjs")], {
      env: { ...process.env, WEB_BUILD_DIR: join(temp, out) },
    });
    build("v1");
    const oldWorker = await readFile(join(temp, "v1/sw.js"), "utf8");
    await writeFile(join(source, "app.js"), (await readFile(join(source, "app.js"), "utf8")) + "\n// synthetic shell upgrade\n");
    build("v2");
    const worker = await readFile(join(temp, "v2/sw.js"), "utf8");
    assert.notEqual(worker, oldWorker);
    assert.ok(!worker.includes("__HONGSHU_SHELL_VERSION__"));
    const html = await readFile(join(temp, "v2/index.html"), "utf8");
    const paths = [...html.matchAll(/(?:href|src)="(\/assets\/[^"?]+)"/g)].map(m => m[1]);
    assert.ok(paths.length >= 8);
    for (const path of paths) {
      const bytes = await readFile(join(temp, "v2", path.slice(1)));
      assert.equal(path.split("/").at(-1).slice(0, 16), createHash("sha256").update(bytes).digest("hex").slice(0, 16));
    }
    const oldName = oldWorker.match(/const CACHE = "([^"]+)"/)[1];
    const stores = new Map([[oldName, new Map([["/app.js", { value: "old" }]])]]);
    const runtime = workerRuntime(worker, stores, async path => ({
      ok: true, value: await readFile(join(temp, "v2", path === "/" ? "index.html" : path.slice(1)), "utf8"),
      clone() { return this; },
    }));
    await runtime.install();
    assert.equal(runtime.skipped, false, "no unprompted activation across open tabs");
    await runtime.activate();
    assert.equal(stores.size, 2, "old version remains usable for old open pages");
    runtime.offline = true;
    const appPath = paths.find(path => path.endsWith("-app.js"));
    assert.ok((await runtime.request(appPath)).value.includes("synthetic shell upgrade"));
    assert.ok((await runtime.request("/")).value.includes(appPath));
    for (const store of stores.values()) assert.ok([...store.keys()].every(path => !path.startsWith("/api")));
    const shell = [...stores.values()].at(-1);
    assert.ok(![...shell.keys()].some(path => path.endsWith("-icon-512.png")), "optional icons do not block install");
    runtime.handlers.message({ data: { type: "ACTIVATE_UPDATE" } });
    assert.equal(runtime.skipped, true);
  } finally { await rm(temp, { recursive: true, force: true }); }
});
