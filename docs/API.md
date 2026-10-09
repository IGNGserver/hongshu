# HTTP API v1

JSON UTF-8；时间统一 Unix 毫秒；分页 limit 1..200，默认 100。
认证为 Android `Authorization: Bearer <device-token>` 或 Web HttpOnly cookie。Web 无用户名；部署配置的单一中枢密码用于创建管理员 Web 会话。
错误为 `{"error":"稳定错误码"}`，不返回数据库/短信内容；登录密码错误返回 401，设备凭据失效时 Web 重新登录、Android 重新配对。

| 方法路径 | 语义 |
| --- | --- |
| GET /healthz | 数据库可用性，无敏感配置 |
| POST /api/login | `{password}`，校验中枢密码并创建管理员浏览器 cookie；弱密码允许，空密码不允许 |
| POST /api/pair | `{code,name,kind}`，kind=android，一次性消费配对码并返回设备 token；Web 浏览器使用统一中枢密码登录 |
| GET /api/me | 本设备、设置、公钥、当前 sequence、数据库 epoch |
| POST /api/logout | 原子撤销本浏览器身份后清除 cookie；保留历史；即使退出最后一个管理员，也可用中枢密码重新登录 |
| GET /api/devices | 设备清单，不含 token |
| PATCH /api/devices/{id} | 本设备或管理员修改 name/upload/notify；admin 不可通过此接口提权 |
| DELETE /api/devices/{id} | 管理员撤销，保留消息；中枢密码可重新创建管理员 Web 身份 |
| POST /api/pairings | 管理员生成 Android 一次性配对 code，十分钟有效；不接收 admin 标记，不通过配对码创建 Web 登录身份 |
| GET/PUT /api/sims | 查询；`{phone,label,subscription_id}` 注册本设备确认的接收号码 |
| GET/PUT /api/contacts | 查询号码名称映射；`{phone,name}` 或 `{contacts:[{phone,name}]}` 批量同步，空 name 删除名称 |
| POST /api/messages | Android 上传，`{messages:[{receiver,sender,body,timestamp,subscription_id,historical:false}]}`，最多 100 条；返回等长 `{id,duplicate,error}`。格式错误整批拒绝；未确认接收号码只标记该条 `sim_not_confirmed`，同批已确认短信仍提交。客户端遇到该错误必须保留本地队列，不能当作成功删除。历史导入设 historical=true，不生成新短信通知 |
| GET /api/sync?after=N&limit=L | `{messages, cursor, more, epoch}`，按 ID 升序；不得跨过未处理页面。epoch 与客户端已记录的值不同表示数据库已回档，必须丢弃已同步缓存并从 0 重拉；未上传队列保留。`after` 大于当前 sequence 返回 `epoch_changed`，旧客户端不得把空页当成已经同步完成 |
| GET /api/conversations?q=&sim=&device=&offset=&limit= | 会话列表，按发件人加接收号码分组、最新消息降序；搜索发件人/名称/正文；offset 分页 |
| GET /api/messages?sender=&q=&sim=&device=&before=&limit= | 按短信时间、ID 降序；before 指向上一页最早消息的 ID，服务端按该消息的时间/ID 二元游标分页 |
| GET /api/ws | 同源 cookie 或 bearer WS，`{type:"changed",cursor:N}`；无重放保证，必须补齐 |
| PUT/DELETE /api/push | Web 订阅 `{endpoint,keys:{p256dh,auth}}` / 注销本设备订阅 |
| GET/PATCH /api/settings | 管理员可修改 `{title}`，不允许远程修改凭据/DB/HTTPS |

请求体最多 1 MiB；不接受未知 JSON 字段；正文最多 64000 UTF-8 bytes，时间限制在 Unix epoch 至未来 24h；客户端按实际 JSON 字节数拆批。
号码不能包含 NUL，接收号码采用用户确认的国际号码（建议 E.164，服务端校验 + 和 3..20 位数字）。
token 和密码没有放在 URL 的接口。登录和配对失败按来源地址限速。
登录和配对请求必须携带与 PUBLIC_URL 相同的 Origin；Android 原生客户端也显式发送该头，不依赖浏览器自动添加。
