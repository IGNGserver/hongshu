package io.hongshu.app

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

data class ConnectionHealth(
    val running: Boolean = false,
    val connected: Boolean = false,
    val detail: String = "服务未运行",
)

object RuntimeHealth {
    val connection = MutableStateFlow(ConnectionHealth())
}

fun smsPermissionStatus(context: Context): String {
    @Suppress("DEPRECATION")
    fun policy(permission: String): String {
        if (!hasPermission(context, permission)) return "未授予"
        val mode =
            runCatching {
                    val op = AppOpsManager.permissionToOp(permission) ?: return@runCatching null
                    context
                        .getSystemService(AppOpsManager::class.java)
                        .checkOpNoThrow(op, Process.myUid(), context.packageName)
                }
                .getOrNull()
        if (Build.VERSION.SDK_INT >= 29 && mode == AppOpsManager.MODE_FOREGROUND)
            return "权限已授予 · 系统策略仅限前台"
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> "权限已授予 · 系统策略允许"
            AppOpsManager.MODE_IGNORED,
            AppOpsManager.MODE_ERRORED -> "权限已授予但系统访问策略拒绝"
            AppOpsManager.MODE_DEFAULT -> "权限已授予 · 系统默认策略"
            else -> "权限已授予 · 无法确认系统策略"
        }
    }
    val vendor =
        if (isXiaomiDevice()) "小米/澎湃 OS 另有自启动与通知类短信授权，以下方厂商授权为准。"
        else "不代表厂商已允许验证码/通知类短信。"
    return "接收：${policy(Manifest.permission.RECEIVE_SMS)}；读取：${policy(Manifest.permission.READ_SMS)}。$vendor"
}

fun vendorSmsStatus(context: Context): String? =
    if (!isXiaomiDevice()) null else xiaomiSmsStatus(xiaomiRomName(), xiaomiSmsAccess(context))

// One content-free line per capture event, for the diagnostics page and copied report.
fun captureEventLabel(event: CaptureEvent): String {
    val source =
        when (event.source) {
            CaptureSource.BROADCAST -> "系统广播"
            CaptureSource.NOTIFICATION -> "短信通知"
            CaptureSource.RECOVERY -> "漏收检查"
            CaptureSource.HISTORY -> "历史补齐"
            CaptureSource.SENT -> "已发送短信"
            CaptureSource.QUEUE -> "本机队列"
            else -> event.source
        }
    val times = if (event.count > 1) " ×${event.count}" else ""
    val outcome =
        when (event.outcome) {
            "saved" -> "已保存$times"
            "duplicate" -> "重复，已忽略$times"
            "ignored" -> "系统或服务通知，已忽略$times"
            "aggregate" -> "多条折叠的汇总通知，已请求补读收件箱$times"
            "queued" -> "暂存 ${event.count} 条，等待与收件箱核对"
            "paired_inbox" -> "已与收件箱记录关联$times"
            "paired_broadcast" -> "已与系统广播关联$times"
            "paired_late" -> "补读后关联 ${event.count} 条"
            "uploaded_unpaired" -> "${event.count} 条未找到对应短信，按通知内容上传"
            "absorbed_duplicate" -> "${event.count} 条重复通知副本已并入已上传短信"
            "auto_ignored" -> "${event.count} 条无法确认接收号码，已自动忽略"
            "restored" -> "${event.count} 条已恢复待处理"
            "discarded" -> "${event.count} 条已放弃"
            "added" ->
                if (event.source == CaptureSource.SENT) "从已发送箱新增 ${event.count} 条"
                else "从收件箱新增 ${event.count} 条"
            else -> event.outcome + times
        }
    return "${formatTimestamp(event.at)} · $source · $outcome"
}

fun notificationCaptureStatus(context: Context): String {
    val c = Config(context)
    if (!c.upload || !c.notificationCapture) return "已关闭 · 系统不投递短信广播且补漏读不到时可开启"
    if (!notificationListenerEnabled(context))
        return "未授予通知使用权 · 请在系统设置允许鸿枢读取通知（提示受限设置时，先在应用信息右上角允许受限设置）"
    val arrived =
        "短信应用通知到达 ${c.receivedNotifications} 次" +
            if (c.lastReceivedNotification > 0) " · 最近 ${formatTimestamp(c.lastReceivedNotification)}"
            else ""
    if (c.notificationFailure.isNotEmpty()) return "$arrived · ${c.notificationFailure}"
    return "$arrived · 只采集开启后的短信通知；发件人可能显示为联系人名称，号码由后续广播/补漏校正"
}

