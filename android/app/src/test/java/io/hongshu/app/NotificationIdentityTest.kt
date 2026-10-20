package io.hongshu.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationIdentityTest {
    private val t = 1_700_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sms = SmsRecord("+1234567890", "10086", "synthetic repeated", t, 7)

    private fun snapshot(vararg times: Long) = NotificationSnapshot(
        "com.android.mms", false, false, "10086", sms.body, null, null,
        times.map { NotificationLine("10086", sms.body, it) }, t + 500, t,
        "synthetic-thread",
    )

    private fun inputs(snapshot: NotificationSnapshot): List<NotificationCaptureInput> =
        parseSmsNotification(snapshot, setOf("com.android.mms"), t).map {
            NotificationCaptureInput(SmsRecord("", it.sender, it.body, it.timestamp, -1), it.identity, it.uncertain)
        }

    private fun database(test: (String) -> Unit) {
        val name = "notification-identity-${UUID.randomUUID()}.db"
        try { test(name) } finally { context.deleteDatabase(name) }
    }

    @Test fun structuredRepeatedBodiesSurviveReorderingContainerTimeChangesAckAndReopen() = database { name ->
        val original = snapshot(t, t + 1_000)
        val replay = original.copy(lines = original.lines.reversed(), postTime = t + 60_000, whenTime = t + 60_000)
        Store(context, name).use { store ->
            assertEquals(2, store.queueNotifications(inputs(original)))
            assertEquals(setOf(t, t + 1_000), store.localPending().map { it.timestamp }.toSet())
            assertEquals(0, store.queueNotifications(inputs(original)))
            assertEquals(0, store.queueNotifications(inputs(replay)))
            assertEquals(1, store.queueNotifications(inputs(snapshot(t, t + 1_000, t + 2_000))))
            assertEquals(3, store.pendingCount())
        }
        Store(context, name).use { store ->
            assertEquals(0, store.queueNotifications(inputs(replay)))
            store.ack(store.localPending().map { it.key })
            assertEquals(0, store.pendingCount())
        }
        Store(context, name).use { store ->
            assertEquals(0, store.queueNotifications(inputs(snapshot(t + 2_000, t + 1_000, t))))
            assertEquals(0, store.pendingCount())
        }
    }

    @Test fun identityDoesNotChangeWhenContactsResolveLater() = database { name ->
        val first = inputs(snapshot(t).copy(lines = listOf(NotificationLine("合成联系人", sms.body, t))))
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(first))
            store.ack(store.localPending().map { it.key })
        }
        Store(context, name).use { store ->
            assertEquals(0, store.queueNotifications(first.map { it.copy(message = it.message.copy(sender = "10086")) }))
            assertEquals(0, store.pendingCount())
        }
    }

    @Test fun sameStructuredTupleKeepsMultiplicityAndMarksUncertainty() = database { name ->
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(inputs(snapshot(t))))
            assertEquals(1, store.queueNotifications(inputs(snapshot(t, t))))
            assertEquals(2, store.pendingCount())
            assertTrue(store.localPending().all { it.notificationUncertain })
            assertEquals(0, store.queueNotifications(inputs(snapshot(t, t))))
            store.assignReceiver(store.localPending().map { it.key }, SimMapping(7, 0, sms.receiver, "SIM1"))
            val pending = store.pending(false)
            assertEquals(1, pending.size)
            uploadPending(Api(Config(context)), store, pending, request = { batch ->
                JSONObject().put("acks", JSONArray().put(JSONObject().put("id", 1)
                    .put("duplicate", true).put("fingerprint", batch.single().second.fingerprint())))
            })
            assertEquals(1, store.pendingCount())
            assertEquals("notification_collision", store.localPending().single().blocked)
        }
        Store(context, name).use { store ->
            assertEquals(0, store.queueNotifications(inputs(snapshot(t, t))))
            assertTrue(store.pending(false).isEmpty())
            assertEquals(1, store.pendingCount())
            assertEquals("notification_collision", store.localPending().single().blocked)
        }
    }

    @Test fun plainNotificationWithChangedTimeIsPreservedAsUncertainObservation() = database { name ->
        val plain = snapshot()
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(inputs(plain)))
            assertEquals(0, store.queueNotifications(inputs(plain.copy(postTime = t + 1_000))))
            assertEquals(1, store.queueNotifications(inputs(plain.copy(whenTime = t + 1_000))))
            assertEquals(2, store.pendingCount())
            assertTrue(store.localPending().all { it.notificationUncertain })
            store.ack(store.localPending().map { it.key })
            assertEquals(0, store.queueNotifications(inputs(plain.copy(whenTime = t + 1_000))))
        }
    }

    @Test fun oneSmsCannotConsumeMultipleNotificationsEvenAfterAckAndReopen() = database { name ->
        Store(context, name).use { store ->
            assertTrue(store.queue(sms))
            store.ack(store.localPending().map { it.key })
            assertEquals(0, store.queueNotifications(inputs(snapshot(t))))
        }
        Store(context, name).use { store ->
            assertEquals(0, store.queueNotifications(inputs(snapshot(t))))
            assertEquals(1, store.queueNotifications(inputs(snapshot(t + 1_000))))
            assertEquals(0, store.reconcileNotificationCaptures())
            assertTrue(store.localPending().single().notificationUncertain)
            assertFalse(store.captureProvider(41, sms.copy(timestamp = t + 500)))
            assertEquals(1, store.pendingCount())
        }
    }

    @Test fun wholeBatchMustBeConsideredBeforeMatchingAnEarlierSms() = database { name ->
        Store(context, name).use { store ->
            assertTrue(store.queue(sms))
            assertEquals(2, store.queueNotifications(inputs(snapshot(t, t + 1_000))))
            assertEquals(3, store.pendingCount())
            assertEquals(2, store.localPending().count { it.notificationUncertain })
            repeat(2) { assertEquals(0, store.reconcileNotificationCaptures()) }
        }
    }

    @Test fun notificationFirstAmbiguitySurvivesProviderAndReconciliation() = database { name ->
        Store(context, name).use { store ->
            assertEquals(2, store.queueNotifications(inputs(snapshot(t, t + 1_000))))
            assertTrue(store.queue(sms.copy(timestamp = t + 500)))
            assertFalse(store.captureProvider(41, sms.copy(timestamp = t + 600)))
            assertEquals(0, store.reconcileNotificationCaptures())
            assertEquals(3, store.pendingCount())
            assertEquals(2, store.localPending().count { it.notificationUncertain })
        }
    }

    @Test fun notificationFirstUniqueLinkKeepsReceiptAfterBothSmsSourcesAndAck() = database { name ->
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(inputs(snapshot(t))))
            assertFalse(store.captureProvider(41, sms.copy(timestamp = t + 2_000)))
            assertFalse(store.queue(sms.copy(timestamp = t + 1_000)))
            assertEquals(sms.receiver, store.localPending().single().receiver)
            store.ack(store.localPending().map { it.key })
        }
        Store(context, name).use { store ->
            assertEquals(0, store.queueNotifications(inputs(snapshot(t))))
            assertTrue(store.captureProvider(42, sms.copy(timestamp = t + 3_000)))
            assertEquals(1, store.pendingCount())
        }
    }

    @Test fun twoSimsAreNeverCollapsedAndUnknownNotificationIsMarked() = database { name ->
        Store(context, name).use { store ->
            assertTrue(store.queue(sms))
            assertTrue(store.queue(sms.copy(receiver = "+9876543210", subscriptionId = 8)))
            assertEquals(1, store.queueNotifications(inputs(snapshot(t))))
            assertEquals(0, store.reconcileNotificationCaptures())
            assertEquals(3, store.pendingCount())
            assertTrue(store.localPending().single { it.subscriptionId < 0 }.notificationUncertain)
        }
    }

    @Test fun knownNotificationSimIsInheritedByUnknownSmsInReverseOrder() = database { name ->
        Store(context, name).use { store ->
            assertTrue(store.queue(sms.copy(receiver = "", subscriptionId = -1)))
            assertFalse(store.queueNotification(sms))
            assertEquals(sms.receiver, store.localPending().single().receiver)
            assertEquals(7, store.localPending().single().subscriptionId)
        }
    }

    @Test fun incompatibleSimsNeverLinkDespiteIdenticalBodyAndTime() = database { name ->
        Store(context, name).use { store ->
            assertTrue(store.queue(sms))
            assertTrue(store.queueNotification(sms.copy(receiver = "+9876543210", subscriptionId = 8)))
            assertEquals(0, store.reconcileNotificationCaptures())
            assertEquals(2, store.pendingCount())
        }
    }

    @Test fun missingSimEvidencePreservesBothRowsAndMarksTheNotification() = database { name ->
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(inputs(snapshot(t))))
            assertTrue(store.queue(sms.copy(receiver = "", subscriptionId = -1)))
            assertEquals(0, store.reconcileNotificationCaptures())
            assertEquals(2, store.pendingCount())
            assertEquals(1, store.localPending().count { it.notificationUncertain })
        }
    }

    @Test fun linkedSmsCannotOverwriteKnownNotificationNumberSnapshot() = database { name ->
        Store(context, name).use { store ->
            assertTrue(store.queueNotification(sms))
            assertFalse(store.queue(sms.copy(receiver = "+9876543210", timestamp = t + 500)))
            assertEquals(sms.receiver, store.localPending().single().receiver)
            assertEquals(7, store.localPending().single().subscriptionId)
        }
    }

    @Test fun versionNineMigrationPreservesQueueAckEvidenceSimAndWatermark() = database { name ->
        val legacy = sms.copy(body = "synthetic legacy")
        val queued = sms.copy(body = "synthetic queued")
        Store(context, name).use { store ->
            store.captureProvider(41, sms, advance = true)
            store.queueNotification(legacy)
            store.ack(store.localPending().map { it.key })
            store.queueNotification(queued)
            store.saveSim(SimMapping(7, 0, sms.receiver, "SIM1"))
            store.writableDatabase.execSQL("DROP TABLE notification_receipts")
            store.writableDatabase.execSQL("DROP TABLE upload_fingerprints")
            store.writableDatabase.version = 9
        }
        Store(context, name).use { store ->
            assertEquals(15, store.readableDatabase.version)
            assertEquals(41L, store.providerWatermark())
            assertEquals(1, store.sims().size)
            assertEquals(queued.body, store.localPending().single().body)
            assertTrue(store.localPending().single().notificationUncertain)
            assertFalse(store.queueNotification(legacy))
            assertFalse(store.queueNotification(queued))
            assertFalse(store.captureProvider(41, sms))
            assertEquals(1, store.pendingCount())
        }
    }

    @Test fun changedNotificationKeyPreservesObservationButMarksBothUncertain() = database { name ->
        fun observed(id: Int): List<NotificationCaptureInput> {
            val sender = android.app.Person.Builder().setName("10086").build()
            val self = android.app.Person.Builder().setName("self").build()
            val notification = android.app.Notification.Builder(context, "synthetic")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(android.app.Notification.MessagingStyle(self).addMessage(sms.body, t, sender))
                .build()
            val sbn = android.service.notification.StatusBarNotification("com.android.mms", "com.android.mms",
                id, null, 1000, 1, 0, notification, android.os.Process.myUserHandle(), t + 500)
            return inputs(notificationSnapshot(sbn))
        }
        val first = observed(1)
        val changedKey = observed(2)
        assertNotEquals(first.single().identity, changedKey.single().identity)
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(first))
            assertEquals(1, store.queueNotifications(changedKey))
            assertEquals(2, store.pendingCount())
            assertTrue(store.localPending().all { it.notificationUncertain })
            assertEquals(0, store.queueNotifications(changedKey))
        }
    }

    @Test fun acknowledgedLegacyEvidenceMarksParsedReplayUncertainAfterUpgrade() = database { name ->
        Store(context, name).use { store ->
            store.queueNotification(sms)
            store.ack(store.localPending().map { it.key })
            store.writableDatabase.execSQL("DROP TABLE notification_receipts")
            store.writableDatabase.execSQL("DROP TABLE upload_fingerprints")
            store.writableDatabase.version = 9
        }
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(inputs(snapshot(t))))
            assertTrue(store.localPending().single().notificationUncertain)
            store.assignReceiver(store.localPending().map { it.key }, SimMapping(7, 0, sms.receiver, "SIM1"))
            assertTrue(store.pending(false).isEmpty())
            assertEquals("notification_collision", store.localPending().single().blocked)
        }
    }
}
