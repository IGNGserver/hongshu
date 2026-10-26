# 2026-10-26 后台短信实时执行与通知

恢复基线 `1d1dec6`，分支 `agent/background-realtime-delivery-resume`，版本保持
`0.2.0-alpha.19`。本轮仅开发、验证和推送任务分支，不创建 PR、不合并、不发布、不部署。

- Android：用户已开启的实时前台服务直接执行同步；广播/观察者/通知/界面唤醒执行器，
  WorkManager 保留持久兜底。网络和最近收件箱采集独立合并触发，可靠游标、ACK 和通知
  仍复用原事务。最近 100 行不推进前向水位，不等待历史或 SIM 服务。
- 实时路径跳过附加元数据；普通 Worker 让出附加工作后保留后继，失败的实时请求不
  无限饿死维护。通知配对的提前复读与到期同步都有服务内定时器，停止服务时取消。
- 连接通知区分握手与真实同步，20 秒未完成显示延迟；诊断新增最近触发/开始/成功/
  中枢提示时间。不增加权限或存储短信内容日志。
- PWA：Push 独立向已打开页面发送无正文刷新提示，不依赖旧 WS 恢复；仍不在 Worker
  下载或缓存短信正文，不能以此声称修复了目标设备上的 APNs/FCM 投递问题。

本机验证（ubuntu-dev，依赖与产物仅在 `/home/lvziw/.build/`）：

- `TMPDIR=本机scratch bash scripts/verify-repository.sh`：通过，12 项运维测试。
- `git diff --check`：通过。
- `cd web && HONGSHU_TEST_TMP=本机scratch npm test && npm run check`：109 项通过，0 失败。
- `python3 website/scripts/check-site.py`：通过，未改官网。
- Android scratch：`./gradlew --no-daemon --offline --max-workers=2 --project-cache-dir
  本机scratch :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin`：
  首轮恢复后通过，322 项中 302 执行、20 项既有截图按条件跳过、0 失败；lint 和设备测试
  源码编译通过，2m48s。新增 `RealtimeDeliveryTest` 8 项、`RealtimeInboxTest` 8 项均执行。
  最终功能代码重跑仍全部通过（3m04s）；后台测试夹具等待在途同步退出后再恢复配置，
  该夹具调整后 `:app:compileDebugAndroidTestKotlin :app:lintDebug` 再次通过。

早期离线运行因缺少依赖失败，在线补齐后针对性单测及 instrumentation 源码编译通过
（6m01s）；没有把依赖缺失、超时或跳过算作验证成功。APK 构建、Go/真实 MySQL 和 API
28/33/35 设备测试由任务分支 CI 执行，不在 NAS 工作区运行构建。

**真机边界**：尚未连接用户手机，未验证目标 ROM 的锁屏/Doze、广播投递、双卡、断网恢复
和真实 Push 到达；合成 provider/HTTP/WS、模拟器和编译均不替代真机验收。必须两端全程
不打开应用，分别测普通短信与验证码；系统强制停止或扣留短信时不保证即时同步。

原任务目录的 Git 登记在暂停期间消失；保留原目录与分支，未删除任何现场。经用户继续
授权临时第 3 个工作区后，在新工作区逐文件核对恢复，未覆盖其他两个并行任务的修改。

# 2026-10-25 通话记录同步（只读）

