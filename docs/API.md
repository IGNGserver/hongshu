# HTTP API v1

JSON UTF-8；时间统一 Unix 毫秒；分页 limit 1..200，默认 100。
认证为统一中枢密码建立会话，支持 HttpOnly Cookie 与 `Authorization: Bearer <token>`。Web 无用户名；所有设备统一使用部署配置的中枢密码认证。
错误为 `{"error":"稳定错误码"}`，不返回数据库/短信内容；登录密码错误返回 401，设备凭据失效时重新使用中枢密码登录。
认证查询的数据库故障返回 `503 database_unavailable`，不表示撤销；客户端保留现有 token 并退避。
登录密码按不透明 UTF-8 字符串传输，不 trim；全局密码校验繁忙返回 `503 authentication_busy`。

| 方法路径 | 语义 |
| --- | --- |
| GET /healthz | 数据库可用性和脱敏 Push 指标：enabled、claim_failures、state_failures、delivery_failures、last_success（毫秒，0 表示尚无成功）；DB 不可用为 503。200 不代表 Push 端到端健康 |
| POST /api/login | `{password, name?, kind?}`，校验中枢密码并创建设备会话；kind=web 或 android；设置 Cookie 并返回 `{device, token}`；弱密码允许，空密码不允许 |
| GET /api/me | 本设备、设置、公钥、当前 sequence、数据库 epoch |
| POST /api/logout | 原子撤销本浏览器身份后清除 cookie；保留历史；即使退出最后一个管理员，也可用中枢密码重新登录 |
| GET /api/devices | 设备清单，不含 token |
| PATCH /api/devices/{id} | 本设备或管理员修改 name/upload/notify；Android 连接后的保存改名复用现有身份；admin 不可通过此接口提权 |
| DELETE /api/devices/{id} | 管理员撤销，保留消息；中枢密码可重新创建管理员 Web 身份 |
| GET/PUT /api/sims | 查询；`{phone,label,subscription_id}` 注册本设备确认的接收号码 |
| GET/PUT /api/contacts | 查询号码名称映射；`{phone,name}` 或 `{contacts:[{phone,name}]}` 批量同步，空 name 删除名称 |
| POST /api/messages | Android 上传，`{messages:[{receiver,sender,body,timestamp,subscription_id,historical:false}]}`，最多 100 条；按请求顺序返回等长 `{id,duplicate,error?,fingerprint}`。fingerprint 为既有幂等指纹（receiver、sender、timestamp、body 以 NUL 分隔的 UTF-8 SHA-256），每条成功/失败均回传，旧客户端可忽略新增字段。格式错误整批拒绝；已存在的相同 fingerprint 优先返回 duplicate，即使当前 SIM 注册后来丢失；其他未确认接收号码只标记该条 `sim_not_confirmed`，同批已确认短信仍提交。客户端先验证整组 ACK，再按指纹关联；旧中枢无此字段时按请求顺序关联，不能检测重排。异常响应保留队列；整批 invalid_message 可二分隔离，单条失败只阻塞该条。历史导入设 historical=true，不生成新短信通知 |
| GET /api/sync?after=N&limit=L | `{messages, cursor, more, epoch}`，按 ID 升序；不得跨过未处理页面。epoch 与客户端已记录的值不同表示数据库已回档，必须丢弃已同步缓存并从 0 重拉；未上传队列保留。`after` 大于当前 sequence 返回 `epoch_changed`，旧客户端不得把空页当成已经同步完成 |
| GET /api/conversations?q=&sim=&device=&offset=&limit= | 会话列表，按发件人加接收号码分组、最新消息降序；搜索发件人/名称/正文；offset 分页 |
| GET /api/messages?sender=&q=&sim=&device=&before=&limit= | 按短信时间、ID 降序；before 指向上一页最早消息的 ID，服务端按该消息的时间/ID 二元游标分页 |
| GET /api/ws | 同源 cookie 或 bearer WS，`{type:"changed",cursor:N}`；无重放保证，必须补齐 |
| PUT/DELETE /api/push | Web 订阅 `{endpoint,keys:{p256dh,auth}}` / 注销本设备订阅 |
| GET/PATCH /api/settings | 管理员可修改 `{title}`，不允许远程修改凭据/DB/HTTPS |

请求体最多 1 MiB；不接受未知 JSON 字段；正文最多 64000 UTF-8 bytes，时间限制在 Unix epoch 至未来 24h；客户端按实际 JSON 字节数拆批。
号码不能包含 NUL，接收号码采用用户确认的号码（建议 E.164，服务端校验 + 和 3..20 位数字）。Android 保存自动模式即授权持续使用系统读取的有效号码；每次变化仍先注册 /sims 再上传，不猜测无法读取的号码。
token 和密码没有放在 URL 的接口。登录失败按来源地址限速。
登录请求必须携带与 PUBLIC_URL 相同的 Origin；Android 原生客户端也显式发送该头，不依赖浏览器自动添加。
显式 TRUSTED_PROXY_CIDRS 配置后才接受代理覆盖的单个 X-Forwarded-For 地址；客户端直连提交的转发头不可信。
HTTPS PUBLIC_URL、直接 TLS 或已通过 Origin 校验的 HTTPS 登录强制 Secure cookie。
Android 每页提交消息、epoch、cursor、初次补齐和待通知状态；Web 无持久缓存，从 /me 当前 head 观察变化，
按需请求会话/历史，不请求 /sync 丢弃正文，因此不再有 /sync 的 409 重试死循环。
## 短信分析扩展

设计与隐私边界见 [MESSAGE_GROUPING.md](MESSAGE_GROUPING.md)。所有接口沿用现有认证。

- `GET /api/analysis/settings`：开关、暂停状态、队列计数、修订号；管理员额外读取非敏感配置及 key_set。
- `PUT /api/analysis/settings`：仅管理员；完整配置 enabled、paused、endpoint、model、timeout_seconds、requests_per_minute、daily_limit、redact；api_key 留空保留，clear_key 显式清除。启用需 consent=true。
- `POST /api/analysis/test`：仅管理员，以固定合成文本测试已保存配置，不发送短信。
- `POST /api/analysis/retry`：管理员恢复全局失败任务；单条使用消息分类接口 retry。
- `PATCH /api/messages/{id}/category`：授权设备设置 category=personal/notification/promotion/spam；category=auto 恢复自动分类；category=retry 重试失败项。
- `GET /api/analysis?after=N&limit=200`：独立元数据增量，返回 items、cursor、more、epoch；游标超过修订头返回 409 analysis_cursor_changed。客户端将结果和游标同事务保存。
- `GET /api/messages`、`GET /api/conversations` 新增 group=otp/other/personal/notification/promotion/spam/pending；空值或 all 为全部。AI 关闭时仅允许 otp/other。
- `GET /api/analysis/counts`：接受 q/sim/device/sender 筛选，返回消息数 counts（不是会话数）。
- `/api/me` 新增 ai_enabled、analysis_revision。消息新增 analysis 对象（otp、expires_at、category、manual、state、error、revision）；原消息 ID 与 /sync 不因分类更新而改变。
