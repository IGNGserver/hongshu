package io.hongshu.app

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

// Synthetic notifications only. Proves parsing, consent boundary and cross-source dedup;
// it cannot prove what a given ROM's Messages app puts into its notifications.
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationCaptureTest {
    private val packages = setOf("com.android.mms")
    private val t = 1_700_000_000_000

    private fun snapshot(
        packageName: String = "com.android.mms",
        title: String? = "10086",
        text: String? = "合成验证码 123456",
        lines: List<NotificationLine> = emptyList(),
        summary: Boolean = false,
        whenTime: Long = t,
    ) =
        NotificationSnapshot(
            packageName,
            summary,
            false,
            title,
            text,
            null,
            null,
            lines,
            t + 500,
            whenTime,
        )

    @Test
    fun onlySmsAppNotificationsAfterConsentAreParsed() {
        assertEquals(
            listOf(SmsNotification("10086", "合成验证码 123456", t)),
            parseSmsNotification(snapshot(), packages, t),
        )
        assertTrue(
            parseSmsNotification(snapshot(packageName = "com.tencent.mm"), packages, 0).isEmpty()
        )
        assertTrue(parseSmsNotification(snapshot(), packages, t + 1).isEmpty())
        assertTrue(parseSmsNotification(snapshot(summary = true), packages, 0).isEmpty())
        assertTrue(parseSmsNotification(snapshot(text = "3条新信息"), packages, 0).isEmpty())
        assertTrue(parseSmsNotification(snapshot(text = "2 new messages"), packages, 0).isEmpty())
        assertTrue(parseSmsNotification(snapshot(title = " "), packages, 0).isEmpty())
    }

    @Test
    fun messagingStyleSkipsOwnRepliesAndOldLines() {
        val parsed =
            parseSmsNotification(
                snapshot(
                    lines =
                        listOf(
                            NotificationLine("95588", "旧消息", t - 1),
                            NotificationLine(null, "我的回复", t + 1),
                            NotificationLine("95588", "合成新消息", t + 2),
                        )
                ),
                packages,
                t,
            )
        assertEquals(listOf(SmsNotification("95588", "合成新消息", t + 2)), parsed)
    }

    @Test
    fun numericTitlesAreNormalizedWithoutContactsPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("+8610086", notificationSender(context, "+86 100-86"))
        assertEquals("张三", notificationSender(context, "张三"))
    }

    @Test
    fun listenerSettingsFallBackToAppDetails() {
        val intents = notificationListenerIntents(ApplicationProvider.getApplicationContext())
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intents.first().action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
    }

    private fun store(test: (Context, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "notification-${UUID.randomUUID()}.db"
        try {
            test(context, name)
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun laterBroadcastUpgradesTheQueuedNotificationRow() = store { context, name ->
        Store(context, name).use { s ->
            assertTrue(s.queueNotification(SmsRecord("", "10086", "合成正文", t, -1)))
            assertFalse(s.queue(SmsRecord("+8613800000000", "10086", "合成正文", t + 30_000, 7), 0))
            assertEquals(1, s.pendingCount())
            val row = s.localPending().single()
            assertEquals("10086", row.sender)
            assertEquals(7, row.subscriptionId)
            assertEquals("+8613800000000", row.receiver)
            // The provider row for the same message attaches too, still without a second upload.
            assertFalse(
                s.captureProvider(41, SmsRecord("+8613800000000", "10086", "合成正文", t + 31_000, 7))
            )
            assertEquals(1, s.pendingCount())
        }
    }

    @Test
    fun identicalBodiesFromDifferentSendersOrKnownSimsAreIndependent() = store { context, name ->
        Store(context, name).use { s ->
            val message = SmsRecord("+1234567890", "10086", "synthetic identical", t, 7)
            assertTrue(s.queueNotification(message))
            assertTrue(s.queueNotification(message.copy(sender = "95588")))
            assertTrue(
                s.queueNotification(message.copy(receiver = "+9876543210", subscriptionId = 8))
            )
            assertTrue(s.queue(message.copy(sender = "张三")))
            assertTrue(s.queue(message.copy(receiver = "+1111111111", subscriptionId = 9)))
            assertEquals(5, s.pendingCount())
        }
    }

    @Test
    fun contactTitleWithoutSenderEvidenceDoesNotConsumeABroadcast() = store { context, name ->
        Store(context, name).use { s ->
            val message = SmsRecord("", "张三", "synthetic identical", t, -1)
            assertTrue(s.queueNotification(message))
            assertTrue(
                s.queue(
                    message.copy(receiver = "+1234567890", sender = "10086", subscriptionId = 7)
                )
            )
            assertEquals(2, s.pendingCount())
        }
    }

    @Test
    fun ambiguousCrossSourceCandidatesAreNotGuessedInEitherArrivalOrder() = store { context, name ->
        Store(context, name).use { s ->
            val message = SmsRecord("+1234567890", "10086", "synthetic ambiguous", t, 7)
            s.queueNotification(message)
            s.queueNotification(message.copy(receiver = "+9876543210", subscriptionId = 8))
            assertTrue(s.queue(message.copy(receiver = "", subscriptionId = -1)))
            val other = message.copy(body = "synthetic reverse")
            s.queue(other)
            s.queue(other.copy(receiver = "+9876543210", subscriptionId = 8))
            assertTrue(s.queueNotification(other.copy(receiver = "", subscriptionId = -1)))
            assertEquals(6, s.pendingCount())
        }
    }

    @Test
    fun lateUnknownProviderCannotEraseKnownSimFromNotification() = store { context, name ->
        Store(context, name).use { s ->
            val message = SmsRecord("+1234567890", "10086", "synthetic identity", t, 7)
            s.queueNotification(message)
            assertFalse(s.captureProvider(42, message.copy(receiver = "", subscriptionId = -1)))
            assertEquals(7, s.localPending().single().subscriptionId)
            assertEquals(message.receiver, s.localPending().single().receiver)
        }
    }

    @Test
    fun notificationNeverDuplicatesCapturedOrRepostedMessages() = store { context, name ->
        Store(context, name).use { s ->
            assertTrue(s.queue(SmsRecord("+8613800000000", "10086", "合成正文", t, 7), 0))
            assertFalse(s.queueNotification(SmsRecord("", "10086", "合成正文", t + 2_000, -1)))
            assertTrue(s.queueNotification(SmsRecord("", "95588", "另一条", t, -1)))
            assertFalse(s.queueNotification(SmsRecord("", "95588", "另一条", t + 5_000, -1)))
            // Same text far apart is a genuinely new message.
            assertTrue(s.queueNotification(SmsRecord("", "95588", "另一条", t + 10 * 60_000, -1)))
            assertEquals(3, s.pendingCount())
        }
    }

    @Test
    fun brandNotificationPairsWithNumericSmsWhenBodyNamesTheBrand() = store { context, name ->
        Store(context, name).use { s ->
            val body = "Google 验证码为 123456"
            assertTrue(s.queueNotification(SmsRecord("", "Google", body, t, -1)))
            assertFalse(
                s.queue(
                    SmsRecord(
                        "+8613800000000",
                        "106812345678",
                        body,
                        t + 30_000,
                        7,
                    )
                )
            )
            assertEquals(1, s.pendingCount())
            assertEquals("106812345678", s.localPending().single().sender)
            assertEquals("+8613800000000", s.localPending().single().receiver)
        }
    }

    @Test
    fun brandNotificationDoesNotGuessBetweenTwoCompatibleCandidates() = store { context, name ->
        Store(context, name).use { s ->
            val body = "Google 验证码为 654321"
            assertTrue(s.queue(SmsRecord("+8613800000000", "106812345678", body, t, 7)))
            assertTrue(s.queue(SmsRecord("+8613900000000", "106812345678", body, t, 8)))
            assertTrue(s.queueNotification(SmsRecord("", "Google", body, t, -1)))
            assertEquals(3, s.pendingCount())
            assertEquals(0, s.reconcileNotificationCaptures())
        }
    }

    @Test
    fun differentBrandTitlesWithTheSameBodyRemainIndependent() = store { context, name ->
        Store(context, name).use { s ->
            val body = "Google 验证码为 789012"
            assertTrue(s.queueNotification(SmsRecord("", "Google", body, t, -1)))
            assertTrue(s.queueNotification(SmsRecord("", "Amazon", body, t + 5_000, -1)))
            assertEquals(2, s.pendingCount())
        }
    }

    @Test
    fun persistenceHonorsSwitchesAndCaptureGeneration() = store { context, name ->
        val config = Config(context)
        val message = listOf(SmsNotification("10086", "合成正文", System.currentTimeMillis() + 1000))
        config.upload = false
        assertEquals(0, persistNotificationSms(context, message, config.captureGeneration, name, config.notificationGeneration))
        config.upload = true
        assertEquals(0, persistNotificationSms(context, message, config.captureGeneration, name, config.notificationGeneration))
        config.notificationCapture = true
        assertTrue(config.notificationSince > 0)
        assertEquals(
            0,
            persistNotificationSms(context, message, config.captureGeneration - 1, name, config.notificationGeneration),
        )
        assertEquals(1, persistNotificationSms(context, message, config.captureGeneration, name, config.notificationGeneration))
        config.notificationCapture = false
        assertEquals(0L, config.notificationSince)
    }

    @Test
    fun inboxChangesOnlyScanWhenAProviderPathIsEnabled() {
        assertFalse(inboxChangeTriggersScan(upload = false, recovery = true, history = true))
        assertFalse(inboxChangeTriggersScan(upload = true, recovery = false, history = false))
        assertTrue(inboxChangeTriggersScan(upload = true, recovery = true, history = false))
        assertTrue(inboxChangeTriggersScan(upload = true, recovery = false, history = true))
    }

    @Test
    fun delayedNotificationCannotCrossDisableAndReenableBoundary() = store { context, name ->
        val config = Config(context)
        config.upload = true
        config.notificationCapture = true
        val captureGeneration = config.captureGeneration
        val oldGeneration = config.notificationGeneration
        // Even a timestamp after the new consent boundary must not bypass the generation check.
        val delayed = listOf(SmsNotification("10086", "delayed synthetic", System.currentTimeMillis() + 1000))
        config.notificationCapture = false
        config.notificationCapture = true
        assertEquals(0, persistNotificationSms(context, delayed, captureGeneration, name, oldGeneration))
        assertEquals(0, persistNotificationSms(context,
            listOf(SmsNotification("10086", "old synthetic", config.notificationSince - 1)),
            captureGeneration, name, config.notificationGeneration))
        assertEquals(1, persistNotificationSms(context, delayed, captureGeneration, name, config.notificationGeneration))
    }
}
