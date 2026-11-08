import { setCacheNameDetails } from "workbox-core";
import { precacheAndRoute, createHandlerBoundToURL, addPlugins } from "workbox-precaching";
import { registerRoute } from "workbox-routing";
import { CacheFirst } from "workbox-strategies";
import { ExpirationPlugin } from "workbox-expiration";
import "./push.js";

// Separate release caches keep old documents and their immutable imports usable.
setCacheNameDetails({ prefix: "hongshu", precache: "shell", suffix: "__HONGSHU_SHELL_VERSION__" });
// A proxy returning an old HTML shell under a new release must fail installation,
// not pin the old document to the new revision for every subsequent offline launch.
addPlugins([{ cacheWillUpdate: async ({ request, response }) => {
  const path = new URL(request.url).pathname;
  if (!response.ok || response.redirected) throw new Error("pwa_public_asset_unavailable");
  const type = response.headers.get("Content-Type") || "";
  if (path === "/") {
    const html = await response.clone().text();
    if (!type.includes("text/html") || !html.includes('name="hongshu-shell-revision" content="__HONGSHU_SHELL_VERSION__"')) throw new Error("pwa_shell_revision_mismatch");
  } else if (path.endsWith(".js") && !/(?:java|ecma)script/i.test(type)) throw new Error("pwa_script_type_mismatch");
  else if (path.endsWith(".css") && !type.includes("text/css")) throw new Error("pwa_style_type_mismatch");
  return response;
} }]);
precacheAndRoute(self.__WB_MANIFEST, { ignoreURLParametersMatching: [], cleanURLs: false });
registerRoute(({ request, url }) => request.method === "GET" && request.mode === "navigate"
  && url.origin === self.location.origin && !url.search && url.pathname === "/index.html",
createHandlerBoundToURL("/"));
// Only content-addressed public build assets. Authenticated APIs, queries and
// foreign origins never match either this route or the exact precache list.
registerRoute(({ request, url }) => request.method === "GET"
  && url.origin === self.location.origin && !url.search
  && /^\/assets\/[a-f0-9]{16}-[^/]+$/.test(url.pathname),
new CacheFirst({ cacheName: "hongshu-public-assets-v1", plugins: [{
  cacheWillUpdate: async ({ response }) => response.ok && !response.redirected ? response : null,
  cachedResponseWillBeUsed: async ({ request, cachedResponse }) => cachedResponse || await caches.match(request),
}, new ExpirationPlugin({ maxEntries: 96, maxAgeSeconds: 31_536_000, purgeOnQuotaError: true })] }));
const SHELL_METADATA = "hongshu-shell-lifecycle-v1";
const SHELL_GRACE_MS = 7 * 24 * 60 * 60 * 1000;
async function rememberShellLease(clientId, revision) {
  if (!clientId || typeof revision !== "string" || !/^[a-f0-9]{24}$/.test(revision)) return;
  const metadata = await caches.open(SHELL_METADATA);
  const now = Date.now();
  await metadata.put(`/client/${clientId}`, new Response(JSON.stringify({ revision, at: now }), { headers: { "Content-Type": "application/json" } }));
  const key = `/shell/${revision}`;
  const previous = await metadata.match(key);
  let firstSeen = now;
  if (previous) {
    try { firstSeen = (await previous.json()).firstSeen || now; } catch { /* renew malformed metadata */ }
  }
  await metadata.put(key, new Response(JSON.stringify({ firstSeen, lastSeen: now }), { headers: { "Content-Type": "application/json" } }));
}
async function cleanupShells(current) {
  const metadata = await caches.open(SHELL_METADATA);
  const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  const active = new Set();
  let unknown = false;
  const now = Date.now();
  for (const client of windows) {
    const record = await metadata.match(`/client/${client.id}`);
    if (!record) { unknown = true; continue; }
    try {
      const lease = await record.json();
      if (now - lease.at <= 24 * 60 * 60 * 1000 && typeof lease.revision === "string") active.add(lease.revision);
      else unknown = true;
    } catch { unknown = true; }
  }
  for (const name of await caches.keys()) {
    if (!name.startsWith("hongshu-shell-") || name === current) continue;
    const revision = name.slice("hongshu-shell-".length);
    if (!/^[a-f0-9]{24}$/.test(revision) || active.has(revision)) continue;
    const key = `/shell/${revision}`;
    const record = await metadata.match(key);
    let lastSeen = now;
    if (record) {
      try {
        const metadataRecord = await record.json();
        lastSeen = metadataRecord.lastSeen || metadataRecord.firstSeen || now;
      } catch { /* restart grace below */ }
    } else {
      await metadata.put(key, new Response(JSON.stringify({ firstSeen: now, lastSeen: now }), { headers: { "Content-Type": "application/json" } }));
    }
    // Unknown open documents may predate leases. Keep every old revision until
    // they close; a closed revision still gets a full rollback/BFCache grace.
    if (!unknown && now - lastSeen >= SHELL_GRACE_MS) await caches.delete(name);
  }
}
self.addEventListener("message", event => {
  if (event.data?.type === "ACTIVATE_UPDATE") event.waitUntil?.(self.skipWaiting());
  if (event.data?.type === "SHELL_ACTIVE") event.waitUntil?.((async () => {
    try {
      await rememberShellLease(event.source?.id, event.data.revision);
      await cleanupShells("hongshu-shell-__HONGSHU_SHELL_VERSION__");
    } catch { /* storage pressure must not break messaging */ }
  })());
});
self.addEventListener("activate", event => event.waitUntil((async () => {
  try { await cleanupShells("hongshu-shell-__HONGSHU_SHELL_VERSION__"); } catch { /* next lease retries */ }
  await self.clients.claim();
})()));