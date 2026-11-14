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
  const otp = typeof payload.otp === "string" && payload.otp.trim() ? notificationText(payload.otp, "", 32) : "";
  const sender = typeof payload.sender === "string" && payload.sender.trim() ? notificationText(payload.sender, "", 120) : "";
  const messageId = typeof payload.message_id === "number" && Number.isInteger(payload.message_id) && payload.message_id > 0 ? payload.message_id : 0;
  const isTest = tag === "hongshu-test";
  const options = {
    body,
    tag,
    icon: "/icon-192.png",
    badge: "/icon-192.png",
    renotify: Boolean(payload.renotify),
    data: { url, sender, otp, messageId, body },
  };
  if (otp) {
    options.actions = [{ action: "copy", title: `复制 ${otp}` }];
  }
  const notify = self.registration.showNotification(title, options);
  // Push is an independent change hint when an open page's socket has gone stale.
  // Never fetch or persist SMS content in the service worker.
  const refresh = !isTest ? self.clients
    .matchAll({ type: "window", includeUncontrolled: true })
    .then(clients => {
      for (const client of clients) {
        try { client.postMessage({ type: "MESSAGES_CHANGED" }); } catch { /* closed client */ }
      }
    }).catch(() => {}) : Promise.resolve();
  const badge = typeof payload.unread_count === "number"
    ? Promise.resolve(
        payload.unread_count > 0
          ? self.navigator?.setAppBadge?.(payload.unread_count)
          : self.navigator?.clearAppBadge?.()
      ).catch(() => {})
    : Promise.resolve();
  e.waitUntil(Promise.all([notify, refresh, badge]));
});
self.addEventListener("notificationclick", (e) => {
  const data = e.notification.data || {};
  const action = e.action || "";
  e.notification.close();
  let candidate;
  try {
    candidate = new URL(notificationPath(data.url), self.location.origin);
    if (candidate.origin !== self.location.origin) candidate = new URL("/", self.location.origin);
  } catch {
    candidate = new URL("/", self.location.origin);
  }
  const target = candidate.pathname + candidate.search + candidate.hash;
  e.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
      try {
        const clientUrl = new URL(client.url);
        if (clientUrl.origin !== self.location.origin) continue;
        let active = client;
        if (clientUrl.pathname + clientUrl.search !== candidate.pathname + candidate.search && typeof client.navigate === "function") {
          active = (await client.navigate(candidate.href)) || client;
        }
        active.postMessage?.({
          type: "NOTIFICATION_OPEN",
          url: target,
          action,
          otp: typeof data.otp === "string" ? data.otp : "",
          sender: typeof data.sender === "string" ? data.sender : "",
          messageId: typeof data.messageId === "number" ? data.messageId : 0,
          body: typeof data.body === "string" ? data.body : (e.notification.body || ""),
        });
        await active.focus?.();
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
