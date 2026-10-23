import { BrowserContacts, contactNumber, contactPickerProperties } from "./local-contacts.js";

const browserContacts = new BrowserContacts();
let pickerProperties = [];
let contactsBusy = false;
let contactsChannel;
let contactsReload = 0;
let contactSearchRequest = 0;

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
export const AI_PROTOCOLS = {
  openai_responses: "OpenAI Responses API（推荐）",
  openai_chat_completions: "OpenAI Chat Completions（兼容）",
  anthropic_messages: "Anthropic Messages",
  gemini_generate_content: "Google Gemini Generate Content",
};
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
// A conversation belongs to the sender. The receiver on a conversation row is only
// the latest message's receiving number, never part of the selection identity.
export const conversationKey = (c) => String(c.sender || "");
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

// Consecutive messages from the same party (incoming from the same device, or outgoing
// by the user) on the same day, at most five minutes apart, share one cluster so time
// and source are shown once. Outgoing messages cluster across devices because all sent
// SMS represent the user themselves.
export const CLUSTER_GAP_MS = 5 * 60 * 1000;
// A message the source phone sent. Hubs before outgoing support omit the field: received.
export const isOutgoing = (m) => m?.direction === "out";
const localDay = (t) => new Date(t).toDateString();
export function clusterMessages(messages) {
  const clusters = [];
  let current = null;
  for (const message of messages) {
    const outgoing = isOutgoing(message);
    const sameParty = current && (outgoing ? current.outgoing : (!current.outgoing && message.device_id === current.device_id));
    if (!current || !sameParty ||
        message.timestamp - current.end > CLUSTER_GAP_MS || localDay(message.timestamp) !== localDay(current.end)) {
      current = { device_id: message.device_id, outgoing, start: message.timestamp, end: message.timestamp, messages: [] };
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

export function updateAppBadge(count) {
  if (typeof navigator !== "undefined" && "setAppBadge" in navigator) {
    if (count > 0) {
      navigator.setAppBadge(count).catch(() => {});
    } else {
      navigator.clearAppBadge().catch(() => {});
    }
  }
  if (typeof document !== "undefined") {
    const baseTitle = "鸿枢 · 短信中枢";
    document.title = count > 0 ? `(${count}) ${baseTitle}` : baseTitle;
  }
}

// -------------------------------------------------------------
// UI State & Configuration
// -------------------------------------------------------------
const $ = (s) => document.querySelector(s);
// Only bounded timings/categories live here. Never retain URLs or response data.
export class NetworkProfile {
  constructor() { this.samples = []; this.slow = false; this.manual = false; }
  observe(ms) {
    this.samples.push(ms);
    if (this.samples.length > 8) this.samples.shift();
    const recent = this.samples.slice(-3);
    if (recent.length === 3 && recent.every(n => n >= 1200)) this.slow = true;
    if (this.samples.length >= 5 && this.samples.slice(-5).every(n => n < 700)) this.slow = false;
  }
  get constrained() { return this.manual || this.slow; }
}
const networkProfile = new NetworkProfile();
const loadingTimings = [];
function loadingMark(stage) {
  loadingTimings.push({ stage, at: Date.now() });
  if (loadingTimings.length > 24) loadingTimings.shift();
}
export function loadingDiagnostics() {
  return { stages: loadingTimings.map(sample => ({ ...sample })), network: networkProfile.samples.slice(), slow: networkProfile.constrained };
}
let metadataTask = null;
let metadataRevision = null;
let metadataUpdatedAt = 0;
let countsController = null;
let metadataChanges = 0;
const detailCache = new Map();
const conversationNodes = new Map();
function rememberDetail() {
  if (!state.messages.length || state.messagesQuery !== historyQueryKey()) return;
  detailCache.delete(state.messagesQuery);
  detailCache.set(state.messagesQuery, { messages: state.messages.slice(-50), more: state.messages.length > 50 || state.historyHasMore });
  while (detailCache.size > 4) detailCache.delete(detailCache.keys().next().value);
}
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
  conversationsQuery: "",
  messagesQuery: "",
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
  historyDirty: false,
  conversationsDirty: false,
  readRetry: false,
  pendingDisplayedRead: null,
  historyHasMore: true,
  historyLoading: false,
  group: "all",
  counts: {},
  ai: null,
  renderedRows: new Map(),
  seenMessageId: 0,
  arrivalsPending: false,
  unreadCount: 0,
};

const inboxQueryKey = () => JSON.stringify([state.group, state.q, state.sim, state.device]);
const historyQueryKey = () => JSON.stringify([inboxQueryKey(), state.sender]);

function invalidateQueryData() {
  if (state.conversationsQuery !== inboxQueryKey()) {
    state.conversations = [];
    state.conversationsLoaded = state.conversationsHasMore = false;
  }
  if (state.messagesQuery !== historyQueryKey()) {
    state.pendingDisplayedRead = null;
    historyAbortController?.abort();
    historyRequestId++;
    state.messages = [];
    state.seenMessageId = 0;
    state.historyHasMore = true;
    state.historyLoading = false;
  }
}

const errors = {
  unauthorized: "登录状态已失效，请重新输入中枢密码",
  invalid_password: "中枢密码不正确",
  rate_limited: "请求过于频繁，请稍后重试",
  push_not_configured: "部署者尚未配置 Web Push",
  push_not_subscribed: "本浏览器尚未订阅通知，请先开启通知",
  push_disabled: "通知开关已关闭，请先开启通知",
  push_subscription_expired: "通知订阅已失效，请关闭后重新开启通知",
  push_delivery_failed: "通知投递失败，请检查网络或稍后重试",
  origin_denied: "PUBLIC_URL 与访问地址不一致",
  invalid_push_endpoint: "浏览器推送地址不可达或不被允许",
  ai_secret_required: "请先在中枢部署环境配置 HONGSHU_AI_SECRET，再保存 API 密钥",
  invalid_ai_endpoint: "AI 地址需为完整 HTTPS 接口；本地服务须由部署者加入允许主机名单",
  ai_consent_or_config_required: "请填写 AI 配置并同意历史和新增短信（包括验证码正文）分析说明",
  invalid_ai_settings: "请检查 AI 参数范围",
  invalid_ai_protocol: "请选择受支持的 AI 协议",
  ai_budget_or_rate_limit: "已达到调用限速或今日额度，请稍后再试",
  ai_disabled: "AI 已关闭，请刷新页面",
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
  markEmailRead: `<svg viewBox="0 0 24 24"><path d="M12 19c0-3.87 3.13-7 7-7 1.08 0 2.09.25 3 .68V6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h9.08c-.05-.33-.08-.66-.08-1zM4 6l8 5 8-5v2l-8 5-8-5V6zm13.35 12.85l-1.77-1.77-1.42 1.41 3.19 3.19 5.66-5.66-1.42-1.41-4.24 4.24z"/></svg>`,
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
  const photo = browserContacts.photo(sender);
  if (photo) {
    const image = el("img", "contact-photo");
    image.alt = "";
    image.src = photo;
    image.onerror = () => image.remove(); // Keep the generated avatar beneath a broken photo.
    node.append(image);
  }
  return node;
}

function displayContact(sender, fallback = "") {
  return browserContacts.find(sender)?.name || state.contacts[sender] || fallback;
}

function contactsChanged(broadcast = true) {
  if (broadcast) contactsChannel?.postMessage("changed"); // No contact data crosses the channel.
  if (!state.me) return;
  if (state.page === "settings") {
    const old = $(".local-contacts-settings");
    if (old) old.replaceWith(localContactsSettings());
  } else {
    const stream = $(".messages");
    const top = stream?.scrollTop;
    renderConversations();
    renderDetail();
    if (top !== undefined && $(".messages")) $(".messages").scrollTop = top;
    $(".local-contact-search")?.replaceWith(localContactSearch());
  }
}

async function reloadLocalContacts() {
  const generation = state.generation;
  const reload = ++contactsReload;
  await browserContacts.load(state.me?.device?.id || "");
  if (reload === contactsReload && generation === state.generation) contactsChanged(false);
}

// Name filtering is entirely local. Only a clicked phone number is used to locate SMS.
function localContactSearch() {
  const box = el("details", "local-contact-search");
  box.hidden = !browserContacts.enabled;
  box.append(el("summary", "", "本机联系人（姓名搜索不上传）"));
  const input = el("input", "local-contact-query");
  input.type = "search";
  input.placeholder = "搜索本机联系人";
  input.setAttribute("aria-label", "搜索本机联系人");
  const results = el("div", "local-contact-results");
  results.setAttribute("aria-live", "polite");
  const render = () => {
    const records = browserContacts.search(input.value);
    results.replaceChildren();
    for (const record of records.slice(0, 50)) {
      const row = button(`${record.name} · ${record.phone}`, () => openLocalContact(record.phone), "text-button");
      results.append(row);
    }
    if (!records.length) results.append(el("p", "muted", "没有匹配的本机联系人，可在设置中重新选择"));
    if (records.length > 50) results.append(el("p", "muted", "结果较多，请输入姓名或号码缩小范围"));
  };
  input.oninput = () => { contactSearchRequest++; render(); };
  box.ontoggle = () => { if (box.open) render(); };
  box.append(input, results);
  return box;
}

async function openLocalContact(phone) {
  if (!browserContacts.find(phone) || !state.me) return;
  const request = ++contactSearchRequest;
  const generation = state.generation;
  const version = browserContacts.version;
  // Scan conversation heads only on explicit selection. No name, photo, contact ID or
  // address-book number is sent to the hub; retain each original sender as its own thread.
  const matches = [];
  for (let offset = 0; ; offset += CONVERSATION_PAGE) {
    const d = await api("/conversations?" + query({ limit: CONVERSATION_PAGE, offset }));
    if (request !== contactSearchRequest || state.page !== "inbox" || generation !== state.generation || version !== browserContacts.version || !browserContacts.find(phone)) return;
    matches.push(...d.conversations.filter(c => contactNumber(c.sender) === phone));
    if (d.conversations.length < CONVERSATION_PAGE) break;
  }
  const unique = [...new Map(matches.map(c => [c.sender, c])).values()];
  if (!unique.length) { toast("此联系人暂无短信会话"); return; }
  if (unique.length > 1) {
    const list = $(".local-contact-results");
    list?.replaceChildren(...unique.map(c => button(`${displayContact(c.sender)} · ${c.sender}`, () => openContactConversation(c.sender), "text-button")));
    return;
  }
  await openContactConversation(unique[0].sender);
}

async function openContactConversation(sender) {
  contactSearchRequest++;
  state.q = state.sim = state.device = "";
  state.group = "all";
  state.sender = sender;
  state.messages = [];
  state.messagesQuery = "";
  state.seenMessageId = 0;
  state.historyLoading = true;
  setDetailOpen(true);
  enterDetailHistory();
  shell();
  await refreshInbox();
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
const LOW_SPEED_KEY = "hongshu-low-speed";
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
  rememberDetail();
  contactSearchRequest++;
  const wasOpen = Boolean(state.sender);
  state.sender = "";
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
  const started = Date.now();
  const controller = new AbortController();
  let timedOut = false;
  const deadline = typeof setTimeout === "function" ? setTimeout(() => {
    timedOut = true; controller.abort();
  }, path === "/analysis/test" ? 90000 : method !== "GET" ? 45000 : networkProfile.constrained ? 25000 : 12000) : null;
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
  const data = await res.json().catch(() => {
    if (!res.ok) return { error: "" };
    throw new Error("中枢返回了无效响应，请稍后重试");
  });
  if (generation !== state.generation || controller.signal.aborted) throw new DOMException("Session ended", "AbortError");
  if (!res.ok) {
    if (res.status === 401 && state.me) {
      disconnect();
      authView();
    }
    throw new ApiError(res.status, data.error);
  }
  return data;
  } catch (error) {
    if (timedOut) throw new DOMException("网络较慢，请重试；已有内容仍可查看", "TimeoutError");
    throw error;
  } finally {
    if (deadline !== null) clearTimeout(deadline);
    networkProfile.observe(Date.now() - started);
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
  contactSearchRequest++;
  state.page = "settings";
  history.pushState({ [SETTINGS_HISTORY]: true }, "");
  shell();
  const generation = state.generation;
  $(".device-settings-rows")?.setAttribute("aria-busy", "true");
  try {
    await reloadMetadata({ force: true });
    if (generation === state.generation && state.page === "settings") {
      $(".device-settings-rows")?.replaceChildren(...deviceRows());
    }
  } catch (error) {
    if (generation === state.generation && error.name !== "AbortError") toast("设备信息暂未刷新，请重试");
  } finally {
    if (generation === state.generation) $(".device-settings-rows")?.setAttribute("aria-busy", "false");
  }
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
  const markAllBtn = iconButton(ICONS.markEmailRead, "全部标记为已读", async () => {
    try {
      setBusy(true);
      await api("/messages/read", "POST", { all: true });
      state.unreadCount = 0;
      updateAppBadge(0);
      for (const c of state.conversations) c.unread_count = 0;
      renderConversations();
      toast("已全部标记为已读");
    } catch (e) {
      toast(e.message);
    } finally {
      setBusy(false);
    }
  }, "mark-all-read");
  header.append(
    logo,
    el("strong", "brand-title", "鸿枢"),
    status,
    el("span", "spacer"),
    markAllBtn,
    iconButton(ICONS.settings, "设置", openSettings),
  );

  const searchContainer = el("div", "search-container");
  searchContainer.innerHTML = `<span class="search-icon">${ICONS.search}</span>`;
  const search = el("input", "search");
  search.type = "search";
  search.placeholder = "搜索短信、号码或中枢别名";
  search.setAttribute("aria-label", "搜索短信");
  search.value = state.q;
  const clearBtn = iconButton(
    ICONS.close,
    "清空搜索",
    () => {
      contactSearchRequest++;
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
    contactSearchRequest++;
    clearBtn.classList.toggle("visible", Boolean(search.value));
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (!search.isConnected || state.page !== "inbox") return;
      state.q = search.value;
      refreshInbox().catch((e) => toast(e.message));
    }, 250);
  };
  searchContainer.append(search, clearBtn);

  const chipRow = el("div", "chip-row");
  // Keep the filter pinned while the group tabs use a controlled, mouse-friendly rail.
  const filterSlot = el("div", "filter-slot");
  const rail = el("div", "chip-rail");
  const previous = el("button", "chip-rail-button");
  previous.type = "button";
  previous.innerHTML = ICONS.chevronLeft;
  previous.setAttribute("aria-label", "向左滚动选项卡");
  previous.title = "向左滚动选项卡";
  const scroller = el("div", "chip-scroller");
  scroller.tabIndex = 0;
  scroller.setAttribute("aria-label", "短信分组选项卡");
  scroller.append(el("div", "group-chips"));
  const next = el("button", "chip-rail-button");
  next.type = "button";
  next.innerHTML = ICONS.chevronRight;
  next.setAttribute("aria-label", "向右滚动选项卡");
  next.title = "向右滚动选项卡";
  rail.append(previous, scroller, next);
  chipRow.append(filterSlot, rail);
  installChipRail(rail, scroller, previous, next);

  const list = el("div", "list");
  list.addEventListener("scroll", () => {
    if (list.scrollHeight - list.scrollTop - list.clientHeight < 240) {
      void loadMoreConversations().catch((e) => toast(e.message));
    }
  }, { passive: true });
  inbox.append(header, searchContainer, localContactSearch(), chipRow, el("p", "analysis-status"), list);
  layout.append(inbox, el("article", "detail"));
  if (state.returning) playReturn(layout);
  main.append(layout);

  renderGroupBar();
  renderFilterMenu();
  renderConversations();
  renderDetail();
  void refreshInbox().catch((e) => toast(e.message));
}

function installChipRail(rail, scroller, previous, next) {
  let pointerStart = null;
  let suppressClick = false;
  const update = () => {
    const max = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
    const overflowing = max > 1;
    rail.classList.toggle("is-overflowing", overflowing);
    previous.hidden = !overflowing || scroller.scrollLeft <= 1;
    next.hidden = !overflowing || scroller.scrollLeft >= max - 1;
    previous.disabled = !overflowing || scroller.scrollLeft <= 1;
    next.disabled = !overflowing || scroller.scrollLeft >= max - 1;
  };
  const move = (direction) => {
    scroller.scrollBy({
      left: direction * Math.max(scroller.clientWidth * 0.78, 160),
      behavior: reducedMotion() ? "auto" : "smooth",
    });
  };
  previous.onclick = () => move(-1);
  next.onclick = () => move(1);
  scroller.addEventListener("scroll", update, { passive: true });
  scroller.addEventListener("wheel", (event) => {
    if (Math.abs(event.deltaY) <= Math.abs(event.deltaX) || scroller.scrollWidth <= scroller.clientWidth) return;
    event.preventDefault();
    scroller.scrollLeft += event.deltaY;
  }, { passive: false });
  scroller.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    pointerStart = { id: event.pointerId, x: event.clientX, scrollLeft: scroller.scrollLeft, moved: false };
  });
  scroller.addEventListener("pointermove", (event) => {
    if (!pointerStart || pointerStart.id !== event.pointerId) return;
    const distance = event.clientX - pointerStart.x;
    if (!pointerStart.moved && Math.abs(distance) < 4) return;
    pointerStart.moved = true;
    suppressClick = true;
    scroller.setPointerCapture?.(event.pointerId);
    rail.classList.add("is-dragging");
    scroller.scrollLeft = pointerStart.scrollLeft - distance;
    event.preventDefault();
  });
  const endPointer = (event) => {
    if (!pointerStart || pointerStart.id !== event.pointerId) return;
    if (scroller.hasPointerCapture?.(event.pointerId)) scroller.releasePointerCapture(event.pointerId);
    pointerStart = null;
    rail.classList.remove("is-dragging");
    if (suppressClick) requestAnimationFrame(() => { suppressClick = false; });
  };
  scroller.addEventListener("pointerup", endPointer);
  scroller.addEventListener("pointercancel", endPointer);
  scroller.addEventListener("click", (event) => {
    if (!suppressClick) return;
    event.preventDefault();
    event.stopPropagation();
  }, true);
  scroller.addEventListener("keydown", (event) => {
    const step = Math.max(scroller.clientWidth * 0.78, 160);
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      move(event.key === "ArrowRight" ? 1 : -1);
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      scroller.scrollTo({ left: event.key === "End" ? scroller.scrollWidth : 0, behavior: reducedMotion() ? "auto" : "smooth" });
    } else if (event.key === "PageDown" || event.key === "PageUp") {
      event.preventDefault();
      scroller.scrollBy({ left: event.key === "PageDown" ? step : -step, behavior: reducedMotion() ? "auto" : "smooth" });
    }
  });
  if (typeof ResizeObserver !== "undefined") new ResizeObserver(update).observe(scroller);
  requestAnimationFrame(update);
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
async function refreshInbox({ metadata = false, preserveHistory = false, revalidateHistory = false, revalidateConversations = false, arrivals = false } = {}) {
  if (!state.me) return false;
  invalidateQueryData();
  if (inboxAbortController) {
    inboxAbortController.abort();
  }
  inboxAbortController = new AbortController();
  const signal = inboxAbortController.signal;

  const gen = ++refreshGeneration;
  const epoch = state.epoch;
  try {
    setBusy(true);
    if (metadata) void reloadMetadata({ force: true }).catch(() => {});
    if (signal.aborted) return false;

    invalidateQueryData();
    const key = inboxQueryKey();
    preserveHistory &&= state.conversationsQuery === key;

    if (gen === refreshGeneration) renderFilterMenu();
    const args = {
      group: state.group,
      q: state.q,
      sim: state.sim,
      device: state.device,
      offset: 0,
      limit: CONVERSATION_PAGE,
    };
    const d = await api("/conversations?" + query(args), "GET", undefined, { signal });
    if (signal.aborted || gen !== refreshGeneration || key !== inboxQueryKey() || epoch !== state.epoch) return false;
    const head = d.conversations;
    const headIsFull = head.length === CONVERSATION_PAGE;
    if (preserveHistory && revalidateConversations) {
      let rows = head;
      let full = headIsFull;
      // Scrolling can append pages while a revalidation request is in flight.
      // Re-read the live range after each await, rather than replacing it with
      // the shorter range captured at the start of this refresh.
      for (let page = 1; page < Math.ceil(state.conversations.length / CONVERSATION_PAGE) && full; page++) {
        const next = await api("/conversations?" + query({ ...args, offset: page * CONVERSATION_PAGE }), "GET", undefined, { signal });
        rows = rows.concat(next.conversations);
        full = next.conversations.length === CONVERSATION_PAGE;
      }
      if (signal.aborted || gen !== refreshGeneration || key !== inboxQueryKey() || epoch !== state.epoch) return false;
      state.conversations = rows;
      state.conversationsHasMore = full;
    } else {
      state.conversations = preserveHistory ? mergeConversationPages(head, state.conversations, headIsFull) : head;
      if (!preserveHistory || !headIsFull) state.conversationsHasMore = headIsFull;
    }
    state.conversationsQuery = key;
    state.conversationsLoaded = true;
    renderGroupBar();
    renderConversations({ arrivals });
    loadingMark("content");
    // In low-speed mode the current thread goes before ancillary counts, too.
    if (!networkProfile.constrained) void refreshCounts(gen, key);
    if (state.sender) await loadHistory(false, { preserve: preserveHistory, revalidate: revalidateHistory });
    if (networkProfile.constrained) void refreshCounts(gen, key);
    return !signal.aborted && gen === refreshGeneration;
  } catch (err) {
    if (err.name === "AbortError" || signal.aborted) return false;
    const list = $(".list");
    if (list && state.me && !state.conversationsLoaded) {
      list.replaceChildren(emptyState(ICONS.warning, "短信暂未加载", "网络较慢或中枢暂不可用，请重试。"),
        button("重试", () => refreshInbox().catch(e => toast(e.message))));
    }
    throw err;
  } finally {
    setBusy(false);
  }
}

