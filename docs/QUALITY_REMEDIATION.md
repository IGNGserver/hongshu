# 2026-11 全平台质量整改台账

基线：`9ceea66b576ffeba3892189416cf403da1b70816`（`0.2.0-alpha.23`）。本台账对应仓库外审查报告
`Hongshu-code-audit-9ceea66.md` 的 S/W/A/O/R 编号。实现遵循“先合同与反例、再代码、最后实测”的顺序；
状态只在同时具备代码和相应自动测试后才记为完成，未取得环境证据的项不写成通过。

本任务不创建 Release、不部署生产、不访问生产环境。外部服务已接受的请求不可撤回；真实 SIM/OEM、
真实 Web Push、外部 AI 端点、容器镜像与灾难恢复仍需对应环境验收。

## 合同决策

1. **会话游标**：保留旧 OFFSET；新增 `cursor/next_cursor/more/head/epoch`。每页查询与提交头处于同一 RR 快照，Web 只连接相同 head/epoch 的页面，变化即废弃整条链。这样不要求为历史会话维护不可变快照表，也不把不同提交的页面合并。同一请求同时给出 `offset` 与 `cursor` 返回 `400 invalid_page`。
2. **上传 ACK**：Android 为每次真实请求持久化不可变 wire fingerprint；ACK 以 key+fingerprint 为条件。整批 `invalid_message` 也只匹配实际发送的 fingerprint。旧身份已被服务端接受时保留新身份待传，不声称可撤回旧服务端记录。
3. **通知吸收**：双方存在可用 sender 时必须规范化一致；不同 sender 或多候选一律保留。即时通知与服务端/provider 配对时采用权威时间戳，避免后续同步证明与 wire fingerprint 不一致。
4. **换绑**：所有未上传 outbox 都阻止静默换绑，包括 auto_ignored；显式清理时事务删除全部旧身份队列与账本。
5. **Web 退出**：服务器撤销优先；网络失败保持会话和联系人。成功/401 后本地退出并异步持久清联系人，失败展示可重试状态。
6. **跨标签身份**：采用 `/me` 前比较 device ID；变化执行 generation+1、取消请求、关 WS、清详情、重载联系人和 Push。login/logout 使用独立 auth channel。
7. **AI 错误**：区分消息永久错误（内容类 400，只死信当前消息）与全局配置/鉴权/限流错误；未知 400 使用连续三次熔断，避免单消息停全局或无限扣费。
8. **关闭**：HTTP drain、worker cancel/join、transport/Redis close、DB close 有明确顺序和超时；关停期间不启动新 worker。
9. **性能**：先消除已证明的重复传输/空轮询/深 OFFSET，再以 EXPLAIN、基准和真实数据决定派生计数或索引；不以未压测为由引入双写计数器。
10. **安全兼容**：raw DSN 拒绝明文 fallback；HTTP 仅作为显式局域网兼容模式并持续警示，不默默改变既有部署 URL；升级后旧 HTTP 连接需重新做一次精确 origin 授权。SQLite 继续使用应用沙箱和禁备份，不宣称 SQLCipher 是必选项。
11. **构建产物回收**：只删除清单授权、内容 hash 命名的旧常规文件；元数据损坏时 fail-closed，未知/活动文档与其后七天宽限期内的壳一律保留。
12. **发布顺序**：镜像默认同时构建 `linux/amd64,linux/arm64`；稳定 `latest` 只在候选 SemVer 严格大于发布前 `latest` 时才前进，旧草稿恢复不会使 `latest` 回退。

## 整改清单

状态含义：**完成**＝代码+自动测试；**完成（运行时待验）**＝代码+单测已通过，但需真机/真实服务/容器环境验收；
**部分**＝已有代码和部分证据，仍缺指定实测；**未实施**＝本轮未改动。

### Go 中枢

