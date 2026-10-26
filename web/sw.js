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
self.addEventListener("message", event => {
  if (event.data?.type === "ACTIVATE_UPDATE") void self.skipWaiting();
});
self.addEventListener("activate", event => event.waitUntil((async () => {
  // Retire old shells only when no open document can still reference them.
  const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  if (!windows.length) {
    const current = "hongshu-shell-__HONGSHU_SHELL_VERSION__";
    await Promise.all((await caches.keys()).filter(key => key.startsWith("hongshu-shell-") && key !== current).map(key => caches.delete(key)));
  }
  await self.clients.claim();
})()));