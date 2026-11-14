package io.hongshu.app

import android.app.Activity
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * Translucent zero-UI trampoline activity so tapping "复制" on an OTP notification
 * acquires temporary window focus and satisfies Android 10+ clipboard restrictions.
 */
class OtpCopyActivity : Activity() {
    private var handled = false
    private val handler = Handler(Looper.getMainLooper())
    private val fallbackCopy = Runnable { performCopyAndFinish() }

    private fun suppressTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressTransition()
        window.setGravity(Gravity.START or Gravity.TOP)
        window.setLayout(1, 1)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.setDimAmount(0f)
    }

    override fun onResume() {
        super.onResume()
        if (!handled) handler.postDelayed(fallbackCopy, 250L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) performCopyAndFinish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(fallbackCopy)
        super.onDestroy()
    }

    private fun performCopyAndFinish() {
        if (handled) return
        handled = true
        handler.removeCallbacks(fallbackCopy)

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val tag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG).orEmpty()
        val otp = intent.getStringExtra(EXTRA_OTP_CODE).orEmpty()
        val copied =
            if (notificationId == OtpNotificationManager.NOTIFICATION_ID && tag.startsWith("otp:")) {
                runCatching { copyNotificationOtp(applicationContext, tag, otp) }.getOrDefault(false)
            } else {
                false
            }
        if (copied && Build.VERSION.SDK_INT < 33) {
            Toast.makeText(applicationContext, "验证码已复制", Toast.LENGTH_SHORT).show()
        }
        if (notificationId != 0 && tag.isNotEmpty()) {
            getSystemService(NotificationManager::class.java).cancel(tag, notificationId)
        }
        finish()
        suppressTransition()
    }
}