| 编号 | 目标 | 状态 | 证据 |
|---|---|---|---|
| S1 | Push 清理事务完整订阅 fencing；旧 404/410 不删新任务 | 完成 | `removeDeadPushSubscription` 在事务内 `FOR UPDATE` 比较 endpoint+p256dh+auth 全键；`push_subscription_test.go` |
| S2 | AI 400 分类、消息死信与全局熔断 | 完成 | `permanentMessageAIError`、`aiHTTPFailure`、`noteAIResult` 连续三次未知 400 熔断；`analysis_test.go` 熔断用例 |
| S3 | 可测试 run、HTTP drain、worker join 与资源关闭 | 完成 | `lifecycle.go` 有序关停+drain 超时；`lifecycle_test.go` 验证在途请求先完成、worker 后退出 |
| S4 | Redis 默认命名空间包含部署/规范化 DB 地址；副本可共享 | 完成 | `defaultRedisPrefix(databaseName, databaseAddress, deploymentID)`；`redis_test.go` |
| S5 | raw DSN 拒绝 AllowFallbackToPlaintext | 完成 | `dbconfig.go:239` 拒绝、`dbconfig_test.go` |
| S6 | 标读变更与响应水位/未读数使用同一事务快照 | 完成 | `read.go:128` 注释与实现：响应从同一 RR 事务派生；`read_sync_test.go` |
| S7 | 有效已读才推进 revision；未读版本缩小与 singleflight | 完成 | `loading.go` `unreadSnapshot`+`unreadFlights`，缓存键含 epoch/head/watermark/revision |
| S8 | AI 类型化调度、事件/正常休眠、finish 错误可观测与重试 | 完成 | `analysisSchedule`+`claimAnalysisScheduled`+`analysisLoop`；`finishAnalysisWithRetry` 失败保留 durable lease 并计数日志 |
| S9 | 按 AI generation 复用 SSRF 安全 transport | 完成 | `effectiveAIClient` 以 generation/timeout/endpoint/protocol 为键，最多 4 个并关闭淘汰者空闲连接 |
| S10 | Push fencing 后有界并发与必要的设备顺序 | 完成 | `claimPushJobs` 每设备只取一条、单批 4 条，`processPushBatch` 内 `sync.WaitGroup` 并发投递；`push_integration_test.go` |
| S11 | Retry-After HTTP-date、永久 4xx 分类、有界 drain | 完成 | `pushRetryAfter` 支持秒与 HTTP-date；`retryablePushStatus`；错误体 `io.LimitReader(32KB)`；`push_test.go`、`push_integration_test.go` |
| S12 | 筛选 keyset、执行计划基准与冗余索引审计 | 完成 | 真实 MySQL 8.4.5 上 `TestIntegrationConversationIndexQueryPlan` 通过：2000 条短信/10 会话夹具下摘要查询走 `index lookup on m using PRIMARY`，无全表扫描、无窗口聚合；索引审计发现 `messages_unread` 与 `push_jobs.pending` 分别被 014 的两条新索引严格覆盖，已由迁移 015 删除（含重建回滚脚本），见 `docs/DATABASE.md` |
| S13 | SMS sync 使用 RR 快照或维护模式防护 | 完成 | `read_sync.go:31` 只读 `LevelRepeatableRead` 事务；`read_sync_test.go` |

### Web / PWA

| 编号 | 目标 | 状态 | 证据 |
|---|---|---|---|
| W1 | Web 跨标签认证协调与 device ID 迁移 | 完成 | 独立 auth BroadcastChannel + `/me` device 比较；`runtime.test.js` |
| W2 | 服务器优先 logout、独立持久清理与失败恢复 | 完成 | `session-refresh.test.js`「logout is server-first and transport failure preserves session and local contacts」 |
| W3 | Web/联系人改用 head-fenced keyset | 完成 | `runtime.test.js`「conversation keyset pages omit offsets, fence head changes and recover once from cursor reset」 |
| W4 | 通话列表/详情 epoch、请求序号和 reset 清理 | 完成 | `calls.test.js` restore/new-call/logout 用例 |
| W5 | Service Worker 旧 shell 可重入安全回收 | 完成 | `shell-lifecycle.test.js`：未知客户端、活动 lease、lease 后七天宽限 |
| W6 | 分析计数 dirty、独立退避和恢复 | 完成 | `runtime.test.js`「analysis counts retain an independent dirty state and recover after failure」 |
| W7 | Push 订阅轮换检查 response.ok 并恢复 | 完成（运行时待验） | `pwa.test.js` 轮换失败上报、自动恢复替换；真实 Push 到达未验证 |
| W8 | busy 引用计数与 mutation 防重入 | 完成（浏览器待验） | 计数式 busy 已实现；需要真实浏览器交互与双标签验证 |
| W9 | 401 持久联系人清理、owner 隔离与可重试失败 | 完成 | `local-contacts.test.js`「legacy records are atomically claimed and owner-scoped logout never deletes another identity」 |
| W10 | 大列表 keyed/窗口化更新、历史上限与焦点保持 | 完成（浏览器待验） | 详情内存上限 4 会话/50 条；滚动焦点需浏览器验收 |
| W11 | 消除联系人打开会话的重复刷新 | 完成 | `runtime.test.js` 元数据 single-flight |
| W12 | OTP 到期 timer 集中管理 | 完成（浏览器计时待验） | 集中 timer 已实现；真实计时未验证 |
| W13 | 通知深链同源 navigate+focus | 完成（真实通知待验） | `pwa.test.js` 同源 focus 用例 |
| W14 | WS 离在线感知、指数退避与抖动 | 完成 | `runtime.test.js`「WebSocket reconnect backoff is bounded and jitter stays inside each window」 |
| W15 | 构建产物按清单和宽限期回收 | 完成 | `build.test.js` 保留被引用资源、仅回收旧 hash 文件 |

### Android

| 编号 | 目标 | 状态 | 证据 |
|---|---|---|---|
| A1 | 上传批次至少一项、非法项阻塞且后续可进展 | 完成 | 合法超大转义记录可单独上传；畸形行由 `Store.pending` 阻塞；`UploadTest.kt` |
| A2 | wire fingerprint 持久化与条件 ACK | 完成 | `outbox.sent_fingerprint`（DB v18）+ 条件 ACK；`UploadTest.kt`、`SyncReconcileTest.kt` |
| A3 | 通知吸收 sender 约束和歧义保留 | 完成 | `NotificationDuplicateAbsorptionTest.kt` 覆盖不同 sender 与多候选保留 |
| A4 | auto_ignored 纳入换绑保护与事务清理 | 完成 | `pendingCount()` 计入 auto_ignored；`resetForNewIdentity` 事务清理；`ReadReceiptTest.kt` |
| A5 | 全局已读 durable outbox、自动补传和真实 UI 状态 | 完成 | `read_outbox` 空 sender 为全局目标；新客户端 `{all:true,message_id:<localHead>}`；`ReadReceiptTest.kt` |
| A6 | SIM 过滤下推 SQL/keyset，修正 500 行截断 | 完成 | `localMessages` 谓词下推并按 keyset 越过无关行；`SyncReconcileTest.kt` |
| A7 | HTTPS 优先、显式 HTTP 兼容授权和持续警示 | 完成 | `requireOriginAccess` 要求显式授权并持久 `cleartext_origin`；`IdentityStateTest.kt` |
| A8 | 广播快速持久化、背压、期限与恢复 | 完成 | `SmsReceiver.onReceive` 返回前 `enqueueBroadcast`；`broadcast_inbox` 有界；`RealtimeCaptureContract.kt` |
| A9 | 实时同步 wakelock 按进度续租/有界任务 | 完成 | 非引用计数 60s lease/45s 续租/15min 总时限；新增 `RealtimeResourceTest.kt`（续租次数、取消释放、总超时） |
| A10 | OkHttp 与协程取消联动 | 完成 | `requestCancellable` 用 `enqueue`+`suspendCancellableCoroutine`，取消即 `Call.cancel()`；新增 `NetworkCancellationTest.kt` 以真实 socket 验证对端读到 EOF |
| A11 | OTP 复制前复核当前意图、过期和撤销 | 完成 | `OtpNotificationReceiver` 先 `consumeOtpIntent(messageId,otp,epoch,now)` 再写剪贴板；`OtpNotificationManagerTest.kt` |
| A12 | getprop 流/进程统一清理 | 完成 | `boundedProcessLine` 限长保留但读到 EOF，避免 SIGPIPE；`XiaomiAccessTest.kt` |
| A13 | 跨 WorkRequest 的连续失败退避与文档一致 | 完成 | `Config.noteSyncFailure`/`clearSyncRetry` 持久化到 prefs；新增 `RealtimeResourceTest.kt` 重建后仍生效、成功后清零 |
| A14 | OTP 剪贴板仅覆盖检查后的延迟清理 | 完成 | API 28+ `clearPrimaryClip()`，API 26/27 用自有空 `ClipData`；新增 `OtpClipboardTest.kt`（SDK 26/35，新用户剪贴板不被清除） |
| A15 | SQLite/Keystore 威胁模型与迁移边界文档 | 完成 | `docs/ARCHITECTURE.md:130` 明确 SQLCipher 非默许边界与迁移前置条件 |

