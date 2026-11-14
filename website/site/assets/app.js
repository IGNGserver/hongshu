import {
  HUB_META,
  DEMO_DEVICES,
  DEMO_SIMS,
  VIEWER_LOCAL_CONTACTS,
  GROUP_LABELS,
  INITIAL_MESSAGES,
  INITIAL_CALLS,
  LIVE_TEMPLATES,
  DEPLOY_TABS,
  avatarInitial,
  isServiceNumber,
  avatarColor,
  visibleGroups,
} from "./data.js";
import { HongshuStage3D } from "./stage3d.js";

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

function el(tag, className = "", text = "") {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== "") node.textContent = text;
  return node;
}

const reducedMotionQuery = window.matchMedia?.("(prefers-reduced-motion: reduce)");
const isReducedMotion = () => Boolean(reducedMotionQuery?.matches);

// Tactile spring animation helper using Web Animations API (CSP style-src 'self' safe)
function springPop(node, scale = 1.045) {
  if (!node || isReducedMotion() || typeof node.animate !== "function") return;
  node.animate(
    [
      { transform: "scale(1)" },
      { transform: `scale(${scale})`, offset: 0.36 },
      { transform: "scale(0.985)", offset: 0.72 },
      { transform: "scale(1)" },
    ],
    { duration: 420, easing: "cubic-bezier(0.22, 1, 0.36, 1)" },
  );
}

// Toast status announcer
let toastTimer = 0;
function showToast(message) {
  const box = $("#site-toast");
  if (!box) return;
  box.textContent = message;
  box.classList.add("is-visible");
  if (!isReducedMotion() && typeof box.animate === "function") {
    box.animate(
      [
        { transform: "translate(-50%, 14px) scale(0.96)", opacity: 0.2 },
        { transform: "translate(-50%, 0) scale(1)", opacity: 1 },
      ],
      { duration: 320, easing: "cubic-bezier(0.18, 0.89, 0.32, 1.12)" },
    );
  }
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    box.classList.remove("is-visible");
  }, 2800);
}

async function copySafeText(text, toastLabel) {
  let copied = false;
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      copied = true;
    } catch {
      copied = false;
    }
  }
  if (!copied) {
    try {
      const area = el("textarea", "sr-only");
      area.value = text;
      document.body.append(area);
      area.select();
      copied = document.execCommand("copy");
      area.remove();
    } catch {
      copied = false;
    }
  }
  showToast(toastLabel || (copied ? `已复制：${text}` : `演示复制：${text}`));
  return copied;
}

function formatTime(ts) {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(ts));
}

function formatRelative(ts) {
  const diffMin = Math.max(0, Math.round((Date.now() - ts) / 60000));
  if (diffMin < 1) return "刚刚";
  if (diffMin < 60) return `${diffMin} 分钟前`;
  const h = Math.floor(diffMin / 60);
  if (h < 24) return `${h} 小时前`;
  return "昨天";
}

function formatDuration(sec) {
  if (!sec || sec <= 0) return "未接通";
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? `${m} 分 ${s} 秒` : `${s} 秒`;
}

function createAvatarNode(displayName, sender, extraClass = "") {
  const service = !displayName && isServiceNumber(sender);
  const node = el(
    "span",
    `hs-avatar ${service ? "hs-avatar--cookie" : ""} ${extraClass}`.trim(),
  );
  node.style.setProperty("--avatar-bg", avatarColor(sender));
  const initial = avatarInitial(displayName, sender);
  node.textContent = initial || (service ? "信" : "号");
  node.setAttribute("aria-hidden", "true");
  return node;
}

// ---------------------------------------------------------------------------
// 1. Studio Theme Controller (Default: Warm Alabaster & Mist Blue Daylight)
// ---------------------------------------------------------------------------
let stage3d = null;
let currentTheme = "light";

function applyTheme(theme) {
  currentTheme = theme === "dark" ? "dark" : "light";
  document.documentElement.dataset.theme = currentTheme;
  const metaTheme = $('meta[name="theme-color"]');
  if (metaTheme) {
    metaTheme.setAttribute("content", currentTheme === "dark" ? "#12151c" : "#f5f4ef");
  }
  const btn = $("#theme-toggle");
  if (btn) {
    const isDark = currentTheme === "dark";
    btn.setAttribute("aria-pressed", String(isDark));
    const label = $(".theme-toggle__label", btn);
    if (label) label.textContent = isDark ? "夜览深色" : "暖白晨雾";
  }
  stage3d?.setDarkTheme(currentTheme === "dark");
}

function initTheme() {
  let saved = "light";
  try {
    saved = sessionStorage.getItem("hongshu-site-theme") || "light";
  } catch {
    saved = "light";
  }
  applyTheme(saved);
  const btn = $("#theme-toggle");
  btn?.addEventListener("click", () => {
    const next = currentTheme === "dark" ? "light" : "dark";
    try {
      sessionStorage.setItem("hongshu-site-theme", next);
    } catch {
      // ignore storage restriction
    }
    applyTheme(next);
    springPop(btn, 1.06);
  });
}

