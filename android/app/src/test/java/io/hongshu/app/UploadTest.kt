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
class UploadTest {
    private val message = SmsRecord("+1234567890", "10086", "synthetic upload", 100, 7)
    private val pending = listOf("a" to message, "b" to message.copy(timestamp = 101))

    private fun ack(id: Long = 1, fingerprint: String? = null, error: String? = null) =
        JSONObject().put("id", id).put("duplicate", false).apply {
            if (fingerprint != null) put("fingerprint", fingerprint)
            if (error != null) put("error", error)
        }

    @Test
    fun legacyOrderAndCorrelatedReorderingBothPreservePartialErrors() {
        val accepted = ack()
        val rejected = ack(0, error = "sim_not_confirmed")
        assertEquals(
            listOf(UploadAck("a", ""), UploadAck("b", "sim_not_confirmed")),
            uploadAcks(pending, JSONArray().put(accepted).put(rejected)),
        )
        accepted.put("fingerprint", pending[0].second.fingerprint())
        rejected.put("fingerprint", pending[1].second.fingerprint())
        assertEquals(
            listOf(UploadAck("b", "sim_not_confirmed"), UploadAck("a", "")),
            uploadAcks(pending, JSONArray().put(rejected).put(accepted)),
        )
    }

    @Test
    fun duplicateFingerprintsStillRequireOneAckPerQueuedRecord() {
        val same = listOf("a" to message, "b" to message)
        val response =
            JSONArray()
                .put(ack(fingerprint = message.fingerprint()))
                .put(ack(fingerprint = message.fingerprint()).put("duplicate", true))
        assertEquals(listOf("a", "b"), uploadAcks(same, response).map { it.key })
    }

