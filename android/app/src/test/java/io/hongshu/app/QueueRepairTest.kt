package io.hongshu.app

import android.app.Application
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueRepairTest {
    private val phone = "+8613800000000"
    private val sim = SimMapping(7, 0, phone, "SIM1")

    private fun withStore(test: (Store) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "queue-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun unknown(body: String = "synthetic code 1234", time: Long = 1_700_000_000_000) =
        SmsRecord("", "+86 10086", body, time, -1)

    @Test
    fun simLessBroadcastInheritsSimFromLaterProviderRow() = withStore { store ->
        store.saveSim(sim)
        assertTrue(store.queue(unknown()))
        assertTrue(store.pending().isEmpty())
        val provider = SmsRecord(phone, "10086", "synthetic code 1234", 1_700_000_004_000, 7)
        assertFalse(store.captureProvider(100, provider))
        assertEquals(1, store.pendingCount())
        assertEquals(phone, store.pending().single().second.receiver)
        assertEquals(PendingCounts(ready = 1), store.pendingCounts())
    }

    @Test
    fun simLessProviderRowPairsWithKnownBroadcast() = withStore { store ->
        store.saveSim(sim)
        assertTrue(store.queue(SmsRecord(phone, "10086", "synthetic", 1_700_000_000_000, 7)))
        assertFalse(
            store.captureProvider(100, SmsRecord("", "+8610086", "synthetic", 1_700_000_001_000, -1))
        )
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun numberChangeOnSameSubscriptionStillPairs() = withStore { store ->
        store.queue(SmsRecord(phone, "10086", "before change", 100, 7))
        assertFalse(store.captureProvider(101, SmsRecord("+8613900000000", "10086", "before change", 101, 7)))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun twoSimLessCapturesAreStillNotMergedByContent() = withStore { store ->
        assertTrue(store.queue(unknown()))
        assertTrue(store.captureProvider(100, unknown().copy(timestamp = 1_700_000_001_000)))
        assertEquals(2, store.pendingCount())
    }

    @Test
    fun differentSendersOrSimsAreNotPaired() = withStore { store ->
        store.queue(SmsRecord(phone, "10086", "same text", 1_700_000_000_000, 7))
        assertTrue(store.captureProvider(100, SmsRecord("", "10010", "same text", 1_700_000_000_000, -1)))
        assertTrue(
            store.captureProvider(101, SmsRecord("+8613900000000", "10086", "same text", 1_700_000_000_000, 8))
        )
        assertEquals(3, store.pendingCount())
    }

    @Test
    fun legacyLeftoverIsFoldedIntoAcknowledgedCounterpart() = withStore { store ->
        store.saveSim(sim)
        val provider = SmsRecord(phone, "10086", "synthetic legacy", 1_700_000_000_000, 7)
        assertTrue(store.captureProvider(100, provider))
        store.ack(store.pending().map { it.first })
        // Shape written by earlier versions: an unpaired SIM-less broadcast row.
        val db = store.writableDatabase
        val key = "legacy-broadcast"
        db.insertOrThrow(
            "captures",
            null,
            ContentValues().apply {
                put("key", key)
                put("broadcast_key", key)
                put("receiver", "")
                put("sender", "+86 10086")
                put("body", provider.body)
                put("timestamp", provider.timestamp + 2000)
                put("sub", -1)
                put("source", "broadcast")
            },
        )
        db.insertOrThrow(
            "outbox",
            null,
            ContentValues().apply {
                put("key", key)
                put("receiver", "")
                put("sender", "+86 10086")
                put("body", provider.body)
                put("timestamp", provider.timestamp + 2000)
                put("sub", -1)
                put("slot", -1)
            },
        )
        assertEquals(PendingCounts(needsSim = 1), store.pendingCounts())
        assertEquals(1, store.reconcileUnknownSimCaptures())
        assertEquals(0, store.pendingCount())
        assertEquals(0, store.reconcileUnknownSimCaptures())
        store.readableDatabase
            .rawQuery("SELECT broadcast_key FROM captures WHERE provider_id=100", null)
            .use {
                assertTrue(it.moveToFirst())
                assertEquals(key, it.getString(0))
            }
    }

    @Test
    fun userAssignedReceiverMakesRowsUploadable() = withStore { store ->
        store.saveSim(sim)
        store.queue(unknown("a"))
        store.queue(unknown("b", 1_700_000_100_000))
        val keys = store.unresolvedKeys()
        assertEquals(2, keys.size)
        assertEquals(2, store.assignReceiver(keys, sim))
        assertTrue(store.unresolvedKeys().isEmpty())
        assertEquals(listOf(phone, phone), store.pending().map { it.second.receiver })
        assertEquals(7, store.pending().first().second.subscriptionId)
        try {
            store.assignReceiver(keys, sim.copy(phone = ""))
            fail("unconfirmed receiver accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun simNotConfirmedRowsCanBeReassigned() = withStore { store ->
        store.saveSim(sim)
        store.queue(SmsRecord(phone, "10086", "rejected", 1, 7))
        val key = store.pending().single().first
        store.block(key, "sim_not_confirmed")
        assertEquals(listOf(key), store.unresolvedKeys())
        store.assignReceiver(listOf(key), sim)
        assertEquals(0, store.blockedCount())
    }

    @Test
    fun discardKeepsLedgerAndRetryReleasesInvalidRows() = withStore { store ->
        store.saveSim(sim)
        val record = SmsRecord(phone, "10086", "synthetic", 1_700_000_000_000, 7)
        store.queue(record)
        store.queue(record.copy(body = "second"))
        val first = store.pending().first { it.second.body == "synthetic" }.first
        val second = store.pending().first { it.second.body == "second" }.first
        store.block(first, "invalid_message")
        assertEquals(listOf(first), store.invalidKeys())
        store.retryInvalid(listOf(first))
        assertEquals(0, store.blockedCount())
        assertEquals(1, store.discard(listOf(second)))
        assertFalse(store.queue(record.copy(body = "second")))
        assertEquals(1, store.pendingCount())
        store.block(first, "invalid_message")
        store.retryInvalid()
        assertTrue(store.invalidKeys().isEmpty())
    }

    @Test
    fun unconfirmedMappingIsNeedsSimNotInvalid() = withStore { store ->
        store.writableDatabase.execSQL("INSERT INTO sims VALUES (7,0,'unknown','SIM1')")
        store.queue(SmsRecord("", "10086", "synthetic", 1, 7))
        assertTrue(store.pending().isEmpty())
        assertEquals(0, store.blockedCount())
        assertEquals(PendingCounts(needsSim = 1), store.pendingCounts())
    }
}