// ---------------------------------------------------------------------------
// 2. 3D WebGL Stage & Scroll-Linked Camera Choreography
// ---------------------------------------------------------------------------
function initStageAndChoreography(onTriggerDemoSync) {
  const canvas = $("#studio-canvas");
  if (!canvas) return;

  const telemetryText = $("#stage-telemetry-text");
  const telemetrySeq = $("#stage-telemetry-seq");

  stage3d = new HongshuStage3D(canvas, {
    darkTheme: currentTheme === "dark",
    reducedMotion: isReducedMotion(),
    onTelemetry: ({ seq, text }) => {
      if (telemetryText && text) telemetryText.textContent = text;
      if (telemetrySeq && seq) telemetrySeq.textContent = `#${seq}`;
    },
  });

  window.addEventListener("resize", () => stage3d.resize(), { passive: true });
  reducedMotionQuery?.addEventListener?.("change", (e) => stage3d.setReducedMotion(e.matches));

  // Pointer studio parallax
  const stageShell = $("#stage-viewport");
  stageShell?.addEventListener("pointermove", (e) => {
    const rect = stageShell.getBoundingClientRect();
    const nx = ((e.clientX - rect.left) / Math.max(1, rect.width)) * 2 - 1;
    const ny = ((e.clientY - rect.top) / Math.max(1, rect.height)) * 2 - 1;
    stage3d.setPointer(nx, ny);
  }, { passive: true });
  stageShell?.addEventListener("pointerleave", () => stage3d.setPointer(0, 0));

  // Pause WebGL render loop when off-screen
  if ("IntersectionObserver" in window && stageShell) {
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          stage3d.setVisible(entry.isIntersecting);
        }
      },
      { threshold: 0.02 },
    );
    io.observe(stageShell);
  }
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") stage3d.start();
  });

  // Hero device trigger buttons
  $$("[data-hero-phone]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const idx = Number(btn.dataset.heroPhone || 0);
      springPop(btn, 1.05);
      $$("[data-hero-phone]").forEach((b) => b.classList.toggle("is-active", b === btn));
      onTriggerDemoSync(idx, { fromHero: true });
    });
  });

  const heroSimulateBtn = $("#hero-simulate-btn");
  let heroCycle = 0;
  heroSimulateBtn?.addEventListener("click", () => {
    springPop(heroSimulateBtn, 1.05);
    const idx = heroCycle % 3;
    heroCycle += 1;
    $$("[data-hero-phone]").forEach((b) =>
      b.classList.toggle("is-active", Number(b.dataset.heroPhone) === idx),
    );
    onTriggerDemoSync(idx, { fromHero: true });
  });

  // Scroll-linked camera choreography across Hero (0) -> Stage 1..3
  const choreoSection = $("#choreography");
  const stageCards = $$("[data-stage-card]");
  const stageTabs = $$("[data-stage-tab]");
  let manualLockUntil = 0;

  function setActiveStageUI(activeIdx) {
    stageCards.forEach((card, i) => card.classList.toggle("is-active", i === activeIdx));
    stageTabs.forEach((tab, i) => {
      const active = i === activeIdx;
      tab.classList.toggle("is-active", active);
      tab.setAttribute("aria-selected", String(active));
    });
    const progressFill = $("#stage-progress-fill");
    if (progressFill) {
      progressFill.style.transform = `scaleX(${(activeIdx + 1) / 3})`;
    }
  }

  stageTabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      const idx = Number(tab.dataset.stageTab || 0);
      manualLockUntil = performance.now() + 900;
      setActiveStageUI(idx);
      stage3d.setStage(idx + 1);
      springPop(tab, 1.05);
      const targetCard = stageCards[idx];
      if (targetCard && window.innerWidth < 980) {
        targetCard.scrollIntoView({ behavior: isReducedMotion() ? "auto" : "smooth", block: "nearest" });
      }
    });
  });

  let scrollTicking = false;
  function updateScrollChoreo() {
    scrollTicking = false;
    if (!choreoSection || performance.now() < manualLockUntil) return;

    const vh = window.innerHeight || 800;
    const rect = choreoSection.getBoundingClientRect();
    const scrollTop = window.scrollY || 0;

    if (scrollTop < 28 || rect.top > vh * 0.58) {
      // Hero panoramic pose at top of page (0.0), smoothly approaching Stage 01 as user starts scrolling
      const heroSpan = Math.max(180, vh * 0.42);
      const heroProgress = Math.max(0, Math.min(1, (scrollTop - 16) / heroSpan));
      stage3d.setStage(heroProgress * 0.92);
      setActiveStageUI(0);
      return;
    }

    const scrollable = Math.max(1, rect.height - vh * 0.52);
    const passed = Math.max(0, Math.min(scrollable, vh * 0.58 - rect.top));
    const ratio = passed / scrollable; // 0..1 across the 3 stages
    const stageFloat = 1 + ratio * 2; // 1.0 -> 3.0
    stage3d.setStage(stageFloat);

    const activeIdx = Math.min(2, Math.max(0, Math.round(ratio * 2)));
    setActiveStageUI(activeIdx);
  }

  window.addEventListener(
    "scroll",
    () => {
      if (!scrollTicking) {
        scrollTicking = true;
        requestAnimationFrame(updateScrollChoreo);
      }
    },
    { passive: true },
  );
  updateScrollChoreo();
}

