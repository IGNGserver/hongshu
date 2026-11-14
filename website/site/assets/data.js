// Synthetic demonstration dataset for the Hongshu official website.
// Every phone number, device name, contact name, verification code, and SMS body
// below is 100% fictional and masked to illustrate privacy & product boundaries.

export const HUB_META = {
  version: "0.2.0-alpha.26",
  endpoint: "https://sms.home-lab.example",
  localBind: "127.0.0.1:18473",
  engine: "Go + MySQL 8.4",
  epoch: 1,
};

export const DEMO_DEVICES = [
  {
    id: "dev-pixel9",
    name: "Pixel 9 · 主力机",
    shortName: "Pixel 9",
    model: "Android 16 · Material 3",
    role: "主力随身机",
    accent: "#2558b0",
    sims: [
      { slot: "SIM 1", label: "主力卡 · 0412", phone: "138****0412" },
    ],
    status: "实时服务在线 · remoteMessaging",
    outboxPending: 0,
  },
  {
    id: "dev-xiaomi14",
    name: "Xiaomi 14 · 备用机",
    shortName: "Xiaomi 14",
    model: "HyperOS · 通知协同采集",
    role: "抽屉双卡机",
    accent: "#3c789e",
    sims: [
      { slot: "SIM 1", label: "生活卡 · 9105", phone: "186****9105" },
      { slot: "SIM 2", label: "流量卡 · 7731", phone: "177****7731" },
    ],
    status: "书房充电常驻 · WorkManager + WS",
    outboxPending: 0,
  },
  {
    id: "dev-s24",
    name: "Galaxy S24 · 工作机",
    shortName: "Galaxy S24",
    model: "Android 15 · 通话与短信",
    role: "办公专线机",
    accent: "#8f6f3e",
    sims: [
      { slot: "SIM 1", label: "工作卡 · 5520", phone: "139****5520" },
    ],
    status: "已开启短信与只读通话记录同步",
    outboxPending: 0,
  },
];

export const DEMO_SIMS = DEMO_DEVICES.flatMap((d) =>
  d.sims.map((s) => ({ ...s, deviceId: d.id, deviceName: d.shortName })),
);

// Local-only contacts on the viewing device (never uploaded to the hub).
export const VIEWER_LOCAL_CONTACTS = {
  "135****8821": { name: "林知夏", note: "查看端本地通讯录匹配（未上传中枢）" },
  "150****3309": { name: "周予安", note: "查看端本地通讯录匹配（未上传中枢）" },
};

// Shared avatar rules mirroring web/app.js and android/Theme.kt
export const AVATAR_COLORS = [
  "#D9883D",
  "#2F9E5B",
  "#D44B7A",
  "#2563C9",
  "#8A46A8",
  "#1496A8",
  "#DE5B54",
  "#5263B8",
];

