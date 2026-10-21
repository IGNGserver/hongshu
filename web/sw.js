const CACHE = "hongshu-shell-__HONGSHU_SHELL_VERSION__";
const notificationText = (value, fallback, max) =>
  typeof value === "string" && value.trim() && value.length <= max ? value : fallback;
const notificationPath = value =>
  typeof value === "string" && value.startsWith("/") && !value.startsWith("//") ? value : "/";
const decodeKey = key =>
  Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0));
const ASSETS = [
  "/",
  "/app.js",
  "/local-contacts.js",
  "/style.css",
  "/icon.svg",
  "/manifest.webmanifest",
  "/icon-192.png",
  "/icon-512.png",
  "/icon-1024.png",
  "/icon-maskable-192.png",
  "/icon-maskable-512.png",
  "/icon-maskable-1024.png",
  "/apple-touch-icon.png",
  "/apple-touch-icon-167.png",
  "/apple-touch-icon-152.png",
  "/apple-touch-icon-precomposed.png",
  "/favicon-32.png",
  "/favicon-16.png",
  "/favicon.ico",
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
  let payload = {};
  try { payload = e.data?.json() ?? {}; } catch { /* generic notification below */ }
  const title = notificationText(payload.title, "鸿枢", 100);
  const body = notificationText(payload.body, "有新短信，打开应用查看", 200);
  const tag = notificationText(payload.tag, "hongshu-new", 100);
  const url = notificationPath(payload.url);
  e.waitUntil(
    self.registration.showNotification(title, {
      body,
      tag,
      icon: "/icon-192.png",
      badge: "/icon-192.png",
      data: { url },
    }),
  );
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const url = notificationPath(e.notification.data?.url);
  e.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((clients) => {
        for (const c of clients) {
          if (new URL(c.url).origin === self.location.origin) return c.focus();
        }
        return self.clients.openWindow(url);
      }),
  );
});
// Safari/Chrome can rotate an endpoint without involving the page. Rebind it
// immediately; if the event is not supported, the next app start does the same.
self.addEventListener("pushsubscriptionchange", (e) => {
  e.waitUntil((async () => {
    try {
      let subscription = e.newSubscription;
      if (!subscription) {
        const me = await fetch("/api/me", { credentials: "same-origin", headers: { Accept: "application/json" } });
        if (!me.ok) return;
        const session = await me.json();
        if (!session.vapid_public_key) return;
        subscription = await self.registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: decodeKey(session.vapid_public_key),
        });
      }
      if (!subscription) return;
      const json = subscription.toJSON();
      await fetch("/api/push", {
        method: "PUT",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ endpoint: json.endpoint, keys: json.keys }),
      });
    } catch { /* next app start retries the binding */ }
  })());
});