### 部署、备份、CI、官网与跨端契约

| 编号 | 目标 | 状态 | 证据 |
|---|---|---|---|
| O1 | 外部 MySQL 使用镜像内迁移或随包交付迁移 | 完成 | `docs/DATABASE.md` 改为容器内 `/app/migrations` 执行，不再绑定源码树 |
| O2 | 备份启动仅依赖 DB，hub 停止仍可备份 | 完成 | `compose.yml` backup 仅 `depends_on db: service_healthy`；`scripts/test-operations.py` |
| O3 | 备份超时、last-success、超龄健康和保留策略 | 完成 | `backup.sh` 单轮 deadline、`gzip -t`、原子重命名、随机名保留；`backup-healthcheck.sh` 以 interval+timeout+grace 判超龄 |
| O4 | 最终归档/镜像 Compose、迁移、备份、恢复与升级 smoke | 部分 | 已用真实 MySQL 8.4.5 完成迁移至 015、`scripts/backup.sh` 一致性备份（gzip 校验、原子 last-success、健康检查通过）与恢复到全新库（18 表、15 条迁移全 `dirty=0`）。仍缺容器镜像/最终归档的 Compose 启动与升级演练（本机无 Docker daemon） |
| O5 | main/PR/Release 检查对称且不可绕过 | 完成 | CI 去掉 push 跳过、扩大 paths；`release.yml` 要求 tag 提交在 `main` 上且 7 个 required check 为 success |
| O6 | 基础镜像/Actions/部署 digest 与依赖/secret 扫描 | 完成 | Dockerfile 三段按 digest；MySQL 多架构 digest；全部 Action 固定 40 位 SHA（本次修正 `gradle/actions` 与 `update-feed.yml`）；Gitleaks/依赖审查/Trivy fail-closed |
| O7 | 初始化 0600 临时文件、原子发布与可恢复失败 | 完成 | `init-deployment.sh` 同盘 `mktemp`+`umask 077`，pull/VAPID/配置校验通过后 `mv -n` 发布 |
| O8 | AMD64/ARM64 发布或明确架构预检 | 完成 | `publish-release.sh` 默认 `linux/amd64,linux/arm64` |
| O9 | stable latest 只允许 SemVer 前进 | 完成 | `compare-semver.sh` 仅当严格更新返回 0；`publish-release.sh` 据此推进 `latest` |
| O10 | Playwright 统一 lockfile、npm ci 和 browser revision | 完成 | `web/package.json` 精确 `1.62.1` 与 lock 一致；CI/Release 使用 lockfile 安装；`verify-repository.sh` 拒绝临时 `npm install playwright` |
| O11 | 嵌套 404 绝对资源路径与部署 URL 检查 | 完成 | `website/site/404.html` 使用 `/assets/...`；`check-site.py` 拒绝相对本地目标 |
| O12 | 共享版本化协议 JSON 和 N/N-1 合同测试 | 部分 | 已建立单一共享合同 `protocol/error-codes.v1.json`（版本 1）：Go 测试用 AST 从源码提取 `fail()` 与 AI 失败路径实际可返回的 49 个码并要求与 fixture 完全一致，另用正则校验上传/通话 ACK 的逐项错误码；Web 测试要求其错误文案表不得包含中枢无法返回的码；Android 测试要求已处理的码都在合同内且仍保留专属文案，并锁定两个上传 surface 的 ACK 词表。仍缺：旧 APK×新 hub、新 APK×旧 hub 的真实版本偏斜构建与联调矩阵 |
| O13 | 外部 MySQL 独立备份单元与恢复说明 | 完成 | `compose.external-backup.yml` 独立单元；`docs/DEPLOYMENT.md` 记录契约与恢复步骤 |
| O14 | 官网就近披露可选 AI 正文外发 | 完成 | `website/site/index.html` 明示默认关闭与配置后正文发送给所选服务商 |
| O15 | 下载入口使用版本匹配文档 | 完成 | 部署链接指向 `blob/v0.2.0-alpha.23/docs/DEPLOYMENT.md`；`check-site.py` 同时拒绝 `blob/main` 漂移链接 |
| O16 | 官网澄清收发同步、通话查看和不代发 | 完成 | 首页明确收发短信与通话记录为查看、无彩信/RCS、不代发短信或拨号 |
| O17 | 运维测试消除隐式宿主 jq 依赖 | 完成 | `test-operations.py` 用内嵌 Python 模拟 `gh`/`docker`/`jq`，18 项在无宿主 jq 环境通过 |
| O18 | 依赖维护、当前验证矩阵与历史归档 | 部分 | 验证矩阵已写入本文件与 `docs/VALIDATION.md`；依赖自动升级与历史归档仍有待梳理 |

