# Android Store：事务所有权与锁顺序

`Store.kt` 有 2700 余行、18 张以上的表，来自短信采集、上传队列、已读回执、收件箱投影、
通知、联系人和通话记录七个领域。审查报告（R1）建议按事务所有权拆分，但前提是先把
"哪张表属于谁""哪些写入必须同事务""锁的顺序"写下来——否则拆分只是把一个大文件切成
几个互相偷偷写对方表的小文件。

本文件是决策记录；[`StoreTableOwnershipTest`](../android/app/src/test/java/io/hongshu/app/StoreTableOwnershipTest.kt)
是机器可校验的副本：新增表未登记、登记了不存在的表、或某个领域没有任何表，测试都会失败。

## 表所有权

| 表 | 领域 | 说明 |
| --- | --- | --- |
| `broadcast_inbox` | 采集 | 广播收到的原始短信，必须在 `onReceive` 返回前落盘 |
| `captures` | 采集 | 采集证据与去重 |
| `capture_events` | 采集 | 采集失败/忽略原因的有界计数 |
| `incomplete_provider` | 采集 | 多段短信未收齐的暂存 |
| `outbox` | 上传 | 待上传队列，含不可变 `sent_fingerprint` |
| `upload_fingerprints` | 上传 | 已发送指纹，条件 ACK 的依据 |
| `incomplete_sent` | 上传 | 发出短信未确认的暂存 |
| `read_outbox` | 已读 | 「全部已读」等持久上行目标 |
| `conversation_reads` | 已读 | 会话级已读游标 |
| `inbox` | 投影 | UI 渲染用的消息投影 |
| `preview_messages` | 投影 | 会话列表预览 |
| `message_analysis` | 投影 | 中枢下发的分类结果 |
| `local_conversation_heads` | 投影 | 会话头，按时间/ID 排序 |
| `notification_receipts` | 通知 | 已展示通知，防重复提醒 |
| `otp_intents` | 通知 | 待复制的验证码意图 |
| `contact_snapshot` | 联系人 | 本地解析出的名称快照 |
| `calls` / `call_outbox` / `call_ledger` | 通话 | 定义在 `CallLog.kt` / `CallView.kt`，不在 `Store.kt`，最容易漏 |
| `sims` | 共享 | 中枢注册的设备号码镜像 |
| `meta` | 共享 | 键值设置与各类游标，**按键前缀归属不同领域** |

`meta` 是唯一真正共享的表：采集、上传、已读、同步四个领域各写自己的键。拆分时不能把整张
表交给单一仓储，也不能让各仓储绕过同一把锁去写。

## 必须同事务的写入对

这些成对写入如果拆成两次事务，中途崩溃会留下可观察的不一致：

| 场景 | 必须原子的写入 |
| --- | --- |
| 广播入库 | `broadcast_inbox` 插入 + 采集游标推进 |
| 进入上传队列 | `outbox` 插入 + 采集侧去重状态 |
| 上传确认 | `outbox` 删除（按 key+fingerprint）+ `upload_fingerprints` 记录 |
| 已读提交 | `conversation_reads` 推进 + `read_outbox` 消费 + `local_conversation_heads` 更新 |
| 通知去重 | `notification_receipts` 插入 + `otp_intents` 写入 |
| OTP 复制 | `otp_intents` 消费（返回 true 才允许写剪贴板） |
| 身份重置 | 全部六张业务表 + `meta` 的游标键在同一次 `resetForNewIdentity` 事务内 |

已有回归覆盖：`UploadTest`、`SyncReconcileTest`、`ReadReceiptTest`、`NotificationDuplicateAbsorptionTest`、
`DurabilityRemediationTest`、`OtpNotificationManagerTest`。拆分任何一处前，这些用例必须先在
新结构上通过。

## 锁顺序

1. `databaseOpenLock` 只保护 `SQLiteOpenHelper` 的打开与版本检查，**不**保护业务读写。
2. `identityLock` 是身份级互斥，保护"换绑/重置"与"写入旧身份数据"的竞争。
3. 持有 `identityLock` 时可以在**同一次**数据库事务内写多张表；反过来，持有数据库事务时
   **不得**再去等待网络、Keystore 或其它仓储的锁。
4. 不允许出现"事务 A 持 lock1 等 lock2"与"事务 B 持 lock2 等 lock1"的组合。跨领域操作只能
   通过单一日志（outbox 类表）串行化，不能互相调用对方的事务方法。

## 拆分时必须保持的不变量

- 任一领域的写入只走自己的仓储入口，`meta` 的键前缀必须与其领域一致。
- `captures`/`outbox`/`read_outbox` 的"先落盘再返回"语义不变：采集回调在返回前必须完成持久化。
- 上传确认必须继续按 `key + sent_fingerprint` 条件删除，不能退化为按 key 删除。
- 身份重置仍是单一事务，不能变成多次提交。
- 上述四个锁规则不因文件拆分而改变。

## 尚未完成

仓储拆分本身（R1）未实施：验收依赖真机回归（锁屏/Doze 下的采集与上传），本机没有设备，
仅靠编译与 JVM 单测无法确认搬迁没有破坏上述不变量。本文件与所有权测试是拆分前必须先有的部分。
