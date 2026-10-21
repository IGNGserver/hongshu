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
class NotificationDuplicateAbsorptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val t = 1_700_000_000_000L
    private val sim = SimMapping(7, 0, "+1234567890", "SIM1")

    private fun database(test: (String) -> Unit) {
        val name = "notification-absorb-${UUID.randomUUID()}.db"
        Config(context).apply {
            automaticSim = false
            autoIgnoreUnassigned = false
        }
        try {
            test(name)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun syncedMessage(device: String, receiver: String, body: String, timestamp: Long) =
        JSONObject()
            .put("id", 1)
            .put("device_id", device)
            .put("receiver", receiver)
            .put("sender", "10086")
            .put("body", body)
            .put("timestamp", timestamp)
            .put("contact", "")
            .put("historical", false)

    @Test
    fun contactNameNotificationIsAbsorbedAfterAuthoritativeAck() = database { name ->
        val body = "synthetic coffee"
        Store(context, name).use { store ->
            assertTrue(store.queueNotification(SmsRecord("", "张三", body, t, -1)))
            assertTrue(store.queue(SmsRecord(sim.phone, "10086", body, t + 30_000, sim.sub), 0))
            assertEquals(2, store.pendingCount())

            val batch = store.pending(false)
            assertEquals(1, batch.size)
            store.ack(batch.map { it.first })

            assertEquals(0, store.pendingCount())
            assertTrue(store.localPending().isEmpty())
            assertEquals(0, store.pendingCounts().total)
            assertEquals(0, store.pendingCounts().ignored)

            val receiptTarget =
                store.readableDatabase
                    .rawQuery("SELECT capture_key FROM notification_receipts", null)
                    .use {
                        assertTrue(it.moveToFirst())
                        it.getString(0)
                    }
            assertEquals(batch.single().first, receiptTarget)
            // The moved receipt keeps the exact notification replay idempotent.
            assertFalse(store.queueNotification(SmsRecord("", "张三", body, t, -1)))
            assertEquals(0, store.pendingCount())
        }
    }

    @Test
    fun syncProofAbsorbsNotificationWhenAckWasLost() = database { name ->
        val body = "synthetic sync proof"
        val providerTime = t + 30_000
        Config(context).deviceId = "device-sync"
        Store(context, name).use { store ->
            assertTrue(store.queueNotification(SmsRecord("", "张三", body, t, -1)))
            assertTrue(store.queue(SmsRecord(sim.phone, "10086", body, providerTime, sim.sub), 0))
            assertEquals(2, store.pendingCount())

            store.applySync(
                JSONArray().put(syncedMessage("device-sync", sim.phone, body, providerTime)),
                cursor = 1,
                self = "device-sync",
            )

            assertEquals(0, store.pendingCount())
            assertTrue(store.localPending().isEmpty())
        }
    }

    @Test
    fun syncCopyFromAnotherDeviceDoesNotAbsorbLocalNotification() = database { name ->
        val body = "synthetic remote"
        Config(context).deviceId = "device-self"
        Store(context, name).use { store ->
            assertTrue(store.queueNotification(SmsRecord("", "张三", body, t, -1)))
            store.applySync(
                JSONArray().put(syncedMessage("device-other", sim.phone, body, t)),
                cursor = 1,
                self = "device-self",
            )
            assertEquals(1, store.pendingCount())
            assertEquals(1, store.pendingCounts().needsSim)
        }
    }

    @Test
    fun autoIgnoreHidesUnresolvedNotificationAndCanRestore() = database { name ->
        Config(context).autoIgnoreUnassigned = true
        Store(context, name).use { store ->
            store.saveSim(sim)
            assertTrue(store.queueNotification(SmsRecord("", "张三", "synthetic hidden", t, -1)))
            assertEquals(1, store.autoIgnoreUnassigned())

            val counts = store.pendingCounts()
            assertEquals(PendingCounts(ignored = 1), counts)
            assertEquals(0, counts.total)
            assertEquals(0, store.pendingCount())
            assertTrue(store.localPending().isEmpty())
            assertTrue(store.localMessages().isEmpty())
            assertTrue(store.pending(false).isEmpty())

            assertEquals(1, store.restoreAutoIgnored())
            assertEquals(PendingCounts(needsSim = 1), store.pendingCounts())
            assertEquals(1, store.pendingCount())
        }
    }

    @Test
    fun withoutUsableSimOnlyNotificationCopiesAreIgnored() = database { name ->
        Config(context).autoIgnoreUnassigned = true
        Store(context, name).use { store ->
            assertTrue(store.queueNotification(SmsRecord("", "张三", "synthetic note", t, -1)))
            assertTrue(store.queue(SmsRecord("", "95588", "synthetic broadcast", t, 7), 0))
            assertEquals(1, store.autoIgnoreUnassigned())

            val counts = store.pendingCounts()
            assertEquals(1, counts.ignored)
            assertEquals(1, counts.needsSim)
            assertEquals(1, counts.total)
        }
    }

    @Test
    fun laterAuthoritativeCopyRestoresAutoIgnoredNotification() = database { name ->
        Config(context).autoIgnoreUnassigned = true
        val body = "synthetic numeric"
        Store(context, name).use { store ->
            store.saveSim(sim)
            assertTrue(store.queueNotification(SmsRecord("", "10086", body, t, -1)))
            assertEquals(1, store.autoIgnoreUnassigned())
            assertEquals(1, store.pendingCounts().ignored)

            assertFalse(store.queue(SmsRecord(sim.phone, "10086", body, t + 30_000, sim.sub), 0))
            val row = store.localPending().single()
            assertEquals("", row.blocked)
            assertEquals(sim.phone, row.receiver)
            assertEquals(PendingCounts(ready = 1), store.pendingCounts())
        }
    }

    @Test
    fun discardedAutoIgnoredRowsKeepTheLedgerAndDoNotReappear() = database { name ->
        Config(context).autoIgnoreUnassigned = true
        val message = SmsRecord("", "张三", "synthetic discard", t, -1)
        Store(context, name).use { store ->
            store.saveSim(sim)
            assertTrue(store.queueNotification(message))
            assertEquals(1, store.autoIgnoreUnassigned())
            assertEquals(1, store.discardAutoIgnored())
            assertEquals(0, store.pendingCount())
            assertTrue(store.autoIgnoredKeys().isEmpty())
            // The capture/receipt ledger still records this observation.
            assertFalse(store.queueNotification(message))
            assertEquals(0, store.pendingCount())
        }
    }

    @Test
    fun savingTheMissingSimRestoresAnAutoIgnoredBroadcastRow() = database { name ->
        Config(context).autoIgnoreUnassigned = true
        val other = SimMapping(8, 1, "+9876543210", "SIM2")
        Store(context, name).use { store ->
            store.saveSim(other)
            assertTrue(store.queue(SmsRecord("", "95588", "synthetic sim restore", t, 7), 0))
            assertEquals(1, store.autoIgnoreUnassigned())
            assertEquals(1, store.pendingCounts().ignored)

            store.updateSimMappings(listOf(sim), automatic = false)
            assertEquals(0, store.pendingCounts().ignored)
            assertEquals(PendingCounts(ready = 1), store.pendingCounts())
            assertEquals(1, store.pendingCount())
        }
    }

    @Test
    fun ignoredRowsDoNotEnterThePromptTotalOrPendingState() {
        val counts = PendingCounts(ignored = 3)
        assertEquals(0, counts.total)
        assertEquals("没有需要处理的未上传短信", counts.label())
        assertEquals(
            PendingState.AUTO_IGNORED,
            pendingState("", AUTO_IGNORED, upload = true, serverAllowed = true, connected = true),
        )
    }
}
