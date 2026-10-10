export const query = (values) =>
  new URLSearchParams(
    Object.entries(values).filter(([, v]) => v !== "" && v !== undefined),
  ).toString();

export function mergeMessages(previous, incoming) {
  const m = new Map(previous.map((v) => [v.id, v]));
  for (const v of incoming) m.set(v.id, v);
  return [...m.values()].sort((a, b) => (a.timestamp - b.timestamp) || a.id - b.id);
}

export function epochReset(seen, stored, observed) {
  return seen && stored !== observed;
}

export function pushKey(key) {
  return Uint8Array.from(
    atob(key.replaceAll("-", "+").replaceAll("_", "/")),
    (c) => c.charCodeAt(0),
  );
}

const $ = (s) => document.querySelector(s);
const state = {
  me: null,
  devices: [],
  sims: [],
  page: "inbox",
  sender: "",
  receiver: "",
  q: "",
  sim: "",
  device: "",
  conversations: [],
  messages: [],
  offset: 0,
  cursor: 0,
  epoch: 0,
  epochSeen: false,
  ws: null,
  generation: 0,
  loggedOut: false,
  busy: false,
};

const errors = {
  unauthorized: "登录状态已失效，请重新输入中枢密码",
  invalid_pairing: "配对码无效或已过期",
  invalid_password: "中枢密码不正确",
  rate_limited: "请求过于频繁，请稍后重试",
  push_not_configured: "部署者尚未配置 Web Push",
  origin_denied: "PUBLIC_URL 与访问地址不一致",
  invalid_push_endpoint: "浏览器推送地址不可达或不被允许",
};

function remoteHTTP() {
  return (
    location.protocol === "http:" &&
    !["localhost", "127.0.0.1", "::1", "[::1]"].includes(location.hostname)
  );
}

let toastTimer = null;
function toast(text) {
  const t = $("#toast");
  if (!t) return;
  t.textContent = text;
  t.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("visible"), 4000);
}

function setBusy(b) {
  state.busy = b;
  const p = $("#global-progress");
  if (p) {
    p.style.display = b ? "block" : "none";
  }
}

async function api(path, method = "GET", body) {
  const res = await fetch("/api" + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
    credentials: "same-origin",
  });
  const data = await res.json();
  if (!res.ok) {
    if (res.status === 401 && state.me) {
      disconnect();
      state.me = null;
      authView();
    }
    throw new Error(errors[data.error] || data.error || "请求失败");
  }
  return data;
}

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}

function button(text, fn, cls = "") {
  const b = el("button", cls, text);
  b.type = "button";
  b.onclick = () => {
    setBusy(true);
    Promise.resolve(fn())
      .catch((e) => toast(e.message))
      .finally(() => setBusy(false));
  };
  return b;
}

function date(t) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    year: "numeric",
  }).format(new Date(t));
}

function authView() {
  const root = $("#app");
  root.replaceChildren();
  const card = el("main", "auth card");
  card.append(
    el("h1", "", "鸿枢 Hongshu"),
    el("p", "muted", "所有手机的短信，在自己的中枢相遇。"),
  );
  if (remoteHTTP()) {
    card.append(
      el(
        "p",
        "security-warning",
        "警告：当前使用明文 HTTP。密码、短信和登录凭据未加密，可能被窃听或篡改；请优先使用 HTTPS 或 VPN。",
      ),
    );
  }
  const form = el("form");
  const password = el("input");
  password.required = true;
  password.type = "password";
  password.maxLength = 1024;
  password.autocomplete = "current-password";
  const label = el("label", "", "中枢密码");
  label.append(password);
  form.append(label);
  const submit = el("button", "primary", "登录");
  submit.type = "submit";
  form.append(submit);
  form.onsubmit = async (e) => {
    e.preventDefault();
    submit.disabled = true;
    setBusy(true);
    try {
      await api("/login", "POST", { password: password.value });
      password.value = "";
      await start();
    } catch (err) {
      toast(err.message);
    } finally {
      submit.disabled = false;
      setBusy(false);
    }
  };
  card.append(
    form,
    el(
      "small",
      "",
      "单用户中枢，无需用户名。Android 设备仍使用管理员生成的一次性配对码；短信不会离线保存在浏览器。",
    ),
  );
  root.append(card);
}

