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

fun realtimeSmsStatus(context: Context, diagnostics: CaptureDiagnostics): String {
    val c = Config(context)
    if (!c.upload) return "已关闭"
    if (!hasPermission(context, Manifest.permission.RECEIVE_SMS)) return "缺少接收权限 · 历史读取不受此权限影响"
    if (c.captureFailure != CaptureFailure.NONE)
        return "最近采集失败：${if (c.captureFailure == CaptureFailure.PARSE) "短信解析" else "本地存储"}"
    if (diagnostics.broadcasts == 0L) return "接收权限已授予 · 等待实际短信验证"
    return "最近处理 ${formatTimestamp(diagnostics.lastBroadcast)} · 已保存 ${diagnostics.saved} 条 · 重复 ${diagnostics.duplicates} 次"
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
        HistoryState.COMPLETE -> "最近扫描 ${formatTimestamp(c.lastHistoryScan)} · 仅覆盖系统可访问收件箱"
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
