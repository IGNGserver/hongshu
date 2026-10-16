package io.hongshu.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * Plain snapshot of everything the settings summary depends on. Keeping the decisions below
 * free of Android types lets them be unit tested and keeps every page using the same wording.
 */
data class SetupFacts(
    val connected: Boolean = false,
    val syncHealthy: Boolean = true,
    val syncStale: Boolean = false,
    val syncDetail: String = "",
    val upload: Boolean = false,
    val serverUploadAllowed: Boolean = true,
    val receiveSms: Boolean = false,
    val readSms: Boolean = false,
    val recovery: Boolean = false,
    val history: Boolean = false,
    val notificationCapture: Boolean = false,
    val notificationAccess: Boolean = false,
    val notify: Boolean = false,
    val postNotifications: Boolean = true,
    val keepAlive: Boolean = false,
    val realtimeRunning: Boolean = false,
    val realtimeConnected: Boolean = false,
    val realtimeDetail: String = "",
    val batteryUnrestricted: Boolean = true,
    val pending: PendingCounts = PendingCounts(),
)

/** Something the user should fix, and the settings page where it can be fixed. */
data class Attention(val title: String, val detail: String, val page: SettingsPage)

fun hubStatus(f: SetupFacts): Pair<StatusLevel, String> =
    when {
        !f.connected -> StatusLevel.Attention to "未连接"
        !f.syncHealthy || f.syncStale -> StatusLevel.Attention to "同步异常"
        else -> StatusLevel.Ok to "已连接"
    }

fun captureStatus(f: SetupFacts): Pair<StatusLevel, String> =
    when {
        !f.upload -> StatusLevel.Off to "已关闭"
        !f.serverUploadAllowed -> StatusLevel.Attention to "中枢已禁止"
        captureNeedsPermission(f) -> StatusLevel.Attention to "需要授权"
        else -> StatusLevel.Ok to "已开启"
    }

fun notificationsStatus(f: SetupFacts): Pair<StatusLevel, String> =
    when {
        !f.connected -> StatusLevel.Off to "未连接"
        !f.notify -> StatusLevel.Off to "已关闭"
        !f.postNotifications -> StatusLevel.Attention to "需要授权"
        else -> StatusLevel.Ok to "已开启"
    }

fun backgroundStatus(f: SetupFacts): Pair<StatusLevel, String> =
    when {
        !f.connected -> StatusLevel.Off to "未连接"
        !f.keepAlive -> StatusLevel.Off to "仅周期同步"
        !f.realtimeRunning -> StatusLevel.Attention to "未运行"
        !f.batteryUnrestricted -> StatusLevel.Attention to "受省电限制"
        f.realtimeConnected -> StatusLevel.Ok to "实时连接中"
        else -> StatusLevel.Ok to "正在连接"
    }

private fun captureNeedsPermission(f: SetupFacts): Boolean =
    !f.receiveSms ||
        ((f.recovery || f.history) && !f.readSms) ||
        (f.notificationCapture && !f.notificationAccess)

/** Ordered list of problems for the settings summary card; empty means everything is fine. */
fun attentionItems(f: SetupFacts): List<Attention> = buildList {
    if (!f.connected) {
        add(Attention("尚未连接中枢", "连接后才能同步和查看短信", SettingsPage.Hub))
        return@buildList
    }
    if (!f.syncHealthy) {
        add(Attention("同步异常", f.syncDetail.ifEmpty { "最近一次同步没有成功" }, SettingsPage.Hub))
    } else if (f.syncStale) {
        add(Attention("长时间未同步", "超过 30 分钟没有成功同步，正在等待重试", SettingsPage.Hub))
    }
    if (f.upload) {
        if (!f.serverUploadAllowed)
            add(Attention("中枢已禁止本机上传", "在中枢的设备管理中允许这台手机上传", SettingsPage.Capture))
        if (!f.receiveSms)
            add(Attention("缺少接收短信权限", "授权后新短信才能实时上传", SettingsPage.Capture))
        if ((f.recovery || f.history) && !f.readSms)
            add(Attention("缺少读取短信权限", "漏收检查和历史补齐需要读取收件箱", SettingsPage.Capture))
        if (f.notificationCapture && !f.notificationAccess)
            add(Attention("缺少通知使用权", "从通知读取短信需要在系统中允许", SettingsPage.Capture))
        if (f.pending.needsSim > 0)
            add(
                Attention(
                    "${f.pending.needsSim} 条短信待确认接收号码",
                    "设置 SIM 卡号码后才能上传",
                    SettingsPage.Sims,
                )
            )
    }
    if (f.notify && !f.postNotifications)
        add(Attention("缺少通知权限", "无法提醒新短信", SettingsPage.Notifications))
    if (f.keepAlive && !f.realtimeRunning)
        add(
            Attention(
                "实时连接未运行",
                f.realtimeDetail.ifEmpty { "周期同步仍在工作，但新短信可能延迟" },
                SettingsPage.Background,
            )
        )
    else if (f.keepAlive && !f.batteryUnrestricted)
        add(Attention("后台运行受省电限制", "建议允许鸿枢不受电池优化限制", SettingsPage.Background))
}

private const val SyncStaleMillis = 30 * 60 * 1000L

/** Reads the snapshot; cheap enough for the main thread except [pending], loaded by the caller. */
fun setupFacts(
    context: Context,
    config: Config,
    health: ConnectionHealth,
    pending: PendingCounts,
): SetupFacts {
    val power = context.getSystemService(PowerManager::class.java)
    val connected = config.token.isNotEmpty()
    return SetupFacts(
        connected = connected,
        syncHealthy = config.syncHealthy,
        syncStale =
            connected && config.lastSync > 0 &&
                System.currentTimeMillis() - config.lastSync > SyncStaleMillis,
        syncDetail = config.status,
        upload = config.upload,
        serverUploadAllowed = config.serverUploadAllowed,
        receiveSms = hasPermission(context, Manifest.permission.RECEIVE_SMS),
        readSms = hasPermission(context, Manifest.permission.READ_SMS),
        recovery = config.recoveryEnabled,
        history = config.historyEnabled,
        notificationCapture = config.notificationCapture,
        notificationAccess = notificationListenerEnabled(context),
        notify = config.notify,
        postNotifications =
            (Build.VERSION.SDK_INT < 33 ||
                hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
        keepAlive = config.keepAlive,
        realtimeRunning = health.running,
        realtimeConnected = health.connected,
        realtimeDetail = health.detail,
        batteryUnrestricted = power.isIgnoringBatteryOptimizations(context.packageName),
        pending = pending,
    )
}
