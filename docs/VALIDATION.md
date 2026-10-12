# 首版开发验证记录

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
- 未知双卡来源先保留，必须补齐用户确认映射；唯一 SIM 兜底须用户明确确认本机单卡。卡槽不再当作号码身份。
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
