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

export function restoreScrollPosition(scrollTop, previousHeight, nextHeight) {
  return scrollTop + Math.max(0, nextHeight - previousHeight);
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
  railExpanded: true,
  historyHasMore: true,
  historyLoading: false,
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
  menu: `<svg viewBox="0 0 24 24"><path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"/></svg>`,
  chevronLeft: `<svg viewBox="0 0 24 24"><path d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg>`,
  chevronRight: `<svg viewBox="0 0 24 24"><path d="m10 6-1.41 1.41L13.17 12l-4.58 4.59L10 18l6-6z"/></svg>`,
  settings: `<svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>`,
  search: `<svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>`,
  arrowDropDown: `<svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>`,
  check: `<svg viewBox="0 0 24 24"><path d="m9 16.17-3.88-3.88L3.71 13.7 9 19l11.29-11.29-1.41-1.42z"/></svg>`,
  person: `<svg viewBox="0 0 24 24"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>`,
  phoneAndroid: `<svg viewBox="0 0 24 24"><path d="M17 1H7c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-2-2-2zm0 18H7V5h10v14zm-5 3c-.83 0-1.5-.67-1.5-1.5S11.17 19 12 19s1.5.67 1.5 1.5S12.83 22 12 22z"/></svg>`,
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

function defaultAvatar(label, extraClass = "") {
  const avatar = el("span", `avatar ${extraClass}`.trim());
  avatar.innerHTML = ICONS.person;
  avatar.setAttribute("role", "img");
  avatar.setAttribute("aria-label", `${label}默认头像`);
  avatar.setAttribute("style", getAvatarStyle(label));
  return avatar;
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
// App Shell Structure (M3 Expressive Navigation Rail + Canvas)
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

  const rail = el("aside", `rail${state.railExpanded ? " expanded" : " collapsed"}`);
  rail.dataset.expanded = String(state.railExpanded);
  rail.setAttribute("aria-label", "主导航");

  const railHeader = el("div", "rail-header");
  const brand = el("div", "brand");
  const brandLogo = el("img", "brand-logo");
  brandLogo.src = "/icon.svg";
  brandLogo.alt = "鸿枢";
  const brandTitle = el("strong", "brand-title", "鸿枢");
  brand.append(brandLogo, brandTitle);
  railHeader.append(brand);

  const railToggle = el("button", "icon-button rail-toggle");
  railToggle.type = "button";
  railToggle.innerHTML = state.railExpanded ? ICONS.chevronLeft : ICONS.chevronRight;
  railToggle.setAttribute(
    "aria-label",
    state.railExpanded ? "收起侧边栏" : "展开侧边栏",
  );
  railToggle.title = railToggle.getAttribute("aria-label");
  railToggle.setAttribute("aria-expanded", String(state.railExpanded));
  attachRipple(railToggle);
  railToggle.onclick = () => {
    state.railExpanded = !state.railExpanded;
    shell();
  };
  railHeader.append(railToggle);
  rail.append(railHeader);

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
    if (state.page === p) item.setAttribute("aria-current", "page");
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

  const foot = el("div", "foot");
  const statusEl = el("div", "status");
  statusEl.title = "已建立长连接实时通信";
  const dot = el("span", "status-dot");
  statusEl.append(dot, el("span", "status-copy"));
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
    const dot = n.querySelector(".status-dot") || el("span", "status-dot");
    const copy = el("span", "status-copy");
    copy.append(
      el("strong", "status-label", online ? "实时连接" : "自动补齐"),
      el("small", "status-state", online ? "已连接" : "等待连接"),
    );
    n.replaceChildren(dot, copy);
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
      state.devices
        .filter((d) => d.kind === "android" && !d.revoked)
        .map((d) => [d.id, d.name]),
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

let activeFilterMenu = null;
let filterMenuEventsInstalled = false;

function installFilterMenuEvents() {
  if (filterMenuEventsInstalled || typeof document === "undefined") return;
  filterMenuEventsInstalled = true;
  document.addEventListener("click", (event) => {
    if (activeFilterMenu && !activeFilterMenu.wrapper.contains(event.target)) {
      activeFilterMenu.close();
    }
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && activeFilterMenu) {
      activeFilterMenu.close(true);
    }
  });
}

function createFilterChip(label, items, value, change) {
  installFilterMenuEvents();
  const wrapper = el("div", "filter-chip-wrapper");
  const trigger = el("button", "filter-chip" + (value ? " active" : ""));
  trigger.type = "button";
  trigger.setAttribute("aria-label", label);
  trigger.setAttribute("aria-haspopup", "listbox");
  trigger.setAttribute("aria-expanded", "false");

  const selected = items.find(([v]) => v === value);
  const triggerLabel = el("span", "filter-chip-label", selected?.[1] || label);
  const arrow = el("span", "filter-chip-arrow");
  arrow.innerHTML = ICONS.arrowDropDown;
  trigger.append(triggerLabel, arrow);

  const menu = el("div", "filter-menu-popup");
  menu.hidden = true;
  menu.setAttribute("role", "listbox");
  menu.setAttribute("aria-label", label);

  const options = [["", label], ...items];
  const optionButtons = [];
  let selectedIndex = Math.max(0, options.findIndex(([v]) => v === value));

  function updateSelected(nextValue) {
    selectedIndex = Math.max(0, options.findIndex(([v]) => v === nextValue));
    triggerLabel.textContent = options[selectedIndex][1];
    trigger.classList.toggle("active", Boolean(nextValue));
    optionButtons.forEach((option, index) => {
      const selected = index === selectedIndex;
      option.classList.toggle("selected", selected);
      option.setAttribute("aria-selected", String(selected));
    });
  }

  function close(focus = false) {
    menu.hidden = true;
    trigger.setAttribute("aria-expanded", "false");
    if (activeFilterMenu?.wrapper === wrapper) activeFilterMenu = null;
    if (focus) trigger.focus();
  }

  function open() {
    if (activeFilterMenu && activeFilterMenu.wrapper !== wrapper) {
      activeFilterMenu.close();
    }
    activeFilterMenu = { wrapper, close };
    menu.hidden = false;
    trigger.setAttribute("aria-expanded", "true");
    optionButtons[selectedIndex]?.focus();
  }

  function choose(nextValue) {
    updateSelected(nextValue);
    close(true);
    change(nextValue);
    state.offset = 0;
    void refreshInbox().catch((e) => toast(e.message));
  }

  options.forEach(([optionValue, optionLabel], index) => {
    const option = el("button", "filter-menu-option");
    option.type = "button";
    option.setAttribute("role", "option");
    option.setAttribute("aria-selected", String(index === selectedIndex));
    option.classList.toggle("selected", index === selectedIndex);
    option.tabIndex = -1;
    const check = el("span", "filter-menu-check");
    check.innerHTML = ICONS.check;
    option.append(check, el("span", "filter-menu-option-label", optionLabel));
    option.onclick = (event) => {
      event.stopPropagation();
      choose(optionValue);
    };
    optionButtons.push(option);
    menu.append(option);
  });

  trigger.onclick = (event) => {
    event.stopPropagation();
    if (menu.hidden) open();
    else close();
  };
  trigger.onkeydown = (event) => {
    if (["Enter", " ", "ArrowDown", "ArrowUp"].includes(event.key)) {
      event.preventDefault();
      open();
    }
  };
  menu.onkeydown = (event) => {
    const current = optionButtons.indexOf(document.activeElement);
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const direction = event.key === "ArrowDown" ? 1 : -1;
      const next = (current + direction + optionButtons.length) % optionButtons.length;
      optionButtons[next]?.focus();
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      optionButtons[event.key === "Home" ? 0 : optionButtons.length - 1]?.focus();
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      const option = optionButtons[current];
      if (option) choose(options[current][0]);
    }
  };

  wrapper.append(trigger, menu);
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
          state.devices
            .filter((d) => d.kind === "android" && !d.revoked)
            .map((d) => [d.id, d.name]),
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
      state.historyHasMore = true;
      state.historyLoading = false;
      $(".layout")?.classList.add("has-detail");
      renderConversations();
      await loadHistory(false);
    };

    const titleText = m.contact || m.sender;
    const avatar = defaultAvatar(titleText);

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
let historyRequestId = 0;
const MESSAGE_PAGE_SIZE = 100;

