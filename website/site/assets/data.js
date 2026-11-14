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
    model: "Android 16 · 主力随身机",
    role: "主力随身机",
    accent: "#2558b0",
    sims: [
      { slot: "SIM 1", label: "主力卡 · 0412", phone: "138****0412" },
    ],
    status: "实时同步在线",
    outboxPending: 0,
  },
  {
    id: "dev-xiaomi14",
    name: "Xiaomi 14 · 备用机",
    shortName: "Xiaomi 14",
    model: "澎湃 OS · 双卡常驻收信",
    role: "抽屉双卡机",
    accent: "#3c789e",
    sims: [
      { slot: "SIM 1", label: "生活卡 · 9105", phone: "186****9105" },
      { slot: "SIM 2", label: "流量卡 · 7731", phone: "177****7731" },
    ],
    status: "书房充电常驻中",
    outboxPending: 0,
  },
  {
    id: "dev-s24",
    name: "Galaxy S24 · 工作机",
    shortName: "Galaxy S24",
    model: "Android 15 · 办公号常用",
    role: "办公专线机",
    accent: "#8f6f3e",
    sims: [
      { slot: "SIM 1", label: "工作卡 · 5520", phone: "139****5520" },
    ],
    status: "已开启短信与通话记录同步",
    outboxPending: 0,
  },
];

export const DEMO_SIMS = DEMO_DEVICES.flatMap((d) =>
  d.sims.map((s) => ({ ...s, deviceId: d.id, deviceName: d.shortName })),
);

// Local-only contacts on the viewing device (never uploaded to the hub).
export const VIEWER_LOCAL_CONTACTS = {
  "135****8821": { name: "林知夏", note: "仅在当前设备匹配通讯录，姓名不上云" },
  "150****3309": { name: "周予安", note: "仅在当前设备匹配通讯录，姓名不上云" },
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
    hubAlias: "云端网盘",
    receiver: "139****5520",
    device_id: "dev-s24",
    direction: "in",
    timestamp: mins(27),
    body: "【云端网盘】您正在新电脑登录个人网盘，确认验证码 903-741，10 分钟内有效，请勿向他人泄露。",
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
    body: "下午三点的项目沟通改到二楼阳光会议室啦，资料我顺路带过去了。",
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
    body: "收到，我把手头这份表格存完就过去。",
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
    body: "备用机放在书房连着 Wi-Fi，刚才那张副卡收到的验证码在电脑网页上直接复制到了，太省事了。",
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
    label: "发送示例验证码",
    deviceId: "dev-xiaomi14",
    receiver: "186****9105",
    sender: "1069070021",
    hubAlias: "星云通行证",
    makeBody: (code) => `【星云通行证】您正在登录电脑浏览器，验证码 ${code}，5 分钟内有效，切勿转发他人。`,
    category: "otp",
    ttlMinutes: 5,
  },
  {
    kind: "work-otp",
    label: "办公手机验证码",
    deviceId: "dev-s24",
    receiver: "139****5520",
    sender: "1069800155",
    hubAlias: "云端网盘",
    makeBody: (code) => `【云端网盘】您正在新设备上登录个人网盘，确认码 ${code}，有效期 5 分钟。`,
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
    makeBody: () => "刚到家把备用机放书房充电了，收到取件短信在电脑上直接就能看到。",
    category: "personal",
    ttlMinutes: 0,
  },
];

export const SCROLL_STAGES = [
  {
    index: 0,
    step: "第 1 步 · 手机端安静收信",
    title: "备用机放抽屉充电，断网或换卡也不漏信。",
    description:
      "不管手头有几台 Android 手机、插了几张电话卡，装上鸿枢 App 就能安静收信。就算临时断网、重启或手机省电待机，也会先把短信妥善排好队，等有网络后自动补传。",
    highlights: [
      "清晰区分每张 SIM 卡收到的短信，换卡槽也不串号",
      "小米澎湃等系统可选配合系统通知识别，自动过滤重复副本",
      "「导入历史短信」和「同步通话记录」都可按需要独立开关",
    ],
    telemetry: "手机安全排队中 → 自动查重确认",
  },
  {
    index: 1,
    step: "第 2 步 · 汇入自己的私有空间",
    title: "只存进你自己的电脑或 NAS，不绕道第三方云。",
    description:
      "短信不经过任何陌生厂商的服务器，直接传回你自己搭建的私有空间。你的家里 NAS、闲置小主机或个人云主机都能运行，账号密码与每台手机的授权由你完全掌控。",
    highlights: [
      "每个设备有独立授权钥匙，手机丢了可随时单独踢出",
      "自动顺序校验与去重，即使重装或恢复备份也不会乱",
      "支持加速未读统计且绝不额外缓存任何短信正文",
    ],
    telemetry: "自有私有中枢 · 已安全落库",
  },
  {
    index: 2,
    step: "第 3 步 · 电脑和主力机随手查看",
    title: "无论坐在哪块屏幕前，打开浏览器就能直接用。",
    description:
      "收到新短信或验证码时，电脑网页版与另一台主力手机会在一两秒内同步亮起。支持按手机/号码筛选、快速搜索、一键复制验证码，还能配合 Android 16+ 跨设备验证码弹窗。",
    highlights: [
      "打开网址即可用，Windows、Mac 与 iPhone 桌面图标都支持推送",
      "可用当前设备自带通讯录匹配联系人姓名，通讯录绝不上传",
      "验证码自动高亮与倒计时，复制后支持定时自动清理剪贴板",
    ],
    telemetry: "全端即时同步完成",
  },
];

export const DEPLOY_TABS = [
  {
    id: "standard",
    label: "推荐：一键单机部署",
    caption: "适合家里 NAS、小主机或个人云服务器，一条命令自动配好中枢与数据库。",
    code: `# 1. 初始化配置文件与网页推送密钥（使用当前发布版本）
HONGSHU_VERSION=0.2.0-alpha.26 \\
PUBLIC_URL=https://sms.example.com \\
VAPID_SUBJECT=mailto:ops@example.com \\
bash scripts/init-deployment.sh

# 2. 启动中枢服务、MySQL 数据库与定时备份
docker compose up -d

# 3. 检查本机是否启动成功（默认端口 18473）
curl --fail http://127.0.0.1:18473/healthz`,
  },
  {
    id: "external",
    label: "进阶：连接已有 MySQL",
    caption: "如果你电脑或 NAS 上已经跑了 MySQL 8.0+，可以共用数据库、只启动轻量的中枢服务。",
    code: `# 提前创建 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin 独立库
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
    label: "进阶：配置 HTTPS 外网访问",
    caption: "搭配 Nginx / Caddy 绑定域名开启 HTTPS，浏览器推送与离线打开更稳定，并关闭访问日志防泄露。",
    code: `# Nginx 反代关键配置（完整示例见对应版本 DEPLOYMENT.md）
location / {
    proxy_pass http://127.0.0.1:18473;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $hongshu_connection;
    proxy_read_timeout 90s;
    # 关闭访问日志，避免记录搜索词或接口路径
    access_log off;
}`,
  },
];
