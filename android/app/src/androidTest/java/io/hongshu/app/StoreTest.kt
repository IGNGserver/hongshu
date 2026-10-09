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