基线 `c123e81`（`0.2.0-alpha.19`），四个 PR 串行集成：
[#91](https://github.com/IGNGserver/hongshu/pull/91) 中枢 → [#92](https://github.com/IGNGserver/hongshu/pull/92) Web →
[#93](https://github.com/IGNGserver/hongshu/pull/93) Android 采集 → 本 PR（Android 查看）。设计见
[CALL_LOG.md](CALL_LOG.md)，契约见 [API.md](API.md)“通话记录”。

- 中枢：迁移 013（`calls` + `call_clock`），`POST/GET /api/calls`、`/api/calls/{id}`、`/api/calls/sync`；
  `/me.call_cursor`、`features: call_log`；不改变短信游标、未读与推送。
- Web：“短信 / 通话”切换、按天分组的列表、详情面板；只刷新通话列表。
- Android：`READ_CALL_LOG` 采集（默认关闭）、`call_outbox`/`call_ledger`（v16）、上传；
  `/api/calls/sync` 缓存（v17）、列表与详情、`AppDestination.Call`。

| PR | 本机验证（macos-dev2，scratch 在 `~/.build/短信同步系统/<slug>`） | PR CI |
| --- | --- | --- |
| #91 | `go test -race`/`go vet`/`go build`/gofmt、verify、diff check 通过；本机无 MySQL，集成测试跳过 | [40161315482](https://github.com/IGNGserver/hongshu/actions/runs/40161315482) 全部通过；`TestIntegrationCallLog`、`TestIntegrationBackupRestore` 在真实 MySQL 上 PASS |
| #92 | `npm test` 106 项、`check`、`build`；内置浏览器 + 合成假中枢实测桌面/手机/深色/分页 | [40162650076](https://github.com/IGNGserver/hongshu/actions/runs/40162650076) 全部通过（rebase 前一次 android 28 仪器测试计数不稳定，与本 PR 无关） |
| #93 | Gradle 单测 292（0 失败）、lint 0 error、assemble 通过 | [40163384189](https://github.com/IGNGserver/hongshu/actions/runs/40163384189) 全部通过（含 android 28/33/35 instrumentation） |
| 本 PR | Gradle 单测 306（0 失败，含 `CallViewTest` 8 项）、lint 0 error、assemble 通过；Robolectric 截图核对手机/平板/深色 | 见 PR |

**尚未真机验证**（编译与模拟器不能代替）：

1. 来电接听、来电未接、拒接、去电接通、去电未接通各一次，确认类型、时长、时间与线路。
2. 双卡分别拨打/接听，确认“本机线路”与 SIM 对应；澎湃 OS 的 `PHONE_ACCOUNT_ID` 能否解析。
3. 隐藏号码来电显示“隐藏号码”；通讯录联系人显示姓名。
4. 通话结束到其他设备可见的时延（应用在前台/后台/被杀三种状态）。
5. 开启“历史补齐”后旧通话一次性补齐且不重复；关闭开关后不再上传。

# 2026-10-23 验证码 AI 复核与提取

基线 `c68fa5e`（`0.2.0-alpha.18`），任务分支 `agent/ai-otp-analysis`，PR
[#89](https://github.com/IGNGserver/hongshu/pull/89)。契约见
[MESSAGE_GROUPING.md](MESSAGE_GROUPING.md)、[API.md](API.md)。

- 验证码仍由规则在入库事务内同步提取并立即显示；AI 开启后验证码也进入持久
  队列，以 `otp` 类别复核最终分组并提取验证码文本。AI 未完成、失败或关闭时
  回退规则结果；规则码保留在 `message_analysis`，人工改回验证码可恢复展示。
- AI 输出严格校验：必须逐字出现在原文、4–8 位字母数字或连字符、至少一位
  数字、边界不允许截断更长号码；不满足则丢弃重试。
- 验证码类短信向 AI 发送时保留数字，链接和邮箱仍脱敏；同意文案已更新。
- 旧规则验证码行通过领取条件懒纳入队列，不批量改 `state`，不需要迁移。
- Web/Android 显示验证码的 AI 复核状态并允许手动归类；Android 本地规则提取
  只在无服务端分析时兜底，AI 开关切换时重置分析游标避免陈旧码。
- 浏览器 smoke 改为覆盖验证码进入 AI 队列，并验证分类器收到原码、其它短信
  不泄漏验证码。

本机验证（macOS，`~/.build` 可用 108 GiB；任务 scratch 18 MiB；Android
构建输出在默认本机缓存 `~/.cache/hongshu/build`，均不在项目卷）：

| 命令/检查 | 实际结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过，版本 `0.2.0-alpha.18`，12 项运维测试，10.9s |
| `git diff --check` | 通过 |
| `server`: `go test -race ./...`、`go vet ./...`、`go build -trimpath` | 通过（race 5.7s）；未设置 `TEST_MYSQL_DSN`，真实 MySQL 集成本机跳过 |
| `web`: `npm test`、`npm run check`、`npm run build` | 97 项通过；检查/构建通过（`WEB_BUILD_DIR` 在 scratch） |
| `android`: scratch `:app:testDebugUnitTest --tests io.hongshu.app.MessageAnalysisTest` | 8 项（API 28/35）通过，0 失败 |

完整 PR CI：[Hongshu validation 39889220318](https://github.com/IGNGserver/hongshu/actions/runs/39889220318)，
全部通过：

| Job | 结论与耗时 |
| --- | --- |
| verify | 通过，8s |
| web | 通过，5s |
| server | 通过，2m40s；真实 MySQL、Go race/vet/build、Chromium 浏览器 smoke |
| android (28) | 通过，9m27s；全量单测、lint、APK/测试 APK、instrumentation |
| android (33) | 通过，8m30s；全量单测、lint、APK/测试 APK、instrumentation |
| android (35) | 通过，10m11s；全量单测、lint、APK/测试 APK、instrumentation |

本次未调用真实 AI provider，未使用真实短信或真机；服务端集成、浏览器流程与
全量 Android 构建由 CI 使用合成数据执行。本机未提供 MySQL，不能把本机跳过
真库测试当作数据库验证通过。

回滚：关闭 AI 即恢复规则即时结果，或回退二进制；没有迁移需要回滚。派生表可
保留，重新升级可继续使用。验证码正文会发送到配置的 AI 服务，已开启 AI 的部署
应重新查看并保存新的同意文案；若要强制重新同意，需额外 `consent_version`。

# 2026-10-22 Safari 网页 App 图标修复

基线 `156a059`（`0.2.0-alpha.15`），任务分支 `agent/pwa-safari-icons`。本版本随 PR 合并发布 `0.2.0-alpha.16` 并部署到 NAS；真机 Safari 图标验收见文末。

- 现象：从 Safari 添加到程序坞/启动台的鸿枢网页 App 显示系统首字母占位图标（深色方块 + “鸿”），而不是蓝色短信气泡品牌图标；同一版本的 Web 标签页 favicon 正常。
- 根因：manifest 的最高图标只有 512×512 maskable。WebKit 的 macOS Web App 图标规则要求至少一个不透明、full-bleed 的
  `1024×1024` maskable 位图（或任意尺寸 SVG）来生成程序坞/启动台/Quick Look 的多尺寸图标；缺失时 Safari 会回退到
  apple-touch-icon（这里最高 180）或系统占位图。Alpha.15 的 manifest 首个图标还是 32×32 favicon，进一步放大了旧版选图器的风险。
- 修复：从 512 主图标 Catmull-Rom 上采样生成 `icon-1024.png` 与 `icon-maskable-1024.png`，将 1024 maskable 放在 manifest
  首位，HTML favicon 也优先声明 1024/512/192 PNG；补充 `/favicon.ico` 与 `apple-touch-icon-precomposed.png` 兜底；
  build、SW、Go 静态白名单和测试同步。

本机验证（macOS 26.7.1，Safari 27.0；产物在 `~/.build/hongshu/pwa-safari-icons/`，开始 `df` 可用 110Gi）：

| 命令/检查 | 实际结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过，12 项运维测试（11.3s）；`git diff --check` 通过 |
| `cd web && npm test && npm run check` | 96 项测试通过；manifest 顺序、1024 any/maskable、SW/build 资源和 Apple 元标签断言通过 |
| `WEB_BUILD_DIR=~/.build/hongshu/pwa-safari-icons/web-dist npm run build` | 通过；产物 manifest 的 7 个图标均存在且尺寸正确，HTML/SW 均指向内容 hash 文件 |
| `cd server && go test -race ./... && go vet ./... && go build -trimpath -o 本机scratch/hongshu .` | 通过（race 5.8s）；新增测试逐条请求 manifest 图标并用 `image/png` 解码核对尺寸，另检查 `/favicon.ico`、`/apple-touch-icon-precomposed.png` |
| 实网只读探测 `https://hongshu.lvziwang.top`（alpha.15） | `manifest.webmanifest` 返回 `application/manifest+json`，图标均 200；说明不是 MIME/静态路由问题 |

真机验收仍未完成：需要在部署包含本修复的镜像后，在受影响的 Mac 上删除旧网页 App，再从 Safari 重新“添加到程序坞”，
确认程序坞/启动台显示蓝色气泡图标。Safari 在添加时缓存图标，直接在旧 App 上刷新不会触发重取。1024 图标由 512 主图标
上采样，若后续有原生 1024 设计稿应替换；本次未运行 Android 构建（改动不涉及 Android），未触发 CI。

# 2026-10-20 多端加载与弱网优化

基线 `585b5ee`（`0.2.0-alpha.12`），任务分支 `agent/loading-performance`；发布工作流目标版本 `0.2.0-alpha.13`（预览版，非 stable）。
实现契约与运行方式见 [LOADING_PERFORMANCE.md](LOADING_PERFORMANCE.md)。

- Web/PWA/桌面安装 PWA：认证后立即呈现壳，列表不等统计/联系人/AI 队列；元数据修订与 single-flight，超时不误登出；有界内存详情缓存和会话节点复用。
- 公共壳：内容 hash、immutable、静态 gzip；核心集合安装、缓存优先和用户同意更新，保留旧页面资源；敏感 API 不入 SW/CDN 缓存。
- Android：按可见页面读取、搜索防抖、本地 v15 派生会话头和批量去重；每轮最多四页下载/一批上传，低速上传字节预算；最近展示预览不推进可靠游标，不触发历史通知，可靠重叠页仍处理通知。
- 中枢：迁移 012，元数据修订与写事务共同提交；只读一致性最近快照；epoch/head/read-revision 键控未读缓存；WS 仍只查 singleton，额外观察元数据与 epoch。

本地验证（源码副本和产物均在 `~/.build/hongshu/loading-performance/`；开始 df 可用 127Gi，后续 125Gi）：

| 命令/检查 | 实际结果 |
| --- | --- |
| `TMPDIR=本机scratch bash scripts/verify-repository.sh` | 通过，12 项运维测试；在任务 worktree 执行以包含真实 Git 跟踪检查 |
| `git diff --check` | 通过 |
| scratch Web `HONGSHU_TEST_TMP=本机scratch npm test && npm run check` | 81 项通过；含挂起增强请求时内容仍显示、两个关键 API 阶段、超时/身份/epoch、离线壳/版本资源与缓存隔离，以及写后强制刷新、有界详情内存和开关可访问名称 |
| scratch Go `go test -race ./... && go vet ./...` | 通过；本机未提供 TEST_MYSQL_DSN，真实 MySQL 集成不计入本机结论 |
| scratch Android `./gradlew --no-daemon --project-cache-dir 本机scratch :app:testDebugUnitTest --tests io.hongshu.app.LoadingTest` | 最后 16 项通过、0 skip/失败（1m33s），含进程重启后的预览/可靠页重叠通知和替换联系人缓存后的网络时效失效；共享迁移合同及 instrumentation Kotlin 编译另在 scratch 通过（1m42s） |

全套 Go/MySQL、Chromium、Android API 28/33/35 构建/模拟器使用现有 workflow_dispatch 验证任务分支，不能以本地跳过真库测试代替。
首轮 [39423200026](https://github.com/IGNGserver/hongshu/actions/runs/39423200026) 的 Go race/真 MySQL/vet/build、Web/verify 通过；浏览器设备断言在异步刷新完成前读取旧列表，Android 共享迁移合同仍断言 v14，导致本轮整体失败、未执行 instrumentation。修正浏览器等待目标设备及显式 aria-busy、写后强制请求不复用旧快照，并同步共享迁移版本断言为 v15；保留撤销设备数量、采集/水位/队列数据和通知的全部断言。
第二轮 [39424518898](https://github.com/IGNGserver/hongshu/actions/runs/39424518898) 的所有 JVM 单测/lint/APK 构建通过；浏览器发现两个开关共用通知标签，Android 三档 instrumentation 均在联系人故障用例失败：本地替换联系人快照后沿用了旧的网络 TTL，跳过了应有重试。已修复独立标签/开关状态，以及无网络时间戳的联系人快照使 TTL 失效；新增相应本地回归，保留原故障/撤销断言。

最终代码 `c279963` 的 [39426623660](https://github.com/IGNGserver/hongshu/actions/runs/39426623660) **全部通过**：

| Job | 结论与耗时 |
| --- | --- |
| verify | 仓库合同与空白检查通过，9s |
| web | `npm test && npm run check && npm run build` 通过，81 项测试，6s |
| server | Go race/真 MySQL 集成/vet/build、Chromium 端到端通过，2m46s；含挂起增强请求仍显示列表、离线壳、独立低速开关、设备撤销、AI 分类及隐私缓存检查 |
| android (28) | JVM 单测、lint、APK/测试 APK、23 项 instrumentation 全部通过，8m50s |
| android (33) | JVM 单测、lint、APK/测试 APK、51 项 instrumentation 全部通过，7m49s |
| android (35) | JVM 单测、lint、APK/测试 APK、51 项 instrumentation 全部通过，8m44s |

三次完整 workflow_dispatch 的 job 时间合计 4313s（约 71.9 job 分钟，另有 push 快速检查）；仓库为 public，标准公开 runner 不占用私仓每月 2000 分钟配额，消耗该配额 0 分钟。产物在 CI 或本机 scratch；本任务 scratch 约 31MiB，保留共享缓存，未把依赖/构建结果写入项目卷。
本机 Chromium（隔离合成 API，不访问生产）在元数据/统计挂起时仍可显示内容；独立低速开关和离线重新加载壳也通过。每次 API 人工延迟 0/600/1000ms 的单样本首批内容约 289/1434/2593ms；这是开发回归探针，不是 RTT 限速、p50/p95 或真机结果。

另用相同合成响应，对比 `585b5ee` 的只读源码快照与当前源码（每档一次新浏览器 context、相同本机 Chromium）：

| 每次 API 人工延迟 | 基线首批内容 | 当前首批内容 |
| --- | --- | --- |
| 0ms | 192ms | 201ms |
| 600ms | 2882ms | 1400ms |
| 1000ms | 4430ms | 2426ms |

基线在首批内容时已发起 11 个 HTTP API，当前 4 个（不计 WS，含独立增强请求）；当前元数据/统计被挂起仍能显示列表，基线允许增强请求正常返回。该对照只验证依赖阶段的收益，无延迟时差异属于噪声；每档单样本，不能用约 51%/45% 的单次差值宣称真实网络或 p95 目标已达标。探针、源码副本和原始输出仅保存在本机 scratch，不含生产短信、口令或会话。

P0/P3 的外部验收仍未完成：北京实际入口 URL、用户机型/网络尚未提供；未登录生产读取短信，未部署新版，因而没有同条件 release 包冷/热启动、已登录公网 waterfall、p50/p95 前后结果。LAN 公共资源探测旧部署：预热首页 TTFB 1.23ms、健康 0.99ms，仅是探测工具验证，不代表新版性能。丢包、10 万条规模、真实厂商 ROM/电池策略仍需隔离/真机验收；“p95 ≤1s / 改善 ≥40%”仍是目标而非已实现的结论。

回滚边界：迁移 012 只增派生时钟，旧服务可保留该表但不能混合写服务；Android v15 升级保留 outbox 与采集证据，旧 APK 不能直接降级打开新版库。壳为保护老标签页保留旧版本缓存，长期多次升级的缓存回收策略需结合目标浏览器配额继续观察。
# 2026-10-20 本机联系人（Android 与可选 Web/PWA）

契约见 [LOCAL_CONTACTS.md](LOCAL_CONTACTS.md)。Android 默认关闭，主动授权后只在本机
显示姓名/缩略头像及按姓名搜索本地短信；监听通讯录变化，撤权、关闭、退后台失效缓存。
PWA 仅保存用户主动选择的联系人，提供独立本机搜索、停用、清除与退出清除；跨标签页
使用 IndexedDB 事务代次防止旧保存复活已清除数据。既有中枢联系人映射不接收本机通讯录。
服务端仅允许新静态模块和本地 blob 图片，无联系人 API/数据库/上传协议变更；本次准备递增至 `0.2.0-alpha.14`。

| 本机实际验证 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh`、`git diff --check`、`test -z "$(gofmt -l server/*.go server/cmd/vapid/*.go)"` | 通过；12 项运维测试 |
| scratch `web`：`npm test`、`npm run check` | 79 项测试通过；新增本机号码匹配、Picker 降级、存储失败/清除/竞态、UI 数据隔离与迟到查询保护 |
| `PLAYWRIGHT_MODULE=… CHROMIUM_EXECUTABLE=… node scripts/local-contacts-smoke.cjs` | 本机 Chromium 通过：真实 IndexedDB/刷新恢复、blob 头像/CSP、本地姓名输入无请求、原始号码保留、多标签页停用/清除、配额失败、清除后旧写入拒绝、退出清除和不支持 Picker 的降级；Picker 返回值及 API 均为合成夹具 |
| scratch Android JDK 17：`./gradlew --no-daemon --project-cache-dir … :app:testDebugUnitTest --tests io.hongshu.app.LocalContactsTest --tests io.hongshu.app.PendingDisplayTest --tests io.hongshu.app.MessageAnalysisTest --tests io.hongshu.app.ProtocolTest :app:lintDebug` | 40 项针对性测试通过（新增 9 项联系人测试）；lint 通过，最终耗时 1m27s；照片测试使用原生图形模式与完整初始化的合成 ContentProvider，不以 mock 空流假装照片读取成功 |
| scratch `server`：`go test -race ./...`、`go vet ./...` | 通过；未设置 `TEST_MYSQL_DSN`，真实数据库测试跳过；静态模块可访问与仅图片 CSP 放行 blob 的合同已执行 |

本地构建副本与测试输出：`~/.build/hongshu/local-contact-display/`，共享 Gradle/Go 缓存
也位于 `~/.build/`；运行前可用空间 125 GiB，未在项目卷生成依赖或构建产物。
全量 APK、真实 MySQL、API 28/33/35 instrumentation 已由任务分支 CI 验证，不在本机重复全仓打包。

完整 CI：[Hongshu validation 39435442646](https://github.com/IGNGserver/hongshu/actions/runs/39435442646)，
验证代码提交 `2c95fed`，全部成功，总历时 8m45s。
`verify` 8s；`web` 7s（测试、语法检查和构建）；`server` 2m54s（真实 MySQL、race/vet/build、
原有浏览器集成和本机联系人 Chromium 流程）；`android (28/33/35)` 分别 8m05s、8m37s、8m38s
（全量单测、lint、debug APK/测试 APK 与对应 API 模拟器 instrumentation）。
另有 [push 快速检查 39435403514](https://github.com/IGNGserver/hongshu/actions/runs/39435403514) 成功，
仅运行 `verify`/`web`。仓库为 public，本轮占用私有仓库月度分钟数为 0。
本机任务 scratch 保留 33 MiB 的构建副本、测试输出和审计记录，未淘汰共享缓存；
物理手机/系统 Picker 验收仍按以下边界单列，不能用 CI 成功代替。

未完成环境验收：物理 Android 手机的授权弹窗、厂商 ROM 通讯录变更/权限撤销/照片访问，
以及真实手机浏览器系统 Contact Picker；合成 provider/Picker 测试不等于上述真机验收。
大量会话时，PWA 点击本机联系人需按页查找原始会话头；没有持久短信缓存，离线不能读取未加载历史。

## Rebase 后与 v0.2.0-alpha.14 发布候选

将本机联系人功能重放到 `v0.2.0-alpha.13` 后，处理了 Android 本地姓名搜索过滤未应用到已同步短信、浏览器已保存联系人加载后会话节点未刷新的问题；生产 Web 构建也修复了相对模块导入未改写为带哈希绝对资源路径的问题。`VERSION` 与中文发布说明已准备为 `0.2.0-alpha.14`，不是 stable。

| 实际验证 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh`、`git diff --check`、`test -z "$(gofmt -l server/*.go server/cmd/vapid/*.go)"` | 通过，12 项运维测试 |
| scratch Web：`HONGSHU_TEST_TMP="$HOME/.build/hongshu/local-contact-display/test-tmp" npm test`、`npm run check`、`WEB_BUILD_DIR="$HOME/.build/hongshu/local-contact-display/web-build" npm run build` | 90 项通过；语法检查与生产构建通过，包含 hashed module import 回归 |
| 本机 Chromium：`PLAYWRIGHT_MODULE="$HOME/.build/guanlan-playwright/node_modules/playwright" CHROMIUM_EXECUTABLE="$HOME/Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-x64/chrome-headless-shell" WEB_BUILD_DIR="$HOME/.build/hongshu/local-contact-display/web-build" node scripts/local-contacts-smoke.cjs` | 通过：合成 Picker、IndexedDB/刷新、照片 CSP、本机搜索无 API 请求、跨标签页清除/竞态及不支持浏览器降级 |
| scratch `server/`：`GOCACHE="$HOME/.build/go-cache" GOMODCACHE="$HOME/.build/go-mod" GOTMPDIR="$HOME/.build/go-tmp" go test -race ./... && GOCACHE="$HOME/.build/go-cache" GOMODCACHE="$HOME/.build/go-mod" GOTMPDIR="$HOME/.build/go-tmp" go vet ./... && GOCACHE="$HOME/.build/go-cache" GOMODCACHE="$HOME/.build/go-mod" GOTMPDIR="$HOME/.build/go-tmp" go build -trimpath -o "$HOME/.build/hongshu/local-contact-display/hongshu" .` | 通过；未设置 `TEST_MYSQL_DSN`，真实 MySQL 集成仍由 PR CI 承担 |
| scratch Android（JDK 17）：`./gradlew --no-daemon --project-cache-dir "$HOME/.build/hongshu/local-contact-display/project-cache" :app:testDebugUnitTest :app:lintDebug` | 265 项 JVM 测试，254 执行、11 项截图测试按设计跳过、0 失败；lint 通过。APK 与 instrumentation 构建交给 PR CI |

上述副本、测试输出与构建产物位于本机 `$HOME/.build/hongshu/local-contact-display/`；验证前后 scratch 约 46 MiB，`$HOME/.build` 可用空间 124 GiB，未在项目卷生成依赖或构建产物。

本次 PR #82 的 [Hongshu validation run 39442513560](https://github.com/IGNGserver/hongshu/actions/runs/39442513560) 最终全部通过：`verify` 9s、`web` 7s、`server` 2m25s（隔离 MySQL/race/vet/build/Chromium）、`android (28)` 9m35s、`android (33)` 7m10s、`android (35)` 7m23s（含 APK 构建和 instrumentation）。API 28 首次执行的 `NetworkTest.largeHistoryQueueContinuesAndRegistersCapturedNumbersAfterSIMChange` 报 pending 数量期望 1805、实际 1605，日志同时有启动期 adb `device offline` 告警；未改代码、测试或断言，仅重跑该失败 job 后 API 28 全部通过。此记录保留首次结果，不把重跑写成首次通过。另有 [push 快速检查](https://github.com/IGNGserver/hongshu/actions/runs/39442439613) 的 `verify`/`web` 通过。该仓库为 public；本次完整 run、失败尝试、job 重跑和 push 检查合计约 34.6 job 分钟，占私仓月度配额 0 分钟。

# 2026-10-19 同步本机发出的短信（Android，发出短信同步 2/2）

依赖中枢 PR #74（`direction` 列与 `features: ["outgoing_sms"]`）。

自动化：
- `OutgoingSmsTest`（Robolectric，原生 SQLite）：跨端指纹向量与中枢一致；type 2/4/5/6 分类与 24 小时重试窗口；
  对端号码沿用会话写法；旧中枢下发出的短信只计入“等待中枢升级”、支持后上传，确认后账本阻止重复；
  同步回来的发出短信不计未读、不计入新短信通知、不提取验证码；观察者对发出的短信触发扫描；开关默认开启并记住边界。

需真机实测（不能以编译代替）：
1. 升级后不动设置，用系统短信应用给另一号码发一条短信：数秒内中枢/Web 出现在该号码会话中且靠右，未读数不变，其他设备无推送。
2. 对方回复后，收发在同一会话；号码写法不同（+86 前缀、空格）时仍为同一会话。
3. 飞行模式下发送（发送失败/排队），恢复网络重发成功后只上传一次。
4. 关闭“历史补齐”时，升级前发出的短信不上传；开启后补齐并标记“历史补齐”。
5. 连接旧版中枢：“未上传”页显示“当前中枢版本还不支持”，中枢升级后自动上传。
6. 澎湃 OS：确认已发送箱可读（READ_SMS 已授予），发送后观察者触发是否及时。

# 2026-10-18 短信链路补充加固（验证码意图、provider 补读、Push 终态）

基线 `8c6d5d7`（`0.2.0-alpha.7`，含 #65 审计整改），分支 `agent/pipeline-reliability`，版本号保持不变。
本轮只补做 #65 未覆盖的部分，契约与未实施项见 [PIPELINE_RELIABILITY.md](PIPELINE_RELIABILITY.md)。

- 验证码通知持久化与单渠道：SQLite v11 `otp_intents` 与消息页同事务提交，进程中断后仍可补发未过期验证码；
  能展示时不再计入普通汇总（不再一条短信响两次）；有效期取服务端解析值与“短信时间 + 10 分钟”的较小者，
  过期不排队、不展示，展示超时按剩余时间；已读、epoch 重置、身份切换撤销意图并取消已展示通知。
- provider 补读与重建：字段为空的行进入 `incomplete_provider` 轮转重读（水位仍前进，不阻塞后续短信）；
  维护任务比较最高 `_id` 与扫描水位，回退即重置重新扫描（“最高 ID 不变但整库替换”不在覆盖范围，需人工核对）。
- 上传不再阻断接收：上传异常延迟到下载完成后才报告给 worker，本机发送队列故障不再拖住接收其他设备短信。
- 服务端：格式非法消息逐条 `invalid_message` ACK（同批有效短信仍提交，已发布客户端已理解该错误类型）；
  上传策略查询失败返回 503 而非 403 `upload_disabled`；Push 投递前跳过已读消息（`suppressed_read`）、
  429 尊重 Retry-After、`attempts>=50` 丢弃（`dropped`）、404/410 清理该设备全部任务与订阅；
  healthz 新增上述两个计数；迁移 010 增加未读索引；`X-Forwarded-Host` 仅在 TRUSTED_PROXY_CIDRS 内
  参与 Origin 匹配；OkHttp 增加 45 秒 call timeout；诊断页显示通知兜底失败原因（脱敏）。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；12 项运维测试 OK |
| `git diff --check` | 通过 |
| `gofmt -l server/*.go server/cmd/vapid/*.go` | 无输出 |
| scratch `~/.build/短信同步系统/pipeline-reliability/`：`go test -race -count=1 ./...`、`go vet ./...`、`go build -trimpath` | 通过；未提供 `TEST_MYSQL_DSN`，真实 MySQL 集成测试（新增逐条 ACK、Push 已读抑制/Retry-After/终态、404/410 全量清理用例）skip，由 PR CI 的隔离 MySQL 执行 |
| `node --check web/app.js`、`node --test web/test/*.test.js` | 通过；69 项测试 0 失败（本轮未改 Web 代码） |
| scratch Android：`./gradlew --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug` | 通过，2m10s；223 项 JVM/Robolectric 测试 0 失败、11 项截图测试按设计跳过（新增 `OtpIntentTest` 6 项、provider 补读/重建合同 2 项），lint 0 错误，debug APK 生成 |
| PR [#66](https://github.com/IGNGserver/hongshu/pull/66) 的 [Hongshu validation 39107521482](https://github.com/IGNGserver/hongshu/actions/runs/39107521482) | 全绿：`verify` 7s、`web` 5s、`server` 2m43s（隔离 MySQL 8.4.5 执行全部真库集成测试，含新增逐条 ACK、Push 已读抑制/Retry-After/终态、404/410 全量清理）、`android (28)` 8m52s、`android (33)` 8m19s、`android (35)` 8m38s（各含 JVM 单测、lint、APK 与模拟器 instrumentation） |
| 首轮 CI 回归与修复 | API 35 首轮失败 `NetworkTest.largeHistoryQueue...`（expected 5, got 205）：我加的 10 秒上传截止在慢速模拟器上把单轮 20 批截成 ~18 批。已移除该截止（保留 20 批上限与“上传失败不阻断接收”隔离），重跑全绿；该失败未通过放宽断言或跳过测试掩盖 |
| 真机、API 36 胶囊、公网 Push、真实 MySQL 恢复演练 | 未执行：本机无 adb 设备、无 MySQL/Docker；由 PR CI 与后续目标环境验收承担，不以本机结果代替 |

边界：本机无 `TEST_MYSQL_DSN` 时 Go 集成测试跳过，不能记作真库验证通过；意图、补读与 Push 新用例均为合成数据合同，
不等于厂商 ROM 行为或真机验收。真机、API 36 胶囊、公网 Push 与真实恢复演练仍未执行，需在目标环境单独验收。

# 2026-10-17 Android 验证码实时胶囊 / 灵动岛通知

基线 `2a173f0`（`0.2.0-alpha.4`），分支 `agent/live-otp-island`，版本号保持不变。

- 功能：当其他设备（`deviceId != self`）收到验证码短信并通过中枢同步到本机时，以高优先级通知与实时状态（Live Updates / 灵动岛）展示验证码；
- 冲突防护：收到验证码的那台手机自身坚决不触发，避免与厂商系统（如小米/HyperOS、OriginOS、ColorOS）自带的验证码提取弹窗冲突；
- 系统级适配：在 Android 16+（API 36+）设备上支持原生 Live Updates（状态栏胶囊药丸 / Promoted Ongoing），在 Android 15 及以下优雅降级为高优先级普通通知；
- 快捷操作：通知附带“复制”动作（写入剪贴板并立即关闭通知）与“关闭”动作（点击后立即清理通知），并设置 10 分钟超时自毁；
- 设置开关：在“通知设置”页新增“验证码实时胶囊 / 灵动岛”开关，默认开启，用户可随时独立控制。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；12 项运维测试 OK |
| `git diff --check origin/main...HEAD` | 通过；无空白与换行错误 |
| `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug` | 通过；新增 `OtpNotificationManagerTest` 3 项测试（构建 ongoing 及 promoted 特征、复制并取消、关闭且不影响剪贴板、本机与远端设备过滤判定），单元测试与 Lint 0 错误 |
| 真机 / Android 16 实时胶囊 | 模拟器与单元测试通过；Android 16 物理真机药丸动画需在后续真机环境实测验收 |

# 2026-10-16 Android 本地库并发与短信时间（PR-4）

基于 `agent/android-pending-ux`（`c5769ee`），分支 `agent/android-store-robustness`，版本保持 `0.1.0-alpha.21`。本轮只推送任务分支。

修复：多个组件各自打开同一 SQLite 文件、未启用 WAL，并发写入锁冲突会让广播落库失败而丢短信；
运营商短信中心时间偏差数小时时，排序、与收件箱配对和上传都使用错误时间。
新增：WAL、写连接 `busy_timeout=10000`、广播落库遇锁重试 3 次；与接收时间相差超过 10 分钟的网络时间改用接收时间，
原网络时间仅保留为重投识别。未改为单例连接（迁移测试依赖真实关闭/重开），理由见 `REALTIME_SMS.md`。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；11 项运维测试 OK |
| `git diff --check` | 通过 |
| `gitleaks dir --no-banner --redact android/app/src docs` | 通过；no leaks found |
| scratch `~/.build/短信同步系统/android-store-robustness`，JDK 17：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin` | 通过，1m45s；107 项 JVM/Robolectric 测试 0 失败（新增 `StoreRobustnessTest` 3 项：3 个连接并发写 300 条无异常、遇锁重试、时间纠正与重投去重），lint 0 错误 |
| API 28/33/35 模拟器、真机锁冲突与漫游时间 | 本机未执行；模拟器由推送 CI 承担，真机未验证 |

# 2026-10-16 Android 界面：M3 Expressive、动效与设置重组

基线 `3ea8242`（`0.1.0-alpha.21`），分支 `agent/android-m3e-ux`，版本号不变。只改 Android 客户端界面层，
无服务端接口、数据库迁移或同步协议变更；新增的“测试连接”只请求已有的 `GET /healthz`。

- 状态：设置页不再每秒轮询，改为 SharedPreferences 监听加回到前台时刷新；首页冷启动先显示加载而不是空状态；
  启动主题与 SplashScreen 跟随应用背景，深色模式不再白屏一帧。
- 动效：导航改为一个可拖动的过渡，前进/后退为 M3 横向切换，预测性返回手势拖动返回过渡；列表项、横幅、条件设置项都有动画。
- 设置：总览页列出需要处理的问题并直达对应页面；拆为中枢连接、短信采集、SIM 卡与号码、待上传短信、通知、后台运行、诊断信息、关于；
  开关即时生效，需要权限的开关在授权后才打开，被拒绝时保持关闭并说明原因；去掉重复按钮，原始状态文字移到诊断页。
- 首页与对话：搜索栏、下拉刷新（等待同步任务真正完成，超时提示已排队）、同步异常/待上传横幅、按卡槽标注的 SIM 筛选、
  对话日期分隔、“复制验证码”、回到最新；首次使用显示设置引导卡片。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；11 项运维测试通过 |
| `git diff --check origin/main...HEAD`、`git diff --name-only origin/main...HEAD` | 通过；改动只在 `android/` 与本文档 |
| `gitleaks git --redact --log-opts=origin/main..HEAD .` | 通过；未发现凭据泄漏 |
| macOS-dev2 scratch `/Users/lvziw/.build/短信同步系统/android-m3e-ux`（根盘剩余 209G）：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | 通过，2m33s；116 项 JVM/Robolectric 测试，105 通过、11 项截图测试按设计跳过、0 失败；lint 无错误，新增代码无新警告（保留一处 `stopService(Intent)` 的 ImplicitSamInstance 误报，与原实现相同）；debug APK 生成 |
| 同一环境 `HONGSHU_SCREENSHOT_DIR=… :app:testDebugUnitTest --tests io.hongshu.app.UiScreenshotTest` | 通过；Robolectric 原生渲染 11 张截图（设置各页、首页、首次使用、对话，含深色），人工检查后修正了短对话被顶到顶部和长号码被截断两处问题 |
| Go/MySQL、Web/PWA | 本轮无改动，未在本机执行；由 PR CI 承担 |
| 真机、模拟器、API 28/33/35 设备矩阵、预测性返回手势实际手感、厂商 ROM | 未执行：本机无 adb 设备和模拟器；截图来自 Robolectric，不能代替真机验证 |# 2026-10-16 Android 未上传短信展示（PR-3）

基于 `agent/android-upload-timeliness`（`082ddf3`），分支 `agent/android-pending-ux`，版本保持 `0.1.0-alpha.21`。本轮只推送任务分支。

修复：“本机待同步短信”含义不明、“已映射待传”等术语；逐条状态只是按开关推测，看不到真实失败原因；
状态栏直接显示 `中枢返回 503 (database_unavailable)`；本机短信上传前在会话列表中不可见；
通知兜底以联系人名称作发件人立即上传导致会话分裂；同步状态混入队列计数。
新增：页面改名“未上传到中枢的短信”并按原因分组（立即上传/指定接收号码/全部重试），显示上次尝试时间与原因；
中枢错误码中文说明；会话视图合并未上传本机记录并标注“未上传”；名称标题的通知记录暂缓 150 秒等待号码；
首页横幅直达该页面。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；11 项运维测试 OK |
| `git diff --check` | 通过 |
| `gitleaks dir --no-banner --redact android/app/src docs` | 通过；no leaks found |
| scratch `~/.build/短信同步系统/android-pending-ux`，JDK 17：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin` | 通过，2m30s；104 项 JVM/Robolectric 测试 0 失败（新增 `PendingDisplayTest` 4 项），lint 0 错误（新增 2 条既有风格的 UseKtx 警告） |
| 界面实际渲染、真机 | 未执行；本机无设备/模拟器，界面仅经编译与 lint 检查，需在设备上目测 |

# 2026-10-16 Android 上传时效（PR-2）

基于 `agent/android-queue-unstick`（`f358f98`），分支 `agent/android-upload-timeliness`，版本保持 `0.1.0-alpha.21`。本轮只推送任务分支。

修复：退避中的同步任务吞掉新短信与打开应用的触发；WorkManager 退避最长翻倍到 5 小时；联系人刷新失败让成功的上传进入退避；
历史分页用失败重试续跑，拖住同一维护链上的新短信补漏；每批上传前重复登记 SIM；本机上传开关 PATCH 失败或同中枢重新登录后，
中枢 `upload=false` 被误当成管理员禁用且永不自愈，重新登录还关闭本机采集。
新增：紧急触发（新短信、补漏观察者、通知兜底、打开应用、中枢推送、用户操作）以加急任务上传并替换排队中的退避任务，
不取消运行中的同步；第 5 次失败起固定 15 分钟重试；联系人失败仅标记；分页成功后 10 秒追加下一页；
SIM 登记按设备 ID 缓存，`sim_not_confirmed` 时对缓存号码重登一次；上传开关“待确认”标志与同步时自动重发 PATCH。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；11 项运维测试 OK |
| `git diff --check` | 通过 |
| `gitleaks dir --no-banner --redact android/app/src docs` | 通过；no leaks found |
| scratch `~/.build/短信同步系统/android-upload-timeliness`，JDK 17：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin` | 通过，1m32s；100 项 JVM/Robolectric 测试 0 失败（新增 `SyncReconcileTest` 2 项、调度 2 项），lint 0 错误 |
| `NetworkTest` 联系人故障与 SIM 登记次数断言 | 已按新语义更新（同步成功、联系人标记不健康；第二轮不重复 PUT），由 CI 模拟器执行 |
| API 28/33/35 模拟器、真机、Android 12 以下加急前台通知 | 本机未执行；模拟器由推送 CI 承担，真机未验证 |

# 2026-10-16 Android 待同步队列解堵（PR-1）

基线 `3ea8242`，分支 `agent/android-queue-unstick`，版本保持 `0.1.0-alpha.21`。本轮只推送任务分支，不建 PR、不发版。

修复：未知订阅的广播与已知订阅的 provider 行不配对，留下永远“待确认号码”的副本；发件人 `+86` 等格式差异导致重复上传；
自动识别取不到号码时删除映射且无法手填；手动模式要求两张卡都填；映射号码无效被误标异常并在读游标中写库；
异常/待确认记录没有任何出口并阻止更换中枢。新增：广播读取框架 `EXTRA_SUBSCRIPTION_INDEX`/`EXTRA_SLOT_INDEX`；
维护任务与保存 SIM 时合并遗留副本；待同步页可为待确认记录指定已确认号码、重试异常、二次确认后放弃上传。
未按卡槽猜测订阅，实时广播与上传路径仍不调用 SIM 服务。无数据库版本变更。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；11 项运维测试 OK |
| `git diff --check` | 通过 |
| `gitleaks dir --no-banner --redact android/app/src docs` | 通过；no leaks found |
| scratch `~/.build/短信同步系统/android-queue-unstick`，JDK 17：`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin` | 通过，5m49s；JVM/Robolectric 96 项测试 0 失败（新增 `QueueRepairTest` 9 项、`ProtocolTest` 4 项），lint 无错误 |
| API 28/33/35 模拟器 instrumentation、真机 | 本机未执行；由推送触发的 CI 承担，未在真实双卡/澎湃 OS 设备验证 |

# 2026-10-16 Android 中枢密码使用密码键盘

版本 `0.1.0-alpha.21`，基线 `5958f84`，分支 `agent/password-keyboard`。Android 设置页的中枢密码输入框增加 `KeyboardType.Password`，不改变密码提交内容或已有凭据行为；本轮无服务端接口和数据库迁移。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；仓库结构、SemVer、凭据样式检查及 11 项运维测试全部通过 |
| `git diff --check` | 通过 |
| `cd /Users/lvziw/.build/短信同步系统/password-keyboard/source/android && ./gradlew --no-daemon --max-workers=2 --console=plain --offline --project-cache-dir /Users/lvziw/.build/短信同步系统/password-keyboard/gradle-project-cache -Pkotlin.project.persistent.dir=/Users/lvziw/.build/短信同步系统/password-keyboard/kotlin-cache :app:testDebugUnitTest` | 通过；Android JVM 单测通过，JDK 17；源码副本、Gradle 项目缓存和可变构建目录均在本机盘 scratch |
| 同一 scratch 环境 `:app:lintDebug` | 通过；保留既有 Kotlin 警告，无新增 lint 错误 |
| Android 完整构建、签名 APK、API 28/33/35 模拟器、Go/MySQL、Web/PWA 与浏览器冒烟 | 本机未重复执行；由 PR CI 和随后 tag Release workflow 承担 |

首次使用独立 Gradle 用户目录运行时因缺少 Kotlin 插件缓存而无法解析依赖；改用本机共享缓存 `/Users/lvziw/.build/gradle` 后，单测和 lint 分别完整重跑通过。未在 worktree 生成依赖或构建产物。本轮未连接真实 Android 设备，未验证不同输入法的具体键盘布局。

# 2026-10-15 短信同步链路加固

基线 `2c0ca5f`，分支 `agent/sync-pipeline-hardening`，版本保持 `0.1.0-alpha.19`。
本轮仅提交并推送任务分支，不创建 PR、不合并、不发布、不部署。

已确认并修复：整批非法记录阻塞有效邻居、ACK 未完整校验即修改队列、历史积压抢占实时配额、
成功未排空仍进入失败退避、重复 SIM 注册、联系人故障提前阻断短信同步、通知仅凭正文误合并，
以及本地总数/待确认/异常统计重叠。通知关联回归还发现 SQLite 文本参数与整数零比较的问题，
已显式转换未知订阅参数，保留已知号码与订阅快照。ACK 指纹为向后兼容的新增字段，无数据库迁移。
服务端/Web 审计未确认新的 P0 丢短信或倒序缺陷，不将未复现问题写成已修复缺陷。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；仓库合同与 11 项运维测试 |
| `git diff --check`、`test -z "$(gofmt -l server/*.go server/cmd/vapid/*.go)"` | 通过 |
| `gitleaks dir --no-banner --redact .` | 通过；未发现凭据泄漏 |
| `cd server && go test -race -count=1 ./... && go vet ./...` | 本机 scratch 通过；未提供 `TEST_MYSQL_DSN`，真实 MySQL 集成测试跳过，不属于真库验证通过 |
| `cd web && npm test && npm run check` | 本机 scratch 通过；16 项测试通过；Web 本轮无代码改动 |
| Android `./gradlew --no-daemon --offline --console=plain --project-cache-dir "$SCRATCH/gradle-project-cache" -Pkotlin.project.persistent.dir="$SCRATCH/kotlin-cache" :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` | 本机 scratch 通过，耗时 1m39s；83 项 JVM/Robolectric 测试全部通过，0 失败/跳过；设备测试源码编译通过，未执行设备测试 |
| `adb devices` | 无设备；未执行真机或模拟器 instrumentation 测试 |
| Go 可执行文件、Web 生产资源、Android lint/APK 与 API 28/33/35 设备矩阵 | 本机未执行；代码提交 `02a39b4` 的 CI 全部通过，详见下方最终结果 |

代码提交 `02a39b4` 的 [Hongshu validation 38751787508](https://github.com/IGNGserver/hongshu/actions/runs/38751787508)
全部成功，墙钟耗时 8m50s：`server` 1m50s，`web` 5s，`verify` 9s，
`android (28)` 6m49s、`android (33)` 8m46s、`android (35)` 7m30s。
`server` 设置隔离 `TEST_MYSQL_DSN` 并执行 `go test -race -count=1 ./... && go vet ./... && go build -trimpath`，
真实 MySQL 集成验证通过；Chromium 冒烟覆盖登录、收件箱、XSS、手机对话、搜索、设备、设置及 PWA 私密缓存边界。
Android 三组均执行 JVM 单测、lint、debug/测试 APK 构建与 `:app:connectedDebugAndroidTest`；
API 28 完成 22 项设备测试，API 33、35 各完成 48 项，全部成功。这是 CI 模拟器验证，不是真机验收。
作业累计 25m09s，按各作业向上取整估算 28 个 Linux runner 分钟；实际配额扣减以 GitHub 账单为准。

本地验证副本为 `~/.build/短信同步系统/sync-pipeline-hardening/source/`；JDK 17、
`GRADLE_USER_HOME=~/.build/gradle`、`HONGSHU_BUILD_DIR=$SCRATCH/android-build`，
`SCRATCH=~/.build/短信同步系统/sync-pipeline-hardening`。源码工作区未存放依赖或构建产物。
Gradle 首次离线依赖不足、在线超时均不记为通过；依赖补齐后首次实际执行为 83 项中 2 项通知去重失败，
修复 SQLite 参数类型后完整重跑为 83/83 通过，没有降低断言或禁用测试。

覆盖新增 ACK 乱序/重复指纹/畸形 ID 与字段/整组防误删、非法批次二分隔离、失败分类、禁用上传、
实时/历史公平筛选、互斥队列统计、通知 sender/SIM/唯一候选匹配及未知 SIM 快照保留。
新增网络设备测试包含大历史队列续传、SIM 注册去重、联系人故障保留缓存且继续上传/下拉及 401；
本地仅编译通过；上述 CI API 33/35 模拟器已执行这些设备场景并通过。

仍需验证：真实设备上的 WorkManager 后继执行与故障退避、目标 ROM 通知字段、
真实双卡/PDU/重启/离线恢复、Push 可达性和生产环境端到端展示。CI MySQL/浏览器/模拟器结果
不能替代以上实测；未知联系人标题或歧义通知优先保留重复，旧中枢无指纹 ACK 仍无法检测合法字段重排，
系统强制停止/厂商屏蔽/首次解锁前的限制不变，不承诺绝对即时或不漏收。

# 2026-10-14 设置页点击后整页变灰卡死修复

基线 `d0af63c`，分支 `agent/fix-settings-busy-freeze`，版本 `0.1.0-alpha.19`（用户 2026-10-14 明确要求合并并发布）。
原因：全屏设置页不显示主页 SnackbarHost，`HongshuUI.action()` 在 `finally` 解除 busy 之前 await 了主页
`showSnackbar`，该调用永不返回，busy 永久为 true。修复见 `UiAction.kt`：busy 先于提示解除，设置页不再向主页排队提示。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh`、`git diff --check` | 通过 |
| Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 与 API 28/33/35 模拟器测试 | 本机 SDK 未下载完成，未在本地执行；以 PR 上 CI 结果为准（run 链接见 PR） |
| 真机确认 | 未执行；需在 HyperOS 手机上点击同步控制页各按钮确认不再卡灰 |

新增 JVM 测试 `UiActionTest`：无 SnackbarHost 时仍解除 busy、设置页不向主页排队提示、失败信息仍提示、取消后解除 busy。

# 2026-10-14 澎湃 OS 短信接收：厂商授权诊断、收件箱即时补漏与短信通知兜底

基线 `0af2312`，分支 `agent/hyperos-sms-delivery`，版本 `0.1.0-alpha.18`（用户 2026-10-14 明确要求合并并发布）。
原因分析与真机矩阵见 [HYPEROS_SMS.md](HYPEROS_SMS.md)。用户真机现象：系统广播到达 0 次；本机无 adb 设备。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；结构/SemVer/凭据样式检查及 11 项运维测试 |
| `git diff --check`、`gitleaks dir --no-banner --redact` | 通过；无凭据泄漏 |
| `go test -race ./...`、`go vet ./...`、`go build -trimpath`（工作区外 `~/.build`） | 通过；真实 MySQL 集成测试无 DSN 时 skip，由 CI 隔离 MySQL 执行 |
| `cd web && npm test && npm run check && npm run build`（工作区外） | 16 项测试、语法检查、生产资源构建通过 |
| Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 与 API 28/33/35 模拟器设备测试 | 本机（macOS-dev2）无 Android SDK，下载速率约 100 KB/s，未在本地执行；以 PR 上的 `Hongshu validation` CI 结果为准（run 链接与结论见 PR） |
| 真机 HyperOS 验收 | 未执行；小米私有 AppOps 读取、安全中心跳转与短信通知字段需按 HYPEROS_SMS.md 矩阵在目标手机确认 |

新增 JVM 测试：`XiaomiAccessTest`（品牌识别、AppOps 模式映射、状态文案、跳转回退）与 `NotificationCaptureTest`
（短信应用过滤、同意时间边界、MessagingStyle 自身回复、汇总通知、通知→广播/补漏升级、重复通知去重、开关与代次）。
合成数据只用于测试，没有真实短信、号码或验证码进入日志。

# 2026-10-13 Android 导航、状态栏与预测式返回

基线 `fe11daf`，分支 `agent/frontend-navigation-safe-area`；版本保持 `0.1.0-alpha.15`，本轮未创建 PR、未合并、未发布或安装到手机。

| 实际命令/检查 | 结果 |
| --- | --- |
| `git diff --check` | 通过 |
| `cd android && BUILD_SCRATCH_ROOT=/Users/lvziw/.build HONGSHU_BUILD_DIR=/Users/lvziw/.build/hongshu/frontend-navigation-safe-area GRADLE_USER_HOME=/Users/lvziw/.cache/hongshu-gradle-macos ./gradlew --no-daemon --offline --max-workers=2 --project-cache-dir /Users/lvziw/.build/hongshu/frontend-navigation-safe-area/gradle-project-cache :app:testDebugUnitTest :app:lintDebug` | 通过；35 项 JVM 单测（含 3 项导航栈测试）、lint 通过；保留既有 lint warnings，无新增错误 |
| 同上环境执行 `./gradlew --no-daemon --offline --max-workers=2 --project-cache-dir /Users/lvziw/.build/hongshu/frontend-navigation-safe-area/gradle-project-cache :app:assembleDebug` | 通过；debug APK 构建成功，产物位于工作区外本机盘缓存 |
| `bash scripts/verify-repository.sh` | 未通过：仓库结构/版本检查通过，但既有 `scripts/test-operations.py` 有 2 项失败、1 项错误（发布草稿恢复 fixture 在当前 Python 3.14/macOS 环境下缺少预期 journal 文件）；本轮未修改发布脚本 |
| `adb devices -l` | 无连接设备，未执行真机/模拟器返回手势、状态栏截图和 instrumentation 验收 |

本轮新增统一 Android 目的地栈、设置全屏 edge-to-edge 页面、左右边缘方向一致的 `PredictiveBackHandler` 动画，以及导航栈单元测试。Gradle 项目缓存和 Android 产物均未留在 worktree；最终构建 scratch 为 `/Users/lvziw/.build/hongshu/frontend-navigation-safe-area`（约 165M），共享 Gradle 缓存为 `/Users/lvziw/.cache/hongshu-gradle-macos`（约 1.5G），本机盘可用空间约 178 GiB。之前 ASCII 路径构建产生的 `/Users/lvziw/.cache/hongshu-build/frontend-navigation-safe-area`（约 164M）作为共享缓存保留。

# 首版开发验证记录

## 2026-10-13 后台验证码采集排查与恢复缺口修复

基线 `fe11daf`，分支 `agent/sms-background-ingestion`，版本保持 `0.1.0-alpha.15`。
详细因果分析、系统假设及真机最小复现矩阵见 [SMS_INGESTION_DIAGNOSIS.md](SMS_INGESTION_DIAGNOSIS.md)。
本轮没有 PR、合并、发布、部署或向手机安装；没有真实短信/验证码记录进入日志。

| 实际命令/检查 | 结果 |
| --- | --- |
| `PATH=<共享 GNU 工具>:$PATH TMPDIR=<工作区外目录> bash scripts/verify-repository.sh` | 通过；版本/结构/凭据样式检查及 7 项运维测试 |
| `git diff --check`、`gitleaks dir --no-banner --redact .` | 通过；无凭据泄漏 |
| `go test -race -count=1 -v ./...`、`go vet ./...`、`go build -trimpath -o "$HONGSHU_BUILD_DIR/hongshu" .`、Go `gofmt` 检查 | 通过；13 项通过，11 项真实 MySQL/逻辑备份测试因缺少隔离 DSN/mysql 客户端而 skip，不记作已验证 |
| `cd web && npm test && npm run check && npm run build` | 16 项测试、语法检查、工作区外生产资源构建通过 |
| `./gradlew --no-daemon --offline --max-workers=2 --project-cache-dir <外部目录> -Pkotlin.project.persistent.dir=<外部目录> :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | JDK 21/SDK 35/Gradle 8.11.1，全部通过；50 项 JVM 测试，无失败/skip；lint 0 errors、64 warnings；debug APK 和设备测试 APK 构建成功，最终日志 android-final.log |
| `ktfmt --kotlinlang-style --dry-run --set-exit-if-changed`（本轮 Kotlin 文件）、`apksigner verify --verbose .../app-debug.apk` | 格式检查通过；debug APK v2 签名有效，未安装 |
| `adb devices -l` | 无设备；未执行 instrumentation/用户手机/真实验证码、双卡或长时后台验收 |

日志在 `/Users/lvziw/tmp/opencode/hongshu-sms-background/`；Gradle 依赖复用本机共享缓存
`/Users/lvziw/.cache/hongshu-gradle-macos/`。Android、Go 可变缓存位于
`/Users/lvziw/.cache/hongshu-build/sms-background-ingestion/`；Web 与 Go 二进制位于
`/Users/lvziw/项目/.build-cache/hongshu/sms-background-ingestion/`，不在 worktree。

失败/中断记录：首轮 Android 组合任务在测试通过后因 600 秒超时中断，不能记作整轮构建通过；
后续重跑完整成功。首轮 Go 使用新建 NFS 可变缓存耗时超限，没有测试结果；改用本机共享缓存后
完整测试、vet、build 通过。未改动断言、禁用 lint 或跳过失败检查。

新增 18 项 JVM 回归：11 项共用设备/宿主的权限、收件箱补漏、分页、独立水位、ACK 去重、
代次取消、关闭期间排除、存储失败、广播入口和等待持久入队合同；4 项同步调度策略；3 项
权限/AppOps 分离诊断。共享合同用 API 35 Robolectric native SQLite 执行，设备版本只完成构建。

边界：合成系统 provider/PDU、原生 SQLite 和 WorkManager 策略合同不证明用户手机广播投递。
新短信补漏默认关闭、需明确开启与 READ_SMS 授权，只有系统可访问收件箱才能恢复；周期调度
可能延迟，不能声称验证码全部/即时可读，也不能把通知 OTP 脱敏等同于 SMS 广播受限。

## 2026-10-13 Android 最近任务隐藏与后台实时连接

基线 `869ec7f`，分支 `agent/android-background-keepalive`；版本保持 `0.1.0-alpha.14`，未发布、未部署。
本轮日志、工具与构建产物位于 `/Users/lvziw/tmp/opencode/hongshu-background-validation/`，不落 worktree。
新增 5 项 JVM 测试（空 Intent/主动停止优先级/开机 opt-in 全组合/重连退避/规范化 WS 身份匹配）和 5 项 Android
instrumentation 测试（当前任务隐藏可逆与重建、类型/Intent/stopWithTask、真实本地 WS 与通知、
退出/移除 UI 任务不停止连接、断连恢复与持久停止、缺少凭据/401 停止）。
CI Android 验证扩展为 API 28、33、35，覆盖旧任务 ID 字段、旧 dataSync 路径与 remoteMessaging 路径。

| 实际命令/检查 | 本轮结果 |
| --- | --- |
| `PATH=<临时 GNU 工具>:$PATH TMPDIR=<外部目录> bash scripts/verify-repository.sh` | 通过；结构、版本、凭据样式扫描及 7 项运维测试 |
| `git diff --check`、`actionlint .github/workflows/ci.yml`、Manifest XML 契约检查 | 通过 |
| `ktfmt --kotlinlang-style --dry-run --set-exit-if-changed`（9 个新增/已格式化 Kotlin 文件） | 通过；MainActivity 原有整体格式未重写 |
| `go test -race ./...`、`go test -race -count=1 -v ./...` | Go 1.24.4，13 项通过；11 项真实 MySQL/备份测试因缺少隔离 DSN/mysql 工具跳过，不能视为通过 |
| `go vet ./...`、`go build -trimpath -o <外部目录>/hongshu .`、Go `gofmt` 检查 | 通过；缓存和二进制在工作区外 |
| `cd web && npm test && npm run check && WEB_BUILD_DIR=<外部目录> npm run build` | 16 项测试、语法检查、生产构建通过 |
| Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | 全部通过；17 项 JVM 单测（新增 5 项）、lint、debug APK 和测试 APK。JDK 17/SDK 35/Gradle 8.11.1；联网补齐缓存后离线完整重跑成功，日志 android-build-reviewed.log |
| Android instrumentation（CI） | `e609edc` 的 CI [38384812005](https://github.com/IGNGserver/hongshu/actions/runs/38384812005) 全部通过：API 28 为 21/21，API 33、35 均为 22/22，含本轮 5 项后台测试 |
| 厂商真机验收 | 本机 `adb devices -l` 无设备，未执行；模拟器结果不等于厂商任务锁/隔夜运行通过 |

推送 `c2c10ae` 后 CI [38382374494](https://github.com/IGNGserver/hongshu/actions/runs/38382374494)：
verify、Web、server（含隔离 MySQL/备份测试与浏览器 smoke）与 API 33/35 设备测试通过。
API 28 的 21 项设备测试中 13 项失败，均为既有 Store 的 AutoCloseable 类型转换崩溃；
SQLiteOpenHelper 在 API 29 前未实现该接口，生产 SIM 扫描/历史导入的 use 路径也受影响。
Store 现显式实现 Closeable，沿用父类 close，不改 schema/数据、不跳过测试；修复后本地再次完整
运行 Android 单测/lint/debug APK/测试 APK，全部通过（android-build-closeable.log）。
修复提交 `e609edc` 的 CI 38384812005 已全绿，API 28 的全部 21 项通过，API 33/35 全部 22 项通过；
verify、Web、server（隔离 MySQL、备份/恢复、浏览器 smoke）亦通过。

首轮失败记录：离线 Android 构建因依赖元数据不完整失败，尚未进入代码编译，改为联网获取。
联网首轮生产代码编译、JVM 测试与 debug APK 打包通过，但 lint 发现 taskId 字段需要 API 29，
测试编译发现 PackageInfo.services 可空；已增加旧系统 id 路径并显式处理可空数组，随后完整重跑。
提交前复查修正了 OkHttp 将 ws/wss 规范化为 http/https 后的身份比较，防止漏处理 401 或清除新身份；
新增测试覆盖默认端口、非默认端口、更换中枢/凭据与无效地址，最终全套 Android 检查再次通过。
macOS 自带 Bash 3.2 与 BSD find 不兼容原有 Linux 运维脚本；仅在外部临时目录构建 GNU Bash 5.3/
findutils 4.10.0 后完整重跑，7 项通过，未修改测试断言或系统默认工具。

边界：没有真实厂商最近任务锁/一键清理、开机恢复、普通进程回收、隔夜 Doze、超过六小时的真机
运行和耗电验收；没有生产中枢与真实短信测试。隐藏卡片不提升进程优先级，不保证厂商保留任务锁。
不使用无声音乐、一像素、无障碍、定时器强拉起或持续唤醒锁；UI 轮询在退后台后暂停。

## 2026-10-13 实时短信独立采集与本机待同步可见性

版本保持 `0.1.0-alpha.14`，基线 `9f976aa`。方案及澎湃实测检查表见 [REALTIME_SMS.md](REALTIME_SMS.md)。
本次不创建 PR、不合并、不发布、不安装到手机或部署中枢。

| 实际命令/任务 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过：版本/结构/凭据样式检查及 7 项隔离运维测试；PATH 使用工作区外 Bash 5.2.37、GNU find 4.10.0 |
| `git diff --check`、`gitleaks dir --no-banner --redact .` | 通过；未发现凭据泄漏 |
| `go test -race ./...`、`go test -race -v ./...`、`go vet ./...`、`go build -trimpath -o "$HONGSHU_BUILD_DIR/hongshu" .` | Go 1.24.4/macOS，通过；13 项测试通过，11 项真实 MySQL/逻辑恢复测试因缺少隔离 MySQL 与客户端而 skip，不能记作已验证 |
| `npm test`、`npm run check`、`npm run build` | Web 16 项通过，语法与工作区外生产资源构建通过 |
| `./gradlew --no-daemon --max-workers=2 --project-cache-dir ... -Pkotlin.project.persistent.dir=... :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | JDK 17/SDK 35/Gradle 8.11.1，通过；27 项 JVM 测试（含 14 项 API 35 Robolectric native SQLite 合同），无失败/skip；lint 0 errors、61 warnings；debug APK 和测试 APK 构建通过 |
| `ktfmt --kotlinlang-style --dry-run --set-exit-if-changed ...`、`apksigner verify --verbose .../app-debug.apk` | 本任务 Kotlin 文件格式检查通过；debug APK v2 签名有效，仅供测试，未安装 |
| `adb devices -l` | 无连接设备，未运行设备 instrumentation、澎湃/双卡真机及系统重启验收 |

Go/Web 产物和日志在 `/Users/lvziw/项目/.build-cache/hongshu/realtime-sms-reliability/`；
Gradle 使用本机共享缓存 `/Users/lvziw/.cache/hongshu-gradle-macos/`，Android 产物/报告在
`/Users/lvziw/.cache/hongshu-build/realtime-sms-reliability/`。没有把缓存、测试短信或凭据放入工作区。

失败不计为通过：系统 Bash 3.2 的 `${RELEASE_IMAGE,,}` 不兼容导致初始运维测试失败；
使用独立缓存中的 GNU 工具重跑通过，未修改发布脚本。首次 Android 构建因 NFS 可变缓存写入过慢中止，
随后改用本机共享缓存。宿主首轮把未加 runner 的合同基类也当作测试执行，且 Robolectric 不按
`ContentResolver.wrap` 的 transport 路由；合同基类改为 abstract，宿主按 authority 注册同一合成 provider，
设备仍使用真实 wrap。并发断言、provider 必须实际被调用的断言和 SQLite 原生事务检查均保留。

本轮合同实际覆盖：真实合成多段 UCS2 PDU、只授 RECEIVE_SMS、只授 READ_SMS/全部拒绝、关闭历史不查询
provider、历史 provider 异常/阻塞仍能实时保存、未知订阅保留、已确认号码快照不随换号变化、ACK 之后的
广播/provider 去重、关闭/重新开启与身份代次取消、v5→v6 无损保留、分页/异常可见、事务失败完整回滚。
新增短路径断言：实时落库及上传筛选不得调用 SIM 服务或读取其权限；旧历史队列的单卡兜底移到独立维护任务。
lint 的同步 SharedPreferences commit 等警告保留，未增加 baseline、禁用检查或降低断言。

未验证边界：READ_SMS 被拒绝/历史 provider 异常的宿主合同不等于用户手机的 ROM 复现。
SQLite reopen 不是物理手机断电/重启，合成 PDU 不是系统成功投递证明；不能承诺 OEM 屏蔽、强制停止或
首次解锁前收到 SMS。真实 MySQL、浏览器 Push、生产部署和厂商双卡验收仍需对应环境。

## 2026-10-12 Android 设置精简与全量历史自动同步

版本保持 `0.1.0-alpha.13`，未发布、未部署。基线 `f3b67d2`；本次日志、构建产物与隔离模拟器在
`/home/lvziw/tmp/opencode/hongshu-android-sync-validation/`，不在 worktree 产生构建缓存。
Android 使用 JDK 17、SDK/API 35、Gradle 8.11.1；可变依赖缓存复用 `/dev/shm/hongshu-gradle`。

| 实际命令/任务 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh`、`git diff --check` | 通过；仓库结构、版本、凭据样式扫描及 7 项运维测试 |
| `TEST_MYSQL_DSN=... MYSQL_BIN=... MYSQLDUMP_BIN=... go test -race -count=1 -v ./...` | Go 1.26.4、独立本地 MySQL 8.4.11；24 项通过，无 skip，含隔离逻辑备份/重复恢复 |
| `go vet ./...`、`go build -trimpath -o "$HONGSHU_BUILD_DIR/hongshu" .`、Go `gofmt` 检查 | 通过；构建目录和 Go 缓存位于工作区外 |
| `npm test`、`npm run check`、`WEB_BUILD_DIR=... npm run build` | Web 16 项通过，语法检查与生产资源构建通过 |
| `./gradlew --no-daemon --offline --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`（另指定工作区外 project/Kotlin 缓存） | 全部通过；12 项 JVM 单测、lint、debug APK 与测试 APK |
| `adb -s emulator-5582 install -r ...`、`adb -s emulator-5582 shell am instrument -w io.hongshu.app.test/androidx.test.runner.AndroidJUnitRunner` | 本次独立 API 35 模拟器，17 项全部通过 |
| `python3 /home/lvziw/tmp/opencode/hongshu-android-sync-validation/ui-smoke.py` | 实际 Android 界面操作通过：手动模式两输入框、自动模式无输入框、六项同步元素、首次连接填写名称并保存、已连接留空密码改名保存且不新增身份；中枢为本地合成 HTTP fixture |

新增覆盖：手动输入按真实卡槽绑定订阅、拒绝歧义卡槽；首次补扫纳入连接前的 1205 条历史、
1000 条分页后重启续扫、增量新短信、关闭后停止采集；v4→v5 保留队列/捕获账本/映射并补扫旧历史；
自动号码变化不重写已捕获接收号码，不将旧卡未知来源归到新卡；广播/provider 在号码变化后仍去重。
实际 HTTP 与 SQLite 测试验证：2005 条队列超过 20 上传批次后仍自动继续，离线期间号码变化仍注册并上传旧号码快照；
改名复用原设备身份、错误密码不销毁原凭据；短信采集权限异常不被网络成功掩盖，100 次同步触发仍合并且保留退避。

失败记录不算通过：首次组合构建因工具的 120 秒超时中断；增加超时后完成。测试注解曾触发
`UseSdkSuppress` lint，已按规则只保留 `SdkSuppress`，未创建 baseline 或禁用检查。
首次 instrumentation 为 16/17 通过：模拟器没有默认网络，网络约束任务未启动。
通过 `cmd wifi connect-network AndroidWifi open` 建立真实模拟器连接，确认 `VALIDATED` 后完整重跑为 17/17，未放宽断言。
首轮密集按键 UI 自动化出现渲染等待 ANR，主线程栈在 `HardwareRenderer.syncAndDrawFrame`。
保留 trace；将本次隔离模拟器降为 720×1600/280dpi，并改用 Ctrl+A 替换文本后完整 UI 链路通过，
没有修改应用的渲染配置或关闭检查。这不是已完成真机输入性能验收的声明。

边界：历史来源是系统可读取收件箱，不包括已删除短信、其他应用已消失的通知、MMS/RCS。
没有真实双卡/运营商换号硬件验收；系统不给本机号码时不会猜测。旧卡无法安全匹配的历史会保留待传，
不会为了声称“全部入库”错填接收号码。前台服务系统时限、省电限制与公网通知端到端仍不能由模拟器代替验证。

## 2026-10-12 icon2 项目图标发布准备

| 检查 | 真实结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过，版本为 `0.1.0-alpha.12` |
| `git diff --check` | 通过 |
| 图标资源校验：PNG 尺寸/格式、Android 启动图标与 Web 512px 主图标 SHA256 | 通过；Web/Android 主图标一致，192px 资源由主图标派生 |
| `python3 scripts/generate-icons.py` 派生资源检查 | 在隔离临时目录执行通过 |
| `cd web && npm test && npm run check && WEB_BUILD_DIR=... npm run build` | 通过；16 个 Web 测试通过，语法检查和生产资源构建通过 |
| `cd android && ./gradlew --no-daemon --offline --max-workers=2 :app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`（分别执行） | 三个任务均通过；Android 单元测试、lint 和 debug APK 构建通过。使用 Android SDK 35、Gradle 8.11.1，构建缓存和产物位于工作区外共享目录 |

本机未安装 Go 工具链，也没有可用的 TEST_MYSQL_DSN/MySQL 实例，因此 `go test -race ./...`、`go vet ./...`、Go build 和浏览器 smoke 未在本机执行；发布工作流会在隔离 MySQL/CI 环境中执行这些检查。没有 Docker daemon，未在本机构建容器镜像。


原始基线验证日期：2026-10-09；2026-10-10 增补了统一密码与 HTTP 支持的本地验证结果。仅记录实际运行的检查；不等同于生产上线、正式版发布或真机兼容性认证。
原始基线记录在 PR 集成和预发布前完成；版本源为 `0.1.0-alpha.1`，不代表当时已有 Release。

## 2026-10-10 统一密码与 HTTP 支持变更

| 检查 | 真实结果 |
| --- | --- |
| `bash scripts/verify-repository.sh`、`git diff --check`、Go `gofmt` 检查 | 全部通过 |
| `TEST_MYSQL_DSN=... MYSQL_BIN=... MYSQLDUMP_BIN=... go test -race -count=1 -v ./...` | Go 1.26.4 / 临时隔离 MySQL 8.4.11；13 个测试全部通过，含 5 个真实 DB 集成/恢复测试，无 skip。测试只创建并删除随机 `hongshu_it_*` 数据库 |
| `go vet ./...`、`go build -trimpath -o "$HONGSHU_BUILD_DIR/hongshu" .`（HONGSHU_BUILD_DIR 指向共享构建缓存） | 通过 |
| `npm test`、`npm run check`、`WEB_BUILD_DIR=... npm run build` | 7 个 Web 测试通过，语法检查和构建通过 |
| Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | Gradle 8.11.1 构建成功；8 个单测通过、lint 通过、debug APK 成功。版本 `0.1.0-alpha.4` / versionCode 3 已由 Gradle 配置；可变缓存使用 `/dev/shm`，产物在共享构建目录 |
| `docker-compose -f compose.yml config --quiet` 与含引号/反斜杠的合成 `.env` 密码解析 | 通过；仅验证 Compose 配置解析，未启动容器 |
| `PLAYWRIGHT_MODULE=... HONGSHU_BINARY=... MYSQL_BIN=... node scripts/browser-smoke.cjs` | 真实 Chromium + Go + 临时隔离 MySQL 通过：密码登录、WS、收件箱、XSS 安全、手机对话/返回、搜索、设备、设置和 PWA 缓存边界 |

本机没有 Docker daemon；Compose 只做配置解析，未启动容器/构建 OCI 镜像。浏览器 smoke 通过 localhost，不代表公网 HTTP 的外网连通性或传输安全已验收；没有 Android 真机。HTTP 明文风险与公网 HTTP 下 Web Push/Service Worker 不可用已记录在部署和安全文档。

## 基线版本验证（2026-10-09）

| 检查 | 真实环境 / 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过：结构、SemVer、凭据样式扫描 |
| `git diff --check` | 通过 |
| `TEST_MYSQL_DSN=... MYSQL_BIN=... MYSQLDUMP_BIN=... go test -race -count=1 -v ./...` | Go 1.26.4 / MySQL 8.0.36，11 个测试全部通过，含 5 个真实 DB 集成测试；无 skip |
| `go vet ./...` | 通过 |
| `go build -trimpath -o "$HONGSHU_BUILD_DIR/hongshu" .` 及 `./cmd/vapid`、`./cmd/healthcheck` 构建 | 三个二进制全部构建成功 |
| `test -z "$(gofmt -l *.go cmd/*/*.go)"` | 全部 Go 文件格式检查通过 |
| `npm test` | Node 22.22.1，6 个测试全部通过 |
| `npm run check` | app.js / sw.js 语法检查通过 |
| `WEB_BUILD_DIR=... npm run build` | PWA 生产资源校验和构建通过，无第三方运行时依赖 |
| `docker compose -f compose.yml config --quiet` | 使用 Compose 2.39.4 通过配置解析；这不是容器运行测试 |
| `bash -n scripts/{init-deployment,backup,verify-repository}.sh` | 通过 |
| `actionlint .github/workflows/*.yml` | CI 与预发布 workflow 检查通过 |
| Gradle wrapper integrity | wrapper JAR SHA256 与 Gradle 官方 8.11.1 发布值一致；distribution 配置 SHA256 校验 |
| `ktfmt --kotlinlang-style --dry-run --set-exit-if-changed ...` | Kotlin 生产代码、JVM 测试、androidTest 格式检查通过 |
| `./gradlew --no-daemon --offline --max-workers=2 --project-cache-dir ... :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | JDK 17 / SDK 35 / Gradle 8.11.1，5 个 JVM 测试通过；lint 无错误；debug APK 与 androidTest APK 构建成功 |
| `apksigner verify --verbose ...` / `aapt2 dump badging ...` / `aapt2 dump xmltree ... --file res/xml/data_extraction_rules.xml` | debug APK 签名有效；应用 ID、版本、min 26/target 35 正确；云备份和设备迁移排除规则确实已打包 |
| `PLAYWRIGHT_MODULE=... HONGSHU_BINARY=... MYSQL_BIN=... node scripts/browser-smoke.cjs` | 真实 Chromium + Go + 隔离 MySQL 通过：初始化、WS 连接及自动更新、收件箱、XSS 安全、手机对话/返回、搜索、设备导航、设置、PWA 不缓存 API |

MySQL 测试覆盖：首次初始化、统一密码登录、设备授权/撤销、最后管理员保护、SIM 确认、
幂等重试、批次原子回滚、12 路并发提交与顺序游标、分页/筛选/搜索、持久 Push 任务、
来源设备排除、历史导入不生成 Push、撤销后保留历史、WS 撤销/跨域拦截、dirty/checksum 迁移拒绝。
恢复测试在隔离随机数据库实际执行 mysqldump -> mysql，验证消息、授权哈希、游标与迁移审计。
仅创建/删除测试自己的随机库，不访问已有生产数据。

单元测试覆盖：客户端/服务端统一 SHA256 指纹向量、号码/消息边界、SSRF 私网阻止、
浏览器凭据 cookie 属性、跨域拒绝、JSON 大小/未知字段/多 JSON 拒绝；标准 Web Push 加密请求和 VAPID 头
由合成订阅与内存 HTTP 接收器验证，未调用真实公网 Push 服务。Web 增量合并/时间排序、
搜索参数编码、VAPID 解码、API 不缓存、Push 正文不显示；Android 分段 Unicode、统一指纹、
JSON 字节数拆批、确认号码、来源/初次补齐/历史导入通知排除。

## 未执行的环境验收与 lint 警告

以下为原基线记录；2026-10-12 的模拟器实测与当前边界见文末新增章节。

SQLite 事务、重启保留、未映射队列防饥饿测试在 androidTest 中，需 Android 设备/模拟器执行。
本轮只构建 androidTest APK，没有执行 instrumentation tests；不以构建代替运行。
Android lint 的同步 SharedPreferences commit、stopService 的 ImplicitSamInstance、API 29 内联常量、
target 35、依赖更新和 KTX 风格等警告仍保留，没有禁用检查或降低错误阈值。
commit 用于持久确认身份/开关；ServiceCompat 对旧系统处理前台服务类型。
Android 12+ 云备份和设备迁移已显式排除全部应用数据，厂商迁移行为尚无真机验证。

当前机器没有 Docker daemon，因此没有实际跑完整 Compose 容器栈。
没有真实 SIM/双卡设备，也没有生产 HTTPS + 浏览器 Push 授权环境：不得声称这些已端到端通过。
推荐的部署 MySQL 8.4.5 与本地测试 8.0.36 不同，CI 配置隔离 8.4.5；CI 的实际结果须查看推送后的 workflow，不能用本地结果代替。

首次推送 CI（[37907523714](https://github.com/IGNGserver/hongshu/actions/runs/37907523714)）中，
仓库、Web、Go/MySQL 8.4.5、真实 Chromium 验证通过；Android 因 setup-android 默认请求已下架的
`tools` 包，在测试开始前失败。改为显式请求 `platform-tools` 后，
[后续 CI](https://github.com/IGNGserver/hongshu/actions/runs/37908227348) 的仓库、Web、Go/MySQL 与 Android jobs 全部通过。

## 运维与兼容性限制

- Android 15+ dataSync 前台服务最长约 6h/24h，Doze/省电/强制停止可延迟通知；不保证全天即时。
- 系统限制可能让非默认短信应用无法读取部分敏感验证码；权限授予不是万能保证。
- 未知双卡来源先保留；保存唯一活动 SIM 的号码后才启用单卡兜底，换卡或变为多卡自动失效。卡槽不当作号码身份。
- Android 私有缓存、MySQL 和备份包含短信明文；传输 HTTPS，凭据 Keystore，但非 E2EE/SQL 字段加密。
- Web 离线仅提供应用壳，不缓存短信正文；Android 可离线查看已补齐历史。
- 异常超长/不合法短信在本地保留，不阻塞其他消息；首版没有异常队列编辑/导出 UI。
- 数据库恢复前记录现行代次，恢复后按部署文档设置高于现行与备份值的 epoch，不能仅给备份值加一。客户端清空已同步缓存并重拉，未上传队列保留；普通写入不改变 epoch。
- 单个中枢进程；多副本通知 worker、MMS/RCS、发送短信、多租户、海量搜索压测不在首版范围。

## 本机构建环境

根盘和 /tmp 已满，未清理其他任务。构建在工作区外独立共享缓存进行：
`/home/lvziw/项目/.build-cache/hongshu/`；MySQL 隔离实例与 Gradle 可变缓存使用 `/dev/shm/hongshu-*`。
CIFS 不支持可靠的 MySQL 文件锁，Gradle 原子缓存写也产生问题，因此这两类运行状态移到本地临时缓存。
生产必须用本地 Linux 数据盘；源码目录没有构建产物、凭据、短信或备份。
完整日志保留在共享缓存，不提交到仓库（避免混入运行时数据）。
最终 APK、Go 工具和 Web 资源包保留在共享缓存的 `artifacts/`，附 `SHA256SUMS`；debug 签名仅供测试，非正式发布产物。

## 2026-10-12 Engineering audit remediation

修复对应与剩余边界见 [AUDIT_REMEDIATION.md](AUDIT_REMEDIATION.md)。基线 e6155be；版本未递增，仍是 alpha.11。
本次缓存/产物在 `/home/lvziw/tmp/opencode/hongshu-remediation/`，Gradle 依赖使用已有共享缓存，
可变 GRADLE_USER_HOME 使用 `/dev/shm/hongshu-gradle`；不在 worktree 堆积产物。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh`、`git diff --check` | 通过；仓库结构/版本/凭据样式与新增 7 项隔离运维测试 |
| `bash -n scripts/*.sh`、`actionlint .github/workflows/{ci,release}.yml` | 通过；发布故障测试 mock gh/docker，无注册表写入或 Release |
| `TEST_MYSQL_DSN=... MYSQL_BIN=... MYSQLDUMP_BIN=... go test -race -count=1 -v ./...` | Go 1.26.4 / 本地独立 MySQL 8.4.11；24 项全部通过，无 skip |
| `go vet ./...`、`go build -trimpath -o "$HONGSHU_BUILD_DIR/hongshu" .`、`test -z "$(gofmt -l *.go cmd/*/*.go)"` | 全部通过 |
| `npm test`、`npm run check`、`WEB_BUILD_DIR=... npm run build` | Web 16 项通过，JS 语法/完整生产壳构建通过；content-only 升级/离线缓存是实际构建+VM 生命周期测试 |
| `PLAYWRIGHT_MODULE=... HONGSHU_BINARY=... MYSQL_BIN=... WEB_BUILD_DIR=... node scripts/browser-smoke.cjs` | Chromium + 本次 Go 二进制 + 独立随机 MySQL 库，通过登录/WS/自动刷新/XSS/手机布局/搜索/设置/壳不缓存 API |
| `./gradlew --no-daemon --offline --project-cache-dir ... -Pkotlin.project.persistent.dir=... :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | JDK 17 / SDK 35 / Gradle 8.11.1，通过；9 项 JVM 单测、lint、debug 与 androidTest APK |
| `adb -s emulator-5582 install -r ...`、`adb -s emulator-5582 shell am instrument -w io.hongshu.app.test/androidx.test.runner.AndroidJUnitRunner` | 新建隔离 API 35 模拟器，12 项 instrumentation 全通过；其中 100 次 WorkManager 触发与 HTTP 故障是真实调度/请求 |
| `aapt dump badging .../app-debug.apk` | versionCode=1000011 / versionName=0.1.0-alpha.11，min 26/target 35 |

实际 MySQL worker 覆盖领取/加密发送/租约过期/重试/失效订阅/撤销复核；HTTP 接收器为合成内存 transport，
不是公网 Web Push。恢复用同一真实 dump 两次，epoch 递增到 2 和 3，且新 head 已超过旧游标。
2000 条短信、10 个会话的 `EXPLAIN ANALYZE` 显示扫描 10 条摘要、10 次 messages 主键点查，
不重新扫描 2000 条历史计算窗口；该小型计划验证不是容量/延迟压测。
模拟器覆盖 v3→v4 无损迁移、捕获来源/SIM 隔离、ACK 空窗、初次同步/通知/epoch、精确密码与凭据保留、
采集健康不被网络成功覆盖。真实厂商 provider/PDU 与双卡硬件仍未验收。

失败记录不作为通过结果：最初 GRADLE_USER_HOME 的离线依赖缺失，改用已有完整缓存后通过；
`:connectedDebugAndroidTest --offline` 因缺少 UTP 插件依赖未执行，改用上表实际 adb runner 完整执行，
没有降低断言或禁用测试。新增网络测试首次命中了旧已完成 worker 的测试等待条件，改为等待本次新 UUID 后重跑通过。
CI 已增加在线 emulator runner，但推送后的 GitHub CI 结果需另行查询，不能用本地结果代替。
本次没有 Docker daemon、未实际解析 Compose/构建 OCI 镜像/启动容器；无真实 SIM、生产恢复、公网 Push、发版或部署。
# 短信分组验证范围

新增 `server/analysis_test.go` 覆盖保守验证码、加密密钥、输出枚举校验、脱敏、私网默认拒绝；
MySQL 测试覆盖按消息分组、权限、元数据分页、手动纠正竞态、暂停重试和租约恢复。
Android `MessageAnalysisTest` 在 API 28/35 覆盖 SQLite 迁移、分类筛选、旧消息元数据更新、
不重复通知、旧同步结果不得覆盖新分类及保留本机待上传消息。
Web 测试覆盖 AI 开关对应入口及待分析状态，浏览器冒烟验证实际分组交互。
真实厂商模型效果、计费、短信权限/验证码系统限制和真机通知仍需独立验收，模拟服务不代表实测。

本轮分支 `agent/sms-ai-grouping`（版本不变），本地 scratch 为 `~/.build/短信同步系统/sms-ai-grouping`：
- `bash scripts/verify-repository.sh`：通过，12 项运维测试；`git diff --check`、`gitleaks dir --no-banner --redact .` 通过。
- `cd server && go test -race -count=1 ./... && go vet ./...`：通过；本机未设置 TEST_MYSQL_DSN，真库项跳过，不作为真库通过依据。
- `cd web && npm test && npm run check`：21 项测试通过，语法检查通过。
- scratch 中 `./gradlew --no-daemon --project-cache-dir "$SCRATCH/gradle-project-cache" :app:testDebugUnitTest --tests io.hongshu.app.MessageAnalysisTest --tests io.hongshu.app.MessageFormatTest`：通过，API 28/35 Robolectric（非模拟器/真机）。
- 补跑 `RealtimeCaptureHostTest.versionFiveMigrationPreservesWatermarkLedgerUnknownAndBlockedRecords`：通过。

### 2026-10-16 完整 CI 结果

功能提交 `3a16351` 的 [Hongshu validation](https://github.com/IGNGserver/hongshu/actions/runs/38907562080)
全部通过；从运行创建至完成 10 分 48 秒（含排队）。六个 job：

| Job | 实际验证 | 结果 / job 耗时 |
| --- | --- | --- |
| verify | `bash scripts/verify-repository.sh`、`git diff --check` | 通过，9 秒 |
| server | 隔离 MySQL 8.4.5：`go test -race -count=1 ./... && go vet ./... && go build -trimpath -o "$RUNNER_TEMP/hongshu" .`；`node scripts/browser-smoke.cjs` | 通过，2 分 11 秒 |
| web | `npm test && npm run check && npm run build` | 21 项测试通过，6 秒 |
| android (28) | `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，以及 `:app:connectedDebugAndroidTest` | 通过，22 项模拟器测试，10 分 37 秒 |
| android (33) | 同上 | 通过，48 项模拟器测试，7 分 43 秒 |
| android (35) | 同上 | 通过，48 项模拟器测试，8 分 16 秒 |

MySQL 验证包括旧数据有界回填、回滚/再升级、分类权限、人工纠正、配置代次、限速额度、
新消息优先/历史倒序、租约恢复、元数据分页及分类事件不依赖新增短信游标。
浏览器验证开启后自动历史分析、验证码不外发、分组、人工纠正、待确认和关闭 AI 恢复三入口。
模型服务仅为本地合成夹具，未发送真实短信给外部 API。

前两轮 CI 的失败没有绕过：[第一轮](https://github.com/IGNGserver/hongshu/actions/runs/38904738801)
修复 MySQL 8.4 保留字列名及旧库迁移夹具；[第二轮](https://github.com/IGNGserver/hongshu/actions/runs/38905887144)
修复仅分类变化没有 WebSocket 通知、刷新期间最终事件丢失，以及隔离网络测试未取消立即运行的周期同步。
保留原有断言，另增加迁移、队列和实时刷新回归测试。

全量构建和模拟器运行在 GitHub Actions；本机只跑快速测试/受影响单测，未在项目卷生成依赖或产物。
本机 `BUILD_SCRATCH_ROOT=~/.build`，空间闸门实测可用 157 GiB；任务快照/测试产物约 23 MiB，
本次 Go 共享缓存约 256 MiB、模块缓存约 21 MiB，均保留用于复测，未删除其他任务缓存。
仓库为 public，本轮 Actions 不消耗私有仓库月度 2000 分钟额度（扣减 0 分钟）。
未创建 PR、未合并、未发布、未部署；任务 worktree 和分支保留。真实厂商模型效果、真实费用、
真机及生产环境验收未执行；垃圾分类不能视为来源认证或可靠的反诈判断。

## 2026-10-18 审计后续验证边界

本轮在 `agent/audit-followup` 延续已通过
[上一轮完整 CI](https://github.com/IGNGserver/hongshu/actions/runs/39096970753) 的修复。
剩余整改聚焦通知同源重放与跨来源关联、AI 候选查询锁范围，以及已读事务的故障回滚验证。

- 现有部署只读核查：无法匹配同发件人消息的非零会话已读水位为 **0**；没有执行生产修复 SQL。
- 本机 `adb devices -l` 没有连接设备；真实 SIM、双卡、厂商省电策略与公网 Push 仍未验收。
- 用户选择本轮先完成代码与 CI；本轮不以模拟器或合成 Push transport 结果替代真机验收。
- 本地检查使用 `~/.build/hongshu/audit-followup`，开始时本机盘可用 141 GiB；依赖缓存复用本机 scratch。
- 本地 `:app:testDebugUnitTest` 首轮定向 6 类、74 项通过；独立复核补齐上传指纹碰撞与旧账本重放后，10 类、97 项通过，再补充边界后碰撞测试类 11 项通过（与前轮重叠，不累加计数）。`:app:compileDebugAndroidTestKotlin` 在指纹碰撞修正后通过，后者仅证明仪器测试代码可编译。
- 最后一轮历史关联修正后，通知碰撞、通知身份、通知采集、实时采集四类共 70 项重新通过，无失败或跳过；覆盖已 ACK 的另一来源仍参与竞争，以及重开、同步后碰撞记录保留。
- 本地 Go `go test -race ./...` 与 `go vet ./...` 通过；没有设置 `TEST_MYSQL_DSN`，MySQL 用例跳过。已读回滚、持续上传期间 AI 领取及查询计划的真实数据库结果以本轮完整 CI 为准。
- 仓库验证（含 12 项隔离运维测试及凭据模式扫描）、`git diff --check`、gofmt 检查和 `actionlint .github/workflows/ci.yml` 通过。

## 2026-10-21 PWA Web Push 加固

分支 `agent/pwa-push-hardening`（基于 `origin/main` a2ab158，未合并）。本轮只修改 Web、Go 与文档，
没有数据库迁移；目标是复用现有标准 Web Push + VAPID，补齐 iOS 主屏幕引导、订阅轮换恢复、
manifest MIME 与测试通知，不改变短信入库和 Android 通知链路。

| 检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh`、`git diff --check`、`gofmt -l` | 通过；仓库验证含 12 项运维测试 |
| `cd server && go test -race -count=1 ./... && go vet ./... && go build -trimpath` | 通过；本机未设置 `TEST_MYSQL_DSN`，真实 MySQL 集成用例跳过，不能记为真库通过 |
| `cd web && npm test` | 96 项通过，含 iOS 引导、VAPID 密钥轮换、服务端订阅丢失恢复、`pushsubscriptionchange`、payload 越界路径防护、manifest MIME |
| `cd web && npm run check && WEB_BUILD_DIR=... npm run build` | 通过；构建校验 `display=standalone`、稳定 `id` 与 any/maskable 图标 |
| 本地 scratch | `~/.build/短信同步系统/pwa-push-hardening`；开始可用约 122 GiB，Go/Web 产物与缓存保留用于复测 |

新增行为：服务端显式设置 `.webmanifest` 的 `application/manifest+json`；`/api/me` 返回
`push_subscribed`；设置页在 iOS Safari 标签页说明“添加到主屏幕”，并在 VAPID 密钥变化、浏览器轮换或
服务端订阅丢失时重新订阅；新增 `POST /api/push/test` 直接向当前设备发送通用测试通知，不写短信、
不经过 `push_jobs`，404/410 时清理订阅与积压任务。Service Worker 解析有长度上限的标题/正文，
只允许同源通知点击路径，并监听 `pushsubscriptionchange`。

本机未执行：真实 iPhone/iPad 主屏幕安装与 APNs、真实 Android Chrome/FCM、国内无 GMS 设备、
生产 HTTPS 部署、真实 MySQL 集成测试、Android 构建与模拟器；CI 已补齐 MySQL、Chromium 冒烟和
Android 模拟器回归（见下）。真实设备与公网 Push 仍须在目标环境单独验收，尤其是 iOS 16.4+
必须从主屏幕图标打开后授权，不能用 CI 的 Chromium 结果代替。

### 2026-10-21 完整 CI

功能提交 `10693d1` 的 [Hongshu validation 39621897496](https://github.com/IGNGserver/hongshu/actions/runs/39621897496)
全部通过；从创建到完成 10 分 24 秒（含排队）。六个 job：

| Job | 实际验证 | 结果 / 耗时 |
| --- | --- | --- |
| verify | `bash scripts/verify-repository.sh`、`git diff --check` | 通过，7 秒 |
| web | `npm test && npm run check && npm run build` | 96 项测试通过，10 秒 |
| server | 隔离 MySQL 8.4.5：`go test -race -count=1 -v ./... && go vet ./... && go build -trimpath`；Chromium `scripts/browser-smoke.cjs`、`local-contacts-smoke.cjs` | 通过，2 分 52 秒；新增测试通知/订阅状态/失效清理/manifest MIME 用例实际执行 |
| android (28) | JVM 单测、lint、APK、androidTest APK 与模拟器 instrumentation | 通过，8 分 59 秒 |
| android (33) | 同上 | 通过，8 分 03 秒 |
| android (35) | 同上 | 通过，7 分 15 秒 |

浏览器冒烟覆盖登录、收件箱、XSS、移动布局、搜索、设置页、PWA 缓存不包含 API；设置页在没有 VAPID 的
测试部署中仍显示“通知已关闭”并单独说明原因。Android 矩阵验证的是现有客户端回归，不代表 PWA 真机
通知或 FCM/APNs 可达。本轮 CI 合计约 27.4 job 分钟；仓库为 public，不消耗私仓月度 2000 分钟配额。
未创建 PR、未合并、未发布、未部署；真实 iPhone/iPad、Android Chrome/FCM、无 GMS ROM 与生产
HTTPS/VAPID 配置仍未验收。

## 2026-10-22 通知副本吸收与无法归属短信自动忽略

分支 `agent/sms-dedup-ignore`（基于 `origin/main` 4038691，`0.2.0-alpha.16`），worktree
`~/项目/.wt/短信同步系统/sms-dedup-ignore`。本轮只修改 Android 与文档，不改服务端协议、Web 或数据库 schema；
不递增版本、不创建 Release。

新增行为：权威广播/收件箱副本 ACK 或同步确认后，把同正文、时间差不超过 60 秒、候选唯一的未配对通知副本
吸收到权威 capture，转移通知身份与上传指纹归属并删除通知 outbox 行；待上传页的设置/分组新增
“自动忽略无法确认接收号码的短信”，以 `outbox.blocked=auto_ignored` 软忽略，不计入提示与上传，
可在待上传页恢复或放弃，保存有效 SIM 或出现权威副本时自动解除。无数据库迁移。

| 实际命令/检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；版本 `0.2.0-alpha.16`，12 项隔离运维测试 OK |
| `git diff --check` | 通过 |
| scratch `~/.build/短信同步系统/sms-dedup-ignore`；`./gradlew --no-daemon :app:testDebugUnitTest` | 274 项测试，0 失败/0 错误/11 跳过；含新增 `NotificationDuplicateAbsorptionTest` 9 项 |
| `./gradlew --no-daemon :app:lintDebug :app:assembleDebug` | 通过；lint 无 error，debug APK 20 MB；最终合并跑 2 分 09 秒，冷跑 4 分 06 秒 |
| 本地构建位置 | 源码副本 `~/.build/短信同步系统/sms-dedup-ignore`（7.1M）；Gradle 输出/缓存 `~/.cache/hongshu`（178M），未在工作区或项目卷生成 `build`/`.gradle` |

测试覆盖：联系人名通知与有号码 provider/广播副本在 ACK 后合并为一份；ACK 丢失后由同步页清理旧副本；
其他设备的同内容同步页不清理本机通知；自动忽略开关的提示总数、会话列表、上传排除、恢复/放弃、
通知身份账本幂等；无可用 SIM 时只忽略通知副本；保存缺失 SIM 后恢复；后续权威副本出现时恢复。

未执行/未覆盖：本机 `adb` 无真机，双卡/澎湃 OS 的真实通知标题、后台进程回收、网络断开重连后的端到端
上传未在本轮验证；由 PR CI 的 Android 模拟器矩阵承担回归，真机行为仍需目标设备验收。
服务端与 Web 未改动，Go/MySQL 与浏览器冒烟以 PR CI 为准。

### 2026-10-22 完整 CI

PR [#85](https://github.com/IGNGserver/hongshu/pull/85) 的
[Hongshu validation 39695130184](https://github.com/IGNGserver/hongshu/actions/runs/39695130184)
全部通过；从创建到完成 7 分 55 秒（含排队）。六个 job 实际执行：

| Job | 结果 / 耗时 |
| --- | --- |
| verify | `bash scripts/verify-repository.sh`、`git diff --check`；通过，7 秒 |
| web | `npm test && npm run check && npm run build`，浏览器与联系人冒烟；通过，6 秒 |
| server | 隔离 MySQL 8.4.5：`go test -race -count=1 -v ./... && go vet ./... && go build -trimpath`；Chromium 与本地联系人冒烟；通过，3 分 13 秒 |
| android (28) | JVM 单测、lint、APK、androidTest APK 与模拟器 instrumentation；通过，7 分 50 秒 |
| android (33) | 同上；通过，7 分 52 秒 |
| android (35) | 同上；通过，6 分 32 秒 |

仓库为 public，本轮 CI 不消耗私仓月度 2000 分钟配额。PR 尚未合并、未发布、未部署；本机仍无真机/adb，
双卡、澎湃 OS 通知字段、后台进程回收与断网重连的端到端行为仍需目标设备验收。

# 2026-10-26 PWA 与手机/平板统一

基线 `1d1dec6`，任务分支 `agent/pwa-adaptive-unification`。本次没有递增版本、发布 Release、触发生产部署或更改 Cloudflare 全区设置。

- 稳定 `/manifest.webmanifest`、`id=/`、`scope=/`；图标与页面脚本使用内容 hash，根图标兼容地址保留。Manifest 不再混入 32px favicon 候选。
- Workbox 7.4.1 injectManifest + 本地打包运行时。新壳安装校验 HTML revision 和 JS/CSS MIME；旧 HTML 或不完整候选不能替代活动版本。更新需用户选择，未保存输入需确认；仅无打开文档时清理旧壳，公共 hash 资源有容量上限。Push、订阅轮换与消息隐私边界保留。
- HTML/manifest/SW 的源站 Cache-Control 改为 `no-store, no-cache, max-age=0, must-revalidate`；hash 资源仍为 immutable。线上代理/CDN 的最终响应须在实际部署后另验。
- 登录/设置均提供安装说明；iOS/iPadOS 指引通过分享菜单添加到主屏幕，缺少 beforeinstallprompt 不当作“不支持”。独立窗口隐藏安装入口。
- 增加安全区、visualViewport、短视口滚动和触控目标。600px 窄平板左栏改为比例宽度；修复设置按钮溢出被详情遮挡，以及筛选栏 ResizeObserver 同步布局循环。保留手机单栏、平板双栏和原有品牌样式。

验证均运行于 ubuntu-dev 本地盘 `~/.build/pwa-adaptive-hongshu`，未在项目卷写入 node_modules/构建目录。开始根盘可用77GiB，最终约68GiB（同期其他任务亦在使用）；本任务 scratch 约96MiB，共享Go缓存约253MiB。

| 检查 | 结果 |
| --- | --- |
| `bash scripts/verify-repository.sh` | 通过；12项operations测试 |
| `git diff --check`、受影响Go文件gofmt检查 | 通过 |
| `HONGSHU_TEST_TMP=本机scratch npm test` | 107项通过，0失败、0跳过 |
| `npm run check` | 通过 |
| `WEB_BUILD_DIR=本机scratch npm run build` | 通过；Workbox运行时本地打包、图标尺寸与依赖图检查通过 |
| `go test -race -run 'Test(Manifest\|ShellAssets\|Static)' ./...`、`go vet ./...` | 通过；本轮仅定向静态资源/缓存契约，不冒充真实MySQL集成 |
| Chromium：`scripts/pwa-browser-smoke.cjs` | 通过：图标、390/600/768/1024/1366px窗口、设置/登录短窗口、通话详情、安装事件、context离线、API隔离、旧HTML拒绝安装、输入保护、用户确认升级、旧hash资源 |
| WebKit：同一脚本，`PWA_BROWSER=webkit` | 通过同一布局/安装/升级用例；因Playwright WebKit `context.setOffline`已知限制，改为fixture服务拒绝socket，并确认无Worker对照页加载失败。此项证明服务不可达时壳恢复，不冒充物理断网 |

浏览器脚本只使用内存中的合成API与消息，不连接真实中枢或真实短信。截图人工检查了600px双栏布局。iOS 27主屏幕安装图标、安卓Edge系统安装、真实软键盘与安全区，以及存量安装的升级仍需目标设备验收；桌面WebKit不能替代系统安装器。完整MySQL/Android验证由已有CI承担，本轮未触发CI或报告其通过。