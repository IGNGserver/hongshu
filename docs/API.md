# HTTP API v1

JSON UTF-8；时间统一 Unix 毫秒；分页 limit 1..200，默认 100。
认证为统一中枢密码建立会话，支持 HttpOnly Cookie 与 `Authorization: Bearer <token>`。Web 无用户名；所有设备统一使用部署配置的中枢密码认证。
错误为 `{"error":"稳定错误码"}`，不返回数据库/短信内容；登录密码错误返回 401，设备凭据失效时重新使用中枢密码登录。
认证查询的数据库故障返回 `503 database_unavailable`，不表示撤销；客户端保留现有 token 并退避。
登录密码按不透明 UTF-8 字符串传输，不 trim；全局密码校验繁忙返回 `503 authentication_busy`。

| 方法路径 | 语义 |
| --- | --- |
| GET /healthz | 数据库可用性和脱敏 Push 指标：enabled、claim_failures、state_failures、delivery_failures、suppressed_read（因已读被抑制）、dropped（重试耗尽被丢弃）、last_success（毫秒，0 表示尚无成功）；DB 不可用为 503。200 不代表 Push 端到端健康 |
| POST /api/login | `{password, name?, kind?}`，校验中枢密码并创建设备会话；kind=web 或 android；设置 Cookie 并返回 `{device, token}`；弱密码允许，空密码不允许 |
| GET /api/me | 本设备、设置、公钥、`push_subscribed`、当前 sequence、数据库 epoch、metadata_revision、ai_paused；`features` 宣告 `outgoing_sms`、`metadata_revision`、`recent_snapshot`、`call_log`；`call_cursor` 为通话记录提交头 |
| GET /api/metadata?since=N | 一致性元数据快照；`revision,epoch,unchanged`，有变化时另含 devices/sims/contacts；需 `metadata_revision` 能力 |
| GET /api/recent?limit=50 | 最近会话头消息展示快照：messages/head/epoch；head 不可用于推进可靠同步游标，需 `recent_snapshot` 能力 |
| POST /api/logout | 原子撤销本浏览器身份后清除 cookie；保留历史；即使退出最后一个管理员，也可用中枢密码重新登录 |
| GET /api/devices | 设备清单，不含 token |
| PATCH /api/devices/{id} | 本设备或管理员修改 name/upload/notify；Android 连接后的保存改名复用现有身份；admin 不可通过此接口提权 |
| DELETE /api/devices/{id} | 管理员撤销，保留消息；中枢密码可重新创建管理员 Web 身份 |
| GET/PUT /api/sims | 查询；`{phone,label,subscription_id}` 注册本设备确认的接收号码 |
| GET/PUT /api/contacts | 查询号码名称映射；`{phone,name}` 或 `{contacts:[{phone,name}]}` 批量同步，空 name 删除名称 |
| POST /api/messages | Android 上传，`{messages:[{receiver,sender,body,timestamp,subscription_id,historical:false}]}`，最多 100 条；按请求顺序返回等长 `{id,duplicate,error?,fingerprint}`。fingerprint 为既有幂等指纹（receiver、sender、timestamp、body 以 NUL 分隔的 UTF-8 SHA-256），每条成功/失败均回传，旧客户端可忽略新增字段。格式非法的消息只拒绝该条 `invalid_message`，同批有效短信仍提交；已存在的相同 fingerprint 优先返回 duplicate，即使当前 SIM 注册后来丢失；其他未确认接收号码只标记该条 `sim_not_confirmed`，同批已确认短信仍提交。客户端先验证整组 ACK，再按指纹关联；旧中枢无此字段时按请求顺序关联，不能检测重排。异常响应保留队列；旧中枢整批 invalid_message 可二分隔离，单条失败只阻塞该条。历史导入设 historical=true，不生成新短信通知。可选 `direction`：省略或 `"in"` 为本机 SIM 收到的短信，`"out"` 为本机发出的短信，其他值按该条 `invalid_message` 拒绝；见下文“收发方向” |
| POST /api/messages/read | `{sender?, message_id?, all?}` 标记已读；all=true 推进全局已读水位线为最新消息；指定 sender 则推进该会话已读游标。显式正数 message_id 必须是该 sender 已提交的消息，否则返回 400；负数无效，省略或 0 保留“当前整个会话已读”的兼容语义，自动标读客户端必须提交实际展示的 ID。返回 `{ok, global_watermark_id, unread_count}` |
| GET /api/read-state?epoch=&since=&head=&limit= | 授权设备共享已读状态，返回 `{items:[{sender,message_id}],global_watermark_id,revision,head,epoch,more,next}`。首请求可带已同步的 since 修订、head 及 epoch，修订和消息头均相同则返回空 items；后续页用 `after=next&revision=首屏修订&head=首屏消息头&epoch=首屏代次`，不可同时使用 since。每页最多 200 条，修订或消息头变化返回 409 read_state_changed，epoch 不匹配返回 409 epoch_changed，409 后从首屏重试。客户端逐页合并，只有完整读取后才保存修订与消息头，不把下行合并再次加入上报队列；旧非法水位仅下发 0，部署者仍须显式修复存量异常 |
| GET /api/sync?after=N&limit=L | `{messages, cursor, more, epoch}`，按 ID 升序；不得跨过未处理页面。epoch 与客户端已记录的值不同表示数据库已回档，必须丢弃已同步缓存并从 0 重拉；未上传队列保留。`after` 大于当前 sequence 返回 `epoch_changed`，旧客户端不得把空页当成已经同步完成 |
| GET /api/conversations?q=&sim=&device=&offset=&limit= | 会话列表，按发件人分组、每个发件人只返回最新消息并按时间降序；搜索发件人/名称/正文；offset 分页。返回消息中的 receiver 仅表示该会话最新消息的接收号码 |
| GET /api/messages?sender=&q=&sim=&device=&before=&limit= | 按短信时间、ID 降序；before 指向上一页最早消息的 ID，服务端按该消息的时间/ID 二元游标分页 |
| GET /api/ws | 同源 cookie 或 bearer WS，`{type:"changed",cursor:N,call_cursor:M}`；无重放保证，必须补齐 |
| POST /api/calls | Android 上传通话记录，见下文“通话记录” |
| GET /api/calls?before=&limit= | 通话记录列表，按开始时间、ID 降序 |
| GET /api/calls/{id} | 单条通话记录详情 |
| GET /api/calls/sync?after=N&limit=L | 通话记录可靠增量，按 ID 升序 |
| PUT/DELETE /api/push | Web 订阅 `{endpoint,keys:{p256dh,auth}}` / 注销本设备订阅 |
| POST /api/push/test | 仅向当前设备已有的订阅直接发送一条通用测试通知，不写入短信或 Push 队列；未配置 VAPID 返回 503 `push_not_configured`，未订阅返回 404 `push_not_subscribed`，`notify=false` 返回 409 `push_disabled`，推送服务拒绝返回 502 `push_delivery_failed`；404/410 会删除该设备的订阅与积压任务并返回 410 `push_subscription_expired` |
| GET/PATCH /api/settings | 管理员可修改 `{title}`，不允许远程修改凭据/DB/HTTPS |

