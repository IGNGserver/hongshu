# HTTP API v1

JSON UTF-8；时间统一 Unix 毫秒；分页 limit 1..200，默认 100。
认证为 Android `Authorization: Bearer <device-token>` 或 Web HttpOnly cookie。
错误为 `{"error":"稳定错误码"}`，不返回数据库/短信内容；401 应停用凭据并重新配对。

| 方法路径 | 语义 |
| --- | --- |
| GET /healthz | 数据库可用性，无敏感配置 |
| POST /api/bootstrap | `{secret,name}`，仅首次，创建管理员浏览器 cookie |
| POST /api/pair | `{code,name,kind}`，kind=android/web，一次性消费配对码；Android 返回 token，Web 仅 cookie |
| GET /api/me | 本设备、设置、公钥、当前 sequence |
| POST /api/logout | 原子撤销本浏览器身份后清除 cookie；保留历史；最后管理员不可退出 |
| GET /api/devices | 设备清单，不含 token |
| PATCH /api/devices/{id} | 本设备或管理员修改 name/upload/notify；admin 不可通过此接口提权 |
| DELETE /api/devices/{id} | 管理员撤销，保留消息；最后管理员不可撤销 |
| POST /api/pairings | 管理员生成一次性 code，十分钟有效；`{admin:true}` 仅授予新的 Web 浏览器管理员权（默认 false），Android 使用普通配对码 |
| GET/PUT /api/sims | 查询；`{phone,label,subscription_id}` 注册本设备确认的接收号码 |
| GET/PUT /api/contacts | 查询号码名称映射；`{phone,name}` 可选名称同步，空 name 删除名称 |
| POST /api/messages | Android 上传，`{messages:[{receiver,sender,body,timestamp,subscription_id,historical:false}]}`，最多 100 条；返回每条 `{id,duplicate}`；整个批次原子提交；历史导入设 historical=true，不生成新短信通知 |
| GET /api/sync?after=N&limit=L | `{messages, cursor, more}`，按 ID 升序；不得跨过未处理页面 |
| GET /api/conversations?q=&sim=&device=&offset=&limit= | 会话列表，按最新消息降序，搜索发件人/名称/正文；offset 分页 |
| GET /api/messages?sender=&q=&sim=&device=&before=&limit= | 按短信时间、ID 降序；before 指向上一页最早消息的 ID，服务端按该消息的时间/ID 二元游标分页 |
| GET /api/ws | 同源 cookie 或 bearer WS，`{type:"changed",cursor:N}`；无重放保证，必须补齐 |
| PUT/DELETE /api/push | Web 订阅 `{endpoint,keys:{p256dh,auth}}` / 注销本设备订阅 |
| GET/PATCH /api/settings | 管理员可修改 `{title}`，不允许远程修改凭据/DB/HTTPS |

请求体最多 1 MiB；不接受未知 JSON 字段；正文最多 64000 UTF-8 bytes，时间限制在 Unix epoch 至未来 24h；客户端按实际 JSON 字节数拆批。
号码不能包含 NUL，接收号码采用用户确认的国际号码（建议 E.164，服务端校验 + 和 3..20 位数字）。
token 没有放在 URL 的接口。首次 setup 和配对失败按来源地址限速。
初始化和配对请求必须携带与 PUBLIC_URL 相同的 Origin；Android 原生客户端也显式发送该头，不依赖浏览器自动添加。
