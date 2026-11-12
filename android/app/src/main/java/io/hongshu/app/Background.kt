package io.hongshu.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

internal fun realtimeServiceType(): Int =
    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
    else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

internal fun mainActivityIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

@Suppress("DEPRECATION")
internal fun recentTaskId(info: ActivityManager.RecentTaskInfo): Int =
    if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id

internal fun updateRecentTaskVisibility(activity: Activity, hidden: Boolean): Boolean =
    runCatching {
            val manager = activity.getSystemService(ActivityManager::class.java)
            val task = manager.appTasks.firstOrNull { recentTaskId(it.taskInfo) == activity.taskId }
            if (task == null) false
            else {
                task.setExcludeFromRecents(hidden)
                true
            }
        }
        .getOrDefault(false)

// Older builds persisted a per-task exclusion. Clear it once, then drop the key so every
// normal launch leaves the app visible in recents again.
internal fun clearLegacyTaskExclusion(activity: Activity) {
    val prefs = activity.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
    if (!prefs.contains(LEGACY_HIDE_FROM_RECENTS)) return
    if (updateRecentTaskVisibility(activity, false)) {
        prefs.edit().remove(LEGACY_HIDE_FROM_RECENTS).apply()
    }
}

// One entry point for both the UI and onTaskRemoved. The caller decides what a failure means.
internal fun startRealtimeService(context: Context, action: String? = null): Boolean {
    val intent = Intent(context, RealtimeService::class.java)
    if (action != null) intent.action = action
    return runCatching {
            ContextCompat.startForegroundService(context, intent)
            true
        }
        .getOrDefault(false)
}

internal fun startRealtimeIfEnabled(context: Context): Boolean {
    val config = Config(context)
    if (
        realtimeStart(null, config.keepAlive, config.token.isNotEmpty(), config.url.isNotEmpty()) !=
            RealtimeStart.CONNECT
    )
        return false
    if (startRealtimeService(context)) return true
    RuntimeHealth.connection.value = ConnectionHealth(detail = "系统不允许启动实时服务；周期同步仍启用")
    return false
}

internal fun requestBatteryExemption(activity: Activity): Boolean {
    val request =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    return startFirstAvailable(activity, listOf(request, list, appDetailsIntent(activity.packageName)))
}

private const val LEGACY_HIDE_FROM_RECENTS = "hide_from_recents"
