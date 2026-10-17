package io.hongshu.app

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class OtpNotificationManagerTest {

    @Test
    fun showOtpNotificationBuildsOngoingNotificationWithActions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        channels(app)

        val shown = OtpNotificationManager.showOtpNotification(
            context = app,
            messageId = 12345L,
            otp = "849201",
            sender = "10086",
            contact = "中国移动",
            deviceName = "备用机",
        )
        assertTrue(shown)

        val notificationManager = app.getSystemService(NotificationManager::class.java)
        val shadowManager = shadowOf(notificationManager)
        val notificationId = (12345L xor (12345L ushr 32)).toInt()
        val notification = shadowManager.getNotification(notificationId)
        assertNotNull("Notification should be posted", notification)

        // Title and body checks
        val extras = notification.extras
        assertEquals("验证码：849201", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertTrue(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("中国移动") == true)
        assertTrue(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("备用机") == true)

        // Promoted Ongoing / Live Updates characteristics check
        assertTrue("Notification must be ongoing", (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue("Should request promoted ongoing in extras", extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals("849201", extras.getCharSequence("android.shortCriticalText")?.toString())

        // Action checks
        assertEquals(2, notification.actions?.size ?: 0)
        val actions = notification.actions!!
        assertEquals("复制", actions[0].title.toString())
        assertEquals("关闭", actions[1].title.toString())
    }

    @Test
    fun receiverCopiesOtpToClipboardAndCancelsNotification() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        channels(app)

        val notificationId = 999
        val manager = app.getSystemService(NotificationManager::class.java)
        val dummy = NotificationCompat.Builder(app, "otp_island")
            .setSmallIcon(R.drawable.ic_hongshu)
            .setContentTitle("Test")
            .build()
        manager.notify(notificationId, dummy)
        assertNotNull(shadowOf(manager).getNotification(notificationId))

        // Trigger Copy Action
        val receiver = OtpNotificationReceiver()
        val copyIntent = Intent(ACTION_COPY_OTP).apply {
            putExtra(EXTRA_OTP_CODE, "5201314")
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        receiver.onReceive(app, copyIntent)

        // Verify clipboard
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("5201314", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        // Verify notification is cancelled
        assertNull(shadowOf(manager).getNotification(notificationId))
    }

    @Test
    fun receiverDismissesNotificationWithoutTouchingClipboard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        channels(app)

        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("pre", "original"))

        val notificationId = 888
        val manager = app.getSystemService(NotificationManager::class.java)
        val dummy = NotificationCompat.Builder(app, "otp_island")
            .setSmallIcon(R.drawable.ic_hongshu)
            .setContentTitle("Test")
            .build()
        manager.notify(notificationId, dummy)
        assertNotNull(shadowOf(manager).getNotification(notificationId))

        // Trigger Dismiss Action
        val receiver = OtpNotificationReceiver()
        val dismissIntent = Intent(ACTION_DISMISS_OTP).apply {
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        receiver.onReceive(app, dismissIntent)

        // Notification cancelled, clipboard untouched
        assertNull(shadowOf(manager).getNotification(notificationId))
        assertEquals("original", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun localSmsOrSameDeviceDoesNotTriggerOtpIsland() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        channels(app)

        val selfDevice = "phone-a"
        val sameDevice = "phone-a"
        val remoteDevice = "phone-b"

        // 1. Same device (local SMS captured on this device) -> should NOT notify
        assertFalse(shouldNotify(sameDevice, selfDevice, false, true))

        // 2. Remote device -> should notify
        assertTrue(shouldNotify(remoteDevice, selfDevice, false, true))

        // 3. Historical message from remote -> should NOT notify
        assertFalse(shouldNotify(remoteDevice, selfDevice, true, true))
    }
}
