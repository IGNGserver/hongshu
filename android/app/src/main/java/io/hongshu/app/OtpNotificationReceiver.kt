package io.hongshu.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

const val ACTION_COPY_OTP = "io.hongshu.app.ACTION_COPY_OTP"
const val ACTION_DISMISS_OTP = "io.hongshu.app.ACTION_DISMISS_OTP"
const val EXTRA_OTP_CODE = "otp_code"
const val EXTRA_NOTIFICATION_ID = "notification_id"
const val EXTRA_NOTIFICATION_TAG = "notification_tag"

// Both identity replacement and epoch writers take identityLock before opening a SQLite
// transaction. Keep that order here, and retain the fence through the clipboard side effect.
internal fun copyNotificationOtp(
    context: Context,
    tag: String,
    otp: String,
    databaseName: String = "hongshu.db",
    writeClipboard: (Context, String) -> Unit = ::copyOtp,
): Boolean = synchronized(identityLock) {
    if (otp.isEmpty() || Config(context).identityChanging) return@synchronized false
    val messageId = tag.substringAfterLast(':').toLongOrNull() ?: return@synchronized false
    if (messageId <= 0) return@synchronized false
    Store(context, databaseName).use { store ->
        if (tag != OtpNotificationManager.notificationTag(context, messageId, store.epoch()))
            return@synchronized false
        writeClipboard(context, otp)
        true
    }
}

class OtpNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val tag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG) ?: return
        if (notificationId != OtpNotificationManager.NOTIFICATION_ID || !tag.startsWith("otp:")) return
        when (intent.action) {
            ACTION_COPY_OTP -> {
                val otp = intent.getStringExtra(EXTRA_OTP_CODE).orEmpty()
                val copied = runCatching { copyNotificationOtp(context, tag, otp) }.getOrDefault(false)
                if (copied) {
                    // Android 13+ (API 33+) provides its own visual clipboard confirmation overlay.
                    if (Build.VERSION.SDK_INT < 33) {
                        Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                if (notificationId != 0) {
                    context.getSystemService(NotificationManager::class.java).cancel(tag, notificationId)
                }
            }
            ACTION_DISMISS_OTP -> {
                if (notificationId != 0) {
                    context.getSystemService(NotificationManager::class.java).cancel(tag, notificationId)
                }
            }
        }
    }
}
