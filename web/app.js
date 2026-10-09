export const query = (values) =>
  new URLSearchParams(
    Object.entries(values).filter(([, v]) => v !== "" && v !== undefined),
  ).toString();
export function mergeMessages(previous, incoming) {
  const m = new Map(previous.map((v) => [v.id, v]));
  for (const v of incoming) m.set(v.id, v);
  return [...m.values()].sort((a, b) => (a.timestamp - b.timestamp) || a.id - b.id);
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
  q: "",
  sim: "",
  device: "",
  conversations: [],
  messages: [],
  offset: 0,
  cursor: 0,
  ws: null,
  generation: 0,
  loggedOut: false,
};
const errors = {
  unauthorized: "授权失效，请重新配对",
  invalid_pairing: "配对码无效或已过期",
  already_initialized: "中枢已初始化，请使用配对码",
  invalid_secret: "初始化密钥不正确",
  rate_limited: "请求过于频繁，请稍后重试",
  last_admin: "请先配对另一个管理员浏览器，再撤销授权或退出最后一个管理员",
  push_not_configured: "部署者尚未配置 Web Push",
  origin_denied: "PUBLIC_URL 与访问地址不一致",
  invalid_push_endpoint: "浏览器推送地址不可达或不被允许",
};
function toast(text) {
  $("#toast").textContent = text;
  $("#toast").className = "visible";
  setTimeout(() => ($("#toast").className = ""), 5000);
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
  b.onclick = () => Promise.resolve(fn()).catch((e) => toast(e.message));
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
  const mode = el("select");
  mode.setAttribute("aria-label", "连接方式");
  for (const [v, t] of [
    ["pair", "使用配对码连接"],
    ["bootstrap", "首次初始化中枢"],
  ]) {
    const o = el("option", "", t);
    o.value = v;
    mode.append(o);
  }
  card.append(mode);
  const form = el("form");
  const name = el("input");
  name.required = true;
  name.maxLength = 100;
  name.value = "我的浏览器";
  const code = el("input");
  code.required = true;
  code.type = "password";
  code.autocomplete = "off";
  for (const [text, input] of [
    ["设备名称", name],
    ["配对码 / 初始化密钥", code],
  ]) {
    const l = el("label", "", text);
    l.append(input);
    form.append(l);
  }
  const submit = el("button", "primary", "安全连接");
  submit.type = "submit";
  form.append(submit);
  form.onsubmit = async (e) => {
    e.preventDefault();
    submit.disabled = true;
    try {
      if (mode.value === "bootstrap")
        await api("/bootstrap", "POST", {
          secret: code.value,
          name: name.value,
        });
      else
        await api("/pair", "POST", {
          code: code.value.trim(),
          name: name.value,
          kind: "web",
        });
      code.value = "";
      await start();
    } catch (err) {
      toast(err.message);
    } finally {
      submit.disabled = false;
    }
  };
  card.append(
    form,
    el(
      "small",
      "",
      "配对码由已连接的管理员生成。短信不存入浏览器持久缓存；通知默认不显示正文。",
    ),
  );
  root.append(card);
}
function shell() {
  const root = $("#app");
  root.replaceChildren();
  const container = el("div", "shell");
  const rail = el("aside", "rail");
  const brand = el("div", "brand");
  brand.append(el("span", "mark", "枢"));
  const label = el("div");
  label.append(
    el("strong", "", state.me.title),
    el("div", "muted", "你的私人短信中枢"),
  );
  brand.append(label);
  rail.append(brand);
  const nav = el("nav");
  nav.setAttribute("aria-label", "主导航");
  for (const [p, t] of [
    ["inbox", "收件箱"],
    ["devices", "设备"],
    ["settings", "设置"],
  ])
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
  rail.append(nav);
  const foot = el("div", "foot");
  foot.append(
    el("div", "status", "连接中"),
    el("p", "muted", "历史长期保留 · 通知内容保护"),
  );
  rail.append(foot);
  container.append(rail, el("main", "main"));
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
  }
}
function renderConversations() {
  const list = $(".list");
  if (!list) return;
  list.replaceChildren();
  if (!state.conversations.length)
    list.append(
      el("div", "empty", "暂无短信。请配置 Android 采集设备及 SIM 号码。"),
    );
  for (const m of state.conversations) {
    const b = button(
      "",
      async () => {
        state.sender = m.sender;
        state.messages = [];
        $(".layout").classList.add("has-detail");
        renderConversations();
        await loadHistory(false);
      },
      "conversation" + (state.sender === m.sender ? " selected" : ""),
    );
    const avatar = el("span", "avatar", (m.contact || m.sender).slice(0, 1));
    const preview = el("div", "preview");
    preview.append(
      el("strong", "", m.contact || m.sender),
      el("p", "", m.body),
      el("small", "", date(m.timestamp)),
    );
    b.append(avatar, preview);
    list.append(b);
  }
  const controls = el("div", "row");
  if (state.offset > 0)
    controls.append(
      button("上一页", () => {
        state.offset = Math.max(0, state.offset - 100);
        return refreshInbox();
      }),
    );
  if (state.conversations.length === 100)
    controls.append(
      button("下一页", () => {
        state.offset += 100;
        return refreshInbox();
      }),
    );
  list.append(controls);
}
let historyAbortController = null;
async function loadHistory(older) {
  const sender = state.sender;
  if (!older) {
    if (historyAbortController) historyAbortController.abort();
    historyAbortController = new AbortController();
  }
  const signal = historyAbortController?.signal;

  const args = {
    sender,
    sim: state.sim,
    device: state.device,
    q: state.q,
    limit: 100,
  };
  if (older && state.messages.length) args.before = state.messages[0].id;
  try {
    const d = await api("/messages?" + query(args));
    if (signal?.aborted || sender !== state.sender) return;
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
        $(".layout").classList.remove("has-detail");
        renderDetail();
      },
      "back",
    ),
  );
  const title = el("div");
  const m = state.messages.at(-1);
  title.append(
    el("strong", "", m?.contact || state.sender),
    el("div", "muted", state.sender),
  );
  h.append(title);
  detail.append(h);
  const messages = el("div", "messages");
  messages.append(button("加载更早记录", () => loadHistory(true)));
  if (!state.messages.length)
    messages.append(el("p", "empty", "没有符合筛选条件的消息"));
  for (const m of state.messages) {
    const b = el("div", "bubble");
    b.append(document.createTextNode(m.body));
    const name =
      state.devices.find((d) => d.id === m.device_id)?.name || "来源设备";
    b.append(
      el(
        "div",
        "metadata",
        date(m.timestamp) + " · " + m.receiver + " · " + name,
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
    const c = el("section", "card");
    c.append(
      el("h2", "", "连接新设备"),
      el("p", "muted", "一次性配对码，有效期 10 分钟。不要通过公开渠道分享。"),
      button(
        "生成配对码",
        async () => {
          const v = await api("/pairings", "POST", {});
          const n = el("p", "code", v.code);
          c.append(n, el("small", "", "有效至 " + date(v.expires_at)));
        },
        "primary",
      ),
      button("生成管理员配对码", async () => {
        if (
          !confirm(
            "此配对码授予设备管理权。建议配对一个备用管理员浏览器，避免丢失唯一管理员身份。",
          )
        )
          return;
        const v = await api("/pairings", "POST", { admin: true });
        c.append(
          el("p", "code", v.code),
          el("small", "", "管理员配对码 · 有效至 " + date(v.expires_at)),
        );
      }),
    );
    main.append(c);
  }
  const grid = el("div", "cards");
  for (const d of state.devices) {
    const c = el("section", "card");
    c.append(
      el("h2", "", d.name),
      el(
        "span",
        "badge",
        d.kind + (d.admin ? " · 管理员" : "") + (d.revoked ? " · 已撤销" : ""),
      ),
    );
    const sims = state.sims.filter((s) => s.device_id === d.id);
    for (const s of sims)
      c.append(el("p", "muted", (s.label || "SIM") + " · " + s.phone));
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
      if (d.kind === "android")
        row.append(
          button(d.upload ? "禁止上传" : "允许上传", async () => {
            await api("/devices/" + d.id, "PATCH", { upload: !d.upload });
            await reloadMetadata();
            shell();
          }),
        );
      if (state.me.device.admin && d.id !== state.me.device.id)
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
      "在线使用实时连接，后台通过标准 Web Push（无需 Firebase）。HTTPS 及浏览器授权是必要条件。",
    ),
  );
  card.append(
    button("启用安全通知", enablePush, "primary"),
    button("关闭浏览器推送", async () => {
      await api("/push", "DELETE");
      const reg = await navigator.serviceWorker.ready;
      await (await reg.pushManager.getSubscription())?.unsubscribe();
      toast("已关闭浏览器推送");
    }),
  );
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
  const privacy = el("section", "card");
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
  if (!("serviceWorker" in navigator) || !("PushManager" in window))
    throw new Error("当前浏览器不支持 Web Push");
  if (!state.me.vapid_public_key) throw new Error("部署者尚未配置 VAPID");
  if ((await Notification.requestPermission()) !== "granted")
    throw new Error("请在浏览器设置中允许通知");
  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub)
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: pushKey(state.me.vapid_public_key),
    });
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
    if (!state.loggedOut && gen === state.generation)
      setTimeout(realtime, 5000 + Math.random() * 2000);
  };
  ws.onerror = () => ws.close();
}
let syncing = false;
async function catchUp() {
  if (syncing || !state.me) return;
  syncing = true;
  try {
    let changed = false;
    for (let i = 0; i < 100; i++) {
      const d = await api(
        "/sync?" + query({ after: state.cursor, limit: 200 }),
      );
      if (d.messages.length) changed = true;
      state.cursor = d.cursor;
      if (!d.more) break;
    }
    if (state.page === "inbox") await refreshInbox();
  } finally {
    syncing = false;
  }
}
async function start() {
  state.me = await api("/me");
  state.cursor = state.me.cursor;
  state.loggedOut = false;
  state.generation++;
  await reloadMetadata();
  shell();
  if ("serviceWorker" in navigator)
    void navigator.serviceWorker
      .register("/sw.js")
      .catch(() => toast("离线应用壳注册失败"));
  realtime();
}
if (typeof document !== "undefined") {
  setInterval(() => {
    if (state.me && document.visibilityState === "visible")
      void catchUp().catch(() => setStatus("离线，历史暂不可用", false));
  }, 30000);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && state.me)
      void catchUp().catch(() => {});
  });
  start().catch(() => authView());
}