fun realtimeSmsStatus(context: Context, diagnostics: CaptureDiagnostics): String {
    val c = Config(context)
    if (!c.upload) return "已关闭"
    if (!hasPermission(context, Manifest.permission.RECEIVE_SMS)) return "缺少接收权限 · 历史读取不受此权限影响"
    val arrived =
        "系统广播到达 ${c.receivedBroadcasts} 次" +
            if (c.lastReceivedBroadcast > 0) " · 最近到达 ${formatTimestamp(c.lastReceivedBroadcast)}"
            else ""
    if (c.captureFailure != CaptureFailure.NONE)
        return "$arrived · ${when (c.captureFailure) {
            CaptureFailure.PARSE -> "短信解析失败"
            CaptureFailure.STORAGE -> "本地存储失败"
            CaptureFailure.SCHEDULING -> "短信已保存 · 后台任务调度失败，重新进入后重试"
            CaptureFailure.NONE -> ""
        }}"
    if (diagnostics.broadcasts == 0L && c.receivedBroadcasts == 0L && isXiaomiDevice())
        return "$arrived · 系统从未投递短信广播：请在下方开启自启动与通知类短信，并开启新短信补漏或短信通知兜底"
    if (diagnostics.broadcasts == 0L) return "$arrived · 接收权限已授予，不代表系统已投递验证码；请用普通短信对照"
    return "$arrived · 最近成功处理 ${formatTimestamp(diagnostics.lastBroadcast)} · 已保存 ${diagnostics.saved} 条 · 重复 ${diagnostics.duplicates} 次"
}

fun recoverySmsStatus(context: Context): String {
    val c = Config(context)
    if (!c.upload || !c.recoveryEnabled) return "已关闭 · 未收到广播时不会自动补漏新短信"
    if (!hasPermission(context, Manifest.permission.READ_SMS)) return "缺少读取短信权限 · 无法补漏"
    return when (c.recoveryState) {
        HistoryState.NOT_SCANNED,
        HistoryState.DISABLED,
        HistoryState.NO_PERMISSION -> "等待收件箱补漏"
        HistoryState.SCANNING -> "正在补漏新短信"
        else -> c.recoveryStatus
    }
}

fun historySmsStatus(context: Context): String {
    val c = Config(context)
    if (!c.upload || !c.historyEnabled) return "已关闭 · 不影响实时接收"
    if (!hasPermission(context, Manifest.permission.READ_SMS)) return "缺少读取权限 · 不影响实时接收"
    return when (c.historyState) {
        HistoryState.NOT_SCANNED -> "等待扫描"
        HistoryState.DISABLED -> "等待扫描"
        HistoryState.NO_PERMISSION -> "权限已恢复 · 等待扫描"
        HistoryState.SCANNING -> "历史短信采集中"
        HistoryState.COMPLETE -> c.captureStatus
        HistoryState.FAILED -> "历史查询失败 · 不影响实时接收和已保存队列"
    }
}

fun smsSyncStatus(context: Context, repo: Repository): String {
    val c = repo.config
    if (!c.upload) return "已关闭"
    if (c.token.isEmpty()) return "未连接中枢或凭据已失效"
    if (!c.serverUploadAllowed) return "中枢已禁止本机上传"
    if (!c.syncHealthy) return c.status
    if (System.currentTimeMillis() - c.lastSync > 30 * 60 * 1000) return "长时间未成功同步，等待重试"
    return "最近同步 ${formatTimestamp(c.lastSync)} · ${c.status}"
}

// What happened to the last upload attempt, for the pending page.
fun uploadAttemptStatus(c: Config, connected: Boolean): String =
    when {
        !c.upload -> "“同步本机短信”已关闭，打开后才会上传"
        c.token.isEmpty() -> "未连接中枢或授权已失效，请在设置中重新连接"
        !c.serverUploadAllowed -> "中枢已禁止本机上传，可在网页端设备管理中开启"
        !connected -> "当前没有网络，联网后自动上传"
        c.lastSyncError.isNotEmpty() && c.lastSyncAttempt > 0 ->
            "上次尝试 ${formatTimestamp(c.lastSyncAttempt)} 失败：${c.lastSyncError}"
        c.lastSync > 0 -> "上次成功同步 ${formatTimestamp(c.lastSync)}，新短信会自动上传"
        else -> "尚未成功同步，将自动上传"
    }

fun keepAliveStatus(context: Context, c: Config, health: ConnectionHealth): String {
    if (!c.keepAlive) return "已关闭 · 仍有系统周期同步"
    if (!health.running) return "${health.detail} · 系统周期同步仍启用"
    val power = context.getSystemService(PowerManager::class.java)
    val restrictions =
        if (power.isPowerSaveMode || !power.isIgnoringBatteryOptimizations(context.packageName))
            " · 受系统省电策略限制"
        else ""
    return health.detail + restrictions
}

fun connectionNotificationStatus(context: Context): String {
    if (
        Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    )
        return "缺少通知权限，连接通知可能不可见"
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "系统已禁止应用通知"
    if (
        context
            .getSystemService(NotificationManager::class.java)
            .getNotificationChannel("connection")
            ?.importance == NotificationManager.IMPORTANCE_NONE
    )
        return "系统已关闭实时连接通知渠道"
    return "实时连接通知已允许"
}

fun notificationStatus(context: Context, c: Config, health: ConnectionHealth): String {
    if (!c.notify) return "已关闭"
    if (c.token.isEmpty()) return "未连接中枢或凭据已失效"
    if (
        Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    )
        return "缺少通知权限"
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "系统已禁止应用通知"
    val channel =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel("messages")
    if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return "系统已关闭短信通知渠道"
    if (!c.syncHealthy) return "通知权限正常 · ${c.status}"
    if (System.currentTimeMillis() - c.lastSync > 30 * 60 * 1000) return "通知权限正常 · 长时间未成功接收更新"
    if (health.connected) return "通知权限正常 · 实时接收连接已建立"
    return "通知权限正常 · 周期接收可用，可能延迟"
}