export function avatarInitial(contact, sender) {
  const name = String(contact || sender || "").trim().replace(/^[【\[(（]+/, "");
  const first = [...name][0];
  if (!first || /[0-9+*]/.test(first)) return null;
  return first.toUpperCase();
}

export function isServiceNumber(sender) {
  const clean = String(sender || "").replace(/^\+86/, "");
  if (clean.includes("*")) return false;
  return /^\d+$/.test(clean) && ((clean.length >= 3 && clean.length <= 6) || (clean.startsWith("106") && clean.length > 6));
}

export function avatarColor(identifier) {
  const str = String(identifier || "");
  if (!str) return AVATAR_COLORS[0];
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (Math.imul(31, hash) + str.charCodeAt(i)) | 0;
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

export const GROUP_LABELS = {
  all: "全部短信",
  otp: "验证码",
  other: "其他短信",
  personal: "个人",
  notification: "通知",
  promotion: "推广",
  spam: "垃圾",
  pending: "待分析",
};

export function visibleGroups(aiEnabled) {
  return aiEnabled
    ? ["all", "otp", "personal", "notification", "promotion", "spam", "pending"]
    : ["all", "otp", "other"];
}

const now = Date.now();
const mins = (m) => now - m * 60 * 1000;

export const INITIAL_MESSAGES = [
  {
    id: 1041,
    sender: "1069070021",
    hubAlias: "星云通行证",
    receiver: "138****0412",
    device_id: "dev-pixel9",
    direction: "in",
    timestamp: mins(2),
    body: "【星云通行证】您的登录验证码为 684219，5 分钟内有效。请勿向他人泄露验证码。",
    unread: true,
    historical: false,
    analysis: {
      otp: "684219",
      category: "otp",
      state: "complete",
      ttlMinutes: 5,
    },
  },
  {
    id: 1039,
    sender: "1068204199",
    hubAlias: "青鸟驿站",
    receiver: "186****9105",
    device_id: "dev-xiaomi14",
    direction: "in",
    timestamp: mins(14),
    body: "【青鸟驿站】您的包裹已到澄园北门服务站，请凭取件码 4-2-1086 在 21:00 前取件。",
    unread: true,
    historical: false,
    analysis: {
      otp: "",
      category: "notification",
      state: "complete",
    },
  },
  {
    id: 1036,
    sender: "1069800155",
    hubAlias: "内网研发网关",
    receiver: "139****5520",
    device_id: "dev-s24",
    direction: "in",
    timestamp: mins(27),
    body: "【内网研发网关】SSH 堡垒机二次验证动态码 903-741，有效期 10 分钟，操作源 10.24.8.12。",
    unread: false,
    historical: false,
    analysis: {
      otp: "903-741",
      category: "otp",
      state: "complete",
      ttlMinutes: 10,
    },
  },
  {
    id: 1032,
    sender: "135****8821",
    hubAlias: "",
    receiver: "138****0412",
    device_id: "dev-pixel9",
    direction: "in",
    timestamp: mins(49),
    body: "下午三点的建筑模型评审改到二楼阳光会议室啦，样机我顺路带过去了。",
    unread: false,
    historical: false,
    analysis: {
      otp: "",
      category: "personal",
      state: "complete",
    },
  },
  {
    id: 1033,
    sender: "135****8821",
    hubAlias: "",
    receiver: "138****0412",
    device_id: "dev-pixel9",
    direction: "out",
    timestamp: mins(46),
    body: "收到，我把私有中枢的备份脚本跑完就过去。",
    unread: false,
    historical: false,
    analysis: {
      otp: "",
      category: "",
      state: "complete",
    },
  },
  {
    id: 1028,
    sender: "95508",
    hubAlias: "示例银行通知",
    receiver: "186****9105",
    device_id: "dev-xiaomi14",
    direction: "in",
    timestamp: mins(115),
    body: "【示例银行】您尾号 0912 储蓄账户于 09:42 发生自动扣款人民币 18.00 元（云存储订阅），余额以客户端账单为准。",
    unread: false,
    historical: false,
    analysis: {
      otp: "",
      category: "notification",
      state: "complete",
    },
  },
  {
    id: 1024,
    sender: "150****3309",
    hubAlias: "",
    receiver: "139****5520",
    device_id: "dev-s24",
    direction: "in",
    timestamp: mins(190),
    body: "备用机放在书房连着 Wi-Fi，刚才海外卡收到的动态码在桌面端直接复制到了，很稳。",
    unread: false,
    historical: true,
    analysis: {
      otp: "",
      category: "personal",
      state: "complete",
    },
  },
  {
    id: 1019,
    sender: "1065502988",
    hubAlias: "云栖书店会员",
    receiver: "177****7731",
    device_id: "dev-xiaomi14",
    direction: "in",
    timestamp: mins(320),
    body: "【云栖书店】秋季纸质设计年鉴展本周六开启，会员凭卡享饮品礼遇，回 T 退订（仅演示，鸿枢不代发退订）。",
    unread: false,
    historical: true,
    analysis: {
      otp: "",
      category: "promotion",
      state: "complete",
    },
  },
];

export const INITIAL_CALLS = [
  {
    id: 512,
    number: "135****8821",
    hubAlias: "",
    type: "incoming",
    video: false,
    duration: 138,
    timestamp: mins(38),
    receiver: "138****0412",
    device_id: "dev-pixel9",
    location: "上海 · 虚构示例",
    historical: false,
  },
  {
    id: 511,
    number: "021-5550****",
    hubAlias: "园区物业前台",
    type: "missed",
    video: false,
    duration: 0,
    timestamp: mins(84),
    receiver: "139****5520",
    device_id: "dev-s24",
    location: "上海 · 固定电话示例",
    historical: false,
  },
  {
    id: 509,
    number: "150****3309",
    hubAlias: "",
    type: "outgoing",
    video: false,
    duration: 312,
    timestamp: mins(210),
    receiver: "139****5520",
    device_id: "dev-s24",
    location: "杭州 · 虚构示例",
    historical: true,
  },
];

export const LIVE_TEMPLATES = [
  {
    kind: "otp",
    label: "发送虚构验证码",
    deviceId: "dev-xiaomi14",
    receiver: "186****9105",
    sender: "1069070021",
    hubAlias: "星云通行证",
    makeBody: (code) => `【星云通行证】您正在登录桌面工作台，验证码 ${code}，5 分钟内有效，切勿转发他人。`,
    category: "otp",
    ttlMinutes: 5,
  },
  {
    kind: "work-otp",
    label: "工作机动态码",
    deviceId: "dev-s24",
    receiver: "139****5520",
    sender: "1069800155",
    hubAlias: "内网研发网关",
    makeBody: (code) => `【内网研发网关】容器镜像仓库发布确认码 ${code}，有效期 5 分钟。`,
    category: "otp",
    ttlMinutes: 5,
  },
  {
    kind: "notice",
    label: "备用机取件通知",
    deviceId: "dev-xiaomi14",
    receiver: "177****7731",
    sender: "1068204199",
    hubAlias: "青鸟驿站",
    makeBody: (_, seq) => `【青鸟驿站】您的冷链生鲜件已存入 03 号温控柜，取件凭证 7-${(seq % 8) + 1}-${3200 + seq}。`,
    category: "notification",
    ttlMinutes: 0,
  },
  {
    kind: "personal",
    label: "主力机联系人短信",
    deviceId: "dev-pixel9",
    receiver: "138****0412",
    sender: "135****8821",
    hubAlias: "",
    makeBody: () => "刚把测试样机放回充电底座了，验证码同步延迟不到一秒。",
    category: "personal",
    ttlMinutes: 0,
  },
];

export const SCROLL_STAGES = [
  {
    index: 0,
    step: "STAGE 01 · 手机端可靠采集",
    title: "不依赖 Google 服务，多卡手机各自安静采集。",
    description:
      "主力机与抽屉里的双卡备用机只需授予 RECEIVE_SMS 即可实时捕获新短信：先写入本机 SQLite 持久队列与去重账本，再经 WorkManager 或实时服务上传，断网、切卡或重启都不会静默丢信。",
    highlights: [
      "SIM 订阅 ID 精确绑定接收号码，换卡槽不串号",
      "HyperOS 等系统可选通知信号协同补读，自动吸收重复副本",
      "“补齐历史短信”与“同步通话记录”均独立授权、按需开启",
    ],
    telemetry: "Android outbox → SHA-256 幂等指纹校验",
  },
  {
    index: 1,
    step: "STAGE 02 · 汇入私有 MySQL 中枢",
    title: "柔和汇聚到你自己的服务器，绝不绕道第三方云。",
    description:
      "每条短信沿加密链路直接提交至你自托管的 Go + MySQL 8.0+ 中枢。服务端通过单例 sync_clock 串行分配提交顺序，在同一事务内完成落库、版本化规则验证码提取与无正文变更广播。",
    highlights: [
      "单一中枢密码（Argon2id）+ 每设备独立 256-bit Bearer 凭据",
      "sync_clock.epoch 守护逻辑备份与回档一致性，防止游标错乱",
      "可选 Redis 仅存未读数等非敏感派生状态，绝不缓存短信正文",
    ],
    telemetry: "127.0.0.1:18473 · sync_clock 事务提交",
  },
  {
    index: 2,
    step: "STAGE 03 · 桌面与多端即时查阅",
    title: "镜头转向桌面：同一条消息，在所有授权设备优雅就位。",
    description:
      "WebSocket 仅推送轻量无正文变更信号，桌面 Web/PWA 与其他授权手机立即增量拉取。支持按采集设备与接收号码筛选、全局搜索、一键复制验证码，以及 Android 16+ 跨设备验证码状态栏胶囊。",
    highlights: [
      "原生零框架 Web/PWA，支持桌面双栏收件箱与 iOS 主屏幕推送",
      "查看端可选本机通讯录装饰（PhoneLookup / Contact Picker），姓名与头像不上传",
      "验证码提取带原文明示有效期进度与 60 秒安全剪贴板清理",
    ],
    telemetry: "WebSocket changed → Web/PWA 增量同步完成",
  },
];

export const DEPLOY_TABS = [
  {
    id: "standard",
    label: "Docker Compose（内置 MySQL）",
    caption: "推荐单机部署：默认仅绑定本机 127.0.0.1:18473，配合反向代理提供 HTTPS。",
    code: `# 1. 使用与当前发布版本一致的初始化脚本生成 0600 权限 .env 与 VAPID 密钥
HONGSHU_VERSION=0.2.0-alpha.26 \\
PUBLIC_URL=https://sms.example.com \\
VAPID_SUBJECT=mailto:ops@example.com \\
bash scripts/init-deployment.sh

# 2. 启动 Go 中枢、MySQL 8.4 与定时一致性逻辑备份容器
docker compose up -d

# 3. 本机健康检查（默认宿主端口 18473 -> 容器 8080）
curl --fail http://127.0.0.1:18473/healthz`,
  },
  {
    id: "external",
    label: "外部共享 MySQL",
    caption: "已有独立 MySQL 8.0+ 实例时，分配独立 utf8mb4_bin schema，只运行轻量中枢容器。",
    code: `# 需提前创建 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin 独立库
HONGSHU_VERSION=0.2.0-alpha.26 \\
HONGSHU_DB_MODE=external \\
HONGSHU_DATABASE_URL='mysql://hongshu:change-me@mysql.internal:3306/hongshu?tls=true' \\
PUBLIC_URL=https://sms.example.com \\
bash scripts/init-deployment.sh

docker compose -f compose.external.yml up -d
curl --fail http://127.0.0.1:18473/healthz`,
  },
  {
    id: "proxy",
    label: "HTTPS 反向代理要点",
    caption: "保障 Secure Cookie、Service Worker 离线壳与 Web Push 正常工作，并关闭访问日志防泄露。",
    code: `# Nginx 关键反代片段（完整限流配置见对应版本 DEPLOYMENT.md）
location / {
    proxy_pass http://127.0.0.1:18473;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $hongshu_connection;
    proxy_read_timeout 90s;
    # 严禁在访问日志记录查询词、凭据或响应路径细节
    access_log off;
}`,
  },
];
