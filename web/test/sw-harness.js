import vm from "node:vm";

export function workerRuntime(source, stores = new Map(), load = async () => ({ ok: true, value: "network", clone() { return this; } }), options = {}) {
  const runtime = { handlers: {}, offline: false, skipped: false, fetches: 0, requests: [], notifications: [], opened: null };
  const key = request => typeof request === "string" ? new URL(request, "https://example.test").pathname : new URL(request.url).pathname;
  const caches = {
    keys: async () => [...stores.keys()],
    delete: async name => stores.delete(name),
    open: async name => {
      if (!stores.has(name)) stores.set(name, new Map());
      const store = stores.get(name);
      return {
        match: async request => store.get(key(request)),
        put: async (request, response) => store.set(key(request), response),
        addAll: async paths => { for (const path of paths) store.set(path, await load(path)); },
      };
    },
    match: async request => [...stores.values()].map(store => store.get(key(request))).find(Boolean),
  };
  const registration = {
    showNotification: async (title, notificationOptions) => { runtime.notifications.push({ title, options: notificationOptions }); },
    pushManager: {
      getSubscription: async () => options.subscription ?? null,
      subscribe: async subscribeOptions => { runtime.subscribeOptions = subscribeOptions; return options.newSubscription ?? null; },
    },
  };
  const clients = {
    claim: async () => {},
    matchAll: async () => options.clients ?? [],
    openWindow: async url => { runtime.opened = url; return {}; },
  };
  const ctx = vm.createContext({ URL, Uint8Array, atob, caches,
    fetch: async (request, init = {}) => {
      runtime.fetches++;
      runtime.requests.push({ path: key(request), init });
      if (runtime.offline) throw Error("offline");
      return load(key(request));
    },
    self: { location: { origin: "https://example.test" }, clients, registration,
      skipWaiting: () => { runtime.skipped = true; }, addEventListener: (name, handler) => { runtime.handlers[name] = handler; } },
  });
  vm.runInContext(source, ctx);
  runtime.install = () => new Promise((resolve, reject) => runtime.handlers.install({ waitUntil: p => p.then(resolve, reject) }));
  runtime.activate = () => new Promise((resolve, reject) => runtime.handlers.activate({ waitUntil: p => p.then(resolve, reject) }));
  runtime.dispatch = async (name, event = {}) => {
    const updates = [];
    runtime.handlers[name]({ ...event, waitUntil: p => updates.push(p) });
    await Promise.all(updates);
  };
  runtime.push = data => runtime.dispatch("push", { data });
  runtime.subscriptionChange = event => runtime.dispatch("pushsubscriptionchange", event);
  runtime.click = notification => runtime.dispatch("notificationclick", { notification });
  runtime.request = async (path, method = "GET") => {
    let pending;
    const updates = [];
    runtime.handlers.fetch({ request: { method, url: new URL(path, "https://example.test").href }, respondWith: p => { pending = p; }, waitUntil: p => updates.push(p) });
    const response = await pending;
    await Promise.all(updates);
    return response;
  };
  return runtime;
}
