package io.hongshu.app

import android.app.Application
import android.content.Context
import android.provider.Telephony
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
class OutgoingSmsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "outgoing-${java.util.UUID.randomUUID()}.db"
    private lateinit var store: Store

    @Before
    fun open() {
        context.getSharedPreferences("hongshu", Context.MODE_PRIVATE).edit().clear().commit()
        Config(context).deviceId = "phone"
        store = Store(context, name)
        store.saveSim(SimMapping(1, 0, "+8613800000000", "SIM1"))
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    private fun sent(id: Long, body: String = "synthetic reply", at: Long = 1_700_000_000_000 + id) =
        id to SmsRecord("+8613800000000", "13900000000", body, at, 1, outgoing = true)

    @Test
    fun sentFingerprintMatchesHubVector() {
        val received = SmsRecord("+8613800000000", "10086", "测试", 1700000000000, 1)
        val sent = received.copy(outgoing = true)
        assertEquals("40315f7bdb01179c52c2db83ef04c8ec9f3957d384188ff8901f4115252fa41a", received.fingerprint())
        // Same vector as server/outgoing_test.go and docs/API.md.
        assertEquals("d56c4a02e82f207e4a4436d1c2ef37c27e942d3883e7232b770334b13bff0adc", sent.fingerprint())
        assertFalse(received.json().has("direction"))
        assertEquals("out", sent.json().getString("direction"))
    }

    @Test
    fun onlySentRowsAreCapturedAndUnfinishedOnesAreRetried() {
        assertEquals(SentRow.CAPTURE, classifySentRow(Telephony.Sms.MESSAGE_TYPE_SENT))
        for (type in listOf(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED, Telephony.Sms.MESSAGE_TYPE_FAILED))
            assertEquals(SentRow.PENDING, classifySentRow(type))
        for (type in listOf(Telephony.Sms.MESSAGE_TYPE_INBOX, Telephony.Sms.MESSAGE_TYPE_DRAFT, 0))
            assertEquals(SentRow.SKIP, classifySentRow(type))

        val now = 1_800_000_000_000
        store.skipSentRows(pending = listOf(5L), ignored = listOf(6L), now = now)
        assertEquals(6L, store.sentWatermark())
        assertEquals(listOf(5L), store.incompleteSentIds(now + 1000))
        // The row went out under the same id: captured once and no longer retried.
        assertEquals(1, store.captureSent(listOf(sent(5))))
        assertEquals(emptyList<Long>(), store.incompleteSentIds(now + 1000))
        // A row still unsent after the retry window is given up.
        store.skipSentRows(pending = listOf(9L), ignored = emptyList(), now = now)
        assertEquals(emptyList<Long>(), store.incompleteSentIds(now + SENT_RETRY_WINDOW_MS + 1))
    }

    @Test
    fun recipientKeepsTheConversationsExistingSpelling() {
        assertEquals("+8613900000000", sentRecipient("139 0000 0000", "+8613900000000") { null })
        // A thread address for a different party (group thread) is not borrowed.
        assertEquals("13900000000", sentRecipient("139-0000-0000", "13700000000") { null })
        assertEquals("+8613900000000", sentRecipient("13900000000", null) { "+8613900000000" })

        store.queue(SmsRecord("+8613800000000", "+8613900000000", "synthetic hello", 1_700_000_000_000, 1))
        assertEquals("+8613900000000", store.knownSenderFor("139 0000 0000"))
        assertNull(store.knownSenderFor("13700000000"))
    }

    @Test
    fun sentRowsWaitForAHubThatStoresDirection() {
        assertEquals(1, store.captureSent(listOf(sent(1))))
        assertEquals(0, store.captureSent(listOf(sent(1))))
        assertTrue("an older hub would file it as received", store.pending(allowFallback = false).isEmpty())
        assertEquals(PendingCounts(awaitingHub = 1), store.pendingCounts())
        assertTrue(store.localPending().single().awaitingHub)

        store.rememberHubOutgoing(true)
        val (key, record) = store.pending(allowFallback = false).single()
        assertEquals("sent:1", key)
        assertTrue(record.outgoing)
        assertEquals(PendingCounts(ready = 1), store.pendingCounts())

        // ACK removes it; the ledger keeps a re-read of the same provider row out.
        store.ack(listOf(key))
        assertEquals(0, store.pendingCount())
        assertEquals(0, store.captureSent(listOf(sent(1))))
    }

    @Test
    fun syncedSentMessagesNeverNotifyOrCountAsUnread() {
        store.rememberHubOutgoing(true)
        store.captureSent(listOf(sent(1, body = "synthetic 验证码 482913")))
        fun message(id: Long, direction: String, device: String, body: String = "synthetic 验证码 482913") =
            JSONObject()
                .put("id", id)
                .put("device_id", device)
                .put("receiver", "+8613800000000")
                .put("sender", "13900000000")
                .put("body", body)
                .put("timestamp", 1_700_000_000_001)
                .put("historical", false)
                .put("direction", direction)
                .put("analysis", JSONObject().put("state", "complete"))
        store.initializeSyncHead(0)
        store.applySync(
            JSONArray().put(message(1, "out", "phone")).put(message(2, "out", "other")).put(message(3, "in", "other", "synthetic hello back")),
            3,
            self = "phone",
            pairedAt = 1,
        )
        // The synced copy of this phone's own sent message replaced its local queue entry.
        assertEquals(0, store.pendingCount())
        assertEquals(mapOf("13900000000" to 1), store.unreadCounts())
        assertEquals(1L, store.pendingNotification())
        val thread = store.messages(sender = "13900000000")
        assertEquals(listOf(true, true, false), thread.sortedBy { it.id }.map { it.outgoing })
        // A code the user forwarded is not offered back to them as their own.
        assertNull(messageOtp(thread.first { it.id == 2L }))
    }

    @Test
    fun versionThirteenRowsUpgradeAsReceived() {
        val legacy = "outgoing-v13-${java.util.UUID.randomUUID()}.db"
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(legacy), null).use { db ->
                db.execSQL("CREATE TABLE outbox (key TEXT PRIMARY KEY, receiver TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, sub INTEGER NOT NULL, slot INTEGER NOT NULL, historical INTEGER NOT NULL DEFAULT 0, blocked TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE inbox (id INTEGER PRIMARY KEY, device TEXT NOT NULL, receiver TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, contact TEXT NOT NULL, historical INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY,value INTEGER NOT NULL)")
                db.execSQL("INSERT INTO outbox VALUES ('k','+8613800000000','10086','synthetic',1,1,0,0,'')")
                db.execSQL("INSERT INTO inbox VALUES (1,'d','+8613800000000','10086','synthetic',1,'',0)")
                db.version = 13
            }
            Store(context, legacy).use { upgraded ->
                assertEquals(15, upgraded.readableDatabase.version)
                for (table in listOf("outbox", "inbox"))
                    upgraded.readableDatabase.rawQuery("SELECT direction FROM $table", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("in", it.getString(0))
                    }
                assertEquals(0L, upgraded.sentWatermark())
                assertFalse(upgraded.hubSupportsOutgoing())
            }
        } finally {
            context.deleteDatabase(legacy)
        }
    }

    @Test
    fun aSentMessageWakesTheProviderScan() {
        assertTrue(inboxChangeTriggersScan(upload = true, recovery = false, history = false, outgoing = true))
        assertFalse(inboxChangeTriggersScan(upload = false, recovery = false, history = false, outgoing = true))
    }

    @Test
    fun outgoingIsOnByDefaultWithABoundary() {
        val config = Config(context)
        assertTrue(config.outgoingEnabled)
        val since = config.outgoingSince
        assertTrue(since > 0)
        assertEquals("the boundary is remembered", since, Config(context).outgoingSince)
        val generation = config.outgoingGeneration
        config.outgoingEnabled = false
        assertFalse(config.outgoingEnabled)
        assertEquals(generation + 1, config.outgoingGeneration)
    }
}