### Web 会话与刷新行为

- 管理员可在设置中查看 Android/Web 身份并撤销指定设备；撤销当前身份等同退出本浏览器。
- Web 自动标读仅在收件箱可见、当前会话已展示响应时提交该响应中最大的 `message_id`，
  不用服务器执行时的最新 ID 替代展示边界；失败保留未读状态供重试。
- 已读水位单调推进。Web 比较 `/me.unread_count` 变化刷新会话未读数；新增消息同时由
  cursor 检测，数据库恢复由 epoch 检测，不引入独立已读修订号。
- 会话和消息缓存只在查询身份（group/q/sim/device，详情另含 sender）一致时合并。
  短信 cursor 或未读数变化重验已加载会话页，分析或相关元数据变化另重验详情页；
  刷新失败保留待刷新/重验标记，下一轮继续补齐。
- 浏览器 Push 订阅与设备身份分别存在。重新登录后须将现有订阅重新绑定当前身份，
  绑定成功才显示通知已开启；通知操作的外部异步等待不能跨越退出/重新登录代次。
- iOS/iPadOS 16.4+ 的 Web Push 只能由“添加到主屏幕”的 Web App 订阅；Safari 标签页不提供后台推送。
  Web 端据此显示安装引导。订阅因 VAPID 密钥变化、浏览器轮换或服务端清理而失效时，客户端应重新订阅并绑定；
  `push_subscribed` 只表示当前身份在服务端是否有订阅，不表示推送服务端到端健康。

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