    private fun store(test: (Context, Store) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "upload-${UUID.randomUUID()}.db"
        try {
            Store(context, name).use { test(context, it) }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun malformedResponsesNeverDeleteOrBlockAnyPartOfTheBatch() = store { context, store ->
        pending.forEach { store.queue(it.second) }
        val queued = store.pending(allowFallback = false)
        val malformed =
            listOf(
                JSONArray(),
                JSONArray().put(ack()).put(ack()).put(ack()),
                JSONArray().put(ack()).put(ack().put("id", 0)),
                JSONArray().put(ack()).put(ack().put("id", -1)),
                JSONArray().put(ack()).put(ack().put("id", "2")),
                JSONArray().put(ack()).put(ack().put("id", 1.5)),
                JSONArray().put(ack()).put(ack().put("id", 1e30)),
                JSONArray().put(ack()).put(ack().put("duplicate", "false")),
                JSONArray().put(ack()).put(ack().apply { remove("duplicate") }),
                JSONArray().put(ack()).put(ack(error = "unknown_error")),
                JSONArray().put(ack()).put(ack(error = "sim_not_confirmed")),
                JSONArray().put(ack()).put(ack().put("error", JSONObject.NULL)),
                JSONArray().put(ack(fingerprint = "unknown")).put(ack()),
                JSONArray().put(ack(fingerprint = queued[0].second.fingerprint())).put(ack()),
                JSONArray()
                    .put(ack(fingerprint = queued[0].second.fingerprint()))
                    .put(ack(fingerprint = queued[0].second.fingerprint())),
            )
        for (acks in malformed) {
            assertThrows(Exception::class.java) {
                uploadPending(
                    Api(Config(context)),
                    store,
                    queued,
                    request = { JSONObject().put("acks", acks) },
                )
            }
            assertEquals(2, store.pendingCount())
            assertEquals(0, store.blockedCount())
        }
    }

    @Test
    fun invalidBatchIsIsolatedWithoutDroppingValidNeighbours() = store { context, store ->
        repeat(8) { store.queue(message.copy(body = "synthetic $it")) }
        val queued = store.pending(allowFallback = false)
        var requests = 0
        uploadPending(
            Api(Config(context)),
            store,
            queued,
            request = { batch ->
                requests++
                if (batch.any { it.second.body == "synthetic 3" })
                    throw ApiException(400, "invalid_message")
                JSONObject()
                    .put(
                        "acks",
                        JSONArray().apply {
                            batch.forEach { put(ack(fingerprint = it.second.fingerprint())) }
                        },
                    )
            },
        )
        assertEquals(7, requests)
        assertEquals(1, store.pendingCount())
        assertEquals("synthetic 3", store.localPending().single().body)
        assertEquals("invalid_message", store.localPending().single().blocked)
    }

    @Test
    fun networkAndOtherHttpFailuresAreNotClassifiedAsBadRecords() = store { context, store ->
        store.queue(message)
        for (failure in
            listOf(
                ApiException(503, "unavailable"),
                ApiException(403, "upload_forbidden"),
                java.io.IOException(),
            )) {
            assertThrows(Exception::class.java) {
                uploadPending(
                    Api(Config(context)),
                    store,
                    store.pending(false),
                    request = { throw failure },
                )
            }
            assertEquals(1, store.pendingCount())
            assertEquals(0, store.blockedCount())
        }
    }

    @Test
    fun disablingUploadStopsFurtherIsolationRequests() = store { context, store ->
        repeat(4) { store.queue(message.copy(body = "synthetic $it")) }
        var enabled = true
        var calls = 0
        uploadPending(
            Api(Config(context)),
            store,
            store.pending(false),
            enabled = { enabled },
            request = {
                calls++
                enabled = false
                throw ApiException(400, "invalid_message")
            },
        )
        assertEquals(1, calls)
        assertEquals(4, store.pendingCount())
        assertEquals(0, store.blockedCount())
    }

    @Test
    fun realtimeLeadsBacklogButBothLanesGetQuotaAndHistoryGetsItsTurn() = store { _, store ->
        repeat(120) {
            store.queue(
                message.copy(
                    body = "synthetic history $it",
                    timestamp = it.toLong(),
                    historical = true,
                )
            )
            store.queue(message.copy(body = "synthetic realtime $it", timestamp = 1000L + it))
        }
        val batch = store.uploadCandidates(0)
        assertEquals(100, batch.size)
        assertFalse(batch.first().second.historical)
        assertEquals(80, batch.count { !it.second.historical })
        assertEquals(20, batch.count { it.second.historical })
        assertEquals((1000L..1079L).toList(), batch.take(80).map { it.second.timestamp })
        val historyFirst = store.uploadCandidates(4)
        assertTrue(historyFirst.first().second.historical)
        assertEquals(80, historyFirst.count { it.second.historical })
        val hugeSizes = historyFirst.map { 450_000 }
        assertTrue(historyFirst.take(uploadBatchCount(hugeSizes)).all { it.second.historical })
        store.ack(store.pending(false, historical = false).map { it.first })
        store.ack(store.pending(false, historical = false).map { it.first })
        assertEquals(100, store.uploadCandidates(0).size)
        assertTrue(store.uploadCandidates(0).all { it.second.historical })
    }

    @Test
    fun queueSummaryPartitionsRetainedRowsWithoutTreatingThemAllAsUploadable() =
        store { context, store ->
            val config = Config(context)
            config.automaticSim = false
            store.queue(message)
            store.queue(message.copy(receiver = "", subscriptionId = 8))
            store.queue(message.copy(body = "synthetic invalid"))
            store.queue(message.copy(body = "synthetic unconfirmed"))
            store.localPending().forEach {
                when (it.body) {
                    "synthetic invalid" -> store.block(it.key, "invalid_message")
                    "synthetic unconfirmed" -> store.block(it.key, "sim_not_confirmed")
                }
            }
            assertEquals(PendingCounts(1, 2, 1), store.pendingCounts())
            store.saveSim(SimMapping(8, 1, "+9876543210", "SIM2"))
            assertEquals(PendingCounts(2, 1, 1), store.pendingCounts())
            config.upload = false
            assertEquals(store.pendingCount(), store.pendingCounts().total)
            assertEquals(PendingCounts(2, 1, 1), store.pendingCounts())
        }
}
