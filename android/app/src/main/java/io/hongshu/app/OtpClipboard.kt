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

@Volatile private var lastCopyToken = ""

internal fun copyOtp(context: Context, code: String) {
    val token = UUID.randomUUID().toString()
    lastCopyToken = token
    val clip = ClipData.newPlainText("验证码", code)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
        putString(OTP_CLIP_ID, token)
    }
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        val current = manager.primaryClip
        val extrasToken = current?.description?.extras?.getString(OTP_CLIP_ID)
        val currentText = current?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        // Clear when extras match our token, or on OEM ROMs that strip extras if token and label+text match.
        val ownsClip =
            lastCopyToken == token &&
                (extrasToken == token ||
                    (extrasToken == null &&
                        current?.description?.label?.toString() == "验证码" &&
                        currentText == code))
        if (ownsClip) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                // clearPrimaryClip() is API 28+. An owned empty clip is the compatible fallback.
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }, OTP_CLIP_TTL_MS)
}
