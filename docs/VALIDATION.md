# 首版开发验证记录

日期：2026-10-09。仅记录实际运行的检查；不等同于生产上线、正式版发布或真机兼容性认证。
`VERSION` 维持 `0.1.0-alpha.1`，未创建 tag/Release/PR，未合并默认分支。

## 已执行

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
| `actionlint .github/workflows/ci.yml` | 通过 |
| Gradle wrapper integrity | wrapper JAR SHA256 与 Gradle 官方 8.11.1 发布值一致；distribution 配置 SHA256 校验 |
| `ktfmt --kotlinlang-style --dry-run --set-exit-if-changed ...` | Kotlin 生产代码、JVM 测试、androidTest 格式检查通过 |
| `./gradlew --no-daemon --offline --max-workers=2 --project-cache-dir ... :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | JDK 17 / SDK 35 / Gradle 8.11.1，5 个 JVM 测试通过；lint 无错误；debug APK 与 androidTest APK 构建成功 |
| `apksigner verify --verbose ...` / `aapt2 dump badging ...` / `aapt2 dump xmltree ... --file res/xml/data_extraction_rules.xml` | debug APK 签名有效；应用 ID、版本、min 26/target 35 正确；云备份和设备迁移排除规则确实已打包 |
| `PLAYWRIGHT_MODULE=... HONGSHU_BINARY=... MYSQL_BIN=... node scripts/browser-smoke.cjs` | 真实 Chromium + Go + 隔离 MySQL 通过：初始化、WS 连接及自动更新、收件箱、XSS 安全、手机对话/返回、搜索、设备导航/配对码、设置、PWA 不缓存 API |

MySQL 测试覆盖：首次初始化、一次性配对重放、设备授权/撤销、最后管理员保护、SIM 确认、
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

SQLite 事务、重启保留、未映射队列防饥饿测试在 androidTest 中，需 Android 设备/模拟器执行。
本轮只构建 androidTest APK，没有执行 instrumentation tests；不以构建代替运行。
Android lint 的同步 SharedPreferences commit、stopService 的 ImplicitSamInstance、API 29 内联常量、
target 35、依赖更新和 KTX 风格等警告仍保留，没有禁用检查或降低错误阈值。
commit 用于持久确认身份/开关；ServiceCompat 对旧系统处理前台服务类型。
Android 12+ 云备份和设备迁移已显式排除全部应用数据，厂商迁移行为尚无真机验证。

当前机器没有 Docker daemon，因此没有实际跑完整 Compose 容器栈。
没有真实 SIM/双卡设备，也没有生产 HTTPS + 浏览器 Push 授权环境：不得声称这些已端到端通过。
推荐的部署 MySQL 8.4.5 与本地测试 8.0.36 不同，CI 配置隔离 8.4.5；CI 的实际结果须查看推送后的 workflow，不能用本地结果代替。

## 运维与兼容性限制

- Android 15+ dataSync 前台服务最长约 6h/24h，Doze/省电/强制停止可延迟通知；不保证全天即时。
- 系统限制可能让非默认短信应用无法读取部分敏感验证码；权限授予不是万能保证。
- 未知双卡来源先保留，必须补齐用户确认映射；唯一 SIM 兜底须用户明确确认本机单卡。
- Android 私有缓存、MySQL 和备份包含短信明文；传输 HTTPS，凭据 Keystore，但非 E2EE/SQL 字段加密。
- Web 离线仅提供应用壳，不缓存短信正文；Android 可离线查看已补齐历史。
- 异常超长/不合法短信在本地保留，不阻塞其他消息；首版没有异常队列编辑/导出 UI。
- 数据库回档后需要按部署文档重新配对/重建客户端缓存；首版无自动 epoch 回档协议。
- 单个中枢进程；多副本通知 worker、MMS/RCS、发送短信、多租户、海量搜索压测不在首版范围。

## 本机构建环境

根盘和 /tmp 已满，未清理其他任务。构建在工作区外独立共享缓存进行：
`/home/lvziw/项目/.build-cache/hongshu/`；MySQL 隔离实例与 Gradle 可变缓存使用 `/dev/shm/hongshu-*`。
CIFS 不支持可靠的 MySQL 文件锁，Gradle 原子缓存写也产生问题，因此这两类运行状态移到本地临时缓存。
生产必须用本地 Linux 数据盘；源码目录没有构建产物、凭据、短信或备份。
完整日志保留在共享缓存，不提交到仓库（避免混入运行时数据）。
最终 APK、Go 工具和 Web 资源包保留在共享缓存的 `artifacts/`，附 `SHA256SUMS`；debug 签名仅供测试，非正式发布产物。