function shell() {
  const root = $("#app");
  root.replaceChildren();

  // Wavy Indeterminate Progress Indicator (Expressive Feedback)
  const progContainer = el("div", "progress-container");
  progContainer.id = "global-progress";
  progContainer.style.display = state.busy ? "block" : "none";
  progContainer.append(el("div", "progress-bar-wavy"));

  const container = el("div", "shell");
  const rail = el("aside", "rail");
  const brand = el("div", "brand");
  brand.append(el("span", "mark", "枢"));
  const brandLabel = el("div");
  brandLabel.append(
    el("strong", "", state.me.title),
    el("div", "muted", "你的私人短信中枢"),
  );
  brand.append(brandLabel);
  rail.append(brand);

  const nav = el("nav");
  nav.setAttribute("aria-label", "主导航");
  for (const [p, t] of [
    ["inbox", "收件箱"],
    ["devices", "设备"],
    ["settings", "设置"],
  ]) {
    nav.append(
      button(
        t,
        async () => {
          state.page = p;
          await reloadMetadata();
          shell();
        },
        state.page === p ? "active" : "",
      ),
    );
  }
  rail.append(nav);

  const foot = el("div", "foot");
  foot.append(
    el("div", "status", "连接中"),
    el("p", "muted", "历史长期保留 · 通知内容保护"),
  );
  rail.append(foot);

  const main = el("main", "main");
  main.append(progContainer);
  if (remoteHTTP()) {
    main.append(
      el(
        "p",
        "security-warning",
        "当前 HTTP 连接未加密：密码、短信和登录凭据可能被窃听或篡改。浏览器离线应用与 Web Push 需要 HTTPS。",
      ),
    );
  }
  container.append(rail, main);
  root.append(container);

  if (state.page === "inbox") inboxView();
  if (state.page === "devices") devicesView();
  if (state.page === "settings") settingsView();

  setStatus(
    state.ws?.readyState === 1 ? "实时连接" : "自动补齐",
    state.ws?.readyState === 1,
  );
}

function setStatus(text, online) {
  const n = $(".status");
  if (n) {
    n.textContent = text;
    n.className = "status" + (online ? " online" : "");
  }
}

function selectFilter(label, items, value, change) {
  const s = el("select");
  s.setAttribute("aria-label", label);
  const all = el("option", "", label);
  all.value = "";
  s.append(all);
  for (const [v, t] of items) {
    const o = el("option", "", t);
    o.value = v;
    s.append(o);
  }
  s.value = value;
  s.onchange = () => {
    change(s.value);
    state.offset = 0;
    void refreshInbox().catch((e) => toast(e.message));
  };
  return s;
}

function inboxView() {
  const main = $(".main");
  const head = el("header", "page-head");
  const title = el("div");
  title.append(el("h1", "", "短信收件箱"), el("small", "", "跨设备历史会话"));
  head.append(
    title,
    button("刷新", () => refreshInbox()),
  );
  main.append(head);

  const layout = el("section", "layout" + (state.sender ? " has-detail" : ""));
  const inbox = el("div", "inbox");
  const search = el("input", "search");
  search.type = "search";
  search.placeholder = "搜索联系人、号码或短信";
  search.setAttribute("aria-label", "搜索短信");
  search.value = state.q;
  let timer;
  search.oninput = () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      state.q = search.value;
      state.offset = 0;
      refreshInbox().catch((e) => toast(e.message));
    }, 250);
  };
  inbox.append(search);

  const f = el("div", "filters");
  const phones = [
    ...new Map(
      state.sims.map((s) => [s.phone, [s.phone, s.label || s.phone]]),
    ).values(),
  ];
  f.append(
    selectFilter("所有 SIM", phones, state.sim, (v) => (state.sim = v)),
    selectFilter(
      "所有设备",
      state.devices.filter((d) => !d.revoked).map((d) => [d.id, d.name]),
      state.device,
      (v) => (state.device = v),
    ),
  );
  inbox.append(f, el("div", "list"));
  layout.append(inbox, el("article", "detail"));
  main.append(layout);

  renderConversations();
  renderDetail();
  void refreshInbox().catch((e) => toast(e.message));
}

