# 通话记录同步

范围：把 Android 手机系统通话记录中的条目（何时、与谁、呼入/呼出/未接等、时长、线路）同步到中枢，
各端可以浏览列表并点开单条查看详情。**只同步、只读**：不拨号、不回拨、不产生来电通知或 Web Push、
不计未读、不做 AI 分类；手机上删除的记录不会从中枢删除。接口契约见 [API.md](API.md)“通话记录”。

## 中枢

- 迁移 013 新增 `calls` 表与单例 `call_clock`。通话的提交顺序 ID 由 `call_clock` 在上传事务内
  `FOR UPDATE` 分配，与短信的 `sync_clock.seq` 完全分开：短信游标、已读水位、会话摘要与
  `/api/sync` 不受通话影响。数据库回档代次共用 `sync_clock.epoch`；逻辑备份按整库导出，自动包含新表。
- 上传权限与短信相同（Android、`upload=true`、未撤销）。锁序为 `call_clock → devices`，
  短信为 `sync_clock → devices`，两条路径不互相等待对方的时钟，不形成环。
- 线路：`receiver` 为空表示系统没有告诉我们是哪张卡，仍然入库；非空则必须是该设备已注册的号码。
- 去重指纹不含线路，详见 API.md。撤销设备保留其通话记录。
- WebSocket 的 `changed` 提示增加 `call_cursor`，仍只读单例行，不扫描 `calls`。

## Android 采集（需 READ_CALL_LOG）

- 设置 → 采集方式 → “同步通话记录”，**默认关闭**；开启时才申请 `READ_CALL_LOG`，拒绝不影响短信。
  关闭后停止读取与上传，已排队的记录保留到再次开启。
- 来源只有 `CallLog.Calls` provider（系统不向普通应用广播通话结束事件）。`ContentObserver`
  监听通话记录变化后请求一次采集，周期维护任务与每次同步兜底。
- 按 provider `_ID` 升序分页扫描，独立游标 `call_provider_id`；游标头小于已记录值视为 provider 重建，
  从头重扫，本机指纹账本与中枢幂等保证不重复。首次开启时：“补齐历史短信”开启则补齐整个通话记录并把
  开启前的条目标记为 `historical`，关闭则只采集开启之后的新记录。
- 类型映射：1 incoming、2 outgoing、3 missed、4 voicemail、5 rejected、6 blocked、
  7 answered_externally，其他厂商值记为 unknown。`NUMBER_PRESENTATION` 映射为 presentation；
  `FEATURES` 含视频位记为 video；`GEOCODED_LOCATION` 作为 location。
- 线路：API 30+ 在已有读取手机状态权限（SIM 设置所用）时由系统接口 `TelephonyManager.getSubscriptionId(PhoneAccountHandle)` 把 `PHONE_ACCOUNT_ID`
  解析为订阅 ID；API 26–29 仅当 `PHONE_ACCOUNT_ID` 本身是整数时按订阅 ID 使用（ICCID 等其他写法视为未知）。
  再按本机已确认的 SIM 映射得到号码（与短信相同，保存唯一活动 SIM 时可兜底）；无法确认时 `receiver` 为空，不猜卡槽。
- 本机队列 `call_outbox` 以指纹为键，HTTP 提交确认（含 duplicate）后才删除；`call_ledger` 记住所有入过队的指纹，
  重扫（历史开关变化、provider 重建）不会重复上传。`sim_not_confirmed` 时把该条改为未知线路重传（指纹不含线路，
  仍是同一条）；`invalid_call` 标记为阻塞，不反复重试。上传失败只在设置里显示并在下次同步重试，不让短信同步退避。
  只有 `/me.features` 含 `call_log` 时才上传，否则留在队列并提示“当前中枢版本还不支持通话记录”。
  换到**另一个**中枢时清空通话队列与账本（不把为旧中枢采集的记录转给新中枢），新中枢开启上传后从系统通话记录重新读取；
  同一中枢重新登录保留队列与账本。诊断与日志不记录号码。

## 查看端

- Web/PWA 与 Android 首页顶部增加“短信 / 通话”切换，仅当中枢宣告 `call_log` 时显示。
- 通话列表按开始时间倒序、按天分组；每行显示对端（本机通讯录姓名 > 中枢别名 > 号码 > “未知号码”）、
  类型图标（未接/拒接为错误色）、时长或“未接通”、时间。点击进入详情。
- 详情显示：对端名称与号码、类型、是否视频、开始时间（完整日期时间）、时长、本机线路
  （SIM 标签与号码，未识别为“未知线路”）、来源设备、归属地（有则显示）、历史导入标记。
  Web 显示来源设备名称；Android 不缓存其他设备名称，显示“本机”（或本机名称）/“另一台手机”。
- Web 无持久缓存，按 `/me.call_cursor` 与 WS 提示分页刷新；Android 通过 `/api/calls/sync` 增量写入本地
  `calls` 表（本地库 v17），每次同步在短信之后最多拉 5 页；页面的 epoch 与本机记录不同则不写入，
  `epoch_changed` 时清空通话缓存从头拉；数据库回档（`resetSyncedCache`）与更换身份时和短信缓存一起清空。
  中枢别名按号码在读取时关联（`contact_snapshot`），本机通讯录姓名与照片在界面层查找，不写入缓存。
- Android 导航：通话详情是独立目的地 `AppDestination.Call(id)`；单栏推入新页面、双栏在右侧详情舱显示，
  切换“短信 / 通话”时回到该视图的列表。
