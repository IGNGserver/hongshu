import { readFile, mkdir, copyFile, writeFile, unlink } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import { build } from "esbuild";
import { injectManifest } from "workbox-build";
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
if (manifest.display !== "standalone" && manifest.display !== "fullscreen")
  throw new Error("Manifest must open as a standalone/fullscreen web app for iOS push");
if (typeof manifest.id !== "string" || manifest.id === "")
  throw new Error("Manifest must have a stable id for Home Screen web apps");
const icons = new Set((manifest.icons || []).map(icon => `${icon.sizes}:${icon.purpose || "any"}`));
for (const required of ["192x192:any", "512x512:any", "1024x1024:any", "192x192:maskable", "512x512:maskable", "1024x1024:maskable"])
  if (!icons.has(required)) throw new Error(`Manifest missing icon ${required}`);
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
  "icon-1024.png",
  "icon-maskable-192.png",
  "icon-maskable-512.png",
  "icon-maskable-1024.png",
  "apple-touch-icon.png",
  "apple-touch-icon-167.png",
  "apple-touch-icon-152.png",
  "apple-touch-icon-precomposed.png",
  "favicon-32.png",
  "favicon-16.png",
  "favicon.ico",
];
const hash = createHash("sha256");
for (const file of assets.concat(["push.js", "build.mjs", "package-lock.json"])) hash.update(file).update(await readFile(resolve(root, file)));
const revision = hash.digest("hex").slice(0, 24);
for (const file of assets)
  await copyFile(resolve(root, file), resolve(out, file));
// Keep legacy names for older clients, but new documents pin every resource to
// immutable bytes. Hash dependencies first so a shell cannot mix release files.
await mkdir(resolve(out, "assets"), { recursive: true });
const paths = {};
const rewrite = text => Object.entries(paths).reduce((s, [name, path]) =>
  s.replaceAll("./" + name, path).replaceAll("/" + name, path), text);
for (const file of assets.filter(f => !["index.html", "sw.js", "app.js", "style.css", "manifest.webmanifest"].includes(f)).concat(["style.css", "app.js"])) {
  let bytes = await readFile(resolve(root, file));
  if (["app.js", "style.css", "manifest.webmanifest"].includes(file)) bytes = Buffer.from(rewrite(bytes.toString()));
  const digest = createHash("sha256").update(bytes).digest("hex").slice(0, 16);
  paths[file] = `/assets/${digest}-${file}`;
  await writeFile(resolve(out, paths[file].slice(1)), bytes);
  if (["app.js", "style.css", "manifest.webmanifest", "icon.svg"].includes(file))
    await writeFile(resolve(out, paths[file].slice(1)) + ".gz", gzipSync(bytes, { level: 9 }));
}
// A stable manifest identity with versioned icon URLs works for both fresh
// installations and updates to an existing Home Screen app.
await writeFile(resolve(out, "manifest.webmanifest"), rewrite(await readFile(resolve(root, "manifest.webmanifest"), "utf8")));
await writeFile(resolve(out, "index.html"), rewrite(await readFile(resolve(root, "index.html"), "utf8"))
  .replace('name="hongshu-shell-revision" content="development"', `name="hongshu-shell-revision" content="${revision}"`));
await build({ entryPoints: [resolve(root, "sw.js")], outfile: resolve(out, "worker-source.js"), bundle: true,
  format: "iife", platform: "browser", target: ["safari15"], minify: false,
  define: { "process.env.NODE_ENV": '"production"' } });
const worker = (await readFile(resolve(out, "worker-source.js"), "utf8")).replaceAll("__HONGSHU_SHELL_VERSION__", revision);
await writeFile(resolve(out, "worker-source.js"), worker);
const injected = await injectManifest({
  swSrc: resolve(out, "worker-source.js"), swDest: resolve(out, "sw.js"), globDirectory: out, globPatterns: [],
  additionalManifestEntries: [ { url: "/", revision }, ...["app.js", "local-contacts.js", "style.css"].map(name => ({ url: paths[name], revision: null })) ],
});
if (injected.warnings.length) throw new Error(injected.warnings.join("\n"));
// This intermediate bundle is never a public asset.
await unlink(resolve(out, "worker-source.js"));
for (const file of ["index.html", "app.js", "style.css", "manifest.webmanifest", "icon.svg"]) {
  await writeFile(resolve(out, file) + ".gz", gzipSync(await readFile(resolve(out, file)), { level: 9 }));
}
console.log("Web production assets validated and copied");
