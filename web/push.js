const notificationText = (value, fallback, max) =>
  typeof value === "string" && value.trim() && value.length <= max ? value : fallback;
const notificationPath = value =>
  typeof value === "string" && value.startsWith("/") && !value.startsWith("//") ? value : "/";
const decodeKey = key =>
  Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0));
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
