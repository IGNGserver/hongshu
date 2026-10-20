const CACHE = "hongshu-shell-__HONGSHU_SHELL_VERSION__";
const ASSETS = [
  "/",
  "/app.js",
  "/style.css",
  "/icon.svg",
  "/manifest.webmanifest",
  "/icon-192.png",
  "/icon-512.png",
  "/icon-maskable-192.png",
  "/icon-maskable-512.png",
  "/apple-touch-icon.png",
  "/apple-touch-icon-167.png",
  "/apple-touch-icon-152.png",
  "/favicon-32.png",
  "/favicon-16.png",
];
self.addEventListener("install", (e) => {
  // Icons of every platform/size must not compete with the first useful view.
  const core = ASSETS.filter(path => path === "/" || /(?:app\.js|style\.css|icon\.svg|manifest\.webmanifest)$/.test(path));
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(core)));
});
self.addEventListener("message", e => {
  if (e.data?.type === "ACTIVATE_UPDATE") self.skipWaiting();
});
self.addEventListener("activate", (e) =>
  // Open old tabs still refer to their immutable files. Do not delete their
  // complete shell when a newer worker takes control after user consent.
  e.waitUntil(self.clients.claim()),
);
// Never cache API responses or SMS content, even when offline.
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  const immutable = /^\/assets\/[a-f0-9]{16}-[^/]+$/.test(url.pathname);
  if (
    e.request.method !== "GET" ||
    url.origin !== self.location.origin || url.search ||
    url.pathname.startsWith("/api/") ||
    (!ASSETS.includes(url.pathname) && !immutable && url.pathname !== "/index.html")
  )
    return;
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const key = url.pathname === "/index.html" ? "/" : e.request;
    const cached = await cache.match(key);
    if (cached) return cached;
    // Hashed files from older open documents are safe across shell versions.
    if (immutable) {
      const old = await caches.match(e.request);
      if (old) return old;
    }
    const response = await fetch(e.request);
    if (response.ok && (ASSETS.includes(url.pathname) || immutable)) {
      const update = cache.put(key, response.clone()).catch(() => {});
      e.waitUntil(update);
    }
    return response;
  })());
});
self.addEventListener("push", (e) => {
  e.waitUntil(
    self.registration.showNotification("鸿枢", {
      body: "有新短信，打开应用查看",
      tag: "hongshu-new",
      icon: "/icon-192.png",
      badge: "/icon-192.png",
      data: { url: "/" },
    }),
  );
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((clients) => {
        for (const c of clients) {
          if (new URL(c.url).origin === self.location.origin) return c.focus();
        }
        return self.clients.openWindow("/");
      }),
  );
});
