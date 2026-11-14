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

    internal fun canPostPromotedNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return runCatching {
            NotificationManager::class.java
                .getMethod("canPostPromotedNotifications")
                .invoke(manager) as? Boolean ?: true
        }.getOrDefault(true)
    }

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

        val copyIntent = Intent(context, OtpCopyActivity::class.java).apply {
            action = ACTION_COPY_OTP
            data = actionData("copy")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra(EXTRA_OTP_CODE, otp)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_TAG, tag)
        }
        val copyPending = PendingIntent.getActivity(
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

        val openIntent = mainActivityIntent(context).apply {
            data = actionData("open")
            putExtra(EXTRA_CONVERSATION_SENDER, sender)
        }
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
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("验证码 $otp · $contentText\n点击“复制”直接写入剪贴板")
            )
            .setContentIntent(openPending)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(remaining.coerceAtMost(OTP_TIMEOUT_MILLIS))
            .addAction(R.drawable.ic_content_copy, "复制", copyPending)
            .addAction(R.drawable.ic_close, "关闭", dismissPending)

        applyPromotedOngoing(builder, otp, sourceLabel)

        manager.notify(tag, notificationId, builder.build())
        return true
    }

    /**
     * Applies Android 16+ (API 36+) promoted ongoing status (Live Updates / 灵动岛药丸)
     * plus Xiaomi HyperOS Focus Notification capsule extras while staying compatible with API <= 35.
     */
    internal fun applyPromotedOngoing(
        builder: NotificationCompat.Builder,
        shortText: String,
        sourceLabel: String = "",
    ) {
        val focusTitle = if (sourceLabel.isNotEmpty()) "$sourceLabel · 验证码" else "鸿枢验证码"
        val focusParam =
            org.json.JSONObject()
                .put("protocol", 1)
                .put("enableFloat", true)
                .put("updatable", true)
                .put("ticker", "验证码 $shortText")
                .put("tickerPic", "")
                .put(
                    "baseInfo",
                    org.json.JSONObject()
                        .put("type", 2)
                        .put("title", shortText)
                        .put("content", focusTitle)
                        .put("showSmallIcon", true),
                )
                .put(
                    "island",
                    org.json.JSONObject()
                        .put("islandProperty", 1)
                        .put("islandOrder", true)
                        .put("islandFirstFloat", true)
                        .put("highlightColor", "#1B6EF3")
                        .put(
                            "smallIslandArea",
                            org.json.JSONObject()
                                .put("combinePicInfo", org.json.JSONObject().put("title", shortText)),
                        )
                        .put(
                            "bigIslandArea",
                            org.json.JSONObject()
                                .put(
                                    "textInfo",
                                    org.json.JSONObject()
                                        .put("title", "验证码 $shortText")
                                        .put("content", focusTitle),
                                ),
                        ),
                )
                .toString()

        // 1. Set framework promoted ongoing extras + HyperOS Focus Notification capsule JSON
        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putCharSequence("android.shortCriticalText", shortText)
            putString("miui.focus.param", focusParam)
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