// ---------------------------------------------------------------------------
// 3. Authentic Android + Desktop Web/PWA Interactive Sync Workbench
// ---------------------------------------------------------------------------
function initWorkbench() {
  const state = {
    seq: 1041,
    view: "sms", // "sms" | "calls"
    sourcePhoneIdx: 1, // Default Xiaomi 14 spare phone
    deviceFilter: "",
    simFilter: "",
    group: "all",
    query: "",
    localContactsEnabled: true,
    aiEnabled: false,
    selectedSender: "1069070021",
    selectedCallId: 512,
    mobileDetailOpen: false,
    messages: INITIAL_MESSAGES.map((m) => ({
      ...m,
      analysis: { ...m.analysis },
      expiresAt: m.analysis?.ttlMinutes ? Date.now() + m.analysis.ttlMinutes * 60000 : 0,
    })),
    calls: INITIAL_CALLS.map((c) => ({ ...c })),
    capsuleOtp: {
      code: "684219",
      sender: "星云通行证",
      fromDevice: "Pixel 9 · 主力机",
      visible: true,
    },
  };

  const els = {
    sourcePhoneTabs: $$("[data-wb-phone]"),
    phoneName: $("#wb-phone-name"),
    phoneModel: $("#wb-phone-model"),
    phoneSims: $("#wb-phone-sims"),
    phoneStatus: $("#wb-phone-status"),
    phoneOutbox: $("#wb-phone-outbox"),
    phonePreviewSender: $("#wb-phone-preview-sender"),
    phonePreviewBody: $("#wb-phone-preview-body"),
    capsuleBox: $("#wb-otp-capsule"),
    capsuleCode: $("#wb-capsule-code"),
    capsuleMeta: $("#wb-capsule-meta"),
    capsuleCopy: $("#wb-capsule-copy"),
    bridgeArc: $("#wb-bridge-packet"),
    bridgeSeq: $("#wb-hub-seq"),
    viewTabs: $$("[data-wb-view]"),
    markAllRead: $("#wb-mark-read"),
    toggleContacts: $("#wb-toggle-contacts"),
    toggleAi: $("#wb-toggle-ai"),
    aiDisclosure: $("#wb-ai-inline-note"),
    searchInput: $("#wb-search-input"),
    searchClear: $("#wb-search-clear"),
    quickTags: $$("[data-wb-quick]"),
    deviceChips: $("#wb-device-chips"),
    simChips: $("#wb-sim-chips"),
    groupChips: $("#wb-group-chips"),
    listContainer: $("#wb-list"),
    detailContainer: $("#wb-detail"),
    pwaShell: $("#wb-pwa-shell"),
  };

  function resolveContactName(sender, hubAlias = "") {
    if (state.localContactsEnabled && VIEWER_LOCAL_CONTACTS[sender]) {
      return VIEWER_LOCAL_CONTACTS[sender].name;
    }
    return hubAlias || "";
  }

  function deviceById(id) {
    return DEMO_DEVICES.find((d) => d.id === id) || DEMO_DEVICES[0];
  }

  function simLabelFor(phone) {
    const found = DEMO_SIMS.find((s) => s.phone === phone);
    return found ? found.label : phone;
  }

  function messageMatchesGroup(msg, group) {
    if (group === "all") return true;
    const isOtp = Boolean(msg.analysis?.otp) && msg.direction !== "out";
    if (!state.aiEnabled) {
      if (group === "otp") return isOtp;
      if (group === "other") return !isOtp;
      return true;
    }
    if (msg.direction === "out") return false;
    const cat = msg.analysis?.category || (isOtp ? "otp" : "pending");
    return cat === group;
  }

  function messageMatchesFilters(msg, ignoreGroup = false) {
    if (state.deviceFilter && msg.device_id !== state.deviceFilter) return false;
    if (state.simFilter && msg.receiver !== state.simFilter) return false;
    if (!ignoreGroup && !messageMatchesGroup(msg, state.group)) return false;
    const q = state.query.trim().toLowerCase();
    if (q) {
      const contact = resolveContactName(msg.sender, msg.hubAlias).toLowerCase();
      const hay = `${msg.sender} ${msg.hubAlias || ""} ${contact} ${msg.receiver} ${msg.body} ${msg.analysis?.otp || ""}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  }

  function buildConversations(ignoreGroup = false) {
    const map = new Map();
    const sorted = [...state.messages].sort((a, b) => b.timestamp - a.timestamp || b.id - a.id);
    for (const m of sorted) {
      if (!messageMatchesFilters(m, ignoreGroup)) continue;
      const existing = map.get(m.sender);
      if (!existing) {
        map.set(m.sender, {
          ...m,
          unreadCount: m.unread ? 1 : 0,
        });
      } else if (m.unread) {
        existing.unreadCount += 1;
      }
    }
    return [...map.values()];
  }

  function renderSourcePhoneCard() {
    const dev = DEMO_DEVICES[state.sourcePhoneIdx];
    els.sourcePhoneTabs.forEach((btn, idx) => {
      const active = idx === state.sourcePhoneIdx;
      btn.classList.toggle("is-active", active);
      btn.setAttribute("aria-selected", String(active));
    });
    if (els.phoneName) els.phoneName.textContent = dev.name;
    if (els.phoneModel) els.phoneModel.textContent = dev.model;
    if (els.phoneStatus) els.phoneStatus.textContent = dev.status;
    if (els.phoneSims) {
      els.phoneSims.replaceChildren(
        ...dev.sims.map((s) => el("span", "sim-pill", `${s.slot} · ${s.phone}`)),
      );
    }
    const latestFromDev = [...state.messages]
      .filter((m) => m.device_id === dev.id)
      .sort((a, b) => b.id - a.id)[0];
    if (latestFromDev && els.phonePreviewSender && els.phonePreviewBody) {
      const cname = resolveContactName(latestFromDev.sender, latestFromDev.hubAlias) || latestFromDev.sender;
      els.phonePreviewSender.textContent = `${cname} · ${latestFromDev.receiver}`;
      els.phonePreviewBody.textContent = latestFromDev.body;
    }

    if (els.capsuleBox) {
      els.capsuleBox.hidden = !state.capsuleOtp.visible;
      if (els.capsuleCode) els.capsuleCode.textContent = state.capsuleOtp.code;
      if (els.capsuleMeta) {
        els.capsuleMeta.textContent = `来自其他设备 (${state.capsuleOtp.fromDevice}) · ${state.capsuleOtp.sender}`;
      }
    }
  }

  function renderFilterBars() {
    // Device chips
    if (els.deviceChips) {
      els.deviceChips.replaceChildren();
      const items = [["", "全部设备"], ...DEMO_DEVICES.map((d) => [d.id, d.shortName])];
      for (const [val, label] of items) {
        const active = state.deviceFilter === val;
        const btn = el("button", `wb-chip ${active ? "is-active" : ""}`.trim(), label);
        btn.type = "button";
        btn.setAttribute("aria-pressed", String(active));
        btn.onclick = () => {
          state.deviceFilter = active && val !== "" ? "" : val;
          springPop(btn);
          renderAll({ flip: true });
        };
        els.deviceChips.append(btn);
      }
    }

    // SIM chips
    if (els.simChips) {
      els.simChips.replaceChildren();
      const items = [["", "全部号码"], ...DEMO_SIMS.map((s) => [s.phone, s.label])];
      for (const [val, label] of items) {
        const active = state.simFilter === val;
        const btn = el("button", `wb-chip wb-chip--subtle ${active ? "is-active" : ""}`.trim(), label);
        btn.type = "button";
        btn.setAttribute("aria-pressed", String(active));
        btn.onclick = () => {
          state.simFilter = active && val !== "" ? "" : val;
          springPop(btn);
          renderAll({ flip: true });
        };
        els.simChips.append(btn);
      }
    }

    // Group chips with live counts
    if (els.groupChips) {
      els.groupChips.replaceChildren();
      const groups = visibleGroups(state.aiEnabled);
      if (!groups.includes(state.group)) state.group = "all";

      for (const g of groups) {
        const count = state.messages.filter(
          (m) => messageMatchesFilters(m, true) && messageMatchesGroup(m, g),
        ).length;
        const active = state.group === g;
        const btn = el("button", `wb-chip wb-chip--group ${active ? "is-active" : ""}`.trim());
        btn.type = "button";
        btn.setAttribute("aria-pressed", String(active));
        btn.append(
          document.createTextNode(GROUP_LABELS[g]),
          el("span", "wb-chip__count", String(count)),
        );
        btn.onclick = () => {
          state.group = g;
          springPop(btn);
          renderAll({ flip: true });
        };
        els.groupChips.append(btn);
      }
    }

    if (els.aiDisclosure) {
      els.aiDisclosure.hidden = !state.aiEnabled;
    }
  }

  function renderSmsList({ flip = false } = {}) {
    const list = els.listContainer;
    if (!list) return;

    const prevTops = new Map();
    if (flip && !isReducedMotion()) {
      $$("[data-conv-key]", list).forEach((row) => {
        prevTops.set(row.dataset.convKey, row.getBoundingClientRect().top);
      });
    }

    list.replaceChildren();
    const convs = buildConversations(false);

    if (!convs.length) {
      const empty = el("div", "wb-empty");
      empty.append(
        el("div", "wb-empty__badge", "信"),
        el("strong", "", state.query ? "没有匹配的演示短信" : "当前筛选条件下暂无会话"),
        el("p", "", "可点击上方“清空”或切换设备/号码筛选查看全部虚构同步记录。"),
      );
      list.append(empty);
      return;
    }

    // Ensure selected conversation remains valid on desktop
    if (!convs.some((c) => c.sender === state.selectedSender)) {
      state.selectedSender = convs[0].sender;
    }

    for (const c of convs) {
      const selected = c.sender === state.selectedSender;
      const row = el("div", `wb-conv ${selected ? "is-selected" : ""}`.trim());
      row.dataset.convKey = c.sender;
      row.tabIndex = 0;
      row.setAttribute("role", "button");
      row.setAttribute("aria-current", selected ? "true" : "false");

      const contactName = resolveContactName(c.sender, c.hubAlias);
      const titleText = contactName || c.sender;
      const dev = deviceById(c.device_id);

      const bodyCol = el("div", "wb-conv__body");
      const topRow = el("div", "wb-conv__top");
      const titleGroup = el("div", "wb-conv__title-wrap");
      titleGroup.append(el("strong", "wb-conv__title", titleText));
      if (state.localContactsEnabled && VIEWER_LOCAL_CONTACTS[c.sender]) {
        titleGroup.append(el("span", "wb-local-tag", "本机通讯录"));
      }
      topRow.append(
        titleGroup,
        el("time", "wb-conv__time", `${dev.shortName} · ${formatRelative(c.timestamp)}`),
      );

      const bottomRow = el("div", "wb-conv__bottom");
      if (c.analysis?.otp && c.direction !== "out") {
        const otpChip = el("button", "wb-otp-chip", `验证码 ${c.analysis.otp}`);
        otpChip.type = "button";
        otpChip.setAttribute("aria-label", `复制虚构验证码 ${c.analysis.otp}`);
        otpChip.onclick = (e) => {
          e.stopPropagation();
          springPop(otpChip, 1.08);
          void copySafeText(
            c.analysis.otp,
            `已复制虚构验证码 ${c.analysis.otp} · 真实端支持 60 秒安全剪贴板清理`,
          );
        };
        bottomRow.append(otpChip);
      }

      bottomRow.append(
        el("p", "wb-conv__snippet", c.direction === "out" ? `我：${c.body}` : c.body),
      );
      if (c.unreadCount > 0) {
        bottomRow.append(el("span", "wb-unread", String(c.unreadCount)));
      }

      bodyCol.append(topRow, bottomRow);
      row.append(createAvatarNode(contactName, c.sender), bodyCol);

      const activate = () => {
        state.selectedSender = c.sender;
        state.mobileDetailOpen = true;
        // Mark messages in this conversation read
        for (const m of state.messages) {
          if (m.sender === c.sender) m.unread = false;
        }
        renderAll({ flip: false });
      };
      row.onclick = activate;
      row.onkeydown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          activate();
        }
      };

      list.append(row);
    }

    if (prevTops.size && !isReducedMotion()) {
      $$("[data-conv-key]", list).forEach((row) => {
        const oldTop = prevTops.get(row.dataset.convKey);
        if (oldTop === undefined) {
          row.animate(
            [
              { opacity: 0, transform: "translateY(-14px) scale(0.98)" },
              { opacity: 1, transform: "translateY(0) scale(1)" },
            ],
            { duration: 380, easing: "cubic-bezier(0.2, 0.9, 0.25, 1)" },
          );
          return;
        }
        const dy = oldTop - row.getBoundingClientRect().top;
        if (Math.abs(dy) > 1) {
          row.animate(
            [{ transform: `translateY(${dy}px)` }, { transform: "translateY(0)" }],
            { duration: 360, easing: "cubic-bezier(0.16, 0.88, 0.24, 1)" },
          );
        }
      });
    }
  }

  function renderSmsDetail() {
    const detail = els.detailContainer;
    if (!detail) return;
    detail.replaceChildren();

    const thread = state.messages
      .filter((m) => m.sender === state.selectedSender)
      .sort((a, b) => a.timestamp - b.timestamp || a.id - b.id);

    if (!thread.length) {
      const empty = el("div", "wb-empty");
      empty.append(
        el("div", "wb-empty__badge", "枢"),
        el("strong", "", "选择一个会话查看详情"),
        el("p", "", "支持跨多台授权手机与多张 SIM 卡聚合展示同一发件人的全部收发记录。"),
      );
      detail.append(empty);
      return;
    }

    const latest = thread.at(-1);
    const contactName = resolveContactName(state.selectedSender, latest.hubAlias);
    const receivers = [...new Set(thread.map((m) => m.receiver).filter(Boolean))];
    const localNote = state.localContactsEnabled ? VIEWER_LOCAL_CONTACTS[state.selectedSender]?.note : "";

    const head = el("header", "wb-detail__head");
    const backBtn = el("button", "wb-back-btn", "← 返回");
    backBtn.type = "button";
    backBtn.onclick = () => {
      state.mobileDetailOpen = false;
      els.pwaShell?.classList.remove("has-mobile-detail");
    };

    const headInfo = el("div", "wb-detail__info");
    const titleCol = el("div", "wb-detail__titles");
    titleCol.append(
      el("strong", "", contactName || state.selectedSender),
      el(
        "span",
        "wb-detail__sub",
        [
          contactName ? state.selectedSender : "",
          `来自 ${receivers.length || 1} 个接收号码`,
          localNote || "只读安全归档",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
    );
    headInfo.append(createAvatarNode(contactName, state.selectedSender, "hs-avatar--sm"), titleCol);

    const copySenderBtn = el("button", "wb-icon-pill", "复制号码");
    copySenderBtn.type = "button";
    copySenderBtn.onclick = () => {
      springPop(copySenderBtn);
      void copySafeText(state.selectedSender, `已复制虚构号码 ${state.selectedSender}`);
    };

    head.append(backBtn, headInfo, copySenderBtn);
    detail.append(head);

    const stream = el("div", "wb-messages");
    stream.append(el("div", "wb-date-chip", "今天 · 私有中枢加密会话"));

    for (const msg of thread) {
      const outgoing = msg.direction === "out";
      const wrap = el("div", `wb-msg ${outgoing ? "wb-msg--out" : ""}`.trim());
      const bubble = el("div", `wb-bubble ${outgoing ? "wb-bubble--out" : ""}`.trim());

      if (msg.analysis?.otp && !outgoing) {
        const otpCard = el("div", "wb-otp-card");
        const otpHead = el("div", "wb-otp-card__head");
        otpHead.append(
          el("span", "", "规则即时提取验证码"),
          el("span", "wb-otp-card__ttl", `原文明示有效 ${msg.analysis.ttlMinutes || 5} 分钟`),
        );
        const otpRow = el("div", "wb-otp-card__row");
        const codeNode = el("strong", "wb-otp-card__code", msg.analysis.otp);
        const copyBtn = el("button", "wb-otp-card__btn", "复制验证码");
        copyBtn.type = "button";
        copyBtn.onclick = () => {
          springPop(copyBtn, 1.07);
          copyBtn.textContent = "已复制 ✓";
          void copySafeText(
            msg.analysis.otp,
            `已复制验证码 ${msg.analysis.otp} · 60 秒后仅在剪贴板未变时自动清理`,
          );
          window.setTimeout(() => {
            copyBtn.textContent = "复制验证码";
          }, 1600);
        };
        otpRow.append(codeNode, copyBtn);

        const barTrack = el("div", "wb-otp-card__bar");
        const barFill = el("i", "");
        barFill.style.setProperty("--otp-fill", "78%");
        barTrack.append(barFill);

        otpCard.append(otpHead, otpRow, barTrack);
        bubble.append(otpCard);
      }

      bubble.append(el("p", "wb-bubble__text", msg.body));

      if (state.aiEnabled && !outgoing) {
        const cat = msg.analysis?.category || (msg.analysis?.otp ? "otp" : "pending");
        bubble.append(
          el("small", "wb-ai-badge", `AI 复核分组：${GROUP_LABELS[cat] || "待分析"} · 可手动调整`),
        );
      }

      const dev = deviceById(msg.device_id);
      const stampParts = [
        formatTime(msg.timestamp),
        dev.name,
        outgoing ? `由 ${simLabelFor(msg.receiver)} 发出 · 本机已发送同步` : `接收号码 ${msg.receiver}`,
        msg.historical ? "历史补齐" : "",
      ].filter(Boolean);

      wrap.append(bubble, el("div", "wb-msg__stamp", stampParts.join(" · ")));
      stream.append(wrap);
    }

    const readonlyFoot = el(
      "div",
      "wb-readonly-bar",
      "只读查看端边界：鸿枢仅同步本机已收发短信，不提供回复输入框，也不通过运营商代发短信。",
    );
    detail.append(stream, readonlyFoot);
  }

  function renderCallsView() {
    const list = els.listContainer;
    const detail = els.detailContainer;
    if (!list || !detail) return;
    list.replaceChildren();
    detail.replaceChildren();

    const typeLabels = { incoming: "呼入", outgoing: "呼出", missed: "未接来电" };
    const filteredCalls = state.calls
      .filter((c) => (!state.deviceFilter || c.device_id === state.deviceFilter) && (!state.simFilter || c.receiver === state.simFilter))
      .sort((a, b) => b.timestamp - a.timestamp);

    if (!filteredCalls.some((c) => c.id === state.selectedCallId) && filteredCalls[0]) {
      state.selectedCallId = filteredCalls[0].id;
    }

    for (const c of filteredCalls) {
      const selected = c.id === state.selectedCallId;
      const missed = c.type === "missed";
      const row = el("button", `wb-conv ${selected ? "is-selected" : ""} ${missed ? "is-missed" : ""}`.trim());
      row.type = "button";
      const cname = resolveContactName(c.number, c.hubAlias);
      const dev = deviceById(c.device_id);

      const bodyCol = el("div", "wb-conv__body");
      const top = el("div", "wb-conv__top");
      top.append(el("strong", "wb-conv__title", cname || c.number), el("time", "wb-conv__time", formatTime(c.timestamp)));

      const bottom = el("div", "wb-conv__bottom");
      bottom.append(
        el(
          "p",
          "wb-conv__snippet",
          `${typeLabels[c.type] || "通话"} · ${missed ? "未接通" : formatDuration(c.duration)} · ${dev.shortName}`,
        ),
      );
      bodyCol.append(top, bottom);
      row.append(createAvatarNode(cname, c.number), bodyCol);
      row.onclick = () => {
        state.selectedCallId = c.id;
        state.mobileDetailOpen = true;
        renderAll({ flip: false });
      };
      list.append(row);
    }

    const activeCall = filteredCalls.find((c) => c.id === state.selectedCallId);
    if (!activeCall) {
      detail.append(el("div", "wb-empty", "当前筛选条件下暂无通话记录"));
      return;
    }

    const cname = resolveContactName(activeCall.number, activeCall.hubAlias);
    const dev = deviceById(activeCall.device_id);
    const head = el("header", "wb-detail__head");
    const backBtn = el("button", "wb-back-btn", "← 返回");
    backBtn.type = "button";
    backBtn.onclick = () => {
      state.mobileDetailOpen = false;
      els.pwaShell?.classList.remove("has-mobile-detail");
    };
    const info = el("div", "wb-detail__info");
    const titles = el("div", "wb-detail__titles");
    titles.append(
      el("strong", "", cname || activeCall.number),
      el("span", "wb-detail__sub", "独立 call_clock 顺序同步 · 不影响短信未读水位"),
    );
    info.append(createAvatarNode(cname, activeCall.number, "hs-avatar--sm"), titles);
    head.append(backBtn, info);

    const facts = el("dl", "wb-call-facts");
    const entries = [
      ["记录类型", `${typeLabels[activeCall.type] || "通话"} · 语音通话`],
      ["通话时长", activeCall.type === "missed" ? "未接通" : formatDuration(activeCall.duration)],
      ["对端号码", activeCall.number],
      ["本机线路", `${simLabelFor(activeCall.receiver)} (${activeCall.receiver})`],
      ["来源手机", dev.name],
      ["归属地示例", activeCall.location],
      ["能力边界", "只同步、只读：不拨打电话、不回拨、不触发跨端来电响铃"],
    ];
    for (const [k, v] of entries) {
      facts.append(el("dt", "", k), el("dd", "", v));
    }
    detail.append(head, facts);
  }

  function renderAll({ flip = false } = {}) {
    renderSourcePhoneCard();
    if (els.bridgeSeq) els.bridgeSeq.textContent = `sync_clock #${state.seq}`;
    els.pwaShell?.classList.toggle("has-mobile-detail", state.mobileDetailOpen);

    els.viewTabs.forEach((t) => {
      const active = t.dataset.wbView === state.view;
      t.classList.toggle("is-active", active);
      t.setAttribute("aria-selected", String(active));
    });

    const isCalls = state.view === "calls";
    if (els.groupChips) els.groupChips.hidden = isCalls;
    renderFilterBars();

    if (isCalls) {
      renderCallsView();
    } else {
      renderSmsList({ flip });
      renderSmsDetail();
    }
  }

  // Trigger a live synthetic sync from a chosen phone into the Hub & Web/PWA Inbox
  function triggerLiveSync(phoneIdx, options = {}) {
    const idx = Math.max(0, Math.min(2, phoneIdx));
    state.sourcePhoneIdx = idx;
    const dev = DEMO_DEVICES[idx];
    const sim = dev.sims[0];
    state.seq += 1;

    const kind = options.kind || (idx === 1 && !options.fromHero ? "otp" : idx === 2 ? "work-otp" : "otp");
    const tpl =
      LIVE_TEMPLATES.find((t) => t.kind === kind) ||
      LIVE_TEMPLATES[state.seq % LIVE_TEMPLATES.length];

    const generatedCode =
      tpl.category === "otp"
        ? (idx === 2
            ? `${310 + ((state.seq * 19) % 620)}-${410 + ((state.seq * 23) % 520)}`
            : String(520100 + ((state.seq * 7919) % 469000)))
        : "";

    const newBody = tpl.makeBody(generatedCode, state.seq);
    const newMsg = {
      id: state.seq,
      sender: tpl.sender,
      hubAlias: tpl.hubAlias,
      receiver: sim.phone,
      device_id: dev.id,
      direction: "in",
      timestamp: Date.now(),
      body: newBody,
      unread: true,
      historical: false,
      analysis: {
        otp: generatedCode,
        category: tpl.category,
        state: "complete",
        ttlMinutes: tpl.ttlMinutes,
      },
    };

    if (els.phoneOutbox) {
      els.phoneOutbox.textContent = "outbox 1 条写入 → 正在提交中枢…";
      els.phoneOutbox.classList.add("is-syncing");
    }

    // Animate bridge light packet
    if (els.bridgeArc && !isReducedMotion() && typeof els.bridgeArc.animate === "function") {
      els.bridgeArc.classList.add("is-flying");
      els.bridgeArc.animate(
        [
          { transform: "translateX(0) scale(0.75)", opacity: 0 },
          { transform: "translateX(45%) scale(1.08)", opacity: 1, offset: 0.45 },
          { transform: "translateX(100%) scale(0.85)", opacity: 0 },
        ],
        { duration: 680, easing: "cubic-bezier(0.22, 1, 0.36, 1)" },
      ).onfinish = () => els.bridgeArc.classList.remove("is-flying");
    }

    // Drive 3D stage simultaneously
    stage3d?.triggerMessagePacket({
      phoneIndex: idx,
      sender: tpl.hubAlias || tpl.sender,
      otp: generatedCode,
      preview: generatedCode ? "5 分钟内有效" : "新通知已到达",
      body: newBody,
    });

    const commit = () => {
      state.messages.push(newMsg);
      state.view = "sms";
      state.selectedSender = newMsg.sender;
      if (state.deviceFilter && state.deviceFilter !== dev.id) state.deviceFilter = "";
      if (state.simFilter && state.simFilter !== sim.phone) state.simFilter = "";
      if (state.group === "otp" && !generatedCode) state.group = "all";

      if (generatedCode) {
        state.capsuleOtp = {
          code: generatedCode,
          sender: tpl.hubAlias || tpl.sender,
          fromDevice: dev.name,
          visible: true,
        };
      }

      if (els.phoneOutbox) {
        els.phoneOutbox.textContent = `outbox 0 待传 · 已确认 #${state.seq}`;
        els.phoneOutbox.classList.remove("is-syncing");
      }
      renderAll({ flip: true });
      if (!options.silentToast) {
        showToast(
          generatedCode
            ? `${dev.shortName} 捕获虚构验证码 ${generatedCode} → 已同步至桌面收件箱`
            : `${dev.shortName} 新短信已归集至私有中枢 #${state.seq}`,
        );
      }
    };

    if (isReducedMotion()) commit();
    else window.setTimeout(commit, 340);
  }

  // Bind Workbench Controls
  els.sourcePhoneTabs.forEach((btn) => {
    btn.addEventListener("click", () => {
      state.sourcePhoneIdx = Number(btn.dataset.wbPhone || 0);
      springPop(btn);
      renderSourcePhoneCard();
    });
  });

  $("#wb-send-otp")?.addEventListener("click", (e) => {
    springPop(e.currentTarget, 1.05);
    triggerLiveSync(state.sourcePhoneIdx, { kind: state.sourcePhoneIdx === 2 ? "work-otp" : "otp" });
  });

  $("#wb-send-notice")?.addEventListener("click", (e) => {
    springPop(e.currentTarget, 1.05);
    triggerLiveSync(state.sourcePhoneIdx, { kind: "notice" });
  });

  $("#wb-send-call")?.addEventListener("click", (e) => {
    springPop(e.currentTarget, 1.05);
    const dev = DEMO_DEVICES[state.sourcePhoneIdx];
    const nextCallId = Math.max(...state.calls.map((c) => c.id)) + 1;
    state.calls.unshift({
      id: nextCallId,
      number: "010-8820****",
      hubAlias: "云主机运维值班",
      type: "incoming",
      video: false,
      duration: 94,
      timestamp: Date.now(),
      receiver: dev.sims[0].phone,
      device_id: dev.id,
      location: "北京 · 虚构示例",
      historical: false,
    });
    state.view = "calls";
    state.selectedCallId = nextCallId;
    renderAll({ flip: true });
    showToast(`${dev.shortName} 通话记录已同步（只读归档，不触发跨端来电响铃）`);
  });

  els.capsuleCopy?.addEventListener("click", () => {
    springPop(els.capsuleCopy, 1.08);
    void copySafeText(
      state.capsuleOtp.code,
      `已从跨设备实时胶囊复制 ${state.capsuleOtp.code}`,
    );
  });

  els.viewTabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      state.view = tab.dataset.wbView || "sms";
      state.mobileDetailOpen = false;
      springPop(tab);
      renderAll({ flip: false });
    });
  });

  els.markAllRead?.addEventListener("click", () => {
    springPop(els.markAllRead);
    for (const m of state.messages) m.unread = false;
    renderAll({ flip: false });
    showToast("已将全部会话标记为已读 · read_outbox 已同步至中枢");
  });

  els.toggleContacts?.addEventListener("click", () => {
    state.localContactsEnabled = !state.localContactsEnabled;
    els.toggleContacts.setAttribute("aria-checked", String(state.localContactsEnabled));
    els.toggleContacts.classList.toggle("is-on", state.localContactsEnabled);
    springPop(els.toggleContacts);
    renderAll({ flip: false });
    showToast(
      state.localContactsEnabled
        ? "已开启查看端本机联系人装饰（姓名仅在当前设备本地显示，不上传中枢）"
        : "已关闭本机联系人装饰，恢复显示原始脱敏号码",
    );
  });

  els.toggleAi?.addEventListener("click", () => {
    state.aiEnabled = !state.aiEnabled;
    els.toggleAi.setAttribute("aria-checked", String(state.aiEnabled));
    els.toggleAi.classList.toggle("is-on", state.aiEnabled);
    springPop(els.toggleAi);
    renderAll({ flip: true });
    showToast(
      state.aiEnabled
        ? "已预览可选 AI 7 组分类（真实环境中开启会把短信正文发给你选择的服务商）"
        : "已回到默认本地规则模式（全部 / 验证码 / 其他，零外部模型调用）",
    );
  });

  els.searchInput?.addEventListener("input", () => {
    state.query = els.searchInput.value;
    if (els.searchClear) els.searchClear.hidden = !state.query;
    renderAll({ flip: true });
  });

  els.searchClear?.addEventListener("click", () => {
    state.query = "";
    if (els.searchInput) {
      els.searchInput.value = "";
      els.searchInput.focus();
    }
    els.searchClear.hidden = true;
    renderAll({ flip: true });
  });

  els.quickTags.forEach((tag) => {
    tag.addEventListener("click", () => {
      const val = tag.dataset.wbQuick || "";
      if (val === "__reset__") {
        state.query = "";
        state.deviceFilter = "";
        state.simFilter = "";
        state.group = "all";
        if (els.searchInput) els.searchInput.value = "";
        if (els.searchClear) els.searchClear.hidden = true;
      } else {
        state.view = "sms";
        if (val === "林知夏" && !state.localContactsEnabled) {
          state.localContactsEnabled = true;
          els.toggleContacts?.setAttribute("aria-checked", "true");
          els.toggleContacts?.classList.add("is-on");
        }
        state.query = val;
        if (els.searchInput) els.searchInput.value = val;
        if (els.searchClear) els.searchClear.hidden = false;
      }
      springPop(tag);
      renderAll({ flip: true });
    });
  });

  renderAll({ flip: false });
  return { triggerLiveSync };
}

