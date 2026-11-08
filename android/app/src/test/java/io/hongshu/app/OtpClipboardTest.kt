package io.hongshu.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OtpClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val looper = shadowOf(Looper.getMainLooper())

    @Test fun delayedCleanupNeverErasesANewerUserClip() {
        copyOtp(context, "123456")
        looper.idleFor(Duration.ofSeconds(30))
        clipboard.setPrimaryClip(ClipData.newPlainText("user", "newer user content"))
        looper.idleFor(Duration.ofSeconds(31))
        assertEquals("newer user content", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun ownClipIsClearedOnLegacyAndCurrentAndroid() {
        copyOtp(context, "123456")
        assertEquals("123456", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        looper.idleFor(Duration.ofSeconds(61))
        val current = clipboard.primaryClip
        assertTrue(current == null || current.getItemAt(0).text.isNullOrEmpty())
    }
}