let inboxAbortController = null;
let refreshGeneration = 0;
async function refreshInbox() {
  if (!state.me) return;
  if (inboxAbortController) {
    inboxAbortController.abort();
  }
  inboxAbortController = new AbortController();
  const signal = inboxAbortController.signal;

  const gen = ++refreshGeneration;
  try {
    setBusy(true);
    await reloadMetadata();
    if (signal.aborted) return;
    const filters = $(".filters");
    if (filters && gen === refreshGeneration) {
      const phones = [
        ...new Map(
          state.sims.map((s) => [s.phone, [s.phone, s.label || s.phone]]),
        ).values(),
      ];
      filters.replaceChildren(
        selectFilter("所有 SIM", phones, state.sim, (v) => (state.sim = v)),
        selectFilter(
          "所有设备",
          state.devices.filter((d) => !d.revoked).map((d) => [d.id, d.name]),
          state.device,
          (v) => (state.device = v),
        ),
      );
    }
    const args = {
      q: state.q,
      sim: state.sim,
      device: state.device,
      offset: state.offset,
      limit: 100,
    };
    const d = await api("/conversations?" + query(args));
    if (signal.aborted || gen !== refreshGeneration) return;
    state.conversations = d.conversations;
    renderConversations();
    if (state.sender) await loadHistory(false);
  } catch (err) {
    if (err.name === "AbortError" || signal.aborted) return;
    throw err;
  } finally {
    setBusy(false);
  }
}

function renderConversations() {
  const list = $(".list");
  if (!list) return;
  list.replaceChildren();
  if (!state.conversations.length) {
    list.append(
      el("div", "empty", "暂无短信。请配置 Android 采集设备及 SIM 号码。"),
    );
    return;
  }
  for (const m of state.conversations) {
    const b = button(
      "",
      async () => {
        state.sender = m.sender;
        state.receiver = m.receiver;
        state.messages = [];
        $(".layout")?.classList.add("has-detail");
        renderConversations();
        await loadHistory(false);
      },
      "conversation" +
        (state.sender === m.sender && state.receiver === m.receiver
          ? " selected"
          : ""),
    );
    const avatar = el("span", "avatar", (m.contact || m.sender).slice(0, 1));
    const preview = el("div", "preview");
    preview.append(
      el("strong", "", m.contact || m.sender),
      el("p", "", [m.receiver, m.body].filter(Boolean).join(" · ")),
      el("small", "", date(m.timestamp)),
    );
    b.append(avatar, preview);
    list.append(b);
  }
  const controls = el("div", "row");
  controls.style.padding = "1rem";
  if (state.offset > 0) {
    controls.append(
      button("上一页", () => {
        state.offset = Math.max(0, state.offset - 100);
        return refreshInbox();
      }),
    );
  }
  if (state.conversations.length === 100) {
    controls.append(
      button("下一页", () => {
        state.offset += 100;
        return refreshInbox();
      }),
    );
  }
  if (controls.children.length > 0) {
    list.append(controls);
  }
}

let historyAbortController = null;
async function loadHistory(older) {
  const sender = state.sender;
  const receiver = state.receiver;
  if (!older) {
    if (historyAbortController) historyAbortController.abort();
    historyAbortController = new AbortController();
  }
  const signal = historyAbortController?.signal;

  const args = {
    sender,
    sim: receiver || state.sim,
    device: state.device,
    q: state.q,
    limit: 100,
  };
  if (older && state.messages.length) args.before = state.messages[0].id;
  try {
    setBusy(true);
    const d = await api("/messages?" + query(args));
    if (signal?.aborted || sender !== state.sender || receiver !== state.receiver) {
      return;
    }
    state.messages = older
      ? mergeMessages(state.messages, d.messages)
      : mergeMessages([], d.messages);
    renderDetail();
    if (!older) {
      const n = $(".messages");
      if (n) n.scrollTop = n.scrollHeight;
    }
  } catch (err) {
    if (err.name === "AbortError" || signal?.aborted) return;
    throw err;
  } finally {
    setBusy(false);
  }
}

function renderDetail() {
  const detail = $(".detail");
  if (!detail) return;
  detail.replaceChildren();
  if (!state.sender) {
    detail.append(el("div", "empty", "选择一个会话，查看完整历史"));
    return;
  }
  const h = el("header", "detail-head");
  h.append(
    button(
      "返回",
      () => {
        state.sender = "";
        state.receiver = "";
        $(".layout")?.classList.remove("has-detail");
        renderDetail();
      },
      "back",
    ),
  );
  const title = el("div");
  const m = state.messages.at(-1);
  title.append(
    el("strong", "", m?.contact || state.sender),
    el("div", "muted", [state.sender, state.receiver].filter(Boolean).join(" · ")),
  );
  h.append(title);
  detail.append(h);

  const messages = el("div", "messages");
  messages.append(button("加载更早记录", () => loadHistory(true)));
  if (!state.messages.length) {
    messages.append(el("p", "empty", "没有符合筛选条件的消息"));
  }
  for (const msg of state.messages) {
    const b = el("div", "bubble");
    b.append(document.createTextNode(msg.body));
    const name =
      state.devices.find((d) => d.id === msg.device_id)?.name || "来源设备";
    b.append(
      el(
        "div",
        "metadata",
        date(msg.timestamp) + " · " + msg.receiver + " · " + name,
      ),
    );
    messages.append(b);
  }
  detail.append(messages);
}

