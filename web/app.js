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

// -------------------------------------------------------------
// UI State & Configuration
// -------------------------------------------------------------
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
  contacts: {},
  inboxDirty: false,
};

const errors = {
  unauthorized: "登录状态已失效，请重新输入中枢密码",
  invalid_password: "中枢密码不正确",
  rate_limited: "请求过于频繁，请稍后重试",
  push_not_configured: "部署者尚未配置 Web Push",
  origin_denied: "PUBLIC_URL 与访问地址不一致",
  invalid_push_endpoint: "浏览器推送地址不可达或不被允许",
};

// SVG Icon Assets (Google Material Symbols / Icons)
const ICONS = {
  chat: `<svg viewBox="0 0 24 24"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H6l-2 2V4h16v12z"/></svg>`,
  devices: `<svg viewBox="0 0 24 24"><path d="M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 9h-4v-7h4v7z"/></svg>`,
  settings: `<svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>`,
  search: `<svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>`,
  arrowDropDown: `<svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>`,
  arrowBack: `<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>`,
  close: `<svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>`,
  contentCopy: `<svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>`,
  warning: `<svg viewBox="0 0 24 24"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>`,
  emptyMessages: `<svg viewBox="0 0 160 160" fill="none" xmlns="http://www.w3.org/2000/svg">
    <circle cx="80" cy="80" r="70" fill="currentColor" fill-opacity="0.12"/>
    <rect x="42" y="46" width="76" height="58" rx="16" fill="currentColor" fill-opacity="0.3"/>
    <path d="M54 62h52M54 74h36M54 86h24" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
    <circle cx="106" cy="102" r="18" fill="var(--md-sys-color-primary)"/>
    <path d="M102 96l8 6-8 6" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>`,
};

// Deterministic Expressive Avatar Color Generator
const AVATAR_PALETTES = [
  { bg: "#E8DEF8", color: "#1D192B" },
  { bg: "#D0E4FF", color: "#001D36" },
  { bg: "#CBE6FF", color: "#001E30" },
  { bg: "#C2E7FF", color: "#001D35" },
  { bg: "#D4E7E4", color: "#0D2825" },
  { bg: "#FFD8E4", color: "#31111D" },
  { bg: "#FFDCC2", color: "#2E1500" },
  { bg: "#EADDFF", color: "#21005D" },
];

function getAvatarStyle(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0;
  }
  const idx = Math.abs(hash) % AVATAR_PALETTES.length;
  const p = AVATAR_PALETTES[idx];
  return `background-color: ${p.bg}; color: ${p.color};`;
}

// Attach Material 3 Ripple effect to interactive DOM elements
function attachRipple(element) {
  element.classList.add("ripple-surface");
  element.addEventListener("pointerdown", (e) => {
    const rect = element.getBoundingClientRect();
    const size = Math.max(rect.width, rect.height);
    const x = e.clientX - rect.left - size / 2;
    const y = e.clientY - rect.top - size / 2;

    const wave = document.createElement("span");
    wave.className = "ripple-wave";
    wave.style.width = `${size}px`;
    wave.style.height = `${size}px`;
    wave.style.left = `${x}px`;
    wave.style.top = `${y}px`;

    element.append(wave);
    wave.addEventListener("animationend", () => wave.remove(), { once: true });
  });
}

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

const sessionRequests = new Set();
class ApiError extends Error {
  constructor(status, code) { super(errors[code] || code || "请求失败"); this.status = status; this.code = code; }
}
async function api(path, method = "GET", body, { signal } = {}) {
  const generation = state.generation;
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  sessionRequests.add(controller);
  try {
  const res = await fetch("/api" + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
    credentials: "same-origin",
    signal: controller.signal,
  });
  const data = await res.json();
  if (generation !== state.generation || controller.signal.aborted) throw new DOMException("Session ended", "AbortError");
  if (!res.ok) {
    if (res.status === 401 && state.me) {
      disconnect();
      authView();
    }
    throw new ApiError(res.status, data.error);
  }
  return data;
  } finally {
    sessionRequests.delete(controller);
    signal?.removeEventListener("abort", abort);
  }
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
  attachRipple(b);
  b.onclick = () => {
    setBusy(true);
    Promise.resolve(fn())
      .catch((e) => toast(e.message))
      .finally(() => setBusy(false));
  };
  return b;
}

