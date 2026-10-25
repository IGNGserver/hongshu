package io.hongshu.app

import android.app.Application
import android.content.Context
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallLogTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "calls-${java.util.UUID.randomUUID()}.db"
    private lateinit var store: Store
    private val line = mapOf(1 to "+8613800000000")
    private val receiver: (Int) -> String = { line[it].orEmpty() }

    @Before
    fun open() {
        context.getSharedPreferences("hongshu", Context.MODE_PRIVATE).edit().clear().commit()
        Config(context).deviceId = "phone"
        store = Store(context, name)
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    private fun row(
        id: Long,
        number: String? = "13900000000",
        type: Int = CallLog.Calls.INCOMING_TYPE,
        date: Long = 1_700_000_000_000 + id * 60_000,
        duration: Long = 30,
        presentation: Int = CallLog.Calls.PRESENTATION_ALLOWED,
        features: Int = 0,
        location: String? = null,
        account: String? = "1",
    ) = CallRow(id, number, type, date, duration, presentation, features, location, null, account)

    private class FakeSource(var all: List<CallRow>) : CallSource {
        var queries = 0
        override fun head(): Long = all.maxOfOrNull { it.id } ?: 0L
        override fun rows(after: Long, since: Long, limit: Int): List<CallRow> {
            queries++
            return all.filter { it.id > after && it.date >= since }.sortedBy { it.id }.take(limit)
        }
        override fun subscription(row: CallRow): Int = row.account?.toIntOrNull() ?: -1
    }

    @Test
    fun fingerprintAndPayloadMatchTheHub() {
        val call = CallRecord("+8613800000000", "13900000000", "incoming", 1700000000000, 75, 1)
        // Same vector as server/calls_test.go and docs/API.md.
        assertEquals("bf4ef9880762b3b21e9f1cf296615e8af7492beea5aaa2020f18afceb543ae97", call.fingerprint())
        assertEquals(call.fingerprint(), call.copy(receiver = "", subscriptionId = -1).fingerprint())
        val json = call.json()
        for (key in listOf("receiver", "number", "type", "timestamp", "duration", "subscription_id", "video", "presentation", "location", "historical"))
            assertTrue(key, json.has(key))
        assertEquals("allowed", json.getString("presentation"))
    }

    @Test
    fun providerValuesMapToProtocolValues() {
        assertEquals("incoming", callType(CallLog.Calls.INCOMING_TYPE))
        assertEquals("outgoing", callType(CallLog.Calls.OUTGOING_TYPE))
        assertEquals("missed", callType(CallLog.Calls.MISSED_TYPE))
        assertEquals("voicemail", callType(CallLog.Calls.VOICEMAIL_TYPE))
        assertEquals("rejected", callType(CallLog.Calls.REJECTED_TYPE))
        assertEquals("blocked", callType(CallLog.Calls.BLOCKED_TYPE))
        assertEquals("answered_externally", callType(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE))
        assertEquals("unknown", callType(10))
        assertEquals("restricted", callPresentation(CallLog.Calls.PRESENTATION_RESTRICTED))
        assertEquals("payphone", callPresentation(CallLog.Calls.PRESENTATION_PAYPHONE))
        assertEquals("unknown", callPresentation(CallLog.Calls.PRESENTATION_UNKNOWN))
        assertEquals("unknown", callPresentation(5))
        assertEquals("allowed", callPresentation(CallLog.Calls.PRESENTATION_ALLOWED))
    }

    @Test
    fun rowsAreNormalizedWithoutGuessing() {
        val now = 1_800_000_000_000
        val plain = callRecord(row(1, number = "139 0000-0000", duration = -5), 1, receiver, boundary = 0, now = now)!!
        assertEquals("13900000000", plain.number)
        assertEquals("+8613800000000", plain.receiver)
        assertEquals(0L, plain.duration)
        assertFalse(plain.historical)
        // Legacy "-2" marker and an empty number without presentation both become withheld numbers.
        val legacy = callRecord(row(2, number = "-2", presentation = CallLog.Calls.PRESENTATION_RESTRICTED), 1, receiver, 0, now = now)!!
        assertEquals("" to "restricted", legacy.number to legacy.presentation)
        val blank = callRecord(row(3, number = ""), 1, receiver, 0, now = now)!!
        assertEquals("unknown", blank.presentation)
        // An unmapped or unknown subscription leaves the line empty instead of borrowing a slot.
        assertEquals("", callRecord(row(4), 2, receiver, 0, now = now)!!.receiver)
        assertEquals("", callRecord(row(5), -1, receiver, 0, now = now)!!.receiver)
        val video = callRecord(row(6, features = CallLog.Calls.FEATURES_VIDEO, location = "北".repeat(120), duration = MAX_CALL_DURATION + 9), 1, receiver, 0, now = now)!!
        assertTrue(video.video)
        assertEquals(100, video.location.length)
        assertEquals(MAX_CALL_DURATION, video.duration)
        assertTrue(callRecord(row(7, date = 1000), 1, receiver, boundary = 2000, now = now)!!.historical)
        assertNull(callRecord(row(8, date = now + 25 * 3600 * 1000L), 1, receiver, 0, now = now))
        assertNull(callRecord(row(9, number = "1".repeat(101)), 1, receiver, 0, now = now))
    }

    @Test
    fun scanPagesQueuesOnceAndSurvivesRescansAndRebuilds() {
        val source = FakeSource((1L..250L).map { row(it) })
        assertEquals(250 to false, scanCalls(store, source, 0, 0, receiver, { true }))
        assertEquals(250L, store.callWatermark())
        assertEquals(250, store.callQueueCounts().pending)
        // Nothing new: one empty page, nothing queued.
        assertEquals(0 to false, scanCalls(store, source, 0, 0, receiver, { true }))
        // History switch: watermark restarts, the ledger keeps every call queued once.
        store.restartCallScan(7)
        assertEquals(7L, store.callHistoryGeneration())
        assertEquals(0 to false, scanCalls(store, source, 0, 0, receiver, { true }))
        assertEquals(250, store.callQueueCounts().pending)
        // Provider rebuilt with lower ids: rescanned from the start, still no duplicates.
        source.all = (1L..3L).map { row(it, date = 1_700_000_000_000 + (it + 247) * 60_000) } + row(4, number = "10086")
        assertEquals(1 to false, scanCalls(store, source, 0, 0, receiver, { true }))
        assertEquals(4L, store.callWatermark())
        // A dated scan skips older rows; a bounded pass reports that more remain.
        val fresh = FakeSource((1L..400L).map { row(it, number = "10010") })
        store.close()
        context.deleteDatabase(name)
        store = Store(context, name)
        assertEquals(100 to true, scanCalls(store, fresh, 0, 0, receiver, { true }, maxRows = 100))
        assertEquals(50 to false, scanCalls(store, fresh, 1_700_000_000_000 + 351 * 60_000, 0, receiver, { true }))
    }

    @Test
    fun aStaleScanCommitsNothing() {
        var live = true
        val source = object : CallSource {
            override fun head() = 300L
            override fun rows(after: Long, since: Long, limit: Int): List<CallRow> {
                live = false // switch turned off while the provider was being read
                return (after + 1..after + limit).map { row(it) }
            }
            override fun subscription(row: CallRow) = 1
        }
        assertEquals(0 to false, scanCalls(store, source, 0, 0, receiver, { live }))
        assertEquals(0L, store.callWatermark())
        assertEquals(0, store.callQueueCounts().pending)
    }

    private fun ack(call: CallRecord, id: Long, error: String = "", duplicate: Boolean = false) =
        JSONObject().put("id", id).put("duplicate", duplicate).put("fingerprint", call.fingerprint()).apply { if (error.isNotEmpty()) put("error", error) }

    @Test
    fun uploadClearsConfirmedRetriesUnknownLinesAndParksInvalid() {
        val records = (1L..4L).map { callRecord(row(it), 1, receiver, 0)!! }
        store.captureCalls(records, 4)
        val sent = mutableListOf<List<CallRecord>>()
        val ensured = mutableListOf<String>()
        val more =
            uploadCalls(Api(Config(context)), store, ensure = { ensured += it.receiver }, maxBatches = 2) { batch ->
                sent += batch
                val acks = JSONArray()
                batch.forEach { call ->
                    acks.put(
                        when {
                            call.timestamp == records[1].timestamp && call.receiver.isNotEmpty() -> ack(call, 0, "sim_not_confirmed")
                            call.timestamp == records[2].timestamp -> ack(call, 0, "invalid_call")
                            call.timestamp == records[3].timestamp -> ack(call, 9, duplicate = true)
                            else -> ack(call, call.timestamp % 1000 + 1)
                        }
                    )
                }
                JSONObject().put("acks", acks)
            }
        assertFalse(more)
        assertEquals(listOf("+8613800000000"), ensured.distinct())
        // Second batch: only the call whose line the hub did not know, now sent as an unknown line.
        assertEquals(2, sent.size)
        assertEquals(listOf(records[1].copy(receiver = "")), sent[1])
        assertEquals(CallQueueCounts(pending = 0, blocked = 1), store.callQueueCounts())
    }

    @Test
    fun aMismatchedResponseLeavesTheQueueUntouched() {
        store.captureCalls(listOf(callRecord(row(1), 1, receiver, 0)!!), 1)
        val other = callRecord(row(2), 1, receiver, 0)!!
        try {
            uploadCalls(Api(Config(context)), store, ensure = {}) { JSONObject().put("acks", JSONArray().put(ack(other, 5))) }
            fail("mismatched ACK accepted")
        } catch (_: IllegalStateException) {}
        assertEquals(1, store.callQueueCounts().pending)
    }

    @Test
    fun aDifferentHubStartsFromTheCallLogNotTheOldQueue() {
        val records = (1L..2L).map { callRecord(row(it), 1, receiver, 0)!! }
        store.captureCalls(records, 2)
        store.applyCallAcks(listOf(CallAck(records[0].fingerprint(), "")))
        // Same hub (re-login): queue and ledger stay, nothing is queued twice.
        store.resetForNewIdentity(clearCaptures = false)
        assertEquals(0, store.captureCalls(records, 2))
        assertEquals(1, store.callQueueCounts().pending)
        // Different hub: the old queue is dropped and a fresh scan queues every call once.
        store.resetForNewIdentity(clearCaptures = true)
        assertEquals(0L, store.callWatermark())
        assertEquals(CallQueueCounts(0, 0), store.callQueueCounts())
        assertEquals(2, store.captureCalls(records, 2))
    }

    @Test
    fun upgradeFromV15AddsTheQueueReentrantly() {
        store.writableDatabase.apply {
            execSQL("DROP TABLE call_outbox")
            execSQL("DROP TABLE call_ledger")
            execSQL("DELETE FROM meta WHERE key IN ('call_provider_id','call_history_generation','hub_call_log')")
            version = 15
        }
        store.close()
        store = Store(context, name)
        assertEquals(16, store.readableDatabase.version)
        assertEquals(CallQueueCounts(0, 0), store.callQueueCounts())
        assertFalse(store.hubSupportsCallLog())
        store.rememberHubCallLog(true)
        assertTrue(store.hubSupportsCallLog())
        // Re-running the creation step is harmless.
        createCallLog(store.writableDatabase)
        assertTrue(store.hubSupportsCallLog())
    }

    @Test
    fun switchIsOffByDefaultAndEnablingSetsTheBoundary() {
        val config = Config(context)
        assertFalse(config.callLogEnabled)
        val before = System.currentTimeMillis()
        config.callLogEnabled = true
        assertTrue(config.callLogSince >= before)
        assertEquals(1L, config.callLogGeneration)
        config.callLogEnabled = false
        assertEquals(0L, config.callLogSince)
        assertEquals(2L, config.callLogGeneration)
    }
}
