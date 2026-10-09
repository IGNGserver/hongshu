# Android 兼容性与调研记录

参考对象：
- https://github.com/pppscn/SmsForwarder ：原生 SMS 广播、双 SIM subscription/slot 厂商差异、分段合并与重试。
- https://github.com/capcom6/android-sms-gateway ：独立采集/网络队列、后台运行说明、权限与本地持久化。
- https://developer.android.com/reference/android/provider/Telephony.Sms.Intents
- https://developer.android.com/about/versions/15/changes/foreground-service-types
- https://developer.android.com/develop/background-work/background-tasks/persistent

首版采用系统 `getMessagesFromIntent` 解析完整广播，不手工按单个 PDU 建消息。
SMS_RECEIVED 设为优先级 999，高于默认短信应用的 0，避免有序广播被提前中止。
subscription extra 优先 `subscription`/`subscription_id`/`sub_id`；卡槽只用于在已授权时解析当前订阅 ID，不作为已保存号码的身份。
不可辨识时仅当用户明确确认本机只有一张 SIM 时使用唯一已确认号码兜底；其他情况保留为未映射记录，不能猜测接收号码。
运营商通常不提供 SIM 本机号；自动识别只作建议，用户必须确认或修改。
历史导入和漏广播补扫读取 inbox 与 sub_id，时间使用本机接收时间 date。date_sent 与广播 PDU 时间经常不一致，不能拿来假装和实时广播是同一指纹。
已有同设备广播记录时，同发件人、同正文、时间差不超过 2 分钟的导入或补扫行视为同一条，不重复上传。
这只合并「广播已入库、收件箱时间略有偏差」的同一条，不合并相隔超过 2 分钟的真实重复验证码。
自动补扫从配对时刻起记水位，不把安装前的收件箱当成新短信。
厂商 timestamp/address 差异仍可能造成重复。

Android 15+ 可能限制非默认短信应用读取敏感验证码；权限授予也不保证所有消息可见。
Google Play 的 SMS 权限政策与设备 sideload 是不同问题；首版按用户自行安装设计，未声称可直接上架。
通知权限 Android 13+ 单独申请；锁屏只显示泛化提示。无 FCM、无后台隐身常驻技巧。
前台服务是用户主动开启的可选链路，dataSync 超时后停止并退回 WorkManager。
厂商省电、Doze、强制停止、设备关机导致延迟；应用提供状态和重连，不能承诺绝对即时。

必须在真实双卡设备验证：不同厂商 extras、长短信编码、跨时区、SIM 更换、缺失订阅 ID、
断网/进程死亡/重启、Android 15 验证码限制。模拟器和构建测试不能替代这项验证。

实际阅读版本（2026-10-08）：SmsForwarder `a3d23026f0058420869163c1d5dfb463ce52fc15`
的 `app/src/main/kotlin/cn/ppps/forwarder/receiver/SmsReceiver.kt`，确认 subscription 比 slot
可靠、系统 PDU 合并与 Worker 模式；SMSGate `bdfd69175666645b36ec9a3541e93ed8b46b6656`
的 `modules/receiver/MessagesReceiver.kt`，确认使用第一段时间、合并 displayMessageBody、
Subscription helper 与独立 ReceiverService。本项目独立实现，不复制日志中的短信输出、不支持其 SMS 命令功能。
Android 官方 FGS timeout 文档确认 target 35+ 的 6h/24h dataSync 限制和 `onTimeout` 处理要求。
