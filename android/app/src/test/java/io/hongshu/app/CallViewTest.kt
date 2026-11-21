package io.hongshu.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
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
class CallViewTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "call-view-${java.util.UUID.randomUUID()}.db"
    private lateinit var store: Store

    @Before
    fun open() {
        context.getSharedPreferences("hongshu", Context.MODE_PRIVATE).edit().clear().commit()
        Config(context).deviceId = "phone"
        store = Store(context, name)
        store.rememberEpoch(1)
        store.rememberHubCallLog(true)
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    private fun call(id: Long, timestamp: Long = 1_700_000_000_000 + id * 60_000, type: String = "incoming", duration: Long = 30, number: String = "13900000000") =
        JSONObject()
            .put("id", id)
            .put("device_id", "phone")
            .put("receiver", if (id % 2 == 0L) "" else "+8613800000000")
            .put("number", number)
            .put("contact", "")
            .put("type", type)
            .put("timestamp", timestamp)
            .put("duration", duration)
            .put("subscription_id", 1)
            .put("video", false)
            .put("presentation", if (number.isEmpty()) "restricted" else "allowed")
            .put("location", "")
            .put("historical", false)

    private fun page(calls: List<JSONObject>, cursor: Long, more: Boolean, epoch: Long = 1) =
        JSONObject().put("calls", JSONArray(calls)).put("cursor", cursor).put("more", more).put("epoch", epoch)

    private fun cached(id: Long, type: String = "incoming", duration: Long = 0, video: Boolean = false, number: String = "13900000000", presentation: String = "allowed") =
        CachedCall(id, "phone", "", number, "", type, 1_700_000_000_000, duration, -1, video, presentation, "", false)

    @Test
    fun labelsMatchTheWebClient() {
        assertEquals("0 秒", formatCallDuration(0))
        assertEquals("3 分 5 秒", formatCallDuration(185))
        assertEquals("1 小时 2 分 5 秒", formatCallDuration(3725))
        assertEquals("呼入 · 1 分 15 秒", callSummary(cached(1, duration = 75)))
        assertEquals("呼出 · 未接通", callSummary(cached(1, type = "outgoing")))
        assertEquals("未接来电", callSummary(cached(1, type = "missed")))
        assertEquals("已拒接", callSummary(cached(1, type = "rejected", duration = 3)))
        assertEquals("呼出 · 视频 · 9 秒", callSummary(cached(1, type = "outgoing", duration = 9, video = true)))
        assertEquals("通话", callSummary(cached(1, type = "vendor")))
        assertEquals("合成联系人", callParty(cached(1), "合成联系人"))
        assertEquals("隐藏号码", callParty(cached(1, number = "", presentation = "restricted")))
        assertTrue(isMissedCall("blocked"))
        assertFalse(isMissedCall("voicemail"))
    }

    @Test
    fun factsOmitEmptyFieldsAndNameUnknownLines() {
        val facts = callFacts(cached(1, type = "missed", number = "", presentation = "restricted"), line = "", device = "本机").toMap()
        assertEquals("未接来电 · 语音通话", facts["类型"])
        assertEquals("未接通", facts["通话时长"])
        assertEquals("隐藏号码", facts["号码"])
        assertEquals("未知线路", facts["本机线路"])
        assertEquals("本机", facts["来源手机"])
        assertFalse("归属地" in facts)
        assertFalse("记录来源" in facts)
        assertTrue(facts.getValue("时间").startsWith("2023年11月"))
    }

    @Test
    fun syncPagesIntoTheCacheNewestFirst() = runBlocking {
        val requests = mutableListOf<String>()
        val pages = ArrayDeque(listOf(page((1L..200L).map { call(it) }, 200, true), page((201L..250L).map { call(it) }, 250, false)))
        assertFalse(syncCallLog(store, { requests += it; pages.removeFirst() }))
        assertEquals(listOf("/calls/sync?after=0&limit=200", "/calls/sync?after=200&limit=200"), requests)
        assertEquals(250L, store.callCursor())
        val newest = store.cachedCalls(3)
        assertEquals(listOf(250L, 249L, 248L), newest.map { it.id })
        assertEquals("", newest[0].receiver)
        assertEquals("+8613800000000", newest[1].receiver)
        assertEquals(250L, store.cachedCall(250)!!.id)
        assertNull(store.cachedCall(999))
        // A bounded pass reports more pages.
        val endless = { _: String -> page(listOf(call(store.callCursor() + 1)), store.callCursor() + 1, true) }
        assertTrue(syncCallLog(store, endless, maxPages = 2))
        assertEquals(252L, store.callCursor())
    }

    @Test
    fun hubContactsAndWithheldNumbersDisplayCorrectly() = runBlocking {
        store.applyContacts(JSONObject().put("13900000000", "合成联系人"))
        assertTrue(syncCallLog(store, { page(listOf(call(1), call(2, number = "")), 2, false) }).not())
        val calls = store.cachedCalls(10).associateBy { it.id }
        assertEquals("合成联系人", calls.getValue(1).contact)
        assertEquals("", calls.getValue(2).contact)
        assertEquals("restricted", calls.getValue(2).presentation)
    }

    @Test
    fun aDifferentEpochOrMissingFeatureWritesNothing() = runBlocking {
        assertFalse(syncCallLog(store, { page(listOf(call(1)), 1, false, epoch = 2) }))
        assertEquals(0L, store.callCursor())
        assertTrue(store.cachedCalls(10).isEmpty())
        store.rememberHubCallLog(false)
        assertFalse(syncCallLog(store, { fail("an old hub has no /calls/sync"); page(emptyList(), 0, false) }))
    }

    @Test
    fun restoresAndCursorConflictsStartOver() = runBlocking {
        syncCallLog(store, { page((1L..3L).map { call(it) }, 3, false) })
        // The hub says our cursor is past its call head: drop the cache and page again.
        assertTrue(syncCallLog(store, { throw ApiException(409, "epoch_changed") }))
        assertEquals(0L, store.callCursor())
        assertTrue(store.cachedCalls(10).isEmpty())
        syncCallLog(store, { page((1L..3L).map { call(it) }, 3, false) })
        // A database restore clears calls together with messages.
        store.resetSyncedCache(2)
        assertEquals(0L, store.callCursor())
        assertTrue(store.cachedCalls(10).isEmpty())
        // A new identity clears them too.
        syncCallLog(store, { page((1L..3L).map { call(it) }, 3, false, epoch = 2) })
        assertEquals(3, store.cachedCalls(10).size)
        store.resetForNewIdentity()
        assertTrue(store.cachedCalls(10).isEmpty())
    }

    @Test
    fun aPageCannotMoveTheCursorBackOrCarryForeignIds() = runBlocking {
        syncCallLog(store, { page((1L..3L).map { call(it) }, 3, false) })
        try {
            syncCallLog(store, { page(emptyList(), 2, false) })
            fail("cursor moved backwards")
        } catch (_: IllegalStateException) {}
        try {
            syncCallLog(store, { page(listOf(call(9)), 4, false) })
            fail("call beyond the page cursor accepted")
        } catch (_: IllegalStateException) {}
        assertEquals(3L, store.callCursor())
        assertEquals(3, store.cachedCalls(10).size)
    }

    @Test
    fun callDetailsAreTheirOwnScreenOnPhonesOnly() {
        assertEquals("call:7", AppDestination.Call(7).screenKey(twoPane = false))
        assertEquals("main", AppDestination.Call(7).screenKey(twoPane = true))
        val stack = pushDestination(listOf(AppDestination.Home), AppDestination.Call(7))
        assertEquals(listOf(AppDestination.Home), popDestination(stack))
        assertTrue(isBackNavigation(stack, AppDestination.Call(7), AppDestination.Home))
    }
    @Test
    fun kindChipsMatchTheRowSemantics() {
        assertTrue(callKindMatches("rejected", "missed"))
        assertTrue(callKindMatches("blocked", "missed"))
        assertTrue(callKindMatches("answered_externally", "incoming"))
        assertTrue(callKindMatches("vendor", "all"))
        assertFalse(callKindMatches("vendor", "missed"))
        assertFalse(callKindMatches("voicemail", "outgoing"))
        assertEquals(listOf("all", "missed", "incoming", "outgoing", "voicemail"), callFilterLabels.keys.toList())
    }

    @Test
    fun filtersAndCountsUseTheSameKindBuckets() = runBlocking {
        val calls =
            listOf(
                call(1, type = "missed"),
                call(2, type = "rejected"),
                call(3, type = "blocked"),
                call(4, type = "incoming"),
                call(5, type = "answered_externally"),
                call(6, type = "outgoing"),
                call(7, type = "voicemail"),
                call(8, type = "vendor"),
            )
        assertFalse(syncCallLog(store, { page(calls, 8, false) }))

        assertEquals((1L..8L).toList(), store.calls().map { it.id }.sorted())
        assertEquals(listOf(1L, 2L, 3L), store.calls(kind = "missed").map { it.id }.sorted())
        assertEquals(listOf(4L, 5L), store.calls(kind = "incoming").map { it.id }.sorted())
        assertEquals(listOf(6L), store.calls(kind = "outgoing").map { it.id })
        assertEquals(listOf(7L), store.calls(kind = "voicemail").map { it.id })

        val counts = store.callCounts()
        assertEquals(8, counts["all"])
        assertEquals(3, counts["missed"])
        assertEquals(2, counts["incoming"])
        assertEquals(1, counts["outgoing"])
        assertEquals(1, counts["voicemail"])

        // Odd ids carry the known line; even ids are unknown-line records.
        val line = "+8613800000000"
        assertEquals(listOf(1L, 3L, 5L, 7L), store.calls(sim = line).map { it.id }.sorted())
        assertEquals(4, store.callCounts(sim = line)["all"])
        assertEquals(2, store.callCounts(sim = line)["missed"])
        assertEquals(listOf(line), store.callReceivers())
    }

    @Test
    fun searchMatchesNumberAndHubAlias() = runBlocking {
        val calls = listOf(call(1, number = "13900001111"), call(2, number = "13900002222"))
        assertFalse(syncCallLog(store, { page(calls, 2, false) }))
        store.applyContacts(JSONObject().put("13900001111", "合成联系人"))

        assertEquals(listOf(2L), store.calls(query = "2222").map { it.id })
        assertEquals(listOf(1L), store.calls(query = "合成").map { it.id })
        assertEquals(listOf(1L, 2L), store.calls(query = "139").map { it.id }.sorted())
        assertEquals(1, store.callCounts(query = "2222")["all"])
        assertEquals(1, store.callCounts(query = "合成")["all"])
    }

    @Test
    fun callNumbersSkipUnknownLinesAndDuplicates() = runBlocking {
        val calls = listOf(call(1, number = "13900000000"), call(2), call(3, number = ""))
        assertFalse(syncCallLog(store, { page(calls, 3, false) }))
        assertEquals(listOf("+8613800000000"), store.callReceivers())
        assertEquals(listOf("13900000000"), store.callNumbers())
    }

}
