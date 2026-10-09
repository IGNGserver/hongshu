import { readFile, mkdir, copyFile } from "node:fs/promises";
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
for (const file of [
  "index.html",
  "app.js",
  "style.css",
  "sw.js",
  "manifest.webmanifest",
  "icon.svg",
  "icon-192.png",
  "icon-512.png",
])
  await copyFile(resolve(root, file), resolve(out, file));
console.log("Web production assets validated and copied");
