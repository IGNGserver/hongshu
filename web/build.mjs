import { readFile, mkdir, copyFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
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
  "local-contacts.js",
  "style.css",
  "sw.js",
  "manifest.webmanifest",
  "icon.svg",
  "icon-192.png",
  "icon-512.png",
  "icon-maskable-192.png",
  "icon-maskable-512.png",
  "apple-touch-icon.png",
  "apple-touch-icon-167.png",
  "apple-touch-icon-152.png",
  "favicon-32.png",
  "favicon-16.png",
];
const hash = createHash("sha256");
for (const file of assets) hash.update(file).update(await readFile(resolve(root, file)));
for (const file of assets)
  await copyFile(resolve(root, file), resolve(out, file));
// Keep legacy names for older clients, but new documents pin every resource to
// immutable bytes. Hash dependencies first so a shell cannot mix release files.
await mkdir(resolve(out, "assets"), { recursive: true });
const paths = {};
const rewrite = text => Object.entries(paths).reduce((s, [name, path]) => s.replaceAll("/" + name, path), text);
for (const file of assets.filter(f => !["index.html", "sw.js", "app.js", "style.css", "manifest.webmanifest"].includes(f)).concat(["style.css", "manifest.webmanifest", "app.js"])) {
  let bytes = await readFile(resolve(root, file));
  if (["app.js", "style.css", "manifest.webmanifest"].includes(file)) bytes = Buffer.from(rewrite(bytes.toString()));
  const digest = createHash("sha256").update(bytes).digest("hex").slice(0, 16);
  paths[file] = `/assets/${digest}-${file}`;
  await writeFile(resolve(out, paths[file].slice(1)), bytes);
  if (["app.js", "style.css", "manifest.webmanifest", "icon.svg"].includes(file))
    await writeFile(resolve(out, paths[file].slice(1)) + ".gz", gzipSync(bytes, { level: 9 }));
}
await writeFile(resolve(out, "index.html"), rewrite(await readFile(resolve(root, "index.html"), "utf8")));
let worker = (await readFile(resolve(root, "sw.js"), "utf8"))
  .replace("__HONGSHU_SHELL_VERSION__", hash.digest("hex").slice(0, 24));
worker = rewrite(worker);
await writeFile(resolve(out, "sw.js"), worker);
for (const file of ["index.html", "app.js", "style.css", "manifest.webmanifest", "icon.svg"]) {
  await writeFile(resolve(out, file) + ".gz", gzipSync(await readFile(resolve(out, file)), { level: 9 }));
}
console.log("Web production assets validated and copied");
