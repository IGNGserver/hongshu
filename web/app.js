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

export const GROUP_LABELS = { all: "全部短信", otp: "验证码", other: "其他短信", personal: "个人", notification: "通知", promotion: "推广", spam: "垃圾", pending: "待分析" };
export function visibleGroups(enabled) {
  return enabled ? ["all", "otp", "personal", "notification", "promotion", "spam", "pending"] : ["all", "otp", "other"];
}
export function analysisLabel(a) {
  if (!a || a.state === "pending") return "等待分析";
  if (a.state === "processing") return "正在分析";
  if (a.state === "failed") return a.error === "needs_review" ? "待确认分类" : "分析失败，可重试";
  return a.manual ? "手动分类" : "已分析";
}
export function aiQueueLabel(s) {
  return `队列：等待 ${s?.queue?.pending || 0} · 处理中 ${s?.queue?.processing || 0} · 失败/待确认 ${s?.queue?.failed || 0} · 已完成 ${s?.queue?.complete || 0} · 规则待回填 ${s?.queue?.backfill || 0}`;
}

// Splits SMS text into plain and http(s) link segments. Only the matched URL ever
// becomes an href; trailing punctuation that ends the sentence stays as text.
const LINK_PATTERN = /https?:\/\/[^\s<>"'，。！？；：、（）【】「」『』《》]+/gi;
export function linkSegments(text) {
  const segments = [];
  let last = 0;
  for (const match of String(text).matchAll(LINK_PATTERN)) {
    const url = match[0].replace(/[.,;:!?)\]}'"]+$/, "");
    if (!url.match(/^https?:\/\/[^/?#\s]+/i)) continue;
    if (match.index > last) segments.push({ text: text.slice(last, match.index) });
    segments.push({ url });
    last = match.index + url.length;
  }
  if (last < text.length) segments.push({ text: text.slice(last) });
  return segments;
}

// Rows and bubbles animate only when they carry something newer than what this
// page has already shown; filtering, polling and re-renders must not replay them.
export function isFreshArrival(value, seenHigh) {
  return seenHigh > 0 && value > seenHigh;
}
export const conversationKey = (c) => `${c.sender}\u0000${c.receiver}`;
// After a realtime arrival, the rows that are new or moved forward since the last render.
export function freshConversationKeys(previous, conversations) {
  const fresh = new Set();
  if (!previous?.size) return fresh;
  for (const c of conversations) {
    const before = previous.get(conversationKey(c));
    if (before === undefined || c.timestamp > before) fresh.add(conversationKey(c));
  }
  return fresh;
}

// A realtime refresh only re-reads the first page. Keep the pages already loaded by
// scrolling, minus anything the fresh head now covers, so the list does not jump back.
export const CONVERSATION_PAGE = 100;
export function mergeConversationPages(head, loaded, headIsFull) {
  if (!headIsFull) return head;
  const keys = new Set(head.map(conversationKey));
  const floor = head.at(-1)?.timestamp ?? Infinity;
  return head.concat(loaded.filter((c) => !keys.has(conversationKey(c)) && c.timestamp <= floor));
}

// Avatar rules shared with Android (MessageFormat.kt avatarInitial / isServiceNumber and
// Theme.kt avatarColorFor), so a sender looks the same on every device.
export function avatarInitial(contact, sender) {
  const name = String(contact || sender || "").trim().replace(/^[【\[(（]+/, "");
  const first = [...name][0];
  if (!first || /[0-9+]/.test(first)) return null;
  return first.toUpperCase();
}
export function isServiceNumber(sender) {
  const digits = String(sender || "").replace(/^\+86/, "");
  return /^\d+$/.test(digits) && ((digits.length >= 3 && digits.length <= 6) || (digits.startsWith("106") && digits.length > 6));
}
export const AVATAR_COLORS = ["#E08D3C", "#34A853", "#E91E63", "#1A73E8", "#9C27B0", "#00ACC1", "#F25C54", "#5C6BC0"];
export function avatarColor(identifier) {
  if (!identifier) return AVATAR_COLORS[0];
  let hash = 0; // java.lang.String.hashCode over UTF-16 code units
  for (let i = 0; i < identifier.length; i++) hash = (Math.imul(31, hash) + identifier.charCodeAt(i)) | 0;
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

// Consecutive messages from the same device on the same day, at most five minutes apart,
// share one cluster so time and source are shown once.
export const CLUSTER_GAP_MS = 5 * 60 * 1000;
const localDay = (t) => new Date(t).toDateString();
export function clusterMessages(messages) {
  const clusters = [];
  let current = null;
  for (const message of messages) {
    if (!current || message.device_id !== current.device_id ||
        message.timestamp - current.end > CLUSTER_GAP_MS || localDay(message.timestamp) !== localDay(current.end)) {
      current = { device_id: message.device_id, start: message.timestamp, end: message.timestamp, messages: [] };
      clusters.push(current);
    }
    current.messages.push(message);
    current.end = message.timestamp;
  }
  return clusters;
}

// Theme preference is per browser: follow the system, or force light or dark.
export const THEME_CHOICES = ["system", "light", "dark"];
export function normalizeTheme(value) {
  return THEME_CHOICES.includes(value) ? value : "system";
}
// Matches --hs-app in style.css for each scheme.
export const THEME_COLORS = { light: "#F3F3FA", dark: "#111318" };
export function themeColorFor(choice, mediaQuery) {
  const scheme = choice === "system" ? (/dark/.test(mediaQuery) ? "dark" : "light") : choice;
  return THEME_COLORS[scheme];
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
  conversationsLoaded: false,
  conversationsHasMore: false,
  loadingMore: false,
  renderedDetailKey: "",
  returning: false,
  cursor: 0,
  epoch: 0,
  epochSeen: false,
  ws: null,
  generation: 0,
  loggedOut: false,
  busy: false,
  contacts: {},
  inboxDirty: false,
  historyHasMore: true,
  historyLoading: false,
  group: "all",
  counts: {},
  ai: null,
  renderedRows: new Map(),
  seenMessageId: 0,
  arrivalsPending: false,
};

const errors = {
  unauthorized: "登录状态已失效，请重新输入中枢密码",
  invalid_password: "中枢密码不正确",
  rate_limited: "请求过于频繁，请稍后重试",
  push_not_configured: "部署者尚未配置 Web Push",
  origin_denied: "PUBLIC_URL 与访问地址不一致",
  invalid_push_endpoint: "浏览器推送地址不可达或不被允许",
  ai_secret_required: "请先在中枢部署环境配置 HONGSHU_AI_SECRET，再保存 API 密钥",
  invalid_ai_endpoint: "AI 地址需为完整 HTTPS 接口；本地服务须由部署者加入允许主机名单",
  ai_consent_or_config_required: "请填写 AI 配置并同意历史和新增短信分析说明",
  invalid_ai_settings: "请检查 AI 参数范围",
  ai_budget_or_rate_limit: "已达到调用限速或今日额度，请稍后再试",
  ai_disabled: "AI 已关闭，请刷新页面",
  otp_rule_managed: "验证码由规则管理，不能手动修改 AI 分类",
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
  sms: `<svg viewBox="0 0 24 24"><path d="M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM9 11H7V9h2v2zm4 0h-2V9h2v2zm4 0h-2V9h2v2z"/></svg>`,
  label: `<svg viewBox="0 0 24 24"><path d="M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z"/></svg>`,
  autoAwesome: `<svg viewBox="0 0 24 24"><path d="M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z"/></svg>`,
  notifications: `<svg viewBox="0 0 24 24"><path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>`,
  palette: `<svg viewBox="0 0 24 24"><path d="M12 3a9 9 0 1 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"/></svg>`,
  logout: `<svg viewBox="0 0 24 24"><path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z"/></svg>`,
  info: `<svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>`,
  visibility: `<svg viewBox="0 0 24 24"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/></svg>`,
  visibilityOff: `<svg viewBox="0 0 24 24"><path d="M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z"/></svg>`,
  arrowDownward: `<svg viewBox="0 0 24 24"><path d="M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z"/></svg>`,
  emptyMessages: `<svg viewBox="0 0 160 160" fill="none" xmlns="http://www.w3.org/2000/svg">
    <circle cx="80" cy="80" r="70" fill="currentColor" fill-opacity="0.12"/>
    <rect x="42" y="46" width="76" height="58" rx="16" fill="currentColor" fill-opacity="0.3"/>
    <path d="M54 62h52M54 74h36M54 86h24" stroke="currentColor" stroke-width="4" stroke-linecap="round"/>
    <circle cx="106" cy="102" r="18" fill="var(--md-sys-color-primary)"/>
    <path d="M102 96l8 6-8 6" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>`,
};

function avatar(contact, sender, extraClass = "") {
  const service = !contact && isServiceNumber(sender);
  const initial = avatarInitial(contact, sender);
  const node = el("span", ["avatar", service ? "avatar-service" : "", extraClass].filter(Boolean).join(" "));
  node.style.backgroundColor = avatarColor(sender);
  if (initial) node.textContent = initial;
  else node.innerHTML = service ? ICONS.sms : ICONS.person;
  node.setAttribute("role", "img");
  node.setAttribute("aria-label", `${contact || sender}头像`);
  return node;
}

function simLabel(phone) {
  const sim = state.sims.find((s) => s.phone === phone);
  return sim?.label || phone;
}

function formatClock(t) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(t));
}

function emptyState(icon, title, text) {
  const empty = el("div", "empty-state");
  const blob = el("div", "empty-blob");
  blob.innerHTML = icon;
  empty.append(blob, el("h3", "", title), el("p", "", text));
  return empty;
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

const THEME_KEY = "hongshu-theme";
function readTheme() {
  try { return normalizeTheme(localStorage.getItem(THEME_KEY)); } catch { return "system"; }
}
function applyTheme(choice) {
  const root = document.documentElement;
  if (choice === "system") delete root.dataset.theme;
  else root.dataset.theme = choice;
  for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
    meta.content = themeColorFor(choice, meta.media);
  }
}
// The only value this page ever stores in the browser: "system", "light" or "dark".
function saveTheme(choice) {
  const theme = normalizeTheme(choice);
  try { localStorage.setItem(THEME_KEY, theme); } catch { /* private mode: applies to this page only */ }
  applyTheme(theme);
}


// The open conversation is one browser history entry, so the system back gesture
// returns to the list on phones instead of leaving the app.
const DETAIL_HISTORY = "hongshuDetail";
function setDetailOpen(open) {
  $(".layout")?.classList.toggle("has-detail", open);
  document.body.dataset.detail = String(open);
}
function enterDetailHistory() {
  if (history.state?.[DETAIL_HISTORY]) history.replaceState({ [DETAIL_HISTORY]: true }, "");
  else history.pushState({ [DETAIL_HISTORY]: true }, "");
}
function leaveDetailHistory() {
  if (history.state?.[DETAIL_HISTORY]) history.back();
}
function closeDetail() {
  const wasOpen = Boolean(state.sender);
  state.sender = "";
  state.receiver = "";
  state.messages = [];
  state.seenMessageId = 0;
  setDetailOpen(false);
  renderConversations();
  renderDetail();
  if (wasOpen) playReturn($(".layout"), "returning-list");
}

// Shared-axis "back": the page you return to slides in from the left (fades on wide screens).
// "returning-list" only animates on phones, where the list was hidden behind the conversation.
function playReturn(layout, cls = "returning") {
  state.returning = false;
  if (!layout || reducedMotion()) return;
  layout.classList.remove(cls);
  void layout.offsetWidth;
  layout.classList.add(cls);
  setTimeout(() => layout.classList.remove(cls), 400);
}

// Only waits longer than 300ms show the progress bar, so quick requests do not flash it.
let busyTimer = null;
function setBusy(b) {
  state.busy = b;
  clearTimeout(busyTimer);
  const p = $("#global-progress");
  if (!p) return;
  if (!b) { p.style.display = "none"; return; }
  busyTimer = setTimeout(() => {
    const bar = $("#global-progress");
    if (state.busy && bar) bar.style.display = "block";
  }, 300);
}

const reducedMotion = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

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
  document.body.dataset.detail = "false";
  const card = el("main", "auth");
  const logo = el("img", "auth-logo");
  logo.src = "/icon.svg";
  logo.alt = "";
  card.append(
    logo,
    el("h1", "", "鸿枢"),
    el("p", "auth-subtitle", "所有手机的短信，在自己的中枢相遇。"),
  );
  if (remoteHTTP()) {
    const warning = el("div", "security-warning");
    warning.setAttribute("role", "alert");
    const icon = el("span");
    icon.innerHTML = ICONS.warning;
    warning.append(icon.firstElementChild, el("span", "", "当前使用明文 HTTP。密码、短信和登录凭据未加密，可能被窃听或篡改；请优先使用 HTTPS 或 VPN。"));
    card.append(warning);
  }
  const form = el("form");
  const password = el("input");
  password.required = true;
  password.type = "password";
  password.maxLength = 1024;
  password.autocomplete = "current-password";
  const label = el("label", "auth-label");
  const field = el("div", "password-field");
  // The toggle sits after the input inside the label, so the label still names the input.
  const reveal = el("button", "icon-button password-toggle");
  reveal.type = "button";
  const setReveal = (shown) => {
    password.type = shown ? "text" : "password";
    reveal.innerHTML = shown ? ICONS.visibilityOff : ICONS.visibility;
    reveal.setAttribute("aria-label", shown ? "隐藏密码" : "显示密码");
    reveal.setAttribute("aria-pressed", String(shown));
  };
  reveal.onclick = (event) => {
    event.preventDefault();
    setReveal(password.type === "password");
    password.focus();
  };
  setReveal(false);
  field.append(password, reveal);
  label.append(el("span", "", "中枢密码"), field);
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
      setReveal(false);
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
  password.focus();
}

// -------------------------------------------------------------
// App Shell: Messages-style layout, no navigation rail.
// Settings is a full page reached from the gear in the list header.
// -------------------------------------------------------------
const SETTINGS_HISTORY = "hongshuSettings";
async function openSettings() {
  state.page = "settings";
  history.pushState({ [SETTINGS_HISTORY]: true }, "");
  await reloadMetadata();
  shell();
}
function closeSettings() {
  if (history.state?.[SETTINGS_HISTORY]) { history.back(); return; }
  state.page = "inbox";
  state.returning = true;
  shell();
}

function shell() {
  const root = $("#app");
  root.replaceChildren();

  const progContainer = el("div", "progress-container");
  progContainer.id = "global-progress";
  progContainer.style.display = state.busy ? "block" : "none";
  progContainer.append(el("div", "progress-bar-wavy"));

  const container = el("div", "shell");
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

  container.append(main);
  root.append(container);

  if (state.page === "settings") settingsView();
  else inboxView();
  document.body.dataset.detail = String(state.page === "inbox" && Boolean(state.sender));

  setStatus(
    state.ws?.readyState === 1 ? "实时连接" : "自动补齐",
    state.ws?.readyState === 1,
  );
}

let lastOnline = null;
function setStatus(text, online) {
  const n = $(".status");
  if (!n) { lastOnline = online; return; }
  const label = online ? "实时连接" : "自动补齐";
  n.title = online ? "已建立长连接，新短信实时到达" : `${text}：每 30 秒自动补齐`;
  n.replaceChildren(el("span", "status-dot"), el("span", "status-label", label));
  n.className = "status" + (online ? " online" : "");
  // Pulse once when the connection comes back, never continuously.
  if (online && lastOnline === false) n.classList.add("reconnected");
  lastOnline = online;
}

// -------------------------------------------------------------
// Inbox column: brand header, pill search, one row of chips, list
// -------------------------------------------------------------
function inboxView() {
  const main = $(".main");

  // Visually hidden page heading for screen readers and the browser smoke test
  main.append(el("h1", "sr-only", "短信收件箱"));

  const layout = el("section", "layout" + (state.sender ? " has-detail" : ""));
  const inbox = el("div", "inbox");

  const header = el("header", "inbox-header");
  const logo = el("img", "brand-logo");
  logo.src = "/icon.svg";
  logo.alt = "";
  const status = el("span", "status");
  status.setAttribute("role", "status");
  header.append(
    logo,
    el("strong", "brand-title", "鸿枢"),
    status,
    el("span", "spacer"),
    iconButton(ICONS.settings, "设置", openSettings),
  );

  const searchContainer = el("div", "search-container");
  searchContainer.innerHTML = `<span class="search-icon">${ICONS.search}</span>`;
  const search = el("input", "search");
  search.type = "search";
  search.placeholder = "搜索短信、号码或联系人";
  search.setAttribute("aria-label", "搜索短信");
  search.value = state.q;
  const clearBtn = iconButton(
    ICONS.close,
    "清空搜索",
    () => {
      search.value = "";
      state.q = "";
      clearBtn.classList.remove("visible");
      search.focus();
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
      refreshInbox().catch((e) => toast(e.message));
    }, 250);
  };
  searchContainer.append(search, clearBtn);

  const chipRow = el("div", "chip-row");
  // The filter comes first so it stays in view while the group chips scroll.
  chipRow.append(el("div", "filter-slot"), el("div", "group-chips"));

  const list = el("div", "list");
  list.addEventListener("scroll", () => {
    if (list.scrollHeight - list.scrollTop - list.clientHeight < 240) {
      void loadMoreConversations().catch((e) => toast(e.message));
    }
  }, { passive: true });
  inbox.append(header, searchContainer, chipRow, el("p", "analysis-status"), list);
  layout.append(inbox, el("article", "detail"));
  if (state.returning) playReturn(layout);
  main.append(layout);

  renderGroupBar();
  renderFilterMenu();
  renderConversations();
  renderDetail();
  void refreshInbox().catch((e) => toast(e.message));
}

// SIM and device filters share one chip and one menu; it only appears when there is
// something to choose between, or a filter is already applied.
let activeFilterMenu = null;
let filterMenuEventsInstalled = false;

function installFilterMenuEvents() {
  if (filterMenuEventsInstalled || typeof document === "undefined") return;
  filterMenuEventsInstalled = true;
  document.addEventListener("click", (event) => {
    if (activeFilterMenu && !activeFilterMenu.wrapper.contains(event.target)) activeFilterMenu.close();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && activeFilterMenu) activeFilterMenu.close(true);
  });
  window.addEventListener("resize", () => activeFilterMenu?.close());
}

function filterSections() {
  const sims = [...new Map(state.sims.map((s) => [s.phone, s.label || s.phone])).entries()];
  const phones = state.devices.filter((d) => d.kind === "android" && !d.revoked).map((d) => [d.id, d.name]);
  return [
    { key: "sim", title: "接收号码", all: "所有 SIM", items: sims },
    { key: "device", title: "采集设备", all: "所有设备", items: phones },
  ];
}

function renderFilterMenu() {
  const slot = $(".filter-slot");
  if (!slot) return;
  installFilterMenuEvents();
  if (activeFilterMenu) activeFilterMenu.close();
  slot.replaceChildren();
  const sections = filterSections();
  const worthShowing = sections.some((s) => s.items.length > 1) || state.sim || state.device;
  if (!worthShowing) return;

  const wrapper = el("div", "filter-chip-wrapper");
  const active = Boolean(state.sim || state.device);
  const trigger = el("button", "filter-chip" + (active ? " active" : ""));
  trigger.type = "button";
  trigger.setAttribute("aria-label", "筛选");
  trigger.setAttribute("aria-haspopup", "listbox");
  trigger.setAttribute("aria-expanded", "false");
  const chosen = sections
    .map((s) => s.items.find(([v]) => String(v) === String(state[s.key]))?.[1])
    .filter(Boolean);
  const check = el("span", "chip-check");
  check.innerHTML = ICONS.check;
  const arrow = el("span", "filter-chip-arrow");
  arrow.innerHTML = ICONS.arrowDropDown;
  trigger.append(check, el("span", "filter-chip-label", chosen.join(" · ") || "筛选"), arrow);

  const menu = el("div", "filter-menu-popup");
  menu.hidden = true;
  menu.setAttribute("role", "listbox");
  menu.setAttribute("aria-label", "筛选");
  const options = [];
  for (const section of sections) {
    if (!section.items.length && !state[section.key]) continue;
    const heading = el("div", "filter-menu-heading", section.title);
    heading.setAttribute("role", "presentation");
    menu.append(heading);
    for (const [value, label] of [["", section.all], ...section.items]) {
      const selected = String(state[section.key] || "") === String(value);
      const option = el("button", "filter-menu-option" + (selected ? " selected" : ""));
      option.type = "button";
      option.tabIndex = -1;
      option.setAttribute("role", "option");
      option.setAttribute("aria-selected", String(selected));
      const mark = el("span", "filter-menu-check");
      mark.innerHTML = ICONS.check;
      option.append(mark, el("span", "filter-menu-option-label", label));
      option.onclick = (event) => { event.stopPropagation(); choose(section.key, value); };
      options.push(option);
      menu.append(option);
    }
  }

  function close(focus = false) {
    menu.hidden = true;
    trigger.setAttribute("aria-expanded", "false");
    if (activeFilterMenu?.wrapper === wrapper) activeFilterMenu = null;
    if (focus) trigger.focus();
  }
  function open() {
    if (activeFilterMenu && activeFilterMenu.wrapper !== wrapper) activeFilterMenu.close();
    activeFilterMenu = { wrapper, close };
    // The chip row scrolls horizontally, so the menu is positioned against the viewport.
    const rect = trigger.getBoundingClientRect();
    menu.style.top = `${rect.bottom + 6}px`;
    menu.style.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - 232))}px`;
    menu.hidden = false;
    trigger.setAttribute("aria-expanded", "true");
    (options.find((o) => o.classList.contains("selected")) || options[0])?.focus();
  }
  function choose(key, value) {
    close(true);
    state[key] = value;
    renderFilterMenu();
    void refreshInbox().catch((e) => toast(e.message));
  }

  trigger.onclick = (event) => {
    event.stopPropagation();
    if (menu.hidden) open(); else close();
  };
  trigger.onkeydown = (event) => {
    if (["ArrowDown", "ArrowUp"].includes(event.key)) { event.preventDefault(); open(); }
  };
  menu.onkeydown = (event) => {
    const current = options.indexOf(document.activeElement);
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      options[(current + step + options.length) % options.length]?.focus();
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      options[event.key === "Home" ? 0 : options.length - 1]?.focus();
    }
  };

  wrapper.append(trigger, menu);
  slot.append(wrapper);
}

let inboxAbortController = null;
let refreshGeneration = 0;
async function refreshInbox({ metadata = true, preserveHistory = false, revalidateHistory = false, arrivals = false } = {}) {
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

    if (gen === refreshGeneration) renderFilterMenu();
    const args = {
      group: state.group,
      q: state.q,
      sim: state.sim,
      device: state.device,
      offset: 0,
      limit: CONVERSATION_PAGE,
    };
    const [d, counts] = await Promise.all([
      api("/conversations?" + query(args), "GET", undefined, { signal }),
      api("/analysis/counts?" + query({q:state.q,sim:state.sim,device:state.device}), "GET", undefined, { signal }),
    ]);
    if (signal.aborted || gen !== refreshGeneration) return;
    const head = d.conversations;
    const headIsFull = head.length === CONVERSATION_PAGE;
    state.conversations = preserveHistory ? mergeConversationPages(head, state.conversations, headIsFull) : head;
    if (!preserveHistory || !headIsFull) state.conversationsHasMore = headIsFull;
    state.conversationsLoaded = true;
    state.counts = counts.counts;
    renderGroupBar();
    renderConversations({ arrivals });
    if (state.sender) await loadHistory(false, { preserve: preserveHistory, revalidate: revalidateHistory });
  } catch (err) {
    if (err.name === "AbortError" || signal.aborted) return;
    throw err;
  } finally {
    setBusy(false);
  }
}

async function loadMoreConversations() {
  if (!state.me || state.loadingMore || !state.conversationsHasMore) return;
  const gen = refreshGeneration;
  state.loadingMore = true;
  $(".list-more")?.removeAttribute("hidden");
  try {
    const d = await api("/conversations?" + query({
      group: state.group, q: state.q, sim: state.sim, device: state.device,
      offset: state.conversations.length, limit: CONVERSATION_PAGE,
    }));
    if (gen !== refreshGeneration || !state.me) return;
    const keys = new Set(state.conversations.map(conversationKey));
    state.conversations = state.conversations.concat(d.conversations.filter((c) => !keys.has(conversationKey(c))));
    state.conversationsHasMore = d.conversations.length === CONVERSATION_PAGE;
  } finally {
    state.loadingMore = false;
  }
  renderConversations();
}

function skeletonRows() {
  const box = el("div", "list-skeleton");
  box.setAttribute("aria-hidden", "true");
  for (let i = 0; i < 6; i++) {
    const row = el("div", "conversation-skeleton");
    row.append(el("span", "skeleton-avatar"), el("span", "skeleton-lines"));
    box.append(row);
  }
  return box;
}

function renderGroupBar() {
  const bar = $(".group-chips");
  if (!bar) return;
  bar.replaceChildren();
  for (const group of visibleGroups(state.me?.ai_enabled)) {
    const selected = state.group === group;
    const chip = el("button", "filter-chip" + (selected ? " active" : ""));
    chip.type = "button";
    const check = el("span", "chip-check");
    check.innerHTML = ICONS.check;
    // " 12" keeps the accessible name as "全部短信 12".
    chip.append(check, document.createTextNode(GROUP_LABELS[group]), el("span", "chip-count", ` ${state.counts[group] || 0}`));
    chip.setAttribute("aria-pressed", String(selected));
    attachRipple(chip);
    chip.onclick = async () => {
      state.group = group;
      closeDetail(); leaveDetailHistory();
      renderGroupBar();
      setBusy(true);
      try { await refreshInbox(); } catch (e) { toast(e.message); } finally { setBusy(false); }
    };
    bar.append(chip);
  }
  const status = $(".analysis-status");
  if (status) {
    status.replaceChildren();
    if (state.me?.ai_enabled) {
      const icon = el("span");
      icon.innerHTML = ICONS.autoAwesome;
      status.append(icon.firstElementChild, document.createTextNode(
        `AI ${state.ai?.paused ? "已暂停" : "已开启"} · 待分析 ${state.counts.pending || 0} 条` +
        (state.ai?.usage_count >= state.ai?.daily_limit ? " · 今日额度已用尽，UTC 零点恢复" : "") +
        (state.ai?.last_error ? " · 服务异常，请在设置中处理" : ""),
      ));
    }
  }
}

function renderConversations({ arrivals = false } = {}) {
  const list = $(".list");
  if (!list) return;
  // FLIP: remember where each row was so rows pushed down by an arrival glide into place.
  const before = arrivals && !reducedMotion()
    ? new Map([...list.querySelectorAll(".conversation")].map((r) => [r.dataset.key, r.getBoundingClientRect().top]))
    : null;
  list.replaceChildren();
  list.setAttribute("aria-busy", String(!state.conversationsLoaded));

  if (!state.conversations.length) {
    state.renderedRows = new Map();
    if (!state.conversationsLoaded) { list.append(skeletonRows()); return; }
    list.append(emptyState(ICONS.sms, "暂无短信", "暂无短信。请配置 Android 采集设备及 SIM 号码。"));
    return;
  }

  const fresh = arrivals ? freshConversationKeys(state.renderedRows, state.conversations) : new Set();
  state.renderedRows = new Map(state.conversations.map((m) => [conversationKey(m), m.timestamp]));
  state.conversations.forEach((m) => {
    const isSelected = state.sender === m.sender && state.receiver === m.receiver;
    const b = el(
      "button",
      "conversation" + (isSelected ? " selected" : "") +
        (fresh.has(conversationKey(m)) ? " conversation-new" : ""),
    );
    b.type = "button";
    b.dataset.key = conversationKey(m);
    if (isSelected) b.setAttribute("aria-current", "true");
    attachRipple(b);

    b.onclick = async () => {
      if (!isSelected) {
        state.sender = m.sender;
        state.receiver = m.receiver;
        state.messages = [];
        state.seenMessageId = 0;
        state.historyHasMore = true;
        // Shown as loading until the first page arrives, instead of a momentary empty state.
        state.historyLoading = true;
      }
      setDetailOpen(true);
      enterDetailHistory();
      renderConversations();
      renderDetail();
      await loadHistory(false);
    };

    const contact = state.contacts[m.sender] || m.contact || "";
    const preview = el("div", "preview");
    const top = el("div", "preview-top");
    top.append(
      el("strong", "", contact || m.sender),
      el("time", "", `${simLabel(m.receiver)} · ${formatRelativeTime(m.timestamp)}`),
    );
    const bottom = el("div", "preview-bottom");
    if (m.analysis?.otp) bottom.append(el("span", "otp-tag", "验证码"));
    bottom.append(el("p", "", m.body));
    preview.append(top, bottom);

    b.append(avatar(contact, m.sender), preview);
    list.append(b);
  });

  const more = el("div", "list-more", "正在加载更早的会话…");
  more.hidden = !state.loadingMore;
  list.append(more);

  if (before?.size) {
    for (const row of list.querySelectorAll(".conversation")) {
      const top = before.get(row.dataset.key);
      if (top === undefined) continue;
      const dy = top - row.getBoundingClientRect().top;
      if (dy) row.animate([{ transform: `translateY(${dy}px)` }, { transform: "none" }], { duration: 350, easing: "cubic-bezier(0.05, 0.7, 0.1, 1)" });
    }
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

async function loadHistory(older, { preserve = false, revalidate = false } = {}) {
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
    group: state.group,
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
    let incoming = d.messages || [];
    let lastPageLength = incoming.length;
    // Metadata can move old messages out of the selected group. Re-fetch the displayed
    // page range instead of retaining stale objects or collapsing the user's older pages.
    if (revalidate && !older) {
      const pages = Math.max(1, Math.ceil(state.messages.length / MESSAGE_PAGE_SIZE));
      for (let page = 1; page < pages && lastPageLength === MESSAGE_PAGE_SIZE; page++) {
        const next = await api("/messages?" + query({...args, before:incoming.at(-1).id}), "GET", undefined, {signal});
        lastPageLength = next.messages?.length || 0;
        incoming = incoming.concat(next.messages || []);
      }
    }
    if (
      signal?.aborted ||
      requestId !== historyRequestId ||
      generation !== state.generation || !state.me ||
      sender !== state.sender ||
      receiver !== state.receiver
    ) {
      return;
    }

    state.messages = older || (preserve && !revalidate)
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
      state.historyHasMore = revalidate ? lastPageLength === MESSAGE_PAGE_SIZE : state.historyHasMore || incoming.length === MESSAGE_PAGE_SIZE;
    }
    state.historyLoading = false;
    const freshCount = renderDetail();

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
      updateJumpButton(freshCount > 0);
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
  if (!detail) return 0;
  detail.replaceChildren();
  // Fade through when a different conversation replaces the current one.
  const detailKey = state.sender ? conversationKey({ sender: state.sender, receiver: state.receiver }) : "";
  if (detailKey && detailKey !== state.renderedDetailKey && !reducedMotion()) {
    detail.classList.remove("detail-enter");
    void detail.offsetWidth;
    detail.classList.add("detail-enter");
  }
  state.renderedDetailKey = detailKey;

  if (!state.sender) {
    const devices = state.devices.filter((d) => d.kind === "android" && !d.revoked).length;
    const sims = new Set(state.sims.map((s) => s.phone)).size;
    detail.append(emptyState(ICONS.chat, "选择一个会话", devices
      ? `短信来自 ${devices} 台手机、${sims} 张 SIM 卡`
      : "连接 Android 手机后，短信会出现在这里"));
    return 0;
  }

  const head = el("header", "detail-head");
  head.append(iconButton(ICONS.arrowBack, "返回", () => {
    // popstate closes the conversation when it owns a history entry.
    if (history.state?.[DETAIL_HISTORY]) history.back();
    else closeDetail();
  }, "back"));

  const contact = state.contacts[state.sender] || state.messages.at(-1)?.contact || "";
  const titleBox = el("div", "detail-head-info");
  const infoText = el("div");
  infoText.append(
    el("strong", "", contact || state.sender),
    el("div", "sub-info", [contact ? state.sender : "", state.receiver ? `接收：${simLabel(state.receiver)}${simLabel(state.receiver) !== state.receiver ? " " + state.receiver : ""}` : ""].filter(Boolean).join(" · ")),
  );
  titleBox.append(avatar(contact, state.sender, "avatar-detail"), infoText);
  head.append(titleBox);

  const headActions = el("div", "detail-head-actions");
  headActions.append(iconButton(ICONS.contentCopy, "复制号码", async () => {
    await copyText(state.sender);
    toast("已复制号码 " + state.sender);
  }));
  head.append(headActions);
  detail.append(head);

  const messages = el("div", "messages");
  const inner = el("div", "messages-inner");
  const historyStatus = el("div", "history-status");
  historyStatus.setAttribute("role", "status");
  historyStatus.setAttribute("aria-live", "polite");
  inner.append(historyStatus);
  messages.append(inner);
  messages.addEventListener("scroll", () => {
    if (messages.scrollTop <= 80 && state.historyHasMore && !state.historyLoading) {
      void loadHistory(true).catch((error) => toast(error.message));
    }
    updateJumpButton();
  }, { passive: true });
  updateHistoryStatus();

  if (!state.messages.length && !state.historyLoading) {
    inner.append(el("p", "empty", "没有符合筛选条件的消息"));
  }

  const seenId = state.seenMessageId;
  let freshCount = 0;
  let lastDay = "";
  for (const cluster of clusterMessages(state.messages)) {
    const dayStr = dateDay(cluster.start);
    if (dayStr !== lastDay) {
      const divider = el("div", "date-divider");
      divider.append(el("span", "", dayStr));
      inner.append(divider);
      lastDay = dayStr;
    }
    const group = el("div", "bubble-group");
    for (const msg of cluster.messages) {
      const fresh = isFreshArrival(msg.id, seenId);
      if (fresh) freshCount++;
      group.append(bubble(msg, fresh));
    }
    const deviceName = state.devices.find((d) => d.id === cluster.device_id)?.name || "来源设备";
    group.append(el("div", "cluster-stamp", `${formatClock(cluster.end)} · ${deviceName}`));
    inner.append(group);
  }
  state.seenMessageId = state.messages.reduce((high, msg) => Math.max(high, msg.id), seenId);

  const jump = el("button", "jump-latest");
  jump.type = "button";
  jump.setAttribute("aria-label", "跳到最新短信");
  jump.innerHTML = ICONS.arrowDownward;
  jump.append(el("span", "jump-label", "最新"));
  jump.onclick = () => {
    messages.scrollTo({ top: messages.scrollHeight, behavior: reducedMotion() ? "auto" : "smooth" });
  };
  detail.append(messages, jump);
  return freshCount;
}

// Shown when the reader has scrolled away from the newest message. A message that
// arrives meanwhile changes the label instead of yanking the scroll position.
function updateJumpButton(announceNew = false) {
  const stream = $(".messages");
  const jump = $(".jump-latest");
  if (!stream || !jump) return;
  const away = stream.scrollHeight - stream.scrollTop - stream.clientHeight > 160;
  if (announceNew && away) jump.classList.add("has-new");
  if (!away) jump.classList.remove("has-new");
  jump.classList.toggle("visible", away);
  jump.querySelector(".jump-label").textContent = jump.classList.contains("has-new") ? "新短信" : "最新";
}

function bubble(msg, fresh) {
  const b = el("div", "bubble" + (fresh ? " bubble-new" : ""));
  const otp = msg.analysis?.otp;
  if (otp) b.append(otpCard(msg));
  b.append(linkedText(msg.body));

  if (state.me?.ai_enabled && !otp) {
    const a = msg.analysis;
    // A plain automatic result needs no label; pending, failed and manual states do.
    if (!a || a.state !== "complete" || a.manual) {
      b.append(el("small", "analysis-message-state", `${GROUP_LABELS[a?.category] || "待分析"} · ${analysisLabel(a)}`));
    }
  }

  const actions = el("div", "bubble-actions");
  actions.append(iconButton(ICONS.contentCopy, "复制短信", async () => {
    await copyText(msg.body);
    toast("已复制短信");
  }));
  if (state.me?.ai_enabled && !otp) {
    const correction = el("details", "category-actions");
    const summary = el("summary");
    summary.innerHTML = ICONS.label;
    summary.append(el("span", "sr-only", "调整分类"));
    summary.title = "调整分类";
    const menu = el("div", "category-menu");
    for (const category of ["personal", "notification", "promotion", "spam", "auto", ...(msg.analysis?.state === "failed" ? ["retry"] : [])]) {
      menu.append(button(GROUP_LABELS[category] || (category === "auto" ? "恢复自动分类" : "重试分析"), async () => {
        correction.open = false;
        await api(`/messages/${msg.id}/category`, "PATCH", { category });
        await refreshInbox({ preserveHistory: true, revalidateHistory: true });
      }));
    }
    correction.append(summary, menu);
    actions.append(correction);
  }
  b.append(actions);
  return b;
}

function otpCard(msg) {
  const code = msg.analysis.otp;
  const card = el("div", "otp-card");
  const label = el("div", "otp-label");
  const validity = el("span");
  label.append(el("span", "", "验证码"), validity);
  const row = el("div", "otp-row");
  const copy = el("button", "otp-copy");
  copy.type = "button";
  const icon = el("span", "otp-copy-icon");
  icon.innerHTML = ICONS.contentCopy + ICONS.check;
  const copyLabel = el("span", "", "复制");
  copy.append(icon, copyLabel);
  copy.setAttribute("aria-label", "复制验证码");
  attachRipple(copy);
  copy.onclick = async () => {
    try {
      if (!navigator.clipboard) throw new Error("复制需要 HTTPS 或浏览器剪贴板权限");
      await navigator.clipboard.writeText(code);
      copy.classList.add("copied");
      copyLabel.textContent = "已复制";
      toast("验证码已复制");
      setTimeout(() => { copy.classList.remove("copied"); copyLabel.textContent = "复制"; }, 1600);
    } catch (error) {
      toast(error.message);
    }
  };
  row.append(el("strong", "otp-code", code), copy);
  card.append(label, row);

  const expires = msg.analysis.expires_at;
  if (expires) {
    // The bar shrinks over the remaining validity stated in the SMS; this is the only
    // continuous animation on the page because it shows time passing.
    const ttl = el("div", "otp-ttl");
    const bar = el("i");
    ttl.append(bar);
    card.append(ttl);
    const total = Math.max(expires - msg.timestamp, 1);
    const remaining = expires - Date.now();
    if (remaining <= 0) {
      card.classList.add("expired");
      validity.textContent = "按短信标注的有效期，可能已过期";
    } else {
      validity.textContent = `有效至 ${formatClock(expires)}`;
      const share = () => `${Math.max(0, Math.min(100, ((expires - Date.now()) / total) * 100))}%`;
      bar.style.width = share();
      if (reducedMotion()) {
        // No continuous motion: step the bar every few seconds so it still shows the time left.
        const timer = setInterval(() => {
          if (!card.isConnected || Date.now() >= expires) clearInterval(timer);
          else bar.style.width = share();
        }, 5000);
      } else {
        requestAnimationFrame(() => {
          bar.style.transition = `width ${remaining}ms linear`;
          bar.style.width = "0%";
        });
      }
      setTimeout(() => {
        if (!card.isConnected) return;
        card.classList.add("expired");
        validity.textContent = "按短信标注的有效期，可能已过期";
      }, remaining);
    }
  }
  return card;
}

async function copyText(text) {
  if (!navigator.clipboard) throw new Error("复制需要 HTTPS 或浏览器剪贴板权限");
  await navigator.clipboard.writeText(text);
}

function linkedText(text) {
  const fragment = document.createDocumentFragment();
  for (const part of linkSegments(text)) {
    if (part.text !== undefined) {
      fragment.append(document.createTextNode(part.text));
      continue;
    }
    const link = el("a", "", part.url);
    link.href = part.url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    fragment.append(link);
  }
  return fragment;
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
// Settings building blocks: segmented groups as on Android (settingsGroup /
// SettingsItem): rounded group ends, tight corners between rows.
// -------------------------------------------------------------
function settingsGroup(title, rows = []) {
  const section = el("section", "settings-section");
  section.append(el("h2", "section-title", title));
  const group = el("div", "settings-group");
  group.append(...rows);
  section.append(group);
  return { section, group };
}

function settingsRow({ icon, title, summary, summaryNode, trailing, tag = "div", cls = "" }) {
  const row = el(tag, `settings-item ${cls}`.trim());
  if (icon) {
    const badge = el("span", "settings-icon");
    badge.innerHTML = icon;
    row.append(badge);
  }
  const text = el("div", "settings-text");
  text.append(el("strong", "settings-title", title));
  if (summaryNode) text.append(summaryNode);
  else if (summary) text.append(el("span", "settings-summary", summary));
  row.append(text);
  if (trailing) row.append(trailing);
  return row;
}

function themeControl() {
  const current = readTheme();
  const box = el("div", "segmented");
  box.setAttribute("role", "radiogroup");
  box.setAttribute("aria-label", "主题");
  const options = [["system", "跟随系统"], ["light", "浅色"], ["dark", "深色"]].map(([value, label]) => {
    const option = el("button", "segmented-option", label);
    option.type = "button";
    option.setAttribute("role", "radio");
    option.dataset.value = value;
    return option;
  });
  function select(option, focus = false) {
    for (const o of options) {
      const on = o === option;
      o.setAttribute("aria-checked", String(on));
      o.tabIndex = on ? 0 : -1;
    }
    if (focus) option.focus();
    saveTheme(option.dataset.value);
  }
  for (const option of options) {
    const on = option.dataset.value === current;
    option.setAttribute("aria-checked", String(on));
    option.tabIndex = on ? 0 : -1;
    option.onclick = () => select(option);
  }
  box.onkeydown = (event) => {
    const index = options.indexOf(document.activeElement);
    if (index < 0) return;
    const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[event.key];
    if (!step) return;
    event.preventDefault();
    select(options[(index + step + options.length) % options.length], true);
  };
  box.append(...options);
  return box;
}

function queueChips(s) {
  const q = s?.queue || {};
  const box = el("div", "queue-chips ai-queue-status");
  box.setAttribute("aria-label", aiQueueLabel(s));
  for (const [label, n] of [["等待", q.pending], ["处理中", q.processing], ["失败/待确认", q.failed], ["已完成", q.complete], ["规则待回填", q.backfill]]) {
    const chip = el("span", "queue-chip");
    chip.append(document.createTextNode(`${label} `), el("b", "", String(n || 0)));
    box.append(chip);
  }
  return box;
}

// -------------------------------------------------------------
// Devices & SIMs: read-only phone inventory, shown inside Settings
// -------------------------------------------------------------
function deviceRows() {
  const phones = state.devices.filter((device) => device.kind === "android" && !device.revoked);
  if (!phones.length) {
    return [settingsRow({ icon: ICONS.phoneAndroid, title: "暂无已连接手机", summary: "连接 Android 手机后，设备信息会显示在这里。" })];
  }
  return phones.map((d) => {
    const sims = state.sims.filter((s) => s.device_id === d.id);
    return settingsRow({
      icon: ICONS.phoneAndroid,
      title: d.name,
      summary: sims.length ? sims.map((s) => `${s.label || "SIM"} ${s.phone}`).join(" · ") : "暂无 SIM 信息",
      trailing: el("span", "badge", "Android 手机"),
      cls: "device-card",
    });
  });
}

// -------------------------------------------------------------
// Settings View
// -------------------------------------------------------------
function settingsView() {
  const main = $(".main");
  const container = el("div", "page-container settings-container");
  const inner = el("div", "page-inner");
  container.append(inner);

  const head = el("header", "page-head");
  head.append(iconButton(ICONS.arrowBack, "返回收件箱", closeSettings), el("h1", "", "设置"));
  inner.append(head);

  const status = el("p", "settings-summary notification-state", "正在读取通知状态…");
  status.setAttribute("aria-live", "polite");
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
  inner.append(settingsGroup("本浏览器", [
    settingsRow({ icon: ICONS.notifications, title: "浏览器通知", summaryNode: status, trailing: toggle }),
    settingsRow({ icon: ICONS.palette, title: "主题", summary: "只影响这台浏览器", trailing: themeControl(), cls: "settings-wrap" }),
  ]).section);

  const ai = settingsGroup("中枢 AI 分组");
  inner.append(ai.section);
  void renderAISettings(ai.group).catch((e) => {
    if (ai.group.isConnected) ai.group.append(settingsRow({ icon: ICONS.warning, title: "无法读取 AI 设置", summary: e.message }));
  });

  inner.append(settingsGroup("设备与 SIM", deviceRows()).section);

  const logoutRow = settingsRow({ tag: "button", icon: ICONS.logout, title: "退出登录", summary: "这台浏览器不保存任何短信", cls: "settings-danger" });
  logoutRow.type = "button";
  logoutRow.setAttribute("aria-label", "退出登录");
  attachRipple(logoutRow);
  logoutRow.onclick = () => {
    setBusy(true);
    Promise.resolve(logout()).catch((e) => toast(e.message)).finally(() => setBusy(false));
  };
  inner.append(settingsGroup("账户", [logoutRow]).section);
  main.append(container);

  void browserPushEnabled().then((enabled) => {
    toggle.setChecked(enabled);
    status.textContent = enabled ? "通知已开启" : "通知已关闭";
  }).catch(() => {
    status.textContent = "通知已关闭";
  });
}

async function renderAISettings(group) {
  const s = await api("/analysis/settings");
  if (!group.isConnected) return;
  const label = s.enabled ? (s.paused ? "已暂停" : "已开启") : "已关闭";
  group.append(settingsRow({
    icon: ICONS.autoAwesome,
    title: "AI 短信分组",
    summary: "开启后，历史及新增非验证码短信会自动发送到配置的 AI 服务分析。验证码仅用规则提取；脱敏不能保证匿名。垃圾分组不会删除或拦截短信。",
    trailing: el("span", "badge" + (s.enabled && !s.paused ? " badge-on" : ""), label),
  }));
  const usage = el("div", "settings-summary");
  usage.append(queueChips(s));
  if (s.last_error) usage.append(el("p", "settings-error", s.last_error));
  group.append(settingsRow({ icon: ICONS.label, title: `队列 · 今日已调用 ${s.usage_count}/${s.daily_limit}`, summaryNode: usage }));
  if (!state.me?.device?.admin) {
    group.append(settingsRow({ icon: ICONS.info, title: "中枢 AI 配置", summary: "请由管理员在 Web 设置配置中枢 AI" }));
    return;
  }
  const form = el("form", "ai-form");
  const fields = {};
  // Booleans render as M3 switches on native checkboxes, so labels, keyboard use and
  // form values keep working; the consent stays a plain checkbox because it is an acknowledgement.
  function field(key, label, type, value, parent = form) {
    const input = el("input"); input.type = type;
    let wrapper;
    if (type === "checkbox") {
      input.checked = Boolean(value);
      const asSwitch = key !== "consent";
      if (asSwitch) { input.setAttribute("role", "switch"); input.className = "m3-switch-input"; }
      wrapper = el("label", asSwitch ? "setting-switch" : "setting-check");
      const text = el("span", "setting-label", label);
      if (asSwitch) wrapper.append(text, input); else wrapper.append(input, text);
    } else {
      input.value = value ?? "";
      wrapper = el("label", "setting-field");
      wrapper.append(el("span", "setting-label", label), input);
    }
    parent.append(wrapper); fields[key] = input;
    return input;
  }
  field("enabled", "启用 AI 分组", "checkbox", s.enabled);
  field("paused", "暂停队列", "checkbox", s.paused);
  field("endpoint", "完整 Chat Completions 接口地址", "url", s.endpoint).placeholder = "https://example.com/v1/chat/completions";
  field("model", "模型", "text", s.model);
  const key = field("api_key", s.key_set ? "API 密钥（已设置；留空保留）" : "API 密钥（本地无鉴权服务可留空）", "password", "");
  key.autocomplete = "new-password";
  field("clear_key", "清除已保存密钥", "checkbox", false);
  const numbers = el("div", "setting-numbers");
  form.append(numbers);
  for (const [name,label,min,max] of [["timeout_seconds","超时（秒）",2,60],["requests_per_minute","每分钟调用上限",1,60],["daily_limit","每日调用上限（含失败与连接测试）",1,10000]]) {
    const input = field(name,label,"number",s[name],numbers); input.min = min; input.max = max; input.required = true; input.inputMode = "numeric";
  }
  field("redact", "遮蔽长数字、邮箱和链接", "checkbox", s.redact);
  field("consent", "我了解开启后会自动分析历史与新增非验证码短信", "checkbox", false);
  const actions = el("div", "setting-actions");
  actions.append(button("保存 AI 设置", async () => {
    if (!form.reportValidity()) return;
    const body = Object.fromEntries(Object.entries(fields).map(([name,input]) => [name,input.type === "checkbox" ? input.checked : input.type === "number" ? Number(input.value) : input.value]));
    await api("/analysis/settings", "PUT", body); key.value = "";
    await catchUp(); shell(); toast("AI 设置已保存");
  }, "primary"));
  actions.append(button("测试连接（仅合成文本）", async () => { await api("/analysis/test", "POST", {}); toast("连接及分类测试通过"); }, "tonal"));
  actions.append(button("恢复队列并重试失败项（每批100条）", async () => { const result = await api("/analysis/retry", "POST", {}); toast(`已重新排队 ${result.retried} 条`); await catchUp(); shell(); }, "text-button"));
  form.append(actions);
  const formRow = el("div", "settings-item settings-form");
  formRow.append(form);
  group.append(formRow);
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
  const [d, s, c, ai] = await Promise.all([api("/devices"), api("/sims"), api("/contacts"), api("/analysis/settings")]);
  if (generation !== state.generation || !state.me) return;
  state.devices = d.devices;
  state.sims = s.sims;
  state.contacts = c.contacts;
  state.ai = ai;
  state.me.ai_enabled = ai.enabled;
  if (!visibleGroups(ai.enabled).includes(state.group)) state.group = "all";
  $(".ai-queue-status")?.replaceWith(queueChips(ai));
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
  state.ai = null; state.counts = {}; state.group = "all";
  state.sender = state.receiver = state.q = state.sim = state.device = "";
  state.cursor = state.epoch = 0;
  state.conversationsLoaded = state.conversationsHasMore = state.loadingMore = false;
  state.renderedDetailKey = "";
  state.epochSeen = false;
  state.inboxDirty = false;
  state.page = "inbox";
  state.arrivalsPending = false;
  state.renderedRows = new Map();
  state.seenMessageId = 0;
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
let resyncGeneration = null;
async function catchUp() {
  const generation = state.generation;
  if (!state.me) return;
  if (syncingGeneration === generation) { resyncGeneration = generation; return; }
  syncingGeneration = generation;
  try {
    const me = await api("/me");
    if (generation !== state.generation || !state.me) return;
    const analysisChanged = me.analysis_revision !== state.me.analysis_revision || me.ai_enabled !== state.me.ai_enabled;
    const changed = analysisChanged || me.cursor !== state.cursor || epochReset(state.epochSeen, state.epoch, me.epoch);
    const previousMetadata = JSON.stringify([state.devices, state.sims, state.contacts]);
    state.arrivalsPending ||= me.cursor > state.cursor && !epochReset(state.epochSeen, state.epoch, me.epoch);
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
      await refreshInbox({ metadata: false, preserveHistory: true, revalidateHistory: analysisChanged, arrivals: state.arrivalsPending });
      state.inboxDirty = false;
      state.arrivalsPending = false;
    }
  } finally {
    if (syncingGeneration === generation) {
      syncingGeneration = null;
      const again = resyncGeneration === generation;
      resyncGeneration = null;
      if (again && generation === state.generation && state.me) await catchUp();
    }
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
  applyTheme(readTheme());
  setInterval(() => {
    if (state.me && document.visibilityState === "visible") {
      void catchUp().catch(() => setStatus("离线，历史暂不可用", false));
    }
  }, 30000);
  window.addEventListener("popstate", (event) => {
    if (state.page === "settings" && !event.state?.[SETTINGS_HISTORY]) {
      state.page = "inbox";
      state.returning = true;
      shell();
      return;
    }
    if (!event.state?.[DETAIL_HISTORY] && state.sender) closeDetail();
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && state.me) {
      void catchUp().catch(() => {});
    }
  });
  start().catch(() => authView());
}
