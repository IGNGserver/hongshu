const CACHE = "hongshu-shell-v1";
const ASSETS = [
  "/",
  "/app.js",
  "/style.css",
  "/icon.svg",
  "/manifest.webmanifest",
];
self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)));
  self.skipWaiting();
});
self.addEventListener("activate", (e) =>
  e.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)),
        ),
      )
      .then(() => self.clients.claim()),
  ),
);
// Never cache API responses or SMS content, even when offline.
self.addEventListener("fetch", (e) => {
  if (
    e.request.method !== "GET" ||
    new URL(e.request.url).origin !== self.location.origin ||
    !ASSETS.includes(new URL(e.request.url).pathname)
  )
    return;
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
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
