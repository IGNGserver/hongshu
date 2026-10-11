import { readFile, mkdir, copyFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
const root = dirname(fileURLToPath(import.meta.url));
if (!process.env.WEB_BUILD_DIR)
  throw new Error(
    "Set WEB_BUILD_DIR to a shared build cache (not the source worktree)",
  );
const out = resolve(process.env.WEB_BUILD_DIR);
if (out === root) throw new Error("Output must differ from source");
const manifest = JSON.parse(
  await readFile(resolve(root, "manifest.webmanifest"), "utf8"),
);
if (manifest.start_url !== "/") throw new Error("Invalid manifest");
await mkdir(out, { recursive: true });
const assets = [
  "index.html",
  "app.js",
  "style.css",
  "sw.js",
  "manifest.webmanifest",
  "icon.svg",
  "icon-192.png",
  "icon-512.png",
];
const hash = createHash("sha256");
for (const file of assets) hash.update(file).update(await readFile(resolve(root, file)));
for (const file of assets)
  await copyFile(resolve(root, file), resolve(out, file));
const worker = (await readFile(resolve(root, "sw.js"), "utf8")).replace("__HONGSHU_SHELL_VERSION__", hash.digest("hex").slice(0, 24));
await writeFile(resolve(out, "sw.js"), worker);
console.log("Web production assets validated and copied");