- `GET /api/analysis/settings`：开关、暂停状态、队列计数、修订号；管理员额外读取非敏感配置及 key_set。队列计数包含验证码；旧规则已完成但尚未 AI 复核的验证码行计为等待。
- `PUT /api/analysis/settings`：仅管理员；完整配置 enabled、paused、protocol、endpoint、model、timeout_seconds、requests_per_minute、daily_limit、redact；protocol 支持 `openai_responses`、`openai_chat_completions`、`anthropic_messages`、`gemini_generate_content`；api_key 留空保留，clear_key 显式清除。启用需 consent=true；同意范围包含验证码正文。`redact=true` 仍遮蔽链接与邮箱，但规则命中验证码或正文含强验证码关键词的短信不遮蔽数字，以便提取验证码。
- `POST /api/analysis/test`：仅管理员，以固定合成文本测试已保存配置，不发送短信。
- `POST /api/analysis/retry`：管理员恢复全局失败任务；单条使用消息分类接口 retry。
- `PATCH /api/messages/{id}/category`：授权设备设置 category=otp/personal/notification/promotion/spam；category=auto 恢复自动分类；category=retry 重试失败项。验证码消息也可人工改分类：改成非 otp 时服务端保留规则提取值但不再对外返回，改回 otp 可恢复展示。
- `GET /api/analysis?after=N&limit=200`：独立元数据增量，返回 items、cursor、more、epoch；游标超过修订头返回 409 analysis_cursor_changed。客户端将结果和游标同事务保存。
- `GET /api/messages`、`GET /api/conversations` 新增 group=otp/other/personal/notification/promotion/spam/pending；空值或 all 为全部。AI 关闭时仅允许 otp/other；AI 开启且消息分析完成时按 AI category 分组（otp 即验证码组），未完成/失败时回退规则验证码，否则 pending。`GET /api/conversations` 返回项包含 `unread_count` 会话未读数。
- `GET /api/analysis/counts`：接受 q/sim/device/sender 筛选，返回消息数 counts（不是会话数）。
- `/api/me` 新增 ai_enabled、analysis_revision、global_watermark_id、unread_count。消息新增 analysis 对象（otp、expires_at、category、manual、state、error、revision）；`analysis.otp` 是当前应展示的验证码，AI 最终分类不是 otp 时为空；原消息 ID 与 /sync 不因分类更新而改变。

## 收发方向（outgoing_sms）

- 消息新增 `direction`：`"in"` 表示上传手机的 SIM 收到，`"out"` 表示从该手机发出。两种方向下
  `sender` 都是**对端号码**（会话键），`receiver` 都是**本机线路号码**（须先经 `/api/sims` 注册），
  因此同一对端的收发短信属于同一会话，`?sender=` 筛选与已读游标同时覆盖两个方向。
- 指纹：`"in"` 保持既有算法不变（已排队的旧消息仍能去重）；`"out"` 在既有四段之后追加 `\x00out`
  再做 SHA-256，所以同号码、同时间、同正文的收发两条不会互相去重。跨端向量：
  receiver `+8613800000000`、sender `10086`、timestamp `1700000000000`、body `测试`、direction `out`
  → `d56c4a02e82f207e4a4436d1c2ef37c27e942d3883e7232b770334b13bff0adc`。
- 发出的短信不计入 `unread_count` / 会话 `unread_count`，不生成 Web Push 任务，不提取验证码，
  也不进入 AI 分类队列（analysis 直接为 `state=complete`、`otp=""`）。它仍推进 sync 游标并触发实时变更通知。