function updateHistoryStatus() {
  const status = $(".history-status");
  if (!status) return;
  if (state.historyLoading) {
    status.textContent = "正在加载更早记录…";
  } else if (!state.historyHasMore) {
    status.textContent = state.messages.length ? "已到最早记录" : "暂无历史记录";
  } else {
    status.textContent = "向上滚动加载更早记录";
  }
}

async function loadHistory(older, { preserve = false } = {}) {
  const sender = state.sender;
  const receiver = state.receiver;
  if (!sender || (older && (!state.historyHasMore || state.historyLoading))) return;

  if (!older) {
    if (historyAbortController) historyAbortController.abort();
    historyAbortController = new AbortController();
    state.historyHasMore = true;
  }
  const signal = historyAbortController?.signal;
  const requestId = ++historyRequestId;
  const generation = state.generation;
  const previousStream = $(".messages");
  const scrollTop = previousStream?.scrollTop || 0;
  const scrollHeight = previousStream?.scrollHeight || 0;
  const atBottom = previousStream &&
    scrollHeight - scrollTop - previousStream.clientHeight < 40;
  const args = {
    sender,
    sim: receiver || state.sim,
    device: state.device,
    q: state.q,
    limit: MESSAGE_PAGE_SIZE,
  };
  if (older && state.messages.length) args.before = state.messages[0].id;

  state.historyLoading = true;
  updateHistoryStatus();
  try {
    setBusy(true);
    const d = await api("/messages?" + query(args), "GET", undefined, { signal });
    if (
      signal?.aborted ||
      requestId !== historyRequestId ||
      generation !== state.generation || !state.me ||
      sender !== state.sender ||
      receiver !== state.receiver
    ) {
      return;
    }

    const incoming = d.messages || [];
    state.messages = older || preserve
      ? mergeMessages(state.messages, incoming)
      : mergeMessages([], incoming);
    state.messages = state.messages.map((message) => ({
      ...message,
      contact: state.contacts[message.sender] || message.contact || "",
    }));
    if (older) {
      state.historyHasMore = incoming.length === MESSAGE_PAGE_SIZE;
    } else if (!preserve) {
      state.historyHasMore = incoming.length === MESSAGE_PAGE_SIZE;
    } else {
      state.historyHasMore ||= incoming.length === MESSAGE_PAGE_SIZE;
    }
    state.historyLoading = false;
    renderDetail();

    const nextStream = $(".messages");
    if (nextStream) {
      if (older) {
        nextStream.scrollTop = restoreScrollPosition(
          scrollTop,
          scrollHeight,
          nextStream.scrollHeight,
        );
      } else if (preserve) {
        nextStream.scrollTop = atBottom
          ? nextStream.scrollHeight
          : Math.min(scrollTop, nextStream.scrollHeight);
      } else {
        nextStream.scrollTop = nextStream.scrollHeight;
      }
    }
  } catch (err) {
    if (err.name === "AbortError" || signal?.aborted) return;
    state.historyHasMore = true;
    throw err;
  } finally {
    if (requestId === historyRequestId) {
      state.historyLoading = false;
      updateHistoryStatus();
      setBusy(false);
    }
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
  const avatar = defaultAvatar(contactTitle, "avatar-detail");

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
  const historyStatus = el("div", "history-status");
  historyStatus.setAttribute("role", "status");
  historyStatus.setAttribute("aria-live", "polite");
  messages.append(historyStatus);
  messages.addEventListener("scroll", () => {
    if (
      messages.scrollTop <= 80 &&
      state.historyHasMore &&
      !state.historyLoading
    ) {
      void loadHistory(true).catch((error) => toast(error.message));
    }
  });
  updateHistoryStatus();

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
// Devices View (read-only Android phone inventory)
// -------------------------------------------------------------
function devicesView() {
  const main = $(".main");
  const container = el("div", "page-container");

  const head = el("header", "page-head");
  head.append(el("h1", "", "设备"));
  container.append(head);

  const phones = state.devices.filter((device) =>
    device.kind === "android" && !device.revoked,
  );
  const grid = el("div", "cards");
  for (const d of phones) {
    const c = el("section", "card stack device-card");
    const cardHead = el("div", "row spread");
    const title = el("div", "device-title");
    const phoneIcon = el("span", "device-icon");
    phoneIcon.innerHTML = ICONS.phoneAndroid;
    title.append(phoneIcon, el("h2", "", d.name));
    cardHead.append(
      title,
      el("span", "badge", "Android 手机"),
    );
    c.append(cardHead);

    const sims = state.sims.filter((s) => s.device_id === d.id);
    if (sims.length) {
      for (const s of sims) {
        const sim = el("div", "device-sim");
        sim.append(
          el("span", "device-sim-label", s.label || "SIM"),
          el("span", "device-sim-phone", s.phone),
        );
        c.append(sim);
      }
    } else {
      c.append(el("p", "muted device-empty-sim", "暂无 SIM 信息"));
    }
    grid.append(c);
  }
  if (!phones.length) {
    const empty = el("div", "empty-state device-empty");
    empty.innerHTML = ICONS.phoneAndroid;
    empty.append(
      el("h3", "", "暂无已连接手机"),
      el("p", "", "连接 Android 手机后，设备信息会显示在这里。"),
    );
    container.append(empty);
  } else {
    container.append(grid);
  }
  main.append(container);
}

function createSwitch(checked, onChange) {
  let value = Boolean(checked);
  const control = el("button", "m3-switch");
  control.type = "button";
  control.setAttribute("role", "switch");
  control.setAttribute("aria-label", "浏览器通知");
  control.setAttribute("aria-checked", String(value));
  const thumb = el("span", "m3-switch-thumb");
  control.append(thumb);
  attachRipple(control);

  control.setChecked = (next) => {
    value = Boolean(next);
    control.setAttribute("aria-checked", String(value));
    control.classList.toggle("checked", value);
  };
  control.setDisabled = (disabled) => {
    control.disabled = disabled;
  };
  control.onclick = async () => {
    if (control.disabled) return;
    await onChange(!value);
  };
  control.setChecked(value);
  return control;
}

function pushCapability() {
  return Boolean(
    window.isSecureContext &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    typeof Notification !== "undefined" &&
    state.me?.vapid_public_key,
  );
}

async function browserPushEnabled() {
  if (!pushCapability() || Notification.permission !== "granted") return false;
  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  return Boolean(state.me?.device?.notify && subscription);
}

function setLocalNotificationState(enabled) {
  if (state.me?.device) state.me.device.notify = enabled;
  const current = state.devices.find((device) => device.id === state.me?.device?.id);
  if (current) current.notify = enabled;
}

async function disablePush() {
  await api("/push", "DELETE");
  if ("serviceWorker" in navigator) {
    const registration = await navigator.serviceWorker.getRegistration();
    await (await registration?.pushManager.getSubscription())?.unsubscribe();
  }
  await api("/devices/" + state.me.device.id, "PATCH", { notify: false });
  setLocalNotificationState(false);
}

async function logout() {
  await api("/logout", "POST", {});
  disconnect();
  authView();
}

// -------------------------------------------------------------
// Settings View
// -------------------------------------------------------------
function settingsView() {
  const main = $(".main");
  const container = el("div", "page-container settings-container");

  const head = el("header", "page-head");
  head.append(el("h1", "", "设置"));
  container.append(head);

  const card = el("section", "card notification-card");
  const copy = el("div", "settings-copy");
  copy.append(el("h2", "", "浏览器通知"));
  const status = el("p", "notification-state", "正在读取通知状态…");
  status.setAttribute("aria-live", "polite");
  copy.append(status);

  const toggle = createSwitch(false, async (next) => {
    toggle.setDisabled(true);
    setBusy(true);
    try {
      if (next) await enablePush();
      else await disablePush();
      toggle.setChecked(next);
      status.textContent = next ? "通知已开启" : "通知已关闭";
    } catch (error) {
      toast(error.message);
    } finally {
      toggle.setDisabled(false);
      setBusy(false);
    }
  });
  toggle.setDisabled(!pushCapability());
  card.append(el("div", "settings-row"));
  card.querySelector(".settings-row").append(copy, toggle);
  container.append(card);

  const footer = el("footer", "settings-footer");
  footer.append(button("退出登录", logout, "logout-button"));
  container.append(footer);
  main.append(container);

  void browserPushEnabled().then((enabled) => {
    toggle.setChecked(enabled);
    status.textContent = enabled ? "通知已开启" : "通知已关闭";
  }).catch(() => {
    status.textContent = "通知已关闭";
  });
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
  setLocalNotificationState(true);
}

async function reloadMetadata() {
  const generation = state.generation;
  const [d, s, c] = await Promise.all([api("/devices"), api("/sims"), api("/contacts")]);
  if (generation !== state.generation || !state.me) return;
  state.devices = d.devices;
  state.sims = s.sims;
  state.contacts = c.contacts;
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
  historyRequestId++;
  state.historyHasMore = true;
  state.historyLoading = false;
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
