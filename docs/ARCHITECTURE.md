# 鸿枢首版架构

单用户中枢、Go HTTP/WebSocket 服务、MySQL 8.0+、原生 Web/PWA、Kotlin Compose Android。
所有已授权设备能读全部短信，管理员能配对/撤销设备；不实现多租户、发送短信、MMS/RCS。

## 可靠性与业务标识

- 接收号码由用户确认，SIM 的系统 subscription ID 仅是本设备映射，不是跨设备标识。
- 会话按发件人分组，接收号码/来源设备是过滤维度；可选联系人名称按号码共享。
- Android 广播将多段 PDU 合并，先事务写 SQLite outbox，再由 WorkManager 上传。
  未配置 SIM 号码时先保留，配置后补传；HTTP 提交确认之前不删除 outbox。
- 历史导入标记 historical，只更新历史与实时会话视图，不触发跨设备新短信通知，避免导入通知风暴。
- 去重指纹为 SHA256(接收号码 NUL 发件人 NUL 毫秒时间 NUL 正文)，唯一键包含来源设备。
  相同来源的历史导入和广播可去重，不错误地合并不同设备收到的同一短信。
- 服务端事务锁定 singleton sync_clock，分配提交顺序 ID、存储短信、创建推送任务后提交。
  不能使用普通 AUTO_INCREMENT 作为可靠游标（并发事务提交顺序可能与 ID 不一致）。
- `/sync?after=N` 升序分页；客户端必须将本地消息与游标在同一事务保存。无历史自动清理。
- 同步 ID 只决定提交顺序；会话/对话按短信时间与 ID 排序，历史导入不能盖过真正的新短信。
- WebSocket 只发无正文的更新提示，连接/重连及周期轮询均执行增量补齐。
- Push 的独立持久队列按订阅重试；失败不能回滚短信。过期订阅 404/410 删除，不删除短信。
  推送默认仅显示“有新短信”；来源设备不创建自己的推送任务、不收到自己的 WS 提示。
- 备份使用一致性逻辑备份，包含授权、消息、游标、队列；恢复必须同时保留部署密钥。

## 身份与安全

首次 bootstrap 需要部署者生成的随机 secret，只能创建一个管理员 Web 身份。
管理员生成高熵、10 分钟过期、一次性配对码；可明确勾选授予新身份管理员权以建立备用管理员。
每设备独立 256-bit bearer，数据库只存 SHA256；最后管理员禁止撤销/退出。
Web 身份使用 HttpOnly / SameSite=Strict cookie，生产 HTTPS 时 Secure；不将凭据放 localStorage。
Android 使用 Keystore AES-GCM 保存凭据。撤销身份阻止后续 HTTP/WS/Push。
Android 禁用应用备份，并通过 Android 12+ dataExtractionRules 明确排除云备份和设备迁移的全部应用数据；厂商迁移行为仍需真机验证。
浏览器写请求检查 Origin；WS 检查 Origin；不提供跨域访问。部署公网必须配置 HTTPS PUBLIC_URL。
推送服务只能访问公开 HTTPS 地址，解析与拨号均阻止私网/回环，防止订阅接口成为 SSRF 通道。
不记录请求正文、Authorization、Cookie、配对码、短信、号码或 Push endpoint。
数据库/备份具有全部短信明文，部署者必须保护磁盘、备份和网络；首版不是端到端加密。

## Android 系统边界

RECEIVE_SMS 与 READ_SMS 独立申请；导入另需 READ_SMS；通讯录仅可选 READ_CONTACTS。
SMS_RECEIVED 广播与通知链路独立，普通查看设备无需短信权限。
WorkManager 使用网络约束和指数退避，应用打开时也触发上传/补齐。
可选用户主动启动的 dataSync 前台服务维护实时连接，明确常驻通知，接受系统时限。
Android 15+ dataSync 前台服务有时长限制，不从 BOOT_COMPLETED 强行启动前台服务。
关闭实时服务仍有周期补齐（系统调度不保证准点）。用户强制停止后必须重新打开应用。
不申请无障碍，不假装默认短信应用，不绕过验证码/受限权限政策。

兼容性调研及未覆盖场景见 ANDROID_COMPATIBILITY.md；接口见 API.md。
