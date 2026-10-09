# 鸿枢 · Hongshu

面向多部 Android 手机用户的自部署短信互通平台：短信可靠上传到自己的 MySQL 中枢，长期保存，
在所有授权 Android 和 Web/PWA 设备上查看。无需 Firebase/FCM。

当前预发布版本见根目录 `VERSION`。实际测试结果和未完成验收见 [验证记录](docs/VALIDATION.md)，不要将构建通过等同于真机兼容性认证。

## 实现范围

- **Android**：Kotlin、Compose、Material 3 Expressive，会话、对话、搜索、SIM 筛选；
  原生 SMS 广播、多段合并、双 SIM 映射、持久 outbox、失败重试、历史导入、可选联系人名称同步。
  上传和通知独立开关；普通查看设备无需短信权限。
- **中枢**：Go + MySQL，独立设备凭据、一次性配对、备用管理员、设备撤销；
  事务幂等入库、提交确认、提交顺序游标、增量补齐、独立持久 Push 队列。
- **Web/PWA**：桌面/手机自适应收件箱、分页历史、搜索、设备/SIM 筛选、设备管理、中枢设置、
  WebSocket 实时提示、标准 Web Push + VAPID、可安装应用壳；不离线持久保存短信正文。
- **运维**：Docker Compose 中枢/数据库/每日一致性备份、审计迁移、HTTPS 反向代理和恢复指南。

短信不会自动清理；验证码/正文不会出现在默认通知中；来源手机不重复接收自己的消息通知。
首版只收件/查看，不发送短信，不处理 MMS/RCS，不提供端到端加密。
Android 系统对验证码、后台运行、强制停止和厂商省电的限制不能绕过。

## 部署

需要 Docker/Compose、HTTPS 域名与反向代理，本地 Linux 数据盘（MySQL 不使用 CIFS）。

```bash
PUBLIC_URL=https://sms.example.com VAPID_SUBJECT=mailto:you@example.com bash scripts/init-deployment.sh
docker compose up -d
curl --fail http://127.0.0.1:8080/healthz
```

完整说明：[部署、备份与恢复](docs/DEPLOYMENT.md)。首次 Web 初始化使用受保护 `.env`
中的 BOOTSTRAP_SECRET，然后在设备页创建配对码。建议配对备用管理员浏览器。
Android 构建/使用：[android/README.md](android/README.md)。本轮不发布 APK Release 或代装。

## 结构与协议

| 目录 | 内容 |
| --- | --- |
| `android/` | Kotlin Compose 客户端、单元测试、Gradle wrapper |
| `web/` | 原生模块 Web/PWA、Service Worker、测试、无第三方运行时依赖 |
| `server/` | Go HTTP/WS/Push 中枢、MySQL 集成测试、VAPID 生成工具 |
| `db/migrations/` | checksum/dirty 审计的 MySQL 迁移 |
| `scripts/` | 仓库校验、部署初始化、一致性备份、图标生成 |
| `docs/` | 架构、API、安全、兼容性、部署和验证 |

- [架构与故障恢复语义](docs/ARCHITECTURE.md)
- [API](docs/API.md)
- [Android 兼容性调研](docs/ANDROID_COMPATIBILITY.md)
- [验证记录](docs/VALIDATION.md)
- [工作区规范](AGENTS.md) / [贡献指南](CONTRIBUTING.md)
- [版本规则](docs/VERSIONING.md) / [发布边界](docs/RELEASE_POLICY.md)

## 开发与验证

主目录只是 Git 锚点，使用 `wt new 短信同步系统 <task-slug>` 建独立分支/worktree；
不得直接开发 `main`、不得 `git pull`。提交与推送开发分支不等于获准合并或发布。

设置 `GOCACHE/GOTMPDIR`、`GRADLE_USER_HOME`、`HONGSHU_BUILD_DIR`、`WEB_BUILD_DIR`
到工作区外的共享缓存。Go 1.24+、Node 22+、JDK 17、Android SDK 35。

```bash
bash scripts/verify-repository.sh
git diff --check
cd server && go test -race ./... && go vet ./...
# Set TEST_MYSQL_DSN to a disposable local MySQL instance for integration tests.
# The tests create/drop ONLY their own random hongshu_it_* databases.
go build -trimpath -o "$HONGSHU_BUILD_DIR/hongshu" .
cd ../web && npm test && npm run check && npm run build
cd ../android && ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI 包含真实隔离 MySQL 服务、Go race/vet/build、Web test/check/build 和 Android test/lint/APK。
真实 SIM、浏览器后台 Push、公网 HTTPS 与备份恢复必须另外做环境验收。

## 安全与许可

不要提交 `.env`、签名材料、数据库备份、真实短信或凭据；参见 [SECURITY.md](SECURITY.md)。
源码按 [MIT License](LICENSE) 提供。SmsForwarder/SMSGate 仅作为兼容性研究参考，不包含其复制代码。