function iconButton(svgHtml, label, fn, cls = "") {
  const b = el("button", "icon-button " + cls);
  b.type = "button";
  b.innerHTML = svgHtml;
  b.setAttribute("aria-label", label);
  b.title = label;
  attachRipple(b);
  b.onclick = (e) => {
    e.stopPropagation();
    setBusy(true);
    Promise.resolve(fn())
      .catch((err) => toast(err.message))
      .finally(() => setBusy(false));
  };
  return b;
}

function formatRelativeTime(t) {
  const now = new Date();
  const d = new Date(t);
  const diff = now - d;
  const sameDay =
    now.getDate() === d.getDate() &&
    now.getMonth() === d.getMonth() &&
    now.getFullYear() === d.getFullYear();

  if (sameDay) {
    return new Intl.DateTimeFormat("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(d);
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (
    yesterday.getDate() === d.getDate() &&
    yesterday.getMonth() === d.getMonth() &&
    yesterday.getFullYear() === d.getFullYear()
  ) {
    return "昨天";
  }
  if (now.getFullYear() === d.getFullYear()) {
    return new Intl.DateTimeFormat("zh-CN", {
      month: "numeric",
      day: "numeric",
    }).format(d);
  }
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "numeric",
    day: "numeric",
  }).format(d);
}

function dateDay(t) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
  }).format(new Date(t));
}

// -------------------------------------------------------------
// Authentication View (M3 Expressive Card)
// -------------------------------------------------------------
function authView() {
  const root = $("#app");
  root.replaceChildren();
  const card = el("main", "auth");
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
  attachRipple(submit);
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
      "muted",
      "单用户中枢，无需用户名。所有设备统一使用中枢密码连接；短信不会离线保存在浏览器。",
    ),
  );
  root.append(card);
}

// -------------------------------------------------------------
// App Shell Structure (Google Messages Slim Rail + Canvas)
// -------------------------------------------------------------
function shell() {
  const root = $("#app");
  root.replaceChildren();

  // Wavy Indeterminate Progress Indicator (Google Messages Top Bar)
  const progContainer = el("div", "progress-container");
  progContainer.id = "global-progress";
  progContainer.style.display = state.busy ? "block" : "none";
  progContainer.append(el("div", "progress-bar-wavy"));

  const container = el("div", "shell");

  // Slim Navigation Rail (Google Messages Web Left Bar)
  const rail = el("aside", "rail");
  rail.setAttribute("aria-label", "主导航");

  const brand = el("div", "brand");
  const mark = el("span", "mark", "枢");
  brand.append(mark);
  // Hidden text for test / screenreader compatibility
  const brandTitle = el("strong", "", state.me?.title || "鸿枢");
  brand.append(brandTitle);
  rail.append(brand);

  const nav = el("nav");
  for (const [p, t, icon] of [
    ["inbox", "收件箱", ICONS.chat],
    ["devices", "设备", ICONS.devices],
    ["settings", "设置", ICONS.settings],
  ]) {
    const item = el(
      "button",
      "rail-item" + (state.page === p ? " active" : ""),
    );
    item.type = "button";
    item.setAttribute("aria-label", t);
    attachRipple(item);

    const iconWrapper = el("div", "rail-icon-wrapper");
    iconWrapper.innerHTML = icon;
    const label = el("span", "rail-label", t);
    item.append(iconWrapper, label);

    item.onclick = async () => {
      state.page = p;
      await reloadMetadata();
      shell();
    };
    nav.append(item);
  }
  rail.append(nav);

  // Status Indicator Dot (Google Messages Clean Bottom Status)
  const foot = el("div", "foot");
  const statusEl = el("div", "status", "实时连接");
  statusEl.title = "已建立长连接实时通信";
  const dot = el("span", "status-dot");
  statusEl.prepend(dot);
  foot.append(statusEl);
  rail.append(foot);

  const main = el("main", "main");
  main.append(progContainer);

  if (remoteHTTP()) {
    const banner = el("div", "security-banner");
    const bannerText = el("div", "security-banner-text");
    bannerText.innerHTML = ICONS.warning;
    bannerText.append(
      document.createTextNode(
        "当前 HTTP 连接未加密：密码、短信和登录凭据可能被窃听或篡改。浏览器离线应用与 Web Push 需要 HTTPS。",
      ),
    );
    banner.append(bannerText);
    main.append(banner);
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
    n.title = text;
    // Keep text node content for compatibility
    const dot = n.querySelector(".status-dot") || el("span", "status-dot");
    n.replaceChildren(dot, document.createTextNode(text));
    n.className = "status" + (online ? " online" : "");
  }
}

