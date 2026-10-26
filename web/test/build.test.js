import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, cp, readFile, writeFile, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";


test("content-only upgrade pins a new public graph and a stable manifest identity", async () => {
  const temp = await mkdtemp(join(process.env.HONGSHU_TEST_TMP || tmpdir(), "hongshu-shell-"));
  try {
    const source = join(temp, "source");
    await cp(new URL("../", import.meta.url), source, { recursive: true, filter: path => !path.includes("node_modules") });
    await symlink(new URL("../node_modules", import.meta.url), join(source, "node_modules"), "dir");
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
    const appPath = paths.find(path => path.endsWith("-app.js"));
    const app = await readFile(join(temp, "v2", appPath.slice(1)), "utf8");
    const contactPath = app.match(/from "(\/assets\/[a-f0-9]{16}-local-contacts\.js)"/)?.[1];
    assert.ok(contactPath, "hashed modules must import their absolute immutable dependency");
    await readFile(join(temp, "v2", contactPath.slice(1)));
    assert.equal(html.match(/rel="manifest" href="([^"]+)"/)[1], "/manifest.webmanifest");
    const manifest = JSON.parse(await readFile(join(temp, "v2/manifest.webmanifest"), "utf8"));
    assert.equal(manifest.id, "/");
    assert.equal(manifest.scope, "/");
    for (const icon of manifest.icons) {
      const bytes = await readFile(join(temp, "v2", icon.src.slice(1)));
      assert.equal(bytes.subarray(1, 4).toString(), "PNG");
      assert.equal(`${bytes.readUInt32BE(16)}x${bytes.readUInt32BE(20)}`, icon.sizes);
    }
    assert.match(worker, /workbox/);
    assert.ok(!worker.includes("self.__WB_MANIFEST"), "build injects the final public graph");
    assert.ok(worker.includes(appPath));
    assert.ok(!worker.includes('url: "/api/'), "authenticated responses are outside the precache");
    const oldApp = (await readFile(join(temp, "v1/index.html"), "utf8")).match(/src="(\/assets\/[^\"]+-app.js)"/)[1];
    assert.notEqual(oldApp, appPath);
  } finally { await rm(temp, { recursive: true, force: true }); }
});
