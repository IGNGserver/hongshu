# 鸿枢 Android

Kotlin / Compose / Material 3 Expressive；Android 8+，target 35。
JDK 17、Android SDK 35、Gradle wrapper 8.11.1（SHA256 校验）。所有依赖来自 Google Maven/Maven Central。

```bash
export GRADLE_USER_HOME="$HOME/.cache/hongshu-gradle"
export HONGSHU_BUILD_DIR="$HOME/.cache/hongshu-android"
./gradlew --no-daemon --project-cache-dir "$GRADLE_USER_HOME/project-cache" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 在 `$HONGSHU_BUILD_DIR/app/outputs/apk/release/`（或 `$HOME/.cache/hongshu/build/app/outputs/apk/release/`）。Release 发布资产只包含经过正式签名的 Android APK，签名密钥采用安全离线/Secret 存储；仓库不包含签名私钥材料。
支持 HTTP 与 HTTPS。HTTP 会明文传输密码、设备凭据和短信正文；公网使用强烈建议配置 HTTPS 或 VPN。HTTPS 证书仍必须受 Android 信任（不禁用证书验证）。

打开先显示会话列表，右上角进入设备/连接设置。新设备默认不采集短信；开启采集、授予新短信权限、
配置确认 SIM 号码后才上传。历史读取/通讯录/通知/SIM 识别权限分开申请。
数据先落本地 outbox，服务器确认后删除；未确认双卡号码保留等待映射。
“同步本机短信”只请求 RECEIVE_SMS；“补齐历史短信”独立请求 READ_SMS，历史不可读不阻断新短信。
“新短信补漏”独立请求 READ_SMS，仅读取开启后的短信；历史关闭时也能补漏未投递广播的短信。
该开关默认关闭，不自动读取旧短信；总采集关闭期间不补漏。重新进入或周期任务执行补扫，可能延迟。
“立即读取并检查收件箱”可主动复查；新增 0 条只表示当前查询未新增，不能证明系统没有或允许读取验证码。
设置 → 本机待同步短信，可离线分页查看正文、时间及等待号码/网络/中枢授权等原因；不混入中枢收件箱。
实时采集、历史读取、本地队列与中枢同步分别显示状态；权限已授予不等于已验证系统投递广播。
实时状态额外记录系统广播入口到达次数/时间，解析、存储和任务调度失败分别显示，无正文诊断日志。
普通查看模式没有 RECEIVE_SMS/READ_SMS 也可正常使用。

实时后台服务必须由用户主动开启，带真实连接状态和停止按钮的常驻通知。
Android 14+ 使用 remoteMessaging 支持跨设备文本消息连续接收，旧系统使用 dataSync 兼容路径；
不通过重启绕过系统限制，WorkManager 周期补齐不保证实时性。
“不在最近任务列表显示”和“开机恢复实时连接”默认关闭、独立配置；首页返回仅退到后台，
从桌面图标或通知重新进入。隐藏不保证厂商仍保留最近任务锁，不代表永不被杀。
使用方式和真机验收矩阵见 [后台运行说明](../docs/ANDROID_BACKGROUND.md)。
收到自己上传的短信不会重复提示。初次历史补齐默认不逐条通知。
本地缓存有全部历史短信，Android 私有目录/禁止备份/禁止截屏，但不是数据库字段级加密。

真实设备验收见 docs/ANDROID_COMPATIBILITY.md；JVM 单测与 APK 构建不能证明厂商双卡广播或验证码权限可用。
实时路径与升级边界见 [REALTIME_SMS.md](../docs/REALTIME_SMS.md)。新增相同的 SQLite/权限/并发合同
同时由 Robolectric native SQLite（JVM）和 AndroidJUnitRunner（设备）执行；前者不能代替 ROM 兼容验收。
