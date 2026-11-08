package io.hongshu.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

internal const val OTP_CLIP_ID = "io.hongshu.app.OTP_CLIP_ID"
internal const val OTP_CLIP_TTL_MS = 60_000L

internal fun copyOtp(context: Context, code: String) {
    val token = UUID.randomUUID().toString()
    val clip = ClipData.newPlainText("验证码", code)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
        putString(OTP_CLIP_ID, token)
    }
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        // A newer user/app clip, including another copy of the same digits, owns the clipboard.
        if (manager.primaryClip?.description?.extras?.getString(OTP_CLIP_ID) == token) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                // clearPrimaryClip() is API 28+. An owned empty clip is the compatible fallback.
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }, OTP_CLIP_TTL_MS)
}