// -------------------------------------------------------------
// Inbox View: Pill Search + M3 Chips + Stream Layout
// -------------------------------------------------------------
function inboxView() {
  const main = $(".main");

  // Keep hidden h1 for screen reader / test locator compatibility
  const srTitle = el("h1", "", "短信收件箱");
  srTitle.style.position = "absolute";
  srTitle.style.width = "1px";
  srTitle.style.height = "1px";
  srTitle.style.padding = "0";
  srTitle.style.margin = "-1px";
  srTitle.style.overflow = "hidden";
  srTitle.style.clip = "rect(0, 0, 0, 0)";
  srTitle.style.border = "0";
  main.append(srTitle);

  const layout = el("section", "layout" + (state.sender ? " has-detail" : ""));
  const inbox = el("div", "inbox");

  // Google Messages Top Search & Filters Bar
  const topbar = el("div", "inbox-topbar");

  // Pill Search Box
  const searchContainer = el("div", "search-container");
  searchContainer.innerHTML = `<span class="search-icon">${ICONS.search}</span>`;

  const search = el("input", "search");
  search.type = "search";
  search.placeholder = "搜索联系人、号码或短信";
  search.setAttribute("aria-label", "搜索短信");
  search.value = state.q;

  const clearBtn = iconButton(
    ICONS.close,
    "清空搜索",
    () => {
      search.value = "";
      state.q = "";
      clearBtn.classList.remove("visible");
      state.offset = 0;
      return refreshInbox();
    },
    "search-clear" + (state.q ? " visible" : ""),
  );

  let timer;
  search.oninput = () => {
    clearBtn.classList.toggle("visible", Boolean(search.value));
    clearTimeout(timer);
    timer = setTimeout(() => {
      state.q = search.value;
      state.offset = 0;
      refreshInbox().catch((e) => toast(e.message));
    }, 250);
  };

  searchContainer.append(search, clearBtn);
  topbar.append(searchContainer);

  // M3 Filter Chips
  const filterRow = el("div", "filter-chips");
  const phones = [
    ...new Map(
      state.sims.map((s) => [s.phone, [s.phone, s.label || s.phone]]),
    ).values(),
  ];
  filterRow.append(
    createFilterChip("所有 SIM", phones, state.sim, (v) => (state.sim = v)),
    createFilterChip(
      "所有设备",
      state.devices.filter((d) => !d.revoked).map((d) => [d.id, d.name]),
      state.device,
      (v) => (state.device = v),
    ),
  );
  topbar.append(filterRow);

  inbox.append(topbar, el("div", "list"));
  layout.append(inbox, el("article", "detail"));
  main.append(layout);

  renderConversations();
  renderDetail();
  void refreshInbox().catch((e) => toast(e.message));
}

function createFilterChip(label, items, value, change) {
  const wrapper = el("div", "filter-chip-wrapper");
  const s = el("select", "filter-chip-select" + (value ? " active" : ""));
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
    s.classList.toggle("active", Boolean(s.value));
    state.offset = 0;
    void refreshInbox().catch((e) => toast(e.message));
  };

  const arrow = el("span", "filter-chip-arrow");
  arrow.innerHTML = ICONS.arrowDropDown;
  wrapper.append(s, arrow);
  return wrapper;
}

