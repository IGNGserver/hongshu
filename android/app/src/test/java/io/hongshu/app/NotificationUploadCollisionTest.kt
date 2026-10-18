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
class NotificationUploadCollisionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val t = 1_700_000_000_000L
    private val sim = SimMapping(7, 0, "+1234567890", "SIM1")
    private val sms = SmsRecord(sim.phone, "10086", "synthetic repeated", t, sim.sub)

    private fun inputs(count: Int, sender: String = sms.sender, key: String = "thread", body: String = sms.body) =
        parseSmsNotification(NotificationSnapshot("com.android.mms", false, false, sender, body, null, null,
            List(count) { NotificationLine(sender, body, t) }, t + 500, t, key), setOf("com.android.mms"), t)
            .map { NotificationCaptureInput(SmsRecord("", it.sender, it.body, it.timestamp, -1), it.identity, it.uncertain) }

    private fun database(test: (String) -> Unit) {
        val name = "notification-upload-${UUID.randomUUID()}.db"
        Config(context).automaticSim = false
        try { test(name) } finally { context.deleteDatabase(name) }
    }

    // The mock uses exactly the server's wire fingerprint: repeated observations get
    // duplicate=true and the same ID, never a fictitious second saved message.
    private class MockHub {
        val saved = linkedMapOf<String, Long>()
        val duplicates = mutableListOf<Boolean>()
        var calls = 0
        fun request(batch: List<Pair<String, SmsRecord>>): JSONObject {
            calls++
            return JSONObject().put("acks", JSONArray().apply {
                batch.forEach { (_, m) ->
                    val fp = m.fingerprint()
                    val duplicate = fp in saved
                    val id = saved.getOrPut(fp) { saved.size + 1L }
                    duplicates.add(duplicate)
                    put(JSONObject().put("id", id).put("duplicate", duplicate).put("fingerprint", fp))
                }
            })
        }
    }

    private fun upload(store: Store, hub: MockHub, batch: List<Pair<String, SmsRecord>> = store.pending(false)) =
        uploadPending(Api(Config(context)), store, batch, request = hub::request)

    private fun syncedMessage(device: String) = JSONObject().put("id", 1).put("device_id", device)
        .put("receiver", sms.receiver).put("sender", sms.sender).put("body", sms.body)
        .put("timestamp", t).put("contact", "").put("historical", false)

    private fun assertCollision(store: Store, count: Int = 1) {
        assertTrue(store.pending(false).isEmpty())
        assertEquals(count, store.pendingCount())
        assertTrue(store.localPending().all { it.blocked == "notification_collision" })
        assertEquals(PendingCounts(collisions = count), store.pendingCounts())
        assertEquals(PendingState.NOTIFICATION_COLLISION,
            pendingState(sim.phone, "notification_collision", true, true, true))
    }

    @Test fun batchCollisionSurvivesRealUploadDuplicateAckRetryAndRestart() = database { name ->
        val hub = MockHub()
        Store(context, name).use { store ->
            assertEquals(2, store.queueNotifications(inputs(2)))
            store.assignReceiver(store.localPending().map { it.key }, sim)
            val batch = store.pending(false)
            assertEquals(1, batch.size)
            // Hub commits, but the client never receives its first ACK.
            assertThrows(java.io.IOException::class.java) {
                uploadPending(Api(Config(context)), store, batch, request = {
                    hub.request(it)
                    throw java.io.IOException("synthetic interrupted response")
                })
            }
            upload(store, hub)
            assertEquals(listOf(false, true), hub.duplicates)
            assertEquals(1, hub.saved.size)
            assertCollision(store)
            store.retryBlocked()
            store.retryInvalid()
            store.ack(store.localPending().map { it.key })
            assertCollision(store)
        }
        Store(context, name).use { store ->
            assertEquals(0, store.queueNotifications(inputs(2)))
            upload(store, hub)
            assertEquals(2, hub.calls)
            assertCollision(store)
        }
    }

    @Test fun laterOccurrenceAfterAckCannotReuseFingerprintAcrossBatchesOrReopen() = database { name ->
        val hub = MockHub()
        Store(context, name).use { store ->
            store.queueNotifications(inputs(1))
            store.assignReceiver(store.localPending().map { it.key }, sim)
            upload(store, hub)
            assertEquals(0, store.pendingCount())
        }
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(inputs(2)))
            store.assignReceiver(store.localPending().map { it.key }, sim)
            upload(store, hub)
            assertEquals(1, hub.calls)
            assertCollision(store)
        }
    }

    @Test fun collisionsAcrossDatabasePagesDoNotStarveUnrelatedReadyMessages() = database { name ->
        val hub = MockHub()
        Store(context, name).use { store ->
            store.queueNotifications(inputs(105))
            // localPending is paginated; explicitly assign every retained occurrence.
            val keys = store.localPending(200).map { it.key }
            assertEquals(105, keys.size)
            store.assignReceiver(keys, sim)
            store.queue(sms.copy(body = "synthetic independent", timestamp = t + 1))
            upload(store, hub, store.pending(false, limit = 1))
            val next = store.pending(false)
            assertEquals("synthetic independent", next.single().second.body)
            upload(store, hub, next)
            assertEquals(2, hub.saved.size)
            assertCollision(store, 104)
        }
    }

    @Test fun receiverResolvedFromSimMappingKeepsWireOwnershipAfterAckAndReopen() = database { name ->
        val hub = MockHub()
        fun mappedInputs(count: Int) = inputs(count).map {
            it.copy(message = it.message.copy(subscriptionId = sim.sub))
        }
        Store(context, name).use { store ->
            store.saveSim(sim)
            store.queueNotifications(mappedInputs(1))
            upload(store, hub)
            assertEquals(0, store.pendingCount())
        }
        Store(context, name).use { store ->
            assertEquals(1, store.queueNotifications(mappedInputs(2)))
            upload(store, hub)
            assertEquals(1, hub.calls)
            assertCollision(store)
        }
    }

    @Test fun receiverChangeAfterPendingAndDuringHttpAckCannotEraseCollision() = database { name ->
        val hub = MockHub()
        Store(context, name).use { store ->
            store.queueNotifications(inputs(1))
            store.assignReceiver(store.localPending().map { it.key }, sim)
            upload(store, hub)
            store.queueNotifications(inputs(1, key = "other-thread"))
            val other = SimMapping(8, 1, "+9876543210", "SIM2")
            val key = store.localPending().single().key
            store.assignReceiver(listOf(key), other)
            val beforeResolution = store.pending(false)
            assertEquals(other.phone, beforeResolution.single().second.receiver)
            uploadPending(Api(Config(context)), store, beforeResolution, request = { batch ->
                val response = hub.request(batch)
                store.assignReceiver(listOf(key), sim)
                response
            })
            assertCollision(store)
            // Changing back to a genuinely different receiver may safely retry its own
            // already accepted fingerprint; this is one capture, not a second occurrence.
            store.assignReceiver(listOf(key), other)
            upload(store, hub)
            assertEquals(0, store.pendingCount())
            assertEquals(listOf(false, false, true), hub.duplicates)
        }
    }

    @Test fun senderEnrichmentRechecksFingerprintWithoutChangingMessageTime() = database { name ->
        val hub = MockHub()
        val body = "Google synthetic repeated"
        val numeric = sms.copy(sender = "106812345678", body = body)
        Store(context, name).use { store ->
            store.queueNotifications(inputs(1, sender = numeric.sender, body = body))
            assertFalse(store.queue(numeric.copy(networkTime = t - 60_000)))
            upload(store, hub)
            store.queueNotifications(inputs(1, sender = "Google", key = "brand", body = body))
            assertFalse(store.queue(numeric.copy(networkTime = t - 30_000)))
            assertEquals(numeric.sender, store.localPending().single().sender)
            assertEquals(t, store.localPending().single().timestamp)
            upload(store, hub)
            assertEquals(1, hub.calls)
            assertCollision(store)
        }
    }

    @Test fun uniqueCrossSourceAssociationStillAcceptsDuplicateAck() = database { name ->
        val hub = MockHub()
        Store(context, name).use { store ->
            store.queueNotifications(inputs(1))
            assertFalse(store.queue(sms))
            assertFalse(store.captureProvider(41, sms))
            val batch = store.pending(false)
            hub.request(batch) // prior request accepted without a local ACK
            upload(store, hub, batch)
            assertEquals(listOf(false, true), hub.duplicates)
            assertEquals(0, store.pendingCount())
            assertEquals(0, store.queueNotifications(inputs(1)))
        }
    }

    private fun assertHistoricalAssociationKeepsCollision(providerFirst: Boolean) = database { name ->
        val hub = MockHub()
        lateinit var collisionKey: String
        Store(context, name).use { store ->
            store.queueNotifications(inputs(1))
            if (providerFirst) assertFalse(store.captureProvider(40, sms))
            else assertFalse(store.queue(sms))
            upload(store, hub)
            assertEquals(0, store.pendingCount())

            assertEquals(1, store.queueNotifications(inputs(2)))
            collisionKey = store.localPending().single().key
            store.assignReceiver(listOf(collisionKey), sim)
            assertCollision(store)
            val later = sms.copy(timestamp = t + 500)
            if (providerFirst) assertTrue(store.queue(later))
            else assertTrue(store.captureProvider(41, later))
            assertEquals(0, store.reconcileNotificationCaptures())
            assertEquals("notification_collision", store.localPending().single { it.key == collisionKey }.blocked)
            assertEquals(2, store.pendingCount())
        }
        Store(context, name).use { store ->
            assertEquals(0, store.reconcileNotificationCaptures())
            store.applySync(JSONArray().put(syncedMessage("synthetic-device")), 1, self = "synthetic-device")
            assertEquals("notification_collision", store.localPending().single { it.key == collisionKey }.blocked)
            assertEquals(0, store.queueNotifications(inputs(2)))
        }
    }

    @Test fun acknowledgedBroadcastRemainsAProviderCandidateDuringReconcile() =
        assertHistoricalAssociationKeepsCollision(providerFirst = false)

    @Test fun acknowledgedProviderRemainsABroadcastCandidateDuringReconcile() =
        assertHistoricalAssociationKeepsCollision(providerFirst = true)

    @Test fun notificationAfterAcknowledgedSmsUsesSameCanonicalOwner() = database { name ->
        val hub = MockHub()
        Store(context, name).use { store ->
            store.queue(sms)
            upload(store, hub)
            assertEquals(0, store.queueNotifications(inputs(1)))
            assertEquals(0, store.pendingCount())
            assertFalse(store.captureProvider(41, sms))
            assertEquals(0, store.pendingCount())
        }
    }

    @Test fun syncedCopyCannotDeleteOrHideOtherNotificationOccurrence() = database { name ->
        val hub = MockHub()
        val config = Config(context)
        config.deviceId = "synthetic-device"
        Store(context, name).use { store ->
            store.queueNotifications(inputs(2))
            store.assignReceiver(store.localPending().map { it.key }, sim)
            upload(store, hub)
            store.applySync(JSONArray().put(syncedMessage(config.deviceId)), 1, self = config.deviceId)
            assertCollision(store)
            assertEquals(1, store.messagesWithLocal(sender = sms.sender).count { it.local })
        }
    }

    @Test fun syncBeforePendingSelectionProvesOnlyOneOccurrence() = database { name ->
        Store(context, name).use { store ->
            store.queueNotifications(inputs(2))
            store.assignReceiver(store.localPending().map { it.key }, sim)
            store.applySync(JSONArray().put(syncedMessage("synthetic-device")), 1, self = "synthetic-device")
            assertCollision(store)
        }
    }

    @Test fun actualUploadGuardRejectsStaleDuplicateBatchEvenWithoutPendingSelection() = database { name ->
        val hub = MockHub()
        Store(context, name).use { store ->
            store.queueNotifications(inputs(2))
            store.assignReceiver(store.localPending().map { it.key }, sim)
            val batch = store.localPending().map { it.key to sms }
            upload(store, hub, batch)
            assertEquals(1, hub.saved.size)
            assertEquals(1, hub.duplicates.size)
            assertCollision(store)
        }
    }
}
