package io.hongshu.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
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

fun smsSyncStatus(context: Context, repo: Repository): String {
    val c = repo.config
    if (!c.upload) return "已关闭"
    if (c.token.isEmpty()) return "未连接中枢或凭据已失效"
    if (!hasPermission(context, Manifest.permission.READ_SMS)) return "无法读取短信：缺少读取权限"
    if (!hasPermission(context, Manifest.permission.RECEIVE_SMS)) return "实时采集缺少权限；历史短信仍可自动读取"
    if (!c.serverUploadAllowed) return "中枢已禁止本机上传"
    if (!c.captureStatus.startsWith("短信读取正常")) return c.captureStatus
    if (!c.syncHealthy) return "${c.captureStatus} · ${c.status}"
    if (System.currentTimeMillis() - c.lastSync > 30 * 60 * 1000) return "短信可读取 · 长时间未成功同步，等待重试"
    val pending = repo.store.pendingCount()
    if (pending > 0) return "读取正常 · 待传 $pending 条（异常 ${repo.store.blockedCount()} 条）· ${c.status}"
    if (c.inboxScanPending) return "历史短信自动采集中"
    return "读取与同步正常 · ${c.status}"
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
