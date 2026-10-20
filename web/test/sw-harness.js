import vm from "node:vm";

export function workerRuntime(source, stores = new Map(), load = async () => ({ ok: true, value: "network", clone() { return this; } })) {
  const runtime = { handlers: {}, offline: false, skipped: false, fetches: 0 };
  const key = request => typeof request === "string" ? request : new URL(request.url).pathname;
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
  const ctx = vm.createContext({ URL, caches,
    fetch: async request => { runtime.fetches++; if (runtime.offline) throw Error("offline"); return load(key(request)); },
    self: { location: { origin: "https://example.test" }, clients: { claim: async () => {} },
      skipWaiting: () => { runtime.skipped = true; }, addEventListener: (name, handler) => { runtime.handlers[name] = handler; } },
  });
  vm.runInContext(source, ctx);
  runtime.install = () => new Promise((resolve, reject) => runtime.handlers.install({ waitUntil: p => p.then(resolve, reject) }));
  runtime.activate = () => new Promise((resolve, reject) => runtime.handlers.activate({ waitUntil: p => p.then(resolve, reject) }));
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