### 结构性重构（R1–R7）

| 编号 | 目标 | 状态 | 说明 |
|---|---|---|---|
| R1 | Store 按事务所有权拆分 | 未实施 | 需先稳定 A2/A3/A4 语义再搬迁；属独立、无行为变化的大改动，本轮不做 |
| R2 | Web 会话状态机拆模块 | 未实施 | 同上；需统一 request context 后进行 |
| R3 | AI 按边界拆分 provider/policy/queue/lease | 未实施 | S2/S8 的错误分类与调度返回类型已先行完成，为拆分铺路 |
| R4 | Compose 页面与同步流程降耦合 | 未实施 | 需 ViewModel/state holder 抽取与设备回归 |
| R5 | 统一跨端请求/错误语义 | 未实施 | 与 O12 同属共享 fixture 工作，未建立 |
| R6 | 性能先测量再派生化 | 部分 | 已消除重复传输/空轮询/深 OFFSET；派生计数与虚拟列表未做，因缺真实规模基准 |
| R7 | 运行可观测性统一 | 完成 | 新增认证端点 `GET /api/ops`（`server/ops.go`）：扁平输出 version/uptime、DB 连接池与延迟、`schema.version/dirty`、Push 计数与 `last_loop/last_success/backlog`、AI `finish_failures/last_success/last_loop/queued`、`workers.running/exited`、`realtime.subscribers`、`redis.enabled`；无法测量的 backlog/queued 返回 `null` 而非 0。`TestOpsNeverLeaksContentCredentialsOrEndpoints` 断言载荷不含短信正文、发件人、token、Push endpoint、VAPID 密钥与 origin，并禁止 body/sender/token/endpoint/secret/password 字段名出现；`TestOpsRequiresAuthentication` 断言未认证与伪造 token 均为 401。备份 last-success 仍由 `backup-healthcheck.sh` 在备份单元内判定（中枢容器不挂载备份卷）。未提供 Prometheus exposition，属于可选扩展 |

