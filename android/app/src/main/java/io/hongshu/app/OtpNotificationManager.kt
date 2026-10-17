package io.hongshu.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object OtpNotificationManager {
    const val CHANNEL_ID = "otp_island"
    private const val OTP_TIMEOUT_MILLIS = 10 * 60 * 1000L // 10 minutes

    /**
     * Builds and displays a promoted ongoing notification (or standard high-priority notification on Android <= 15)
     * for verification codes received from other devices.
     */
    fun showOtpNotification(
        context: Context,
        messageId: Long,
        otp: String,
        sender: String,
        contact: String = "",
        deviceName: String = "",
    ): Boolean {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
            return false

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        // Generate a stable notification ID per message ID (clamped into Int range)
        val notificationId = (messageId xor (messageId ushr 32)).toInt().let { if (it == 0) 1000 else it }

        val copyIntent = Intent(context, OtpNotificationReceiver::class.java).apply {
            action = ACTION_COPY_OTP
            putExtra(EXTRA_OTP_CODE, otp)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val copyPending = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 1,
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val dismissIntent = Intent(context, OtpNotificationReceiver::class.java).apply {
            action = ACTION_DISMISS_OTP
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val openIntent = mainActivityIntent(context)
        val openPending = PendingIntent.getActivity(
            context,
            notificationId * 2,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val sourceLabel = contact.ifEmpty { sender }
        val deviceLabel = if (deviceName.isNotEmpty()) " · 来自 $deviceName" else ""
        val contentText = "$sourceLabel$deviceLabel"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hongshu)
            .setContentTitle("验证码：$otp")
            .setContentText(contentText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(OTP_TIMEOUT_MILLIS)
            .addAction(R.drawable.ic_content_copy, "复制", copyPending)
            .addAction(R.drawable.ic_close, "关闭", dismissPending)

        applyPromotedOngoing(builder, otp)

        manager.notify(notificationId, builder.build())
        return true
    }

    /**
     * Applies Android 16+ (API 36+) promoted ongoing status (Live Updates / 灵动岛药丸)
     * via reflection and metadata extras while maintaining backward compatibility with API <= 35.
     */
    internal fun applyPromotedOngoing(builder: NotificationCompat.Builder, shortText: String) {
        // 1. Set the framework extras directly so system UI recognizes promoted ongoing status
        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putCharSequence("android.shortCriticalText", shortText)
        }
        builder.addExtras(extras)

        // 2. Reflectively invoke setRequestPromotedOngoing & setShortCriticalText if available on builder
        runCatching {
            val method = builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
            method.invoke(builder, true)
        }
        runCatching {
            val method = builder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
            method.invoke(builder, shortText)
        }
    }
}