async function refreshCounts(gen, key) {
  const epoch = state.epoch;
  countsController?.abort();
  countsController = new AbortController();
  const signal = countsController.signal;
  try {
    const counts = await api("/analysis/counts?" + query({ q: state.q, sim: state.sim, device: state.device }), "GET", undefined, { signal });
    if (!signal.aborted && gen === refreshGeneration && key === inboxQueryKey() && state.me && epoch === state.epoch) {
      state.counts = counts.counts;
      renderGroupBar();
    }
  } catch { /* Next refresh retries; never discard a successful content page. */ }
}

async function loadMoreConversations() {
  if (!state.me || state.loadingMore || !state.conversationsHasMore) return;
  const gen = refreshGeneration;
  const epoch = state.epoch;
  const key = inboxQueryKey();
  if (key !== state.conversationsQuery) return;
  state.loadingMore = true;
  $(".list-more")?.removeAttribute("hidden");
  try {
    const d = await api("/conversations?" + query({
      group: state.group, q: state.q, sim: state.sim, device: state.device,
      offset: state.conversations.length, limit: CONVERSATION_PAGE,
    }));
    if (gen !== refreshGeneration || epoch !== state.epoch || !state.me || key !== inboxQueryKey()) return;
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
        `AI ${(state.me.ai_paused ?? state.ai?.paused) ? "已暂停" : "已开启"} · 待分析 ${state.counts.pending || 0} 条` +
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
  const liveKeys = new Set(state.conversations.map(conversationKey));
  for (const key of conversationNodes.keys()) if (!liveKeys.has(key)) conversationNodes.delete(key);
  state.conversations.forEach((m) => {
    const isSelected = state.sender === m.sender;
    const key = conversationKey(m);
    const signature = JSON.stringify([m, isSelected, state.contacts[m.sender], browserContacts.version, simLabel(m.receiver), fresh.has(key)]);
    const cached = conversationNodes.get(key);
    if (cached?.signature === signature) { list.append(cached.node); return; }
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
      contactSearchRequest++;
      if (!isSelected) {
        rememberDetail();
        state.sender = m.sender;
        const cached = detailCache.get(historyQueryKey());
        state.messages = cached?.messages.slice() || [];
        state.messagesQuery = cached ? historyQueryKey() : "";
        state.seenMessageId = 0;
        state.historyHasMore = cached?.more ?? true;
        // Shown as loading until the first page arrives, instead of a momentary empty state.
        state.historyLoading = !cached;
      }
      setDetailOpen(true);
      enterDetailHistory();
      renderConversations();
      renderDetail();
      await loadHistory(false, { preserve: true });
    };

    const contact = displayContact(m.sender, m.contact || "");
    const preview = el("div", "preview");
    const top = el("div", "preview-top");
    top.append(
      el("strong", "", contact || m.sender),
      el("time", "", `${simLabel(m.receiver)} · ${formatRelativeTime(m.timestamp)}`),
    );
    const bottom = el("div", "preview-bottom");
    if (m.analysis?.otp && !isOutgoing(m)) bottom.append(el("span", "otp-tag", "验证码"));
    bottom.append(el("p", "", isOutgoing(m) ? `我：${m.body}` : m.body));
    if (m.unread_count > 0) {
      bottom.append(el("span", "unread-badge", m.unread_count > 99 ? "99+" : String(m.unread_count)));
    }
    preview.append(top, bottom);

    b.append(avatar(contact, m.sender), preview);
    conversationNodes.set(key, { signature, node: b });
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
  if (!sender || (older && (!state.historyHasMore || state.historyLoading))) return;
  const key = historyQueryKey();
  if (older && key !== state.messagesQuery) return;
  preserve &&= key === state.messagesQuery;

  if (!older) {
    if (historyAbortController) historyAbortController.abort();
    historyAbortController = new AbortController();
    state.historyHasMore = true;
  }
  const signal = historyAbortController?.signal;
  const requestId = ++historyRequestId;
  const generation = state.generation;
  const epoch = state.epoch;
  const previousStream = $(".messages");
  const scrollTop = previousStream?.scrollTop || 0;
  const scrollHeight = previousStream?.scrollHeight || 0;
  const atBottom = previousStream &&
    scrollHeight - scrollTop - previousStream.clientHeight < 40;
  const args = {
    group: state.group,
    sender,
    sim: state.sim,
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
      generation !== state.generation || epoch !== state.epoch || !state.me ||
      key !== historyQueryKey()
    ) {
      return;
    }

    state.messages = older || (preserve && !revalidate)
      ? mergeMessages(state.messages, incoming)
      : mergeMessages([], incoming);
    state.messagesQuery = key;
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
    rememberDetail();
    const freshCount = renderDetail();

    if (!older && incoming.length > 0 && $(".messages")) {
      const conv = state.conversations.find((c) => c.sender === sender);
      if (conv?.unread_count > 0) {
        // Bind the write to the response actually rendered, not a newer server head.
        const message_id = incoming.reduce((high, message) => Math.max(high, message.id), 0);
        state.pendingDisplayedRead = { generation, epoch: state.epoch, key, sender, message_id };
        void flushDisplayedRead();
      }
    }

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

async function flushDisplayedRead() {
  const pending = state.pendingDisplayedRead;
  if (!pending) return;
  if (!state.me || pending.generation !== state.generation || pending.epoch !== state.epoch ||
      pending.key !== historyQueryKey() || pending.key !== state.messagesQuery ||
      !state.messages.some(message => message.id === pending.message_id)) {
    state.pendingDisplayedRead = null;
    return;
  }
  if (state.page !== "inbox" || document.visibilityState !== "visible" || !$(".messages")) return;
  // Consume before awaiting so concurrent visibility/WS polls cannot submit it twice.
  state.pendingDisplayedRead = null;
  await markDisplayedRead(pending.sender, pending.message_id);
}

async function markDisplayedRead(sender, message_id) {
  const generation = state.generation;
  try {
    await api("/messages/read", "POST", { sender, message_id });
    if (generation === state.generation && state.me) await catchUp();
  } catch (error) {
    if (generation === state.generation && state.me && error.name !== "AbortError") {
      // Keep existing counts and retry on the next poll, without a tight retry loop.
      state.readRetry = true;
      toast(error.message);
    }
  }
}

function renderDetail() {
  const detail = $(".detail");
  if (!detail) return 0;
  detail.replaceChildren();
  // Fade through when a different conversation replaces the current one.
  const detailKey = state.sender ? conversationKey({ sender: state.sender }) : "";
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

  const contact = displayContact(state.sender, state.messages.at(-1)?.contact || "");
  const receiverCount = new Set(state.messages.map((message) => message.receiver).filter(Boolean)).size;
  const titleBox = el("div", "detail-head-info");
  const infoText = el("div");
  infoText.append(
    el("strong", "", contact || state.sender),
    el("div", "sub-info", [contact ? state.sender : "", receiverCount ? `来自 ${receiverCount} 个接收号码` : "全部接收号码"].filter(Boolean).join(" · ")),
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
    const group = el("div", "bubble-group" + (cluster.outgoing ? " outgoing" : ""));
    for (const msg of cluster.messages) {
      const fresh = isFreshArrival(msg.id, seenId);
      if (fresh) freshCount++;
      const item = el("div", "message-item");
      item.append(bubble(msg, fresh), messageStamp(msg));
      group.append(item);
    }
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

function messageStamp(msg) {
  const deviceName = state.devices.find((device) => device.id === msg.device_id)?.name || "来源手机";
  const line = msg.receiver ? `${simLabel(msg.receiver)}${simLabel(msg.receiver) === msg.receiver ? "" : " " + msg.receiver}` : "";
  // The phone's own line is the recipient of a received message and the sender of a sent one.
  const receiver = isOutgoing(msg) ? (line ? `由 ${line} 发出` : "发送号码未知") : (line || "接收号码未知");
  return el("div", "message-stamp", [formatClock(msg.timestamp), deviceName, receiver, isOutgoing(msg) ? "已发送" : "", msg.historical ? "历史补齐" : ""].filter(Boolean).join(" · "));
}

export function analysisGroupLabel(msg) {
  const analysis = msg.analysis;
  if (analysis?.category) return GROUP_LABELS[analysis.category] || "待分析";
  // The rule code is already present while AI is pending; keep the provisional
  // result labelled as 验证码 instead of making it look unclassified.
  if (analysis?.otp) return GROUP_LABELS.otp;
  return GROUP_LABELS.pending;
}

function bubble(msg, fresh) {
  const outgoing = isOutgoing(msg);
  const b = el("div", "bubble" + (outgoing ? " bubble-out" : "") + (fresh ? " bubble-new" : ""));
  const otp = outgoing ? "" : msg.analysis?.otp;
  if (otp) b.append(otpCard(msg));
  b.append(linkedText(msg.body));

  // The user's own messages are not classified. OTP messages now also expose
  // the AI review state: the rule card appears immediately, then AI may confirm
  // or move the message, and a failed review is visible and retryable.
  if (state.me?.ai_enabled && !outgoing) {
    const a = msg.analysis;
    // A plain automatic result needs no label; pending, failed and manual states do.
    if (!a || a.state !== "complete" || a.manual) {
      b.append(el("small", "analysis-message-state", `${analysisGroupLabel(msg)} · ${analysisLabel(a)}`));
    }
  }

  const actions = el("div", "bubble-actions");
  actions.append(iconButton(ICONS.contentCopy, "复制短信", async () => {
    await copyText(msg.body);
    toast("已复制短信");
  }));
  if (state.me?.ai_enabled && !outgoing) {
    const correction = el("details", "category-actions");
    const summary = el("summary");
    summary.innerHTML = ICONS.label;
    summary.append(el("span", "sr-only", "调整分类"));
    summary.title = "调整分类";
    const menu = el("div", "category-menu");
    for (const category of ["otp", "personal", "notification", "promotion", "spam", "auto", ...(msg.analysis?.state === "failed" ? ["retry"] : [])]) {
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

function createSwitch(checked, onChange, label = "浏览器通知") {
  let value = Boolean(checked);
  const control = el("button", "m3-switch");
  control.type = "button";
  control.setAttribute("role", "switch");
  control.setAttribute("aria-label", label);
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

export function isIOSDevice(nav = typeof navigator !== "undefined" ? navigator : {}) {
  return /iPad|iPhone|iPod/.test(nav.userAgent || "") ||
    (nav.platform === "MacIntel" && Number(nav.maxTouchPoints) > 1);
}

export function isStandaloneApp(win = typeof window !== "undefined" ? window : {}, nav = typeof navigator !== "undefined" ? navigator : {}) {
  let display = false;
  try { display = Boolean(win.matchMedia?.("(display-mode: standalone)")?.matches); } catch { /* older browser */ }
  return display || nav.standalone === true;
}

export function samePushKey(left, right) {
  if (!left || !right) return true;
  const a = new Uint8Array(left);
  const b = new Uint8Array(right);
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

export function pushAvailability() {
  if (!window.isSecureContext) {
    return { ready: false, message: "Web Push 需要 HTTPS 或 localhost；当前地址无法订阅通知。" };
  }
  if (!state.me?.vapid_public_key) {
    return { ready: false, message: "部署者尚未配置 VAPID，暂时无法开启通知。" };
  }
  // iOS Safari tabs may expose no PushManager at all. Tell the user how to
  // install before reporting a generic "unsupported browser" dead end.
  if (isIOSDevice(navigator) && !isStandaloneApp(window, navigator)) {
    return { ready: false, message: "iPhone/iPad 需要先添加到主屏幕：用 Safari 打开本页，点“分享” → “添加到主屏幕”，再从主屏幕图标打开并开启通知。" };
  }
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || typeof Notification === "undefined") {
    return { ready: false, message: "当前浏览器不支持 Web Push。" };
  }
  if (Notification.permission === "denied") {
    return { ready: false, message: "通知权限已被拒绝，请到系统设置 → 通知中允许本应用。" };
  }
  return { ready: true, message: "通知未开启。" };
}

function pushCapability() {
  return pushAvailability().ready;
}

// A subscription belongs to one VAPID key and one server identity. Reuse it only
// when both still match; otherwise take a fresh endpoint and re-bind it.
async function reusablePushSubscription(registration, vapid, { create = false, check = () => {} } = {}) {
  const key = pushKey(vapid);
  check();
  let subscription = await registration.pushManager.getSubscription();
  check();
  const stale = subscription && (
    state.me?.push_subscribed === false ||
    (subscription.options?.applicationServerKey && !samePushKey(subscription.options.applicationServerKey, key))
  );
  if (stale) {
    await subscription.unsubscribe();
    check();
    subscription = null;
  }
  if (!subscription && create) {
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: key,
    });
    check();
  }
  return subscription;
}

async function browserPushEnabled() {
  if (!pushCapability() || Notification.permission !== "granted") return false;
  if (!state.me.device.notify) return false;
  const session = pushSession();
  const registration = await navigator.serviceWorker.getRegistration();
  checkPushSession(session);
  if (!registration) return false;
  const subscription = await reusablePushSubscription(registration, state.me.vapid_public_key, {
    create: true,
    check: () => checkPushSession(session),
  });
  checkPushSession(session);
  if (!subscription) return false;
  // Logout removes the server binding but leaves the browser's subscription intact.
  const json = subscription.toJSON();
  await api("/push", "PUT", { endpoint: json.endpoint, keys: json.keys });
  checkPushSession(session);
  state.me.push_subscribed = true;
  return true;
}

function pushSession() {
  return { generation: state.generation, device: state.me?.device?.id };
}

function checkPushSession(session) {
  if (!session.device || session.generation !== state.generation || session.device !== state.me?.device?.id) {
    throw new DOMException("Session ended", "AbortError");
  }
}

function setLocalNotificationState(enabled) {
  if (state.me) state.me.push_subscribed = enabled;
  if (state.me?.device) state.me.device.notify = enabled;
  const current = state.devices.find((device) => device.id === state.me?.device?.id);
  if (current) current.notify = enabled;
}

async function disablePush() {
  const session = pushSession();
  checkPushSession(session);
  await api("/push", "DELETE");
  checkPushSession(session);
  if ("serviceWorker" in navigator) {
    const registration = await navigator.serviceWorker.getRegistration();
    checkPushSession(session);
    const subscription = await registration?.pushManager.getSubscription();
    checkPushSession(session);
    await subscription?.unsubscribe();
    checkPushSession(session);
  }
  await api("/devices/" + session.device, "PATCH", { notify: false });
  checkPushSession(session);
  setLocalNotificationState(false);
}

async function logout() {
  try { await browserContacts.clear(); }
  finally { contactsChanged(); }
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
// Devices & SIMs: inventory and administrator revocation, shown inside Settings
// -------------------------------------------------------------
function deviceRows() {
  if (!state.devices.length) {
    return [settingsRow({ icon: ICONS.devices, title: "暂无已连接设备" })];
  }
  return state.devices.map((d) => {
    const sims = state.sims.filter((s) => s.device_id === d.id);
    const current = d.id === state.me?.device?.id;
    const trailing = el("div");
    trailing.append(el("span", "badge", d.revoked ? "已撤销" : current ? "本浏览器" : d.kind === "android" ? "Android 手机" : "Web 浏览器"));
    if (state.me?.device?.admin && !d.revoked) {
      trailing.append(button("撤销设备", () => revokeDevice(d.id), "text-button"));
    }
    return settingsRow({
      icon: d.kind === "android" ? ICONS.phoneAndroid : ICONS.devices,
      title: d.name,
      summary: sims.length ? sims.map((s) => `${s.label || "SIM"} ${s.phone}`).join(" · ") : d.kind === "android" ? "暂无 SIM 信息" : "浏览器会话",
      trailing,
      cls: "device-card",
    });
  });
}

async function revokeDevice(id) {
  const generation = state.generation;
  const current = id === state.me?.device?.id;
  await api("/devices/" + encodeURIComponent(id), "DELETE");
  if (generation !== state.generation) return;
  if (current) { disconnect(); authView(); return; }
  await reloadMetadata({ force: true });
  if (generation === state.generation && state.me) shell();
}

// -------------------------------------------------------------
// Settings View
// -------------------------------------------------------------
function localContactsSettings() {
  const { section, group } = settingsGroup("本机联系人");
  section.classList.add("local-contacts-settings");
  const toggle = createSwitch(browserContacts.enabled, async enabled => {
    if (contactsBusy) return;
    contactsBusy = true;
    try { await browserContacts.toggle(enabled); }
    catch (error) { toast(error.message); }
    finally { contactsBusy = false; contactsChanged(); }
  });
  toggle.setAttribute("aria-label", "使用本机联系人");
  toggle.setDisabled(contactsBusy || !browserContacts.records.length);
  group.append(settingsRow({ icon: ICONS.person, title: "使用本机联系人",
    summary: `已选择 ${browserContacts.records.length} 个号码。姓名和头像只保存在本浏览器，不上传、不跨设备同步。`, trailing: toggle }));
  const actions = el("div", "local-contact-actions");
  const pick = button("选择或更新联系人", async () => {
    if (contactsBusy || !pickerProperties.length) return;
    const generation = state.generation;
    const version = browserContacts.version;
    contactsBusy = true;
    try {
      // Must run directly from the click, before any storage/network await.
      const selected = await navigator.contacts.select(pickerProperties, { multiple: true });
      if (!selected.length || generation !== state.generation || version !== browserContacts.version || !state.me) return;
      const saved = await browserContacts.select(selected);
      if (saved && generation === state.generation) toast("本机联系人已保存；通讯录变更后请重新选择更新");
    } catch (error) {
      if (error.name !== "AbortError") toast("联系人未保存：" + error.message);
    } finally { contactsBusy = false; contactsChanged(); }
  }, "text-button");
  pick.disabled = contactsBusy || !pickerProperties.length;
  const clear = button("清除本机联系人", async () => {
    if (contactsBusy) return;
    contactsBusy = true;
    try { await browserContacts.clear(); toast("本机联系人已清除"); }
    finally { contactsBusy = false; contactsChanged(); }
  }, "text-button");
  clear.disabled = contactsBusy;
  actions.append(pick, clear);
  group.append(settingsRow({ icon: ICONS.info, title: "仅本地显示",
    summary: pickerProperties.length ? "浏览器不会持续读取通讯录；照片不受支持或无法读取时使用默认头像。退出登录会清除所选联系人。" : "当前浏览器不支持联系人选择，或没有使用 HTTPS/localhost；已有本地数据仍可停用或清除。",
    trailing: actions, cls: "settings-wrap" }));
  return section;
}

function settingsView() {
  const main = $(".main");
  const container = el("div", "page-container settings-container");
  const inner = el("div", "page-inner");
  container.append(inner);

  const head = el("header", "page-head");
  head.append(iconButton(ICONS.arrowBack, "返回收件箱", closeSettings), el("h1", "", "设置"));
  inner.append(head);

  const availability = pushAvailability();
  const status = el("p", "settings-summary notification-state", availability.ready ? "正在读取通知状态…" : "通知已关闭");
  status.setAttribute("aria-live", "polite");
  const summary = el("div", "notification-summary");
  summary.append(status);
  if (!availability.ready) summary.append(el("p", "settings-summary notification-hint", availability.message));
  const sendTest = button("发送测试通知", async () => {
    sendTest.disabled = true;
    try {
      await api("/push/test", "POST", {});
      toast("测试通知已发送");
    } finally {
      sendTest.disabled = toggle.getAttribute("aria-checked") !== "true";
    }
  }, "text-button");
  sendTest.disabled = true;
  const toggle = createSwitch(false, async (next) => {
    toggle.setDisabled(true);
    setBusy(true);
    try {
      if (next) await enablePush();
      else await disablePush();
      toggle.setChecked(next);
      sendTest.disabled = !next;
      status.textContent = next ? "通知已开启" : "通知已关闭";
    } catch (error) {
      toast(error.message);
    } finally {
      toggle.setDisabled(!availability.ready);
      setBusy(false);
    }
  });
  toggle.setDisabled(!availability.ready);
  inner.append(settingsGroup("本浏览器", [
    settingsRow({ icon: ICONS.notifications, title: "浏览器通知", summaryNode: summary, trailing: toggle }),
    settingsRow({ icon: ICONS.info, title: "测试通知", summary: "只向本浏览器发送一条通用通知，不依赖真实短信，也不包含短信内容。", trailing: sendTest, cls: "settings-wrap" }),
    settingsRow({ icon: ICONS.palette, title: "主题", summary: "只影响这台浏览器", trailing: themeControl(), cls: "settings-wrap" }),
  ]).section);
  inner.append(localContactsSettings());

  const ai = settingsGroup("中枢 AI 分组");
  inner.append(ai.section);
  void renderAISettings(ai.group).catch((e) => {
    if (ai.group.isConnected) ai.group.append(settingsRow({ icon: ICONS.warning, title: "无法读取 AI 设置", summary: e.message }));
  });

  const deviceGroup = settingsGroup("设备与 SIM", deviceRows());
  deviceGroup.group.classList.add("device-settings-rows");
  inner.append(deviceGroup.section);

  const lowSpeed = createSwitch(networkProfile.manual, async next => {
    networkProfile.manual = next;
    lowSpeed.setChecked(next);
    try { localStorage.setItem(LOW_SPEED_KEY, String(next)); } catch { /* session only */ }
  }, "低速优化");
  inner.append(settingsGroup("网络", [settingsRow({ icon: ICONS.settings, title: "低速优化", summary: "优先当前内容，减少预取并容许更长的交互等待", trailing: lowSpeed })]).section);

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
    sendTest.disabled = !enabled;
    status.textContent = enabled ? "通知已开启" : "通知已关闭";
  }).catch(() => {
    status.textContent = availability.ready ? "无法确认通知状态，请重新开启" : "通知已关闭";
  }).finally(() => {
    toggle.setDisabled(!availability.ready);
  });
}

async function renderAISettings(group) {
  const s = await api("/analysis/settings");
  if (!group.isConnected) return;
  state.ai = s;
  const label = s.enabled ? (s.paused ? "已暂停" : "已开启") : "已关闭";
  group.append(settingsRow({
    icon: ICONS.autoAwesome,
    title: "AI 短信分组",
    summary: "开启后，历史及新增短信（包括验证码）会自动发送到配置的 AI 服务分析；验证码先由规则即时显示，AI 再复核分类并提取验证码。验证码短信中的数字不遮蔽，链接和邮箱仍遮蔽；脱敏不能保证匿名。垃圾分组不会删除或拦截短信。",
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
  function selectField(key, label, value, options, parent = form) {
    const select = el("select");
    for (const [optionValue, optionLabel] of options) {
      const option = el("option", "", optionLabel);
      option.value = optionValue;
      select.append(option);
    }
    select.value = options.some(([optionValue]) => optionValue === value) ? value : options[0]?.[0] || "";
    const wrapper = el("label", "setting-field");
    wrapper.append(el("span", "setting-label", label), select);
    parent.append(wrapper); fields[key] = select;
    return select;
  }
  field("enabled", "启用 AI 分组", "checkbox", s.enabled);
  field("paused", "暂停队列", "checkbox", s.paused);
  selectField("protocol", "AI 协议", s.protocol, Object.entries(AI_PROTOCOLS));
  const endpoint = field("endpoint", "完整 Chat Completions 接口地址", "url", s.endpoint);
  endpoint.placeholder = s.protocol === "openai_responses"
    ? "https://example.com/v1/responses"
    : s.protocol === "anthropic_messages"
      ? "https://api.anthropic.com/v1/messages"
      : s.protocol === "gemini_generate_content"
        ? "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        : "https://example.com/v1/chat/completions";
  fields.protocol.onchange = () => {
    endpoint.placeholder = fields.protocol.value === "openai_responses"
      ? "https://example.com/v1/responses"
      : fields.protocol.value === "anthropic_messages"
        ? "https://api.anthropic.com/v1/messages"
        : fields.protocol.value === "gemini_generate_content"
          ? "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
          : "https://example.com/v1/chat/completions";
  };
  field("model", "模型", "text", s.model);
  const key = field("api_key", s.key_set ? "API 密钥（已设置；留空保留）" : "API 密钥（本地无鉴权服务可留空）", "password", "");
  key.autocomplete = "new-password";
  field("clear_key", "清除已保存密钥", "checkbox", false);
  const numbers = el("div", "setting-numbers");
  form.append(numbers);
  for (const [name,label,min,max] of [["timeout_seconds","超时（秒）",2,60],["requests_per_minute","每分钟调用上限",1,60],["daily_limit","每日调用上限（含失败与连接测试）",1,10000]]) {
    const input = field(name,label,"number",s[name],numbers); input.min = min; input.max = max; input.required = true; input.inputMode = "numeric";
  }
  field("redact", "遮蔽链接、邮箱和非验证码短信的长数字", "checkbox", s.redact);
  field("consent", "我了解开启后会自动分析历史与新增短信（包括验证码正文）", "checkbox", false);
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
  const availability = pushAvailability();
  if (!availability.ready) throw new Error(availability.message);
  const session = pushSession();
  checkPushSession(session);
  const vapid = state.me.vapid_public_key;
  const permission = await Notification.requestPermission();
  checkPushSession(session);
  if (permission !== "granted") {
    throw new Error("请在浏览器设置中允许通知");
  }
  const reg = await navigator.serviceWorker.ready;
  checkPushSession(session);
  const sub = await reusablePushSubscription(reg, vapid, {
    create: true,
    check: () => checkPushSession(session),
  });
  checkPushSession(session);
  if (!sub) throw new Error("无法创建通知订阅");
  const json = sub.toJSON();
  await api("/push", "PUT", { endpoint: json.endpoint, keys: json.keys });
  checkPushSession(session);
  await api("/devices/" + session.device, "PATCH", { notify: true });
  checkPushSession(session);
  setLocalNotificationState(true);
}

async function reloadMetadata({ force = false } = {}) {
  const generation = state.generation;
  const epoch = state.epoch;
  const revision = state.me?.metadata_revision;
  if (!force && metadataUpdatedAt && (revision != null ? revision === metadataRevision : Date.now() - metadataUpdatedAt < 60000)) return;
  if (metadataTask?.generation === generation && metadataTask.epoch === epoch) {
    if (!force) return metadataTask.promise;
    // A mutation needs a read that starts after its commit, not an older in-flight snapshot.
    try { await metadataTask.promise; } catch { /* explicit refresh retries */ }
    if (generation !== state.generation || epoch !== state.epoch || !state.me) return;
    return reloadMetadata({ force: true });
  }
  const task = { generation, epoch };
  task.promise = (async () => {
    let d, s, c;
    let appliedRevision = revision;
    if (state.me?.features?.includes("metadata_revision")) {
      const data = await api("/metadata?" + query({ since: metadataRevision ?? 0 }));
      if (generation !== state.generation || epoch !== state.epoch || !state.me) return;
      if (data.epoch != null && data.epoch !== state.epoch) throw new DOMException("Metadata epoch changed", "AbortError");
      if (data.unchanged) { metadataUpdatedAt = Date.now(); return; }
      d = data; s = data; c = data; appliedRevision = data.revision;
    } else {
      [d, s, c] = await Promise.all([api("/devices"), api("/sims"), api("/contacts")]);
    }
    if (generation !== state.generation || epoch !== state.epoch || !state.me) return;
    state.devices = d.devices; state.sims = s.sims; state.contacts = c.contacts;
    metadataRevision = appliedRevision; metadataUpdatedAt = Date.now();
  })();
  metadataTask = task;
  try { return await task.promise; }
  finally { if (metadataTask === task) metadataTask = null; }
}

function disconnect() {
  contactsReload++;
  browserContacts.reset();
  state.loggedOut = true;
  state.generation++;
  metadataTask = null; metadataRevision = null; metadataUpdatedAt = 0;
  detailCache.clear(); conversationNodes.clear();
  countsController?.abort();
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
  state.messagesQuery = state.conversationsQuery = "";
  state.devices = [];
  state.sims = [];
  state.contacts = {};
  state.ai = null; state.counts = {}; state.group = "all";
  state.sender = state.q = state.sim = state.device = "";
  state.cursor = state.epoch = 0;
  state.conversationsLoaded = state.conversationsHasMore = state.loadingMore = false;
  state.renderedDetailKey = "";
  state.epochSeen = false;
  state.inboxDirty = false;
  state.historyDirty = state.conversationsDirty = false;
  state.readRetry = false;
  state.pendingDisplayedRead = null;
  state.page = "inbox";
  state.arrivalsPending = false;
  state.renderedRows = new Map();
  state.seenMessageId = 0;
  state.unreadCount = 0;
  updateAppBadge(0);
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
    const analysisChanged = me.analysis_revision !== state.me.analysis_revision || me.ai_enabled !== state.me.ai_enabled || me.ai_paused !== state.me.ai_paused;
    if (analysisChanged) detailCache.clear();
    const unreadChanged = me.unread_count !== state.me.unread_count;
    const cursorChanged = me.cursor !== state.cursor;
    const changed = analysisChanged || unreadChanged || cursorChanged || epochReset(state.epochSeen, state.epoch, me.epoch);
    // Persist invalidation before metadata I/O; a failed request must not consume it.
    state.inboxDirty ||= changed || state.readRetry;
    state.historyDirty ||= analysisChanged;
    // An arrival and a read can cancel in the total; cursor changes also recheck tail rows.
    state.conversationsDirty ||= analysisChanged || unreadChanged || cursorChanged;
    state.arrivalsPending ||= me.cursor > state.cursor && !epochReset(state.epochSeen, state.epoch, me.epoch);
    if (epochReset(state.epochSeen, state.epoch, me.epoch)) {
      detailCache.clear(); metadataRevision = null; metadataUpdatedAt = 0;
      state.pendingDisplayedRead = null;
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
    if (!visibleGroups(me.ai_enabled).includes(state.group)) {
      state.group = "all"; state.inboxDirty = true;
      state.historyDirty = state.conversationsDirty = true;
    }
    if (typeof me.unread_count === "number") {
      state.unreadCount = me.unread_count;
      updateAppBadge(state.unreadCount);
    }
    const metadataNeeded = !metadataUpdatedAt || (me.metadata_revision != null ? me.metadata_revision !== metadataRevision : Date.now() - metadataUpdatedAt >= 60000);
    if (metadataNeeded) {
      const previousMetadata = JSON.stringify([state.devices, state.sims, state.contacts]);
      void reloadMetadata().then(() => {
        if (generation !== state.generation || !state.me) return;
        if (previousMetadata !== JSON.stringify([state.devices, state.sims, state.contacts])) {
          detailCache.clear();
          metadataChanges++;
          renderFilterMenu(); renderConversations();
          // A contact rename can change the search result and older detail labels.
          state.inboxDirty = state.historyDirty = state.conversationsDirty = true;
        }
      }).catch(() => {});
    }
    if (generation !== state.generation || !state.me) return;
    if (state.page === "inbox" && state.inboxDirty) {
      const metadataVersion = metadataChanges;
      state.readRetry = false;
      const refreshed = await refreshInbox({ metadata: false, preserveHistory: true, revalidateHistory: state.historyDirty, revalidateConversations: state.conversationsDirty, arrivals: state.arrivalsPending });
      if (generation === state.generation && refreshed !== false) {
        state.inboxDirty = metadataVersion !== metadataChanges;
        state.historyDirty = state.conversationsDirty = state.inboxDirty;
        state.arrivalsPending = false;
      }
    }
    // A hidden WS refresh may have consumed all data changes without marking the
    // rendered messages read. Visibility recovery must work even for an idle head.
    if (generation === state.generation) await flushDisplayedRead();
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
  const sessionGeneration = state.generation;
  if (typeof state.me.unread_count === "number") {
    state.unreadCount = state.me.unread_count;
    updateAppBadge(state.unreadCount);
  }
  if (sessionGeneration !== state.generation || !state.me || state.loggedOut) return;
  loadingMark("authenticated");
  shell();
  loadingMark("shell");
  void reloadLocalContacts().catch(error => toast(error.message));
  void reloadMetadata().then(() => {
    if (sessionGeneration !== state.generation || !state.me) return;
    renderFilterMenu(); renderConversations();
  }).catch(() => toast("附加信息暂不可用，短信仍可查看"));
  void browserPushEnabled().catch((error) => {
    if (error.name !== "AbortError") toast("通知订阅恢复失败，请在设置中重新开启");
  });
  realtime();
}

if (typeof document !== "undefined") {
  void contactPickerProperties(navigator, window.isSecureContext, window.top === window).then(properties => {
    pickerProperties = properties;
    if (state.page === "settings") contactsChanged(false);
  });
  if (typeof BroadcastChannel === "function") {
    contactsChannel = new BroadcastChannel("hongshu-local-contacts");
    contactsChannel.onmessage = () => {
      if (state.me) void reloadLocalContacts().catch(error => toast(error.message));
    };
  }
  applyTheme(readTheme());
  try { networkProfile.manual = localStorage.getItem(LOW_SPEED_KEY) === "true"; } catch { /* default */ }
  void registerShell();
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
      if (!contactsBusy) void reloadLocalContacts().catch(error => toast(error.message));
      void catchUp().catch(() => {});
    }
  });
  start().catch(startupFailure);
}

function startupFailure(error) {
  if (error.status === 401) { authView(); return; }
  const root = $("#app");
  root.replaceChildren(emptyState(ICONS.warning, "暂时无法连接中枢", "网络较慢或服务暂不可用。登录状态不会因此丢失。"), button("重试", () => start().catch(startupFailure)));
}

async function registerShell() {
  if (!("serviceWorker" in navigator)) return;
  try {
    const registration = await navigator.serviceWorker.register("/sw.js");
    const offer = () => {
      if (!registration.waiting || $(".shell-update")) return;
      const update = button("新版本已就绪，点击更新", () => {
        navigator.serviceWorker.addEventListener("controllerchange", () => location.reload(), { once: true });
        registration.waiting?.postMessage({ type: "ACTIVATE_UPDATE" });
      }, "shell-update");
      document.body.append(update);
    };
    offer();
    registration.addEventListener("updatefound", () => registration.installing?.addEventListener("statechange", offer));
  } catch { /* Online shell remains usable; a subsequent visit retries installation. */ }
}