- 收到的验证码短信与其它入站消息一样进入持久 AI 队列；AI 返回 `category` 与 `otp` 两个字段，
  `category=otp` 表示验证码。AI 完成前 `analysis.otp` 为规则结果，用户可立即复制；AI 完成且分类不是 otp 时
  `analysis.otp` 返回空，原始规则值仍保留在派生表，改回 otp 或在关闭 AI 后重新查看即可恢复。
- 能力协商：Android 只在 `/api/me` 的 `features` 含 `outgoing_sms` 时上传 `direction:"out"`。
  旧中枢会忽略未知字段，把发出的短信当作“从收件人收到的短信”保存，所以不能无条件上传。
- 旧客户端读取新中枢时看不到 `direction`，会把发出的短信显示为收到的短信；Web 与 Android 需随中枢一起升级。
- 回滚迁移 011 会**删除**所有 `direction='out'` 的消息（而不是改成收到），手机端仍保留系统短信库中的原件，
  重新升级后可再次上传。

## 通话记录（call_log）

只同步、只读：中枢保存手机系统通话记录的条目，供各端查看列表与详情。不拨号、不回拨、
不产生来电通知/Web Push、不计未读、不进 AI 队列；手机上删除记录不会同步删除，中枢无自动清理。

- 通话是与短信独立的数据流：独立的 `calls` 表和单例 `call_clock` 提交时钟（迁移 013）。
  `sync_clock.seq`、`/me.cursor`、已读水位与 `/api/sync` 都只属于短信，上传通话不改变它们；
  数据库代次共用 `sync_clock.epoch`，回档后客户端同时重置短信与通话缓存。
- 字段：`number` 对端号码（隐藏号码时为空）、`receiver` 本机线路号码（无法识别线路时为空）、
  `type` 为 `incoming`/`outgoing`/`missed`/`rejected`/`blocked`/`voicemail`/`answered_externally`/`unknown`、
  `timestamp` 通话开始时间（毫秒）、`duration` 通话秒数（0..2592000）、`subscription_id`（本机映射，未知为 -1）、
  `video`、`presentation` 为 `allowed`/`restricted`/`unknown`/`payphone`（省略视为 `allowed`）、
  `location` 系统提供的号码归属地（最多 100 字符，可为空）、`historical` 是否为开启同步前的旧记录。
  返回项另含 `id`、`device_id`（来源设备）与 `contact`（中枢别名，按 `number` 匹配，隐藏号码不匹配）。
- `POST /api/calls`：`{calls:[...]}` 最多 100 条，与短信上传相同的权限（`kind=android` 且 `upload=true`）。
  按请求顺序返回等长 `{acks:[{id,duplicate,error?,fingerprint}],cursor}`。格式非法只拒绝该条
  `invalid_call`；`receiver` 非空但不是本设备已注册的号码只拒绝该条 `sim_not_confirmed`；
  `receiver` 为空表示线路未知，照常入库并在详情显示“未知线路”。`presentation=allowed` 时 `number` 必须非空。
- 指纹（同设备幂等）：`SHA-256("call" NUL number NUL timestamp NUL type NUL duration)`，不含线路，
  以免手机之后才确认 SIM 时生成第二条。跨端向量：number `13900000000`、timestamp `1700000000000`、
  type `incoming`、duration `75` → `bf4ef9880762b3b21e9f1cf296615e8af7492beea5aaa2020f18afceb543ae97`。
- `GET /api/calls?before=&limit=`：返回 `{calls,more,head,epoch}`，按 `timestamp`、`id` 降序；
  `before` 是上一页最后一条的 ID（不存在返回 400 `invalid_cursor`）；`head` 是同一快照下的 `call_clock`。
- `GET /api/calls/{id}`：`{call}`；不存在返回 404 `not_found`。
- `GET /api/calls/sync?after=N&limit=L`：`{calls,cursor,more,epoch}`，语义同 `/api/sync`；
  `after` 大于当前通话提交头返回 409 `epoch_changed`。客户端将本地通话与游标同事务保存。
- 能力协商：Android 只在 `/api/me` 的 `features` 含 `call_log` 时上传通话；旧中枢没有该接口，
  通话记录留在本机队列。查看端同样只在有该能力时显示“通话”入口。
- 回滚迁移 013 会**删除**全部已上传的通话记录；手机系统通话记录仍在。