审查报告自身把 R1–R7 列为“不应与正确性修复混在一个巨型 PR 中”的独立结构化重构，并要求先有对应测试再搬迁代码。本轮保留该边界，未以机械拆文件冒充完成。

## 验收矩阵（本轮实际执行）

| 平台 | 命令/范围 | 结果 |
|---|---|---|
| Go | `gofmt` 检查、`go test -race -count=1 ./...`、`go vet ./...`、`go build -trimpath` | 全部通过（8.18s）。未设 `TEST_MYSQL_DSN`，真实 MySQL 集成/迁移/备份恢复/`EXPLAIN` 用例在本机跳过 |
| Web | `npm test`、`npm run check`、`npm run build` | 120/120 通过，语法检查与生产构建通过 |
| Android | `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | 通过；单测 351 项、0 失败、20 项截图用例按条件跳过；lint、Debug APK、设备测试 APK 均成功 |
| 运维 | `bash scripts/verify-repository.sh` | 通过；含 pin/lock 合同与 18 项运维测试 |
| 官网 | `python3 website/scripts/check-site.py` | 通过，7 文件 12 KB |
| CI 定义 | PyYAML 解析 4 个 workflow、38 个 `run` 块 `bash -n`、内嵌 Python 片段编译、Action 40 位 SHA 对上游 commit 校验 | 全部通过；修复 2 处真实缺陷（见下） |

### 真实 MySQL 暴露并修复的问题

本机缺少 MySQL 时集成用例整体跳过，掩盖了下列问题；本轮下载官方 MySQL 8.4.5 二进制在工作区外起临时实例后暴露并修复：

1. `TestReadRevisionIsStrictlyMonotonicAndTransactional` 沿用“每次标读都推进 revision”的旧期望，与 S7“仅有效水位推进才失效其他客户端”矛盾；改为断言重复同一边界不推进、更新的消息才推进，保留“无效请求回滚不改变 revision”。
2. `TestConcurrentReadAndUploadLockOrdering` 断言 12 次并发固定产生 8 次 revision 推进；实际只有有效推进才增加，且数量随并发上传抬高 head 而变化。改为断言 4 次上传全部提交（`seq=8`）、标读均提交且推进次数落在有效范围内，锁序不变量不变。
3. `TestIntegrationPushConcurrentWorkersDoNotPreleaseTail` 让同一设备两个任务并发投递，与 S10 的按设备顺序互斥。拆成两个更准确的用例：同一设备只领取一条且队尾保持排队，另一设备可同时领取；慢设备投递不阻塞其他设备。
4. 索引审计发现两条完全冗余的索引，新增迁移 015 删除并提供回滚。

### 本轮新发现并修复的缺陷

1. `.github/workflows/update-feed.yml` 仍使用浮动 `actions/checkout@v4`，并把 runner 钉在 `ubuntu-latest`；已改为已验证 commit SHA 与 `ubuntu-24.04`。
2. `.github/workflows/ci.yml` 的 `gradle/actions/wrapper-validation` 40 位值在上游不是解引用后的 commit（`git/commits` 404），流水线会在该步骤失败；已改为 `v4.4.4` 的 commit `748248d`。
3. `androidTest/NetworkTest.kt` 在 `saveConnection`/`login` 改为 suspend 后未同步，导致 `:app:compileDebugAndroidTestKotlin` 失败；已按调用点包裹 `runBlocking`，未改动生产语义。
4. `RealtimeResourceTest`/`NetworkCancellationTest` 首版在 `runBlocking` 单线程上阻塞等待，协程无法启动；改为 IO 调度执行真实调用后通过（属测试夹具缺陷，非生产缺陷）。
