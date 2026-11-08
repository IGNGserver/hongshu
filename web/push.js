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
  const notify = self.registration.showNotification(title, {
    body,
    tag,
    icon: "/icon-192.png",
    badge: "/icon-192.png",
    data: { url },
  });
  // Push is an independent change hint when an open page's socket has gone stale.
  // Never fetch or persist SMS content in the service worker.
  const refresh = tag === "hongshu-new" ? self.clients
    .matchAll({ type: "window", includeUncontrolled: true })
    .then(clients => {
      for (const client of clients) {
        try { client.postMessage({ type: "MESSAGES_CHANGED" }); } catch { /* closed client */ }
      }
    }).catch(() => {}) : Promise.resolve();
  e.waitUntil(Promise.all([notify, refresh]));
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  let target = notificationPath(e.notification.data?.url);
  try {
    const candidate = new URL(target, self.location.origin);
    if (candidate.origin !== self.location.origin) target = "/";
    else target = candidate.pathname + candidate.search + candidate.hash;
  } catch { target = "/"; }
  e.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
      try {
        if (new URL(client.url).origin !== self.location.origin) continue;
        const navigated = await client.navigate(new URL(target, self.location.origin).href);
        await (navigated || client).focus?.();
        return;
      } catch { /* try another existing window before opening a new one */ }
    }
    const client = await self.clients.openWindow(target);
    await client?.focus?.();
  })());
});
// Safari/Chrome can rotate an endpoint without involving the page. Rebind it
// immediately; if the event is not supported, the next app start does the same.
self.addEventListener("pushsubscriptionchange", (e) => {
  e.waitUntil((async () => {
    const tell = async message => {
      const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of clients) { try { client.postMessage(message); } catch { /* closed client */ } }
    };
    try {
      let subscription = e.newSubscription;
      if (!subscription) {
        const me = await fetch("/api/me", { credentials: "same-origin", headers: { Accept: "application/json" } });
        if (!me.ok) throw new Error("push_session_unavailable");
        const session = await me.json();
        if (!session.vapid_public_key) throw new Error("push_not_configured");
        subscription = await self.registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: decodeKey(session.vapid_public_key),
        });
      }
      if (!subscription) throw new Error("push_subscription_unavailable");
      const json = subscription.toJSON();
      const bound = await fetch("/api/push", {
        method: "PUT",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ endpoint: json.endpoint, keys: json.keys }),
      });
      if (!bound.ok) throw new Error("push_binding_rejected");
      await tell({ type: "PUSH_SUBSCRIPTION_CHANGED" });
    } catch {
      // The next app start can reuse or replace this still-local subscription.
      // Never report a rotated endpoint as bound until the hub accepted it.
      await tell({ type: "PUSH_SUBSCRIPTION_FAILED" }).catch(() => {});
    }
  })());
});