async function devicesView() {
  const main = $(".main");
  main.append(el("h1", "", "设备与 SIM"));

  if (state.me.device.admin) {
    const c = el("section", "card stack");
    c.append(
      el("h2", "", "连接 Android 设备"),
      el(
        "p",
        "muted",
        "Android 使用一次性配对码绑定，有效期 10 分钟。不要通过公开渠道分享。其他浏览器直接使用中枢密码登录。",
      ),
      button(
        "生成配对码",
        async () => {
          const v = await api("/pairings", "POST", {});
          const n = el("p", "code", v.code);
          c.append(n, el("small", "", "有效至 " + date(v.expires_at)));
        },
        "primary",
      ),
    );
    main.append(c);
  }

  const grid = el("div", "cards");
  for (const d of state.devices) {
    const c = el("section", "card stack");
    const head = el("div", "row spread");
    head.append(
      el("h2", "", d.name),
      el(
        "span",
        "badge",
        d.kind + (d.admin ? " · 管理员" : "") + (d.revoked ? " · 已撤销" : ""),
      ),
    );
    c.append(head);

    const sims = state.sims.filter((s) => s.device_id === d.id);
    for (const s of sims) {
      c.append(el("p", "muted", (s.label || "SIM") + " · " + s.phone));
    }

    if (!d.revoked && (state.me.device.admin || d.id === state.me.device.id)) {
      const row = el("div", "row");
      row.append(
        button("重命名", async () => {
          const name = prompt("设备名称", d.name);
          if (!name?.trim()) return;
          await api("/devices/" + d.id, "PATCH", { name: name.trim() });
          await reloadMetadata();
          shell();
        }),
      );
      row.append(
        button(d.notify ? "关闭通知" : "开启通知", async () => {
          await api("/devices/" + d.id, "PATCH", { notify: !d.notify });
          await reloadMetadata();
          shell();
        }),
      );
      if (d.kind === "android") {
        row.append(
          button(d.upload ? "禁止上传" : "允许上传", async () => {
            await api("/devices/" + d.id, "PATCH", { upload: !d.upload });
            await reloadMetadata();
            shell();
          }),
        );
      }
      if (state.me.device.admin && d.id !== state.me.device.id) {
        row.append(
          button(
            "撤销授权",
            async () => {
              if (!confirm("撤销 " + d.name + "？历史短信仍保留。")) return;
              await api("/devices/" + d.id, "DELETE");
              await reloadMetadata();
              shell();
            },
            "danger",
          ),
        );
      }
      c.append(row);
    }
    grid.append(c);
  }
  main.append(grid);
}

function settingsView() {
  const main = $(".main");
  main.append(el("h1", "", "中枢设置"));

  const card = el("section", "card stack");
  card.append(
    el("h2", "", "浏览器通知"),
    el(
      "p",
      "muted",
      "在线使用实时连接，后台通过标准 Web Push（无需 Firebase）。Web Push、Service Worker 和可安装离线应用需要 HTTPS 或 localhost；公网 HTTP 不支持。",
    ),
  );
  const notifyRow = el("div", "row");
  notifyRow.append(
    button("启用安全通知", enablePush, "primary"),
    button("关闭浏览器推送", async () => {
      await api("/push", "DELETE");
      if (window.isSecureContext && "serviceWorker" in navigator) {
        const reg = await navigator.serviceWorker.getRegistration();
        await (await reg?.pushManager.getSubscription())?.unsubscribe();
      }
      toast("已关闭浏览器推送");
    }),
  );
  card.append(notifyRow);
  main.append(card);

  if (state.me.device.admin) {
    const c = el("section", "card stack");
    c.append(el("h2", "", "中枢名称"));
    const input = el("input");
    input.value = state.me.title;
    input.maxLength = 100;
    input.setAttribute("aria-label", "中枢名称");
    c.append(
      input,
      button("保存名称", async () => {
        await api("/settings", "PATCH", { title: input.value });
        state.me.title = input.value;
        shell();
      }),
    );
    main.append(c);
  }

  const privacy = el("section", "card stack");
  privacy.append(
    el("h2", "", "隐私与存储"),
    el(
      "p",
      "",
      "历史短信不会自动清理。MySQL 与备份是权威数据源。此浏览器不会离线持久保存短信正文。",
    ),
    el(
      "p",
      "muted",
      "通知只显示泛化提示。首版是收件/查看平台，不支持发送短信、MMS 或 RCS。",
    ),
    button("退出此浏览器", async () => {
      await api("/logout", "POST", {});
      disconnect();
      state.me = null;
      state.messages = [];
      state.conversations = [];
      authView();
    }),
  );
  main.append(privacy);
}

