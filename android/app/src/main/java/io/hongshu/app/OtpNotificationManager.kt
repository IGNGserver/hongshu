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
    internal const val NOTIFICATION_ID = 1
    internal fun identityTag(context: Context): String = Config(context).let {
        "otp:${sha256("${it.url}\u0000${it.deviceId}")}:"
    }
    internal fun notificationTag(context: Context, messageId: Long, epoch: Long = 0): String =
        "${identityTag(context)}$epoch:$messageId"
    private const val OTP_TIMEOUT_MILLIS = 10 * 60 * 1000L // 10 minutes

    /**
     * Whether a verification-code notification could actually be posted right now. The answer
     * decides between the dedicated OTP channel and the ordinary summary, so it must be
     * checked at intent-creation time.
     */
    internal fun otpNotificationsAvailable(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
                ?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)

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
        epoch: Long = 0,
        expiresAt: Long = System.currentTimeMillis() + OTP_TIMEOUT_MILLIS,
    ): Boolean {
        if (!otpNotificationsAvailable(context)) return false
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        // Full identity/epoch/message tags avoid both service IDs and folded Long collisions.
        val notificationId = NOTIFICATION_ID
        val tag = notificationTag(context, messageId, epoch)
        fun actionData(action: String) = android.net.Uri.Builder().scheme("hongshu")
            .authority("otp").appendPath(tag).appendPath(action).build()

        val copyIntent = Intent(context, OtpNotificationReceiver::class.java).apply {
            action = ACTION_COPY_OTP
            data = actionData("copy")
            putExtra(EXTRA_OTP_CODE, otp)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_TAG, tag)
        }
        val copyPending = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 1,
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val dismissIntent = Intent(context, OtpNotificationReceiver::class.java).apply {
            action = ACTION_DISMISS_OTP
            data = actionData("dismiss")
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_TAG, tag)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val openIntent = mainActivityIntent(context).setData(actionData("open"))
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
            .setTimeoutAfter(remaining.coerceAtMost(OTP_TIMEOUT_MILLIS))
            .addAction(R.drawable.ic_content_copy, "复制", copyPending)
            .addAction(R.drawable.ic_close, "关闭", dismissPending)

        applyPromotedOngoing(builder, otp)

        manager.notify(tag, notificationId, builder.build())
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

// The display deadline for a verification code. A server-declared validity window is honored,
// but the code never outlives ten minutes from the SMS timestamp: a late sync of an old
// message must not highlight it as if it just arrived.
internal fun otpNotificationExpiry(timestamp: Long, expiresAt: Long): Long =
    minOf(timestamp + 10 * 60 * 1000L, expiresAt.takeIf { it > 0 } ?: Long.MAX_VALUE)
