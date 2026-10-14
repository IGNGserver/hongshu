# Android 兼容性与调研记录

参考对象：
- https://github.com/pppscn/SmsForwarder ：原生 SMS 广播、双 SIM subscription/slot 厂商差异、分段合并与重试。
- https://github.com/capcom6/android-sms-gateway ：独立采集/网络队列、后台运行说明、权限与本地持久化。
- https://developer.android.com/reference/android/provider/Telephony.Sms.Intents
- https://developer.android.com/about/versions/15/changes/foreground-service-types
- https://developer.android.com/develop/background-work/background-tasks/persistent

首版采用系统 `getMessagesFromIntent` 解析完整广播，不手工按单个 PDU 建消息。
SMS_RECEIVED 设为优先级 999，但优先级不能突破系统广播筛选或验证码访问限制；默认短信角色不是接收 SMS_RECEIVED 的必要条件。
subscription extra 优先 `subscription`/`subscription_id`/`sub_id`；卡槽只用于在已授权时解析当前订阅 ID，不作为已保存号码的身份。
不可辨识时仅当用户明确确认本机只有一张 SIM 时使用唯一已确认号码兜底；其他情况保留为未映射记录，不能猜测接收号码。
运营商通常不提供 SIM 本机号；自动识别只作建议，用户必须确认或修改。
历史短信与漏广播补扫自动读取 inbox 与 sub_id，时间使用本机接收时间 date。date_sent 与广播 PDU 时间经常不一致，不能拿来假装和实时广播是同一指纹。
开启同步后采集全部可访问的收件箱记录，包括连接前记录；系统已删除/限制读取的短信、MMS/RCS、
已消失的其他应用通知不在可读取范围。旧卡订阅无法安全匹配号码时保留待传记录，不猜测归属。
自动号码识别使用 READ_PHONE_STATE/READ_PHONE_NUMBERS，API 33+ 使用 getPhoneNumber，旧系统使用
SubscriptionInfo.number；运营商未提供号码时明确显示未识别，不能宣称自动识别必然成功。
已有同设备广播记录时，同发件人、同正文、时间差不超过 2 分钟的导入或补扫行视为同一条，不重复上传。
这只合并「广播已入库、收件箱时间略有偏差」的同一条，不合并相隔超过 2 分钟的真实重复验证码。
历史补齐从独立 provider ID 水位读取全部可访问收件箱，以历史边界区分旧消息。
可选的新短信补漏另用 ID 水位和用户开启时间，不开启全量历史，也不推进历史水位。
厂商 timestamp/address 差异仍可能造成重复。

权限授予也不保证所有消息可见。厂商可能另设验证码/通知类短信访问策略；本机未实测，不推断具体策略。
Android 官方 SMS_RECEIVED 文档说明带 SMS Retriever 哈希的消息可能只向对应应用及指定列表投递。
Android 15 的 OTP 通知脱敏针对 NotificationListenerService，不能以此直接断言 SMS 广播被拦截；它会影响可选的短信通知兜底。
小米/澎湃 OS 另有私有 AppOps：自启动（10008）、开机启动（10007）、通知类短信（10018）。未允许时广播不投递、
服务号短信从收件箱隐藏，而标准权限仍显示已授予。应用最佳努力读取并提供跳转，详见 [HYPEROS_SMS.md](HYPEROS_SMS.md)。
Android 17 起 OTP 短信对非默认应用扣留三小时（广播与收件箱查询均受影响），届时需重新验证。
Google Play 的 SMS 权限政策与设备 sideload 是不同问题；首版按用户自行安装设计，未声称可直接上架。
通知权限 Android 13+ 单独申请；锁屏只显示泛化提示。无 FCM、无无声音乐/一像素/无障碍保活。
前台服务是用户主动开启的可选链路。Android 14+ 的跨设备短信连接采用 remoteMessaging，
声明 FOREGROUND_SERVICE_REMOTE_MESSAGING；Android 8–13 使用 dataSync 兼容路径。
SQLiteOpenHelper 的 AutoCloseable 接口仅在 API 29+ 存在；Store 显式实现 Closeable，
保持 Android 8/9 上 Kotlin use 的正常关闭路径，避免运行时类型转换崩溃。
超时仍及时停止并退回 WorkManager，不循环重启耗尽配额的服务；普通进程回收通过 START_STICKY 尝试恢复。
开机恢复是独立 opt-in，凭据不可用或系统拒绝启动时保留周期同步，不声称必然恢复。
隐藏最近任务只设置当前任务的 exclude 标记，不结束/移除任务、不提升进程优先级。
参考 SmsForwarder 的 MainActivity.kt/SettingsFragment.kt：setExcludeFromRecents 与前台服务独立；
该参考版本 targetSdk=33，不能将其旧版保活行为直接套用到鸿枢的 targetSdk=35。
先锁定后隐藏是否仍保留清理保护，必须按实际厂商/系统验证；没有通用公开 API 可读取锁定状态。
参考 https://developer.android.com/develop/background-work/services/fgs/service-types#remote-messaging
与 https://developer.android.com/reference/android/app/ActivityManager.AppTask#setExcludeFromRecents(boolean)。
厂商省电、Doze、强制停止、设备关机导致延迟；应用提供状态和重连，不能承诺绝对即时。

必须在真实双卡设备验证：不同厂商 extras、长短信编码、跨时区、SIM 更换、缺失订阅 ID、
断网/进程死亡/重启、Android 15 验证码限制。模拟器和构建测试不能替代这项验证。

实际阅读版本（2026-10-08）：SmsForwarder `a3d23026f0058420869163c1d5dfb463ce52fc15`
的 `app/src/main/kotlin/cn/ppps/forwarder/receiver/SmsReceiver.kt`，确认 subscription 比 slot
可靠、系统 PDU 合并与 Worker 模式；SMSGate `bdfd69175666645b36ec9a3541e93ed8b46b6656`
的 `modules/receiver/MessagesReceiver.kt`，确认使用第一段时间、合并 displayMessageBody、
Subscription helper 与独立 ReceiverService。本项目独立实现，不复制日志中的短信输出、不支持其 SMS 命令功能。
Android 官方 FGS timeout 文档确认 target 35+ 的 6h/24h dataSync 限制和 `onTimeout` 处理要求。