async function enablePush() {
  if (!window.isSecureContext) {
    throw new Error("Web Push 需要 HTTPS 或 localhost；公网 HTTP 不支持安全上下文");
  }
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
    throw new Error("当前浏览器不支持 Web Push");
  }
  if (!state.me.vapid_public_key) throw new Error("部署者尚未配置 VAPID");
  if ((await Notification.requestPermission()) !== "granted") {
    throw new Error("请在浏览器设置中允许通知");
  }
  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: pushKey(state.me.vapid_public_key),
    });
  }
  const json = sub.toJSON();
  await api("/push", "PUT", { endpoint: json.endpoint, keys: json.keys });
  await api("/devices/" + state.me.device.id, "PATCH", { notify: true });
  toast("已启用隐私通知");
}

async function reloadMetadata() {
  const [d, s] = await Promise.all([api("/devices"), api("/sims")]);
  state.devices = d.devices;
  state.sims = s.sims;
}

function disconnect() {
  state.loggedOut = true;
  state.generation++;
  state.ws?.close();
  state.ws = null;
  refreshGeneration++;
}

function realtime() {
  const gen = state.generation;
  const ws = new WebSocket(location.origin.replace(/^http/, "ws") + "/api/ws");
  state.ws = ws;
  ws.onopen = () => {
    setStatus("实时连接", true);
    void catchUp().catch((e) => toast(e.message));
  };
  ws.onmessage = () => void catchUp().catch((e) => toast(e.message));
  ws.onclose = () => {
    setStatus("断线，等待补齐", false);
    if (!state.loggedOut && gen === state.generation) {
      setTimeout(realtime, 5000 + Math.random() * 2000);
    }
  };
  ws.onerror = () => ws.close();
}

let syncing = false;
async function catchUp() {
  if (syncing || !state.me) return;
  syncing = true;
  try {
    const me = await api("/me");
    let changed = false;
    if (epochReset(state.epochSeen, state.epoch, me.epoch)) {
      state.messages = [];
      state.conversations = [];
      state.cursor = 0;
      changed = true;
    }
    state.epoch = me.epoch;
    state.epochSeen = true;
    for (let i = 0; i < 100; i++) {
      const d = await api(
        "/sync?" + query({ after: state.cursor, limit: 200 }),
      );
      if (epochReset(state.epochSeen, state.epoch, d.epoch)) {
        state.messages = [];
        state.conversations = [];
        state.cursor = 0;
        state.epoch = d.epoch;
        state.epochSeen = true;
        changed = true;
        continue;
      }
      if (d.messages.length) changed = true;
      state.cursor = d.cursor;
      state.epoch = d.epoch;
      state.epochSeen = true;
      if (!d.more) break;
    }
    if (state.page === "inbox") await refreshInbox();
  } finally {
    syncing = false;
  }
}

async function start() {
  state.me = await api("/me");
  state.cursor = 0;
  state.epoch = state.me.epoch;
  state.epochSeen = true;
  state.loggedOut = false;
  state.generation++;
  await reloadMetadata();
  shell();
  if ("serviceWorker" in navigator) {
    void navigator.serviceWorker
      .register("/sw.js")
      .catch(() => toast("离线应用壳注册失败"));
  }
  realtime();
}

if (typeof document !== "undefined") {
  setInterval(() => {
    if (state.me && document.visibilityState === "visible") {
      void catchUp().catch(() => setStatus("离线，历史暂不可用", false));
    }
  }, 30000);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && state.me) {
      void catchUp().catch(() => {});
    }
  });
  start().catch(() => authView());
}
