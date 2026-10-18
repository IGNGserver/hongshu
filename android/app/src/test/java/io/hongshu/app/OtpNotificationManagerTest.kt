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
        val notificationId = OtpNotificationManager.NOTIFICATION_ID
        val notification = shadowManager.getNotification(OtpNotificationManager.notificationTag(app, 12345L), notificationId)
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

        val notificationId = OtpNotificationManager.NOTIFICATION_ID
        val tag = OtpNotificationManager.notificationTag(app, 999)
        val manager = app.getSystemService(NotificationManager::class.java)
        val dummy = NotificationCompat.Builder(app, "otp_island")
            .setSmallIcon(R.drawable.ic_hongshu)
            .setContentTitle("Test")
            .build()
        manager.notify(tag, notificationId, dummy)
        assertNotNull(shadowOf(manager).getNotification(tag, notificationId))

        // Trigger Copy Action
        val receiver = OtpNotificationReceiver()
        val copyIntent = Intent(ACTION_COPY_OTP).apply {
            putExtra(EXTRA_OTP_CODE, "5201314")
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_TAG, tag)
        }
        receiver.onReceive(app, copyIntent)

        // Verify clipboard
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("5201314", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))

        // Verify notification is cancelled
        assertNull(shadowOf(manager).getNotification(tag, notificationId))
    }

    @Test
    fun receiverDismissesNotificationWithoutTouchingClipboard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        channels(app)

        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("pre", "original"))

        val notificationId = OtpNotificationManager.NOTIFICATION_ID
        val tag = OtpNotificationManager.notificationTag(app, 888)
        val manager = app.getSystemService(NotificationManager::class.java)
        val dummy = NotificationCompat.Builder(app, "otp_island")
            .setSmallIcon(R.drawable.ic_hongshu)
            .setContentTitle("Test")
            .build()
        manager.notify(tag, notificationId, dummy)
        assertNotNull(shadowOf(manager).getNotification(tag, notificationId))

        // Trigger Dismiss Action
        val receiver = OtpNotificationReceiver()
        val dismissIntent = Intent(ACTION_DISMISS_OTP).apply {
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_TAG, tag)
        }
        receiver.onReceive(app, dismissIntent)

        // Notification cancelled, clipboard untouched
        assertNull(shadowOf(manager).getNotification(tag, notificationId))
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

    @Test fun otpActionsAndTagsCannotReplaceOrCancelServiceAndSummaryNotifications() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        channels(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(manager)
        for (id in 1..3) manager.notify(id, NotificationCompat.Builder(app, "connection")
            .setSmallIcon(R.drawable.ic_hongshu).setContentTitle("base-$id").build())
        val ids = listOf(1L, 2L, 3L, 4294967296L) // 1 and 2^32 used to fold to the same ID.
        for (id in ids) assertTrue(OtpNotificationManager.showOtpNotification(app, id, "code-$id", "10086"))
        val notifications = ids.map { shadow.getNotification(OtpNotificationManager.notificationTag(app, it), 1) }
        assertEquals(ids.size, notifications.map { it.actions[0].actionIntent }.toSet().size)
        notifications[1].actions[0].actionIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadow.getNotification(OtpNotificationManager.notificationTag(app, 2), 1))
        assertEquals("code-2", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        for (id in 1..3) assertEquals("base-$id", shadow.getNotification(id).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        manager.cancel(2)
        assertNotNull(shadow.getNotification(OtpNotificationManager.notificationTag(app, 1), 1))
        assertNotNull(shadow.getNotification(OtpNotificationManager.notificationTag(app, 3), 1))
    }

    @Test fun conversationCopyAlsoMarksClipboardSensitive() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        copyOtp(app, "123456")
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("123456", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test fun completingInitialSyncNeverPromotesOldOtpMessages() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        channels(app)
        val config = Config(app)
        config.deviceId = "self"
        config.notify = true
        config.liveOtpIsland = true
        val name = "otp-page-${java.util.UUID.randomUUID()}.db"
        val now = System.currentTimeMillis()
        fun message(id: Long, timestamp: Long, source: String = "remote", historical: Boolean = false) =
            org.json.JSONObject().put("id", id).put("device_id", source).put("receiver", "+1234567890")
                .put("sender", "10086").put("body", "验证码为 849201").put("timestamp", timestamp).put("historical", historical)
        try {
            Store(app, name).use { store ->
                store.initializeSyncHead(4)
                val page = org.json.JSONObject().put("epoch", 1).put("cursor", 4).put("messages",
                    org.json.JSONArray().put(message(1, now - 120_000)).put(message(2, now))
                        .put(message(3, now, "self")).put(message(4, now, historical = true)))
                applyMessagePage(app, config, store, page, pairedAt = now - 60_000)
                assertTrue(store.initialComplete())
                val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
                assertNull(notifications.getNotification(OtpNotificationManager.notificationTag(app, 1, 1), 1))
                assertNotNull(notifications.getNotification(OtpNotificationManager.notificationTag(app, 2, 1), 1))
                assertNull(notifications.getNotification(OtpNotificationManager.notificationTag(app, 3, 1), 1))
                assertNull(notifications.getNotification(OtpNotificationManager.notificationTag(app, 4, 1), 1))
                // The code owns its dedicated channel; it is not also counted for the summary.
                assertEquals(0L, store.pendingNotification())
                applyMessagePage(app, config, store, page, pairedAt = now - 60_000)
                assertEquals(0L, store.pendingNotification())
            }
        } finally { app.deleteDatabase(name) }
    }
}
