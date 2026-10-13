package io.hongshu.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreTest {
    private fun withStore(test: (Store) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "audit-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionThreeUpgradePreservesIdentityQueueSIMsAndMessages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                store.queue(SmsRecord("+1234567890", "10086", "queued synthetic", 1700000000000, 7))
                store.saveSim(SimMapping(7, 0, "+1234567890", "synthetic"))
                store.applySync(JSONArray().put(message(1)), 1)
                val db = store.writableDatabase
                db.execSQL("DROP TABLE captures")
                db.execSQL("DROP TABLE contact_snapshot")
                db.execSQL("DROP INDEX outbox_ready")
                db.execSQL("DROP INDEX inbox_receiver")
                db.execSQL(
                    "DELETE FROM meta WHERE key IN ('provider_id','initial_head','initial_complete','notify_pending')"
                )
                db.version = 3
            }
            Store(context, name).use { store ->
                assertEquals(6, store.readableDatabase.version)
                assertEquals(1, store.pendingCount())
                assertEquals(1, store.sims().size)
                assertEquals(1L, store.cursor())
                assertEquals(1, store.messages("10086").size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionFourRevisitsOldHistoryWithoutLosingCapturedEvidence() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "history-upgrade-${java.util.UUID.randomUUID()}.db"
        val sms = SmsRecord("+1234567890", "10086", "synthetic", 100, 7)
        try {
            Store(context, name).use { store ->
                store.captureProvider(50, sms, advance = true)
                store.saveSim(SimMapping(7, 0, sms.receiver, "SIM1"))
                store.initializeHistoryBoundary(200)
                store.writableDatabase.execSQL("DELETE FROM meta WHERE key='history_boundary'")
                store.writableDatabase.version = 4
            }
            Store(context, name).use { store ->
                assertEquals(6, store.readableDatabase.version)
                assertEquals(0L, store.providerWatermark())
                assertEquals(1, store.pendingCount())
                assertEquals(1, store.sims().size)
                assertFalse(store.captureProvider(50, sms, advance = true))
                assertTrue(store.captureProvider(10, sms.copy(body = "older missed SMS")))
                assertEquals(2, store.pendingCount())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun automaticUpdatesDoNotRewriteCapturedNumbersOrDropUnresolvedSMS() = withStore { store ->
        val old = SimMapping(7, 0, "+1234567890", "SIM1")
        store.updateSimMappings(listOf(old), true)
        store.queue(SmsRecord(old.phone, "10086", "before change", 100, 7))
        store.updateSimMappings(listOf(old.copy(phone = "+9876543210")), true)
        assertFalse(
            store.captureProvider(101, SmsRecord("+9876543210", "10086", "before change", 101, 7))
        )
        assertEquals(old.phone, store.pending().single().second.receiver)
        store.queue(SmsRecord("", "10086", "unresolved", 200, 7))
        store.updateSimMappings(listOf(old.copy(phone = "")), true)
        assertTrue(store.sims().isEmpty())
        assertEquals(2, store.pendingCount())
        assertEquals(1, store.pending().size)
        store.updateSimMappings(listOf(old.copy(sub = 8)), true)
        assertEquals(1, store.pending().size) // new card must not resolve old subscription
        store.updateSimMappings(listOf(old), true)
        assertEquals(2, store.pending().size)
    }

    @Test
    fun captureLedgerSeparatesSIMsProviderRowsAndRemoteCache() = withStore { store ->
        val sms = SmsRecord("+1234567890", "10086", "synthetic SMS", 1700000000000, 7)
        store.applySync(JSONArray().put(message(1)), 1)
        assertTrue(store.captureProvider(100, sms)) // remote cached message is not a local capture
        assertTrue(store.captureProvider(101, sms.copy(timestamp = sms.timestamp + 1000)))
        assertTrue(
            store.captureProvider(102, sms.copy(receiver = "+9876543210", subscriptionId = 8))
        )
        assertFalse(store.captureProvider(100, sms))
        assertEquals(3, store.pendingCount())
    }

    @Test
    fun captureLedgerSurvivesACKAndPairsOnlyOneProviderRow() = withStore { store ->
        val sms = SmsRecord("+1234567890", "10086", "synthetic SMS", 1700000000000, 7)
        store.queue(sms)
        store.ack(store.pending().map { it.first })
        assertFalse(store.captureProvider(100, sms.copy(timestamp = sms.timestamp + 1000)))
        assertTrue(store.captureProvider(101, sms.copy(timestamp = sms.timestamp + 2000)))
        assertTrue(
            store.captureProvider(102, sms.copy(receiver = "+9876543210", subscriptionId = 8))
        )
        assertEquals(2, store.pendingCount())
    }

    @Test
    fun unidentifiedSIMsAreNotApproximatelyMerged() = withStore { store ->
        val sms = SmsRecord("", "10086", "synthetic SMS", 1700000000000, -1)
        store.queue(sms)
        assertTrue(store.captureProvider(100, sms.copy(timestamp = sms.timestamp + 1000)))
        assertEquals(2, store.pendingCount())
    }

    @Test
    fun connectionFloorAndProviderWatermarkUseDifferentClocks() = withStore { store ->
        store.resetForNewIdentity(connectedAt = 1000)
        assertEquals(1000L, store.captureSince())
        store.captureProvider(
            100,
            SmsRecord("+1234567890", "10086", "new", 3000, 7),
            advance = true,
        )
        store.captureProvider(
            101,
            SmsRecord("+1234567890", "10086", "late old date", 2000, 7),
            advance = true,
        )
        assertEquals(101L, store.providerWatermark())
        assertEquals(2, store.pendingCount())
    }

    @Test
    fun notificationIntentAndInitialBaselineSurvivePartialPages() = withStore { store ->
        store.initializeSyncHead(3)
        store.rememberEpoch(1)
        store.applySync(JSONArray().put(message(1)), 1, "self", 1800000000000, 1)
        assertFalse(store.initialComplete())
        assertEquals(0L, store.pendingNotification())
        store.applySync(JSONArray().put(message(2)).put(message(3)), 3, "self", 1800000000000, 1)
        assertTrue(store.initialComplete())
        assertEquals(0L, store.pendingNotification()) // retry must not announce initial history
        store.applySync(JSONArray().put(message(4)), 4, "self", 1800000000000, 1)
        assertEquals(1L, store.pendingNotification())
        // A failed next page cannot erase committed notification intent.
        try {
            store.applySync(
                JSONArray().put(message(5)).put(JSONObject().put("id", 6)),
                6,
                "self",
                0,
                1,
            )
            fail("malformed page accepted")
        } catch (_: org.json.JSONException) {}
        assertEquals(1L, store.pendingNotification())
        assertEquals(4L, store.cursor())
        store.applySync(JSONArray().put(message(4)), 4, "self", 0, 1)
        assertEquals(1L, store.pendingNotification())
        store.acknowledgeNotification(1)
        assertEquals(0L, store.pendingNotification())
    }

    @Test
    fun pageEpochGuardRejectsMixedNamespacesAndRestorePreservesOutbox() = withStore { store ->
        store.rememberEpoch(1)
        store.applySync(JSONArray().put(message(1)), 1, pageEpoch = 1)
        store.queue(SmsRecord("+1234567890", "10086", "queued", 1700000000000, 7))
        try {
            store.applySync(JSONArray().put(message(2)), 2, pageEpoch = 2)
            fail("mixed epoch accepted")
        } catch (_: IllegalStateException) {}
        assertEquals(1L, store.cursor())
        store.resetSyncedCache(2)
        store.applySync(JSONArray().put(message(1).put("body", "new namespace")), 1, pageEpoch = 2)
        assertEquals("new namespace", store.messages("10086").single().body)
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun unchangedContactsDoNotRewriteHistoryAndRemovalIsApplied() = withStore { store ->
        store.applySync(JSONArray().put(message(1)), 1)
        store.applyContacts(JSONObject().put("10086", "synthetic contact"))
        val writes =
            store.readableDatabase.rawQuery("SELECT total_changes()", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
        store.applyContacts(JSONObject().put("10086", "synthetic contact"))
        val after =
            store.readableDatabase.rawQuery("SELECT total_changes()", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
        assertEquals(writes, after)
        store.applyContacts(JSONObject())
        assertEquals("", store.messages("10086").single().contact)
    }

    private fun message(id: Long) =
        JSONObject()
            .put("id", id)
            .put("device_id", "synthetic")
            .put("receiver", "+1234567890")
            .put("sender", "10086")
            .put("body", "synthetic SMS")
            .put("timestamp", 1700000000000)
            .put("contact", "")

    @Test
    fun cursorAndMessagesCommitAtomicallyAndReplayIsIdempotent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "test-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                try {
                    store.applySync(JSONArray().put(message(1)).put(JSONObject().put("id", 2)), 2)
                    fail("malformed sync committed")
                } catch (_: org.json.JSONException) {}
                assertEquals(0L, store.cursor())
                assertTrue(store.messages().isEmpty())
                assertEquals(
                    2,
                    store.applySync(JSONArray().put(message(1)).put(message(2)), 2).size,
                )
                assertTrue(
                    store.applySync(JSONArray().put(message(1)).put(message(2)), 2).isEmpty()
                )
                assertEquals(2L, store.cursor())
                assertEquals(2, store.messages("10086").size)
            }
            Store(context, name).use { assertEquals(2L, it.cursor()) }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun unmappedRowsDoNotStarveLaterMappedRowsAndOutboxSurvivesRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "test-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                for (i in 0 until 120) store.queue(
                    SmsRecord("", "10086", "synthetic $i", 1700000000000 + i, -1)
                )
                val known = SmsRecord("+1234567890", "10086", "known", 1700000100000, 1)
                store.queue(known)
                store.queue(known)
                assertEquals(121, store.pendingCount())
                assertEquals(1, store.pending().size)
            }
            Store(context, name).use { store ->
                val pending = store.pending()
                assertEquals(1, pending.size)
                store.ack(pending.map { it.first })
                assertEquals(120, store.pendingCount())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
