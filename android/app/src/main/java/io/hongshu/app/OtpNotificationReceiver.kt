package io.hongshu.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

const val ACTION_COPY_OTP = "io.hongshu.app.ACTION_COPY_OTP"
const val ACTION_DISMISS_OTP = "io.hongshu.app.ACTION_DISMISS_OTP"
const val EXTRA_OTP_CODE = "otp_code"
const val EXTRA_NOTIFICATION_ID = "notification_id"

class OtpNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        when (intent.action) {
            ACTION_COPY_OTP -> {
                val otp = intent.getStringExtra(EXTRA_OTP_CODE).orEmpty()
                if (otp.isNotEmpty()) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("OTP Code", otp))
                    // Android 13+ (API 33+) provides its own visual clipboard confirmation overlay.
                    if (Build.VERSION.SDK_INT < 33) {
                        Toast.makeText(context, "验证码 $otp 已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                if (notificationId != 0) {
                    context.getSystemService(NotificationManager::class.java).cancel(notificationId)
                }
            }
            ACTION_DISMISS_OTP -> {
                if (notificationId != 0) {
                    context.getSystemService(NotificationManager::class.java).cancel(notificationId)
                }
            }
        }
    }
}
