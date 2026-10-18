package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
class ReadSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun withStore(test: (Store) -> Unit) {
        val name = "read-sync-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                val messages = JSONArray()
                for ((id, sender) in listOf(1L to "10010", 2L to "10086", 3L to "10086")) {
                    messages.put(JSONObject().put("id", id).put("device_id", "remote").put("sender", sender)
                        .put("receiver", "+1234567890").put("body", "synthetic").put("timestamp", id).put("historical", false))
                }
                store.applySync(messages, 3, pageEpoch = 1)
                test(store)
            }
        } finally { context.deleteDatabase(name) }
    }
    private fun page(revision: Long, sender: String = "", id: Long = 0, more: Boolean = false, epoch: Long = 1): JSONObject =
        JSONObject().put("epoch", epoch).put("revision", revision).put("head", 3).put("global_watermark_id", 0)
            .put("more", more).put("next", if (more) sender else "")
            .put("items", JSONArray().apply { if (sender.isNotEmpty()) put(JSONObject().put("sender", sender).put("message_id", id)) })

    @Test fun remoteConversationReadsMergeWithoutFeedbackAndCheckpointOnlyAfterLastPage() = withStore { store ->
        var requests = 0
        assertTrue(runBlocking { syncReadState(store) { path ->
            requests++
            if (requests == 1) {
                assertFalse(path.contains("since="))
                page(10, "10010", 1, more = true)
            } else {
                assertTrue(path.contains("after=10010"))
                assertTrue(path.contains("revision=10"))
                assertEquals(-1L, store.readStateRevision())
                page(10, "10086", 2)
            }
        } })
        assertEquals(2, requests)
        assertEquals(mapOf("10086" to 1), store.unreadCounts())
        assertTrue(store.pendingReads().isEmpty())
        assertEquals(10L, store.readStateRevision())
        val changes = Store.changes.value
        assertTrue(runBlocking { syncReadState(store) { path ->
            assertTrue(path.contains("since=10")); page(10)
        } })
        assertEquals(changes, Store.changes.value)
    }

    @Test fun concurrentRemoteChangeRetriesWithoutLosingOfflineReadIntent() = withStore { store ->
        store.markRead("10086", 3)
        var requests = 0
        assertFalse(runBlocking { syncReadState(store) {
            if (++requests == 1) page(10, "10010", 1, more = true)
            else throw ApiException(409, "read_state_changed")
        } })
        assertEquals(-1L, store.readStateRevision())
        assertEquals(listOf("10086" to 3L), store.pendingReads())
        assertTrue(runBlocking { syncReadState(store) { page(11, "10086", 2) } })
        assertTrue(store.unreadCounts().isEmpty())
        assertEquals(listOf("10086" to 3L), store.pendingReads())
    }

    @Test fun restoredEpochAndNewIdentityMustRedownloadReadMarkers() = withStore { store ->
        store.applyReadState(page(10, "10086", 3))
        store.resetSyncedCache(2)
        assertEquals(-1L, store.readStateRevision())
        store.applyReadState(page(20, epoch = 2))
        store.resetSyncedCache(2) // even a defensive same-epoch cache reset invalidates metadata
        assertEquals(-1L, store.readStateRevision())
        store.applyReadState(page(20, epoch = 2))
        store.resetForNewIdentity()
        assertEquals(-1L, store.readStateRevision())
    }

    @Test fun epochMismatchAndNonAdvancingPagesCannotCheckpoint() = withStore { store ->
        try {
            runBlocking { syncReadState(store) { page(10, "10086", 2, epoch = 2) } }
            fail("mixed epoch accepted")
        } catch (_: IllegalStateException) {}
        assertEquals(mapOf("10010" to 1, "10086" to 2), store.unreadCounts())
        try {
            runBlocking { syncReadState(store) { page(10, "10086", 2, more = true) } }
            fail("non-advancing cursor accepted")
        } catch (_: IllegalStateException) {}
        assertEquals(-1L, store.readStateRevision())
    }
}
