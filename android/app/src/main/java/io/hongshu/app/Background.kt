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

internal fun startRealtimeIfEnabled(context: Context): Boolean {
    val config = Config(context)
    if (
        realtimeStart(null, config.keepAlive, config.token.isNotEmpty(), config.url.isNotEmpty()) !=
            RealtimeStart.CONNECT
    )
        return false
    return try {
        ContextCompat.startForegroundService(context, Intent(context, RealtimeService::class.java))
        true
    } catch (_: Exception) {
        RuntimeHealth.connection.value = ConnectionHealth(detail = "系统不允许启动实时服务；周期同步仍启用")
        false
    }
}

internal fun openBackgroundSettings(activity: Activity, battery: Boolean): Boolean {
    val intent =
        if (battery) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}"),
            )
    return runCatching {
            activity.startActivity(intent)
            true
        }
        .getOrElse {
            runCatching {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${activity.packageName}"),
                        )
                    )
                    true
                }
                .getOrDefault(false)
        }
}