// ---------------------------------------------------------------------------
// 4. Self-Hosting Deployment Configurator Tabs & Copy
// ---------------------------------------------------------------------------
function initDeploymentTabs() {
  const tabBar = $("#deploy-tablist");
  const codeBlock = $("#deploy-code");
  const captionEl = $("#deploy-caption");
  const copyBtn = $("#deploy-copy-btn");
  if (!tabBar || !codeBlock) return;

  let activeTab = DEPLOY_TABS[0];

  function selectTab(tab) {
    activeTab = tab;
    $$("[data-deploy-tab]", tabBar).forEach((b) => {
      const on = b.dataset.deployTab === tab.id;
      b.classList.toggle("is-active", on);
      b.setAttribute("aria-selected", String(on));
    });
    codeBlock.textContent = tab.code;
    if (captionEl) captionEl.textContent = tab.caption;
  }

  tabBar.replaceChildren(
    ...DEPLOY_TABS.map((tab, idx) => {
      const btn = el("button", `deploy-tab ${idx === 0 ? "is-active" : ""}`.trim(), tab.label);
      btn.type = "button";
      btn.setAttribute("role", "tab");
      btn.dataset.deployTab = tab.id;
      btn.setAttribute("aria-selected", String(idx === 0));
      btn.onclick = () => {
        springPop(btn);
        selectTab(tab);
      };
      return btn;
    }),
  );

  selectTab(DEPLOY_TABS[0]);

  copyBtn?.addEventListener("click", () => {
    springPop(copyBtn, 1.06);
    void copySafeText(activeTab.code, `已复制「${activeTab.label}」参考命令`);
  });
}

// ---------------------------------------------------------------------------
// 5. Gentle Scroll-Reveal Choreography
// ---------------------------------------------------------------------------
function initScrollReveal() {
  const nodes = $$("[data-reveal]");
  if (!nodes.length) return;
  if (isReducedMotion() || !("IntersectionObserver" in window)) {
    nodes.forEach((n) => n.classList.add("is-revealed"));
    return;
  }
  const io = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) {
          e.target.classList.add("is-revealed");
          io.unobserve(e.target);
        }
      }
    },
    { threshold: 0.12, rootMargin: "0px 0px -40px 0px" },
  );
  nodes.forEach((n) => io.observe(n));
}

// Boot
initTheme();
const wb = initWorkbench();
initStageAndChoreography((phoneIdx, opts) => wb?.triggerLiveSync(phoneIdx, opts));
initDeploymentTabs();
initScrollReveal();

// Update version badge from HUB_META if present
const verBadge = $("#hub-version-tag");
if (verBadge) verBadge.textContent = `v${HUB_META.version}`;
