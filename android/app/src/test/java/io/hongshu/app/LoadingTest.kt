package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LoadingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun withStore(block: (Store) -> Unit) {
        val name = "loading-${java.util.UUID.randomUUID()}.db"
        try { Store(context, name).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun message(id: Long, time: Long = id, sender: String = "synthetic"): JSONObject =
        JSONObject().put("id", id).put("device_id", "remote").put("receiver", "+123456789")
            .put("sender", sender).put("body", "synthetic-$id").put("timestamp", time)
            .put("historical", false)
    private fun recent(vararg messages: JSONObject, head: Long = 10_000, epoch: Long = 1): JSONObject =
        JSONObject().put("messages", JSONArray(messages.toList())).put("head", head).put("epoch", epoch)

    @Test fun networkModeNeedsSustainedEvidenceAndKeepsOnlyEightPayloadFreeSamples() {
        val profile = LoadingNetworkProfile()
        repeat(2) { profile.observe(1500) }
        assertFalse(profile.slow)
        profile.observe(1500)
        assertTrue(profile.slow)
        repeat(4) { profile.observe(100) }
        assertTrue(profile.slow)
        profile.observe(100)
        assertFalse(profile.slow)
        repeat(100) { profile.observe(200) }
        assertEquals(8, profile.timings().size)
        profile.reset()
        assertTrue(profile.timings().isEmpty())
    }

    @Test fun recentContentIsVisibleWithoutSkippingReliableHistoryOrCreatingNotification() = withStore { store ->
        store.rememberEpoch(1)
        store.initializeSyncHead(10_000)
        store.applyRecent(recent(message(10_000)))
        assertEquals(0L, store.cursor())
        assertFalse(store.initialComplete())
        assertEquals(10_000L, store.previewHead())
        assertEquals(0L, store.pendingNotification())
        assertEquals(10_000L, store.messages().single().id)
        store.applySync(JSONArray().put(message(1)), 1, pageEpoch = 1)
        assertEquals(1L, store.cursor())
        assertEquals(10_000L, store.messages().single().id)
        assertEquals(2, store.messages(sender = "synthetic").size)
    }

    @Test fun previewDoesNotSwallowReliableNotificationsAndRedeliveryIsIdempotent() = withStore { store ->
        store.rememberEpoch(1)
        store.initializeSyncHead(0)
        store.applySync(JSONArray(), 0, pageEpoch = 1)
        assertTrue(store.initialComplete())
        store.applyRecent(recent(message(1), head = 1))
        assertEquals(0L, store.pendingNotification())
        store.applySync(JSONArray().put(message(1)), 1, "self", 0, 1)
        assertEquals(1L, store.pendingNotification())
        store.applySync(JSONArray().put(message(1)), 1, "self", 0, 1)
        assertEquals(1L, store.pendingNotification())
    }

    @Test fun previewOverlapAndNotificationMarkerSurviveProcessRestart() {
        val name = "loading-restart-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                store.rememberEpoch(1)
                store.initializeSyncHead(0)
                store.applySync(JSONArray(), 0, pageEpoch = 1)
                store.applyRecent(recent(message(1), head = 1))
            }
            Store(context, name).use { store ->
                assertEquals(0L, store.cursor())
                store.applySync(JSONArray().put(message(1)), 1, "self", 0, 1)
                assertEquals(1L, store.pendingNotification())
            }
            Store(context, name).use { store ->
                store.applySync(JSONArray().put(message(1)), 1, "self", 0, 1)
                assertEquals(1L, store.pendingNotification())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun epochAndIdentityResetDropPreviewAndDerivedHeadsNotPendingCaptures() = withStore { store ->
        store.rememberEpoch(1)
        store.queue(SmsRecord("+123456789", "pending", "synthetic pending", 50, 1))
        store.applyRecent(recent(message(10)))
        store.resetSyncedCache(2)
        assertEquals(0L, store.previewHead())
        assertTrue(store.messages().isEmpty())
        assertEquals(1, store.pendingCount())
        try { store.applyRecent(recent(message(10))); fail("mixed epoch accepted") } catch (_: IllegalStateException) {}
        store.applyRecent(recent(message(2), head = 2, epoch = 2))
        assertEquals(2L, store.messages().single().id)
        store.resetForNewIdentity()
        assertTrue(store.messages().isEmpty())
        assertEquals(1, store.pendingCount())
    }

    @Test fun summaryHonorsMessageTimeAndTieBreakerDuringOutOfOrderBackfill() = withStore { store ->
        store.applySync(JSONArray().put(message(50, 100)).put(message(60, 80)).put(message(40, 100)), 60, pageEpoch = 1)
        assertEquals(50L, store.messages().single().id)
        store.applySync(JSONArray().put(message(70, 100)), 70, pageEpoch = 1)
        assertEquals(70L, store.messages().single().id)
        assertEquals(4, store.messages(sender = "synthetic").size)
        assertEquals(1, store.messages(q = "synthetic-40").size)
    }

    @Test fun emptyPollAndIdenticalContactsDoNotInvalidateLocalLists() = withStore { store ->
        store.applySync(JSONArray(), 0, pageEpoch = 1)
        store.applyContacts(JSONObject().put("synthetic", "Contact"), 1)
        val version = Store.changes.value
        store.applySync(JSONArray(), 0, pageEpoch = 1)
        store.applyContacts(JSONObject().put("synthetic", "Contact"), 2)
        assertEquals(version, Store.changes.value)
        assertEquals(2L, store.contactsRevision())
    }

    @Test fun replacingCachedContactsInvalidatesPreviousNetworkFreshness() = withStore { store ->
        val names = JSONObject().put("synthetic", "Contact")
        store.applyContacts(names, checkedAt = System.currentTimeMillis())
        assertTrue(store.contactsCheckedAt() > 0)
        store.applyContacts(names)
        assertEquals(0L, store.contactsCheckedAt())
    }
}