let inboxAbortController = null;
let refreshGeneration = 0;
async function refreshInbox({ metadata = true, preserveHistory = false } = {}) {
  if (!state.me) return;
  if (inboxAbortController) {
    inboxAbortController.abort();
  }
  inboxAbortController = new AbortController();
  const signal = inboxAbortController.signal;

  const gen = ++refreshGeneration;
  try {
    setBusy(true);
    if (metadata) await reloadMetadata();
    if (signal.aborted) return;

    const filterRow = $(".filter-chips");
    if (filterRow && gen === refreshGeneration) {
      const phones = [
        ...new Map(
          state.sims.map((s) => [s.phone, [s.phone, s.label || s.phone]]),
        ).values(),
      ];
      filterRow.replaceChildren(
        createFilterChip("所有 SIM", phones, state.sim, (v) => (state.sim = v)),
        createFilterChip(
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
    const d = await api("/conversations?" + query(args), "GET", undefined, { signal });
    if (signal.aborted || gen !== refreshGeneration) return;
    state.conversations = d.conversations;
    renderConversations();
    if (state.sender) await loadHistory(false, { preserve: preserveHistory });
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
    const empty = el("div", "empty-state");
    empty.innerHTML = ICONS.emptyMessages;
    empty.append(
      el("h3", "", "暂无短信"),
      el("p", "", "暂无短信。请配置 Android 采集设备及 SIM 号码。"),
    );
    list.append(empty);
    return;
  }

  state.conversations.forEach((m, idx) => {
    const isSelected =
      state.sender === m.sender && state.receiver === m.receiver;
    const b = el(
      "button",
      "conversation" + (isSelected ? " selected" : "") + " conversation-enter",
    );
    b.type = "button";
    b.style.animationDelay = `${Math.min(idx * 25, 250)}ms`;
    attachRipple(b);

    b.onclick = async () => {
      state.sender = m.sender;
      state.receiver = m.receiver;
      state.messages = [];
      $(".layout")?.classList.add("has-detail");
      renderConversations();
      await loadHistory(false);
    };

    const titleText = m.contact || m.sender;
    const avatar = el("span", "avatar", titleText.slice(0, 1).toUpperCase());
    avatar.setAttribute("style", getAvatarStyle(titleText));

    const preview = el("div", "preview");
    const previewTop = el("div", "preview-top");
    previewTop.append(
      el("strong", "", titleText),
      el("time", "", formatRelativeTime(m.timestamp)),
    );

    const snippet = el("p", "", [m.receiver, m.body].filter(Boolean).join(" · "));
    preview.append(previewTop, snippet);

    b.append(avatar, preview);
    list.append(b);
  });

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

// -------------------------------------------------------------
// Detail Message Stream (Google Messages Layout & Grouping)
// -------------------------------------------------------------
let historyAbortController = null;
async function loadHistory(older, { preserve = false } = {}) {
  const sender = state.sender;
  const receiver = state.receiver;
  if (!older) {
    if (historyAbortController) historyAbortController.abort();
    historyAbortController = new AbortController();
  }
  const signal = historyAbortController?.signal;
  const generation = state.generation;
  const previousStream = $(".messages");
  const scrollTop = previousStream?.scrollTop || 0;
  const scrollHeight = previousStream?.scrollHeight || 0;
  const atBottom = previousStream && scrollHeight - scrollTop - previousStream.clientHeight < 40;

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
    const d = await api("/messages?" + query(args), "GET", undefined, { signal });
    if (
      signal?.aborted ||
      generation !== state.generation || !state.me ||
      sender !== state.sender ||
      receiver !== state.receiver
    ) {
      return;
    }
    state.messages = older || preserve
      ? mergeMessages(state.messages, d.messages)
      : mergeMessages([], d.messages);
    renderDetail();
    state.messages = state.messages.map(m => ({ ...m, contact: state.contacts[m.sender] || "" }));
    const n = $(".messages");
    if (n) n.scrollTop = older ? scrollTop + n.scrollHeight - scrollHeight : preserve && !atBottom ? scrollTop : n.scrollHeight;
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
    const empty = el("div", "empty-state");
    empty.innerHTML = ICONS.emptyMessages;
    empty.append(
      el("h3", "", "开启跨设备短信"),
      el("p", "", "选择一个会话，查看完整历史"),
    );
    detail.append(empty);
    return;
  }

  // Top App Bar
  const head = el("header", "detail-head");
  const backBtn = iconButton(
    ICONS.arrowBack,
    "返回",
    () => {
      state.sender = "";
      state.receiver = "";
      $(".layout")?.classList.remove("has-detail");
      renderDetail();
    },
    "back",
  );
  head.append(backBtn);

  const contactTitle = state.messages.at(-1)?.contact || state.sender;
  const avatar = el("span", "avatar", contactTitle.slice(0, 1).toUpperCase());
  avatar.style.width = "40px";
  avatar.style.height = "40px";
  avatar.style.minWidth = "40px";
  avatar.setAttribute("style", getAvatarStyle(contactTitle));

  const titleBox = el("div", "detail-head-info");
  const infoText = el("div");
  infoText.append(
    el("strong", "", contactTitle),
    el(
      "div",
      "sub-info",
      [state.sender, state.receiver].filter(Boolean).join(" · "),
    ),
  );
  titleBox.append(avatar, infoText);
  head.append(titleBox);

  const headActions = el("div", "detail-head-actions");
  headActions.append(
    iconButton(ICONS.contentCopy, "复制号码", () => {
      navigator.clipboard?.writeText(state.sender);
      toast("已复制号码: " + state.sender);
    }),
  );
  head.append(headActions);
  detail.append(head);

  // Message Stream
  const messages = el("div", "messages");
  messages.append(button("加载更早记录", () => loadHistory(true), "text-button"));

  if (!state.messages.length) {
    messages.append(el("p", "empty", "没有符合筛选条件的消息"));
  }

  let lastDay = "";
  let currentGroup = null;

  state.messages.forEach((msg, idx) => {
    const dayStr = dateDay(msg.timestamp);
    if (dayStr !== lastDay) {
      const divider = el("div", "date-divider");
      divider.append(el("span", "", dayStr));
      messages.append(divider);
      lastDay = dayStr;
      currentGroup = null;
    }

    if (!currentGroup) {
      currentGroup = el("div", "bubble-group");
      messages.append(currentGroup);
    }

    const b = el("div", "bubble");
    b.append(document.createTextNode(msg.body));

    const name =
      state.devices.find((d) => d.id === msg.device_id)?.name || "来源设备";
    b.append(
      el(
        "div",
        "metadata",
        formatRelativeTime(msg.timestamp) + " · " + msg.receiver + " · " + name,
      ),
    );

    // Hover Copy Quick Action
    const actions = el("div", "bubble-actions");
    const copyBtn = iconButton(ICONS.contentCopy, "复制短信", () => {
      navigator.clipboard?.writeText(msg.body);
      toast("已复制短信正文");
    });
    actions.append(copyBtn);
    b.append(actions);

    currentGroup.append(b);
  });

  detail.append(messages);
}

// -------------------------------------------------------------
// Devices Management View (Google Messages Material Cards)
// -------------------------------------------------------------
async function devicesView() {
  const main = $(".main");
  const container = el("div", "page-container");

  const head = el("header", "page-head");
  head.append(el("h1", "", "设备与 SIM"));
  container.append(head);

  if (state.me.device.admin) {
    const c = el("section", "card stack");
    c.append(
      el("h2", "", "连接新设备"),
      el(
        "p",
        "muted",
        "所有设备（Android 手机或其他浏览器）直接使用统一中枢密码连接，无需生成配对码。",
      ),
    );
    container.append(c);
  }

  const grid = el("div", "cards");
  for (const d of state.devices) {
    const c = el("section", "card stack");
    const cardHead = el("div", "row spread");
    cardHead.append(
      el("h2", "", d.name),
      el(
        "span",
        "badge",
        d.kind + (d.admin ? " · 管理员" : "") + (d.revoked ? " · 已撤销" : ""),
      ),
    );
    c.append(cardHead);

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
  container.append(grid);
  main.append(container);
}

// -------------------------------------------------------------
// Settings View
// -------------------------------------------------------------
function settingsView() {
  const main = $(".main");
  const container = el("div", "page-container");

  const head = el("header", "page-head");
  head.append(el("h1", "", "中枢设置"));
  container.append(head);

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
  container.append(card);

  if (state.me.device.admin) {
    const c = el("section", "card stack");
    c.append(el("h2", "", "中枢名称"));
    const input = el("input");
    input.value = state.me.title;
    input.dataset.loadedTitle = state.me.title;
    input.maxLength = 100;
    input.setAttribute("aria-label", "中枢名称");
    c.append(
      input,
      button("保存名称", async () => {
        if (input.dataset.loadedTitle !== state.me.title) throw new Error("中枢名称已被其他设备修改，请重新打开设置后确认");
        await api("/settings", "PATCH", { title: input.value });
        state.me.title = input.value;
        shell();
      }),
    );
    container.append(c);
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
  container.append(privacy);
  main.append(container);
}

// -------------------------------------------------------------
// Web Push Subscription Handlers
// -------------------------------------------------------------
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
  const generation = state.generation;
  const [d, s, c] = await Promise.all([api("/devices"), api("/sims"), api("/contacts")]);
  if (generation !== state.generation || !state.me) return;
  state.devices = d.devices;
  state.sims = s.sims;
  state.contacts = c.contacts;
}

function refreshSettingsSnapshot() {
  const input = $('[aria-label="中枢名称"]');
  if (input && input.value === input.dataset.loadedTitle) {
    input.value = state.me.title;
    input.dataset.loadedTitle = state.me.title;
  }
}

function disconnect() {
  state.loggedOut = true;
  state.generation++;
  state.ws?.close();
  state.ws = null;
  refreshGeneration++;
  for (const request of sessionRequests) request.abort();
  historyAbortController?.abort();
  inboxAbortController?.abort();
  state.me = null;
  state.messages = [];
  state.conversations = [];
  state.devices = [];
  state.sims = [];
  state.contacts = {};
  state.sender = state.receiver = state.q = state.sim = state.device = "";
  state.offset = state.cursor = state.epoch = 0;
  state.epochSeen = false;
  state.inboxDirty = false;
}

function realtime() {
  const gen = state.generation;
  const ws = new WebSocket(location.origin.replace(/^http/, "ws") + "/api/ws");
  state.ws = ws;
  ws.onopen = () => {
    if (gen !== state.generation || !state.me) { ws.close(); return; }
    setStatus("实时连接", true);
    void catchUp().catch((e) => toast(e.message));
  };
  ws.onmessage = () => { if (gen === state.generation && state.me) void catchUp().catch((e) => toast(e.message)); };
  ws.onclose = () => {
    if (gen === state.generation && state.me) setStatus("断线，等待补齐", false);
    if (!state.loggedOut && gen === state.generation) {
      setTimeout(() => { if (!state.loggedOut && gen === state.generation) realtime(); }, 5000 + Math.random() * 2000);
    }
  };
  ws.onerror = () => ws.close();
}

let syncingGeneration = null;
async function catchUp() {
  const generation = state.generation;
  if (syncingGeneration === generation || !state.me) return;
  syncingGeneration = generation;
  try {
    const me = await api("/me");
    if (generation !== state.generation || !state.me) return;
    const changed = me.cursor !== state.cursor || epochReset(state.epochSeen, state.epoch, me.epoch);
    const previousMetadata = JSON.stringify([state.devices, state.sims, state.contacts]);
    if (epochReset(state.epochSeen, state.epoch, me.epoch)) {
      state.messages = [];
      state.conversations = [];
      state.cursor = 0;
    }
    state.epoch = me.epoch;
    state.epochSeen = true;
    // This browser has no durable message cache. Observe the authoritative head;
    // fetch only displayed history, never replay/download discarded SMS bodies.
    state.cursor = me.cursor;
    state.me = me;
    refreshSettingsSnapshot();
    await reloadMetadata();
    if (generation !== state.generation || !state.me) return;
    const metadataChanged = previousMetadata !== JSON.stringify([state.devices, state.sims, state.contacts]);
    state.inboxDirty ||= changed || metadataChanged;
    if (state.page === "inbox" && state.inboxDirty) {
      await refreshInbox({ metadata: false, preserveHistory: true });
      state.inboxDirty = false;
    }
  } finally {
    if (syncingGeneration === generation) syncingGeneration = null;
  }
}

async function start() {
  disconnect();
  const generation = state.generation;
  state.me = await api("/me");
  if (generation !== state.generation) return;
  state.cursor = state.me.cursor;
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
