package io.hongshu.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

// Sent-box rows that are still sending or failed are rechecked for this long.
internal const val SENT_RETRY_WINDOW_MS = 24 * 60 * 60 * 1000L

// Notification post time and SMS provider/PDU time differ by seconds, rarely minutes.
internal const val NOTIFICATION_MATCH_WINDOW = 120_000L

// A notification has no SIM and may use a contact name as its sender, so the normal pairing
// above can refuse it. Once the authoritative broadcast/provider copy is accepted by the hub,
// a body match within this tighter window is enough to retire the redundant observation.
internal const val NOTIFICATION_ABSORB_WINDOW = 60_000L

// outbox.blocked state for rows the user asked to stop prompting about. It is intentionally a
// soft state: the capture ledger and notification receipt stay, so saving a SIM or pairing a
// later authoritative copy can restore the row without re-capturing or guessing.
internal const val AUTO_IGNORED = "auto_ignored"

// A notification is only evidence that an SMS arrived. Until the broadcast or the inbox copy
// pairs with it, its row waits: the inbox read it triggers normally lands within seconds and
// supplies the exact sender, SIM and text. A numeric title waits briefly (verification codes);
// a contact or brand title waits longer, since uploading it would file a name-keyed thread.
internal const val NOTIFICATION_NUMBER_HOLD = 30_000L
internal const val NOTIFICATION_NAME_HOLD = 150_000L

internal const val CAPTURE_EVENT_LIMIT = 200
internal const val CAPTURE_EVENT_FOLD_MS = 60_000L

data class CaptureEvent(val at: Long, val source: String, val outcome: String, val count: Int)

// Event sources; outcomes are short codes rendered by captureEventLabel.
internal object CaptureSource {
    const val BROADCAST = "broadcast"
    const val NOTIFICATION = "notification"
    const val RECOVERY = "recovery"
    const val HISTORY = "history"
    const val SENT = "sent"
    const val QUEUE = "queue"
}

private const val UNPAIRED_NOTIFICATION =
    "EXISTS (SELECT 1 FROM captures c WHERE c.key=outbox.key AND c.source='notification' AND c.broadcast_key IS NULL AND c.provider_id IS NULL)"
private const val NOTIFICATION_RELEASE_AT =
    "(timestamp+CASE WHEN sender GLOB '*[^0-9+]*' THEN $NOTIFICATION_NAME_HOLD ELSE $NOTIFICATION_NUMBER_HOLD END)"

// SQL predicate (one `?`: now) for notification rows still waiting for their SMS counterpart.
private const val HELD_NOTIFICATION =
    "$UNPAIRED_NOTIFICATION AND $NOTIFICATION_RELEASE_AT>CAST(? AS INTEGER)"

data class CachedMessage(
    val id: Long,
    val deviceId: String,
    val receiver: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val contact: String,
    val historical: Boolean = false,
    // Captured on this phone and not yet acknowledged by the hub; id is -(outbox rowid).
    val local: Boolean = false,
    val analysis: MessageAnalysis = MessageAnalysis(),
    val unreadCount: Int = 0,
    // Sent from the source phone; sender is then the recipient (see SmsRecord.outgoing).
    val outgoing: Boolean = false,
)

data class SimMapping(val sub: Int, val slot: Int, val phone: String, val label: String)

internal data class ConversationSnapshot(
    val deviceId: String,
    val epoch: Long,
    val sender: String,
    val messages: List<CachedMessage>,
)

class Store(private val context: Context, databaseName: String = "hongshu.db") :
    SQLiteOpenHelper(context, databaseName, null, 16), Closeable {
    // SQLiteOpenHelper only implements AutoCloseable on API 29+; Kotlin use must work on 26–28 too.
    private val config = Config(context)

    companion object {
        val changes = MutableStateFlow(0L)
    }

    @Volatile private var closed = false

    // The UI closes its store when the composition leaves, while a read started on the IO
    // thread may still be running. Such a read has no screen left to update, so it is
    // dropped instead of crashing the process on a closed connection pool.
    fun <T> readUnlessClosed(fallback: T, block: Store.() -> T): T {
        if (closed) return fallback
        return try {
            block()
        } catch (e: IllegalStateException) {
            if (closed) fallback else throw e
        }
    }

    override fun close() {
        closed = true
        super.close()
    }

    // The broadcast receiver, notification listener, workers and UI each open their own
    // connection to this file. WAL lets readers proceed during a write, and a longer busy
    // timeout on the write (primary) connection makes concurrent writers wait instead of
    // failing a broadcast capture; WAL reader connections keep Android's default.
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA busy_timeout=10000", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE outbox (key TEXT PRIMARY KEY, receiver TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, sub INTEGER NOT NULL, slot INTEGER NOT NULL, historical INTEGER NOT NULL DEFAULT 0, blocked TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE inbox (id INTEGER PRIMARY KEY, device TEXT NOT NULL, receiver TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, contact TEXT NOT NULL, historical INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX sender_history ON inbox(sender,timestamp,id)")
        db.execSQL("CREATE INDEX inbox_timeline ON inbox(timestamp,id)")
        db.execSQL(
            "CREATE TABLE sims (sub INTEGER PRIMARY KEY, slot INTEGER NOT NULL, phone TEXT NOT NULL, label TEXT NOT NULL)"
        )
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY,value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO meta VALUES ('cursor',0)")
        db.execSQL("INSERT INTO meta VALUES ('inbox_date',0)")
        db.execSQL("INSERT INTO meta VALUES ('inbox_id',0)")
        db.execSQL("INSERT INTO meta VALUES ('capture_since',0)")
        db.execSQL("INSERT INTO meta VALUES ('epoch',0)")
        db.execSQL("INSERT INTO meta VALUES ('epoch_seen',0)")
        createReliabilityState(db)
        db.execSQL("INSERT INTO meta VALUES ('history_boundary',0)")
        createCaptureDiagnostics(db)
        createMessageAnalysis(db)
        createReadState(db)
        createReadOutbox(db)
        createNotificationReceipts(db)
        createReliabilityExtras(db)
        createCaptureEvents(db)
        createOutgoing(db)
        createLoadingState(db)
        createCallLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("INSERT OR IGNORE INTO meta VALUES ('inbox_date',0)")
            db.execSQL("INSERT OR IGNORE INTO meta VALUES ('inbox_id',0)")
            db.execSQL("INSERT OR IGNORE INTO meta VALUES ('capture_since',0)")
        }
        if (oldVersion < 3) {
            db.execSQL("INSERT OR IGNORE INTO meta VALUES ('epoch',0)")
            db.execSQL("INSERT OR IGNORE INTO meta VALUES ('epoch_seen',0)")
        }
        if (oldVersion < 4) {
            createReliabilityState(db)
            // Preserve queued captures without guessing whether legacy rows came from a broadcast.
            db.execSQL(
                "INSERT OR IGNORE INTO captures (key,broadcast_key,receiver,sender,body,timestamp,sub,source) SELECT key,key,receiver,sender,body,timestamp,sub,'legacy' FROM outbox"
            )
        }
        if (oldVersion < 5) {
            db.execSQL("INSERT OR IGNORE INTO meta VALUES ('history_boundary',0)")
            // Revisit the old connection-time filter once, without discarding dedup evidence.
            db.execSQL("UPDATE meta SET value=0 WHERE key='provider_id'")
        }
        if (oldVersion < 6) createCaptureDiagnostics(db)
        if (oldVersion < 7) createMessageAnalysis(db)
        if (oldVersion < 8) createReadState(db)
        if (oldVersion < 9) createReadOutbox(db)
        if (oldVersion < 10) createNotificationReceipts(db)
        if (oldVersion < 11) createReliabilityExtras(db)
        if (oldVersion < 12) purgeNotificationNoise(db)
        if (oldVersion < 13) createCaptureEvents(db)
        if (oldVersion < 14) createOutgoing(db)
        if (oldVersion < 15) createLoadingState(db)
        if (oldVersion < 16) createCallLog(db)
    }

    // Derived heads only: migration never drops captured SMS or resets cursors.
    private fun createLoadingState(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_conversation_heads (sender TEXT PRIMARY KEY,message_id INTEGER NOT NULL,timestamp INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS local_heads_timeline ON local_conversation_heads(timestamp,message_id)")
        db.execSQL("INSERT OR REPLACE INTO local_conversation_heads SELECT i.sender,MAX(i.id),i.timestamp FROM inbox i JOIN (SELECT sender,MAX(timestamp) t FROM inbox GROUP BY sender) l ON l.sender=i.sender AND l.t=i.timestamp GROUP BY i.sender,i.timestamp")
        db.execSQL("CREATE TABLE IF NOT EXISTS preview_messages (id INTEGER PRIMARY KEY)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('preview_head',0)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('contacts_revision',0)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('contacts_checked_at',0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS inbox_local_identity ON inbox(device,receiver,sender,timestamp,direction)")
    }

    fun previewHead(): Long = meta("preview_head")
    fun contactsRevision(): Long = meta("contacts_revision")
    fun contactsCheckedAt(): Long = meta("contacts_checked_at")

    fun applyRecent(data: JSONObject) {
        val epoch = data.getLong("epoch")
        check(epochSeen() && epoch() == epoch) { "Snapshot epoch changed" }
        val messages = data.getJSONArray("messages")
        val head = data.getLong("head")
        check(head >= 0 && messages.length() <= 200 && (0 until messages.length()).all { messages.getJSONObject(it).getLong("id") in 1..head }) { "Invalid display snapshot" }
        applySync(messages, head, pageEpoch = epoch, preview = true)
    }

    // v14: messages this phone sent. Both queues and the synced cache carry a direction; every
    // existing row was received. Sent rows have their own provider cursor and retry list, so
    // they never move the inbox scan that broadcasts and notifications pair against.
    private fun createOutgoing(db: SQLiteDatabase) {
        for (table in listOf("outbox", "inbox")) {
            val present = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == "direction" }
            }
            if (!present) db.execSQL("ALTER TABLE $table ADD COLUMN direction TEXT NOT NULL DEFAULT 'in'")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS incomplete_sent (id INTEGER PRIMARY KEY,first_seen INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('sent_provider_id',0)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('hub_outgoing',0)")
    }

    fun sentWatermark(): Long = meta("sent_provider_id")

    fun sentHistoryGeneration(): Long = meta("sent_history_generation")

    // The dated scan (history off) steps over older rows; when the history switch changes the
    // sent box is read again from the start. The capture ledger keeps that from re-uploading.
    fun restartSentScan(historyGeneration: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO meta VALUES ('sent_history_generation',0)")
            db.execSQL("UPDATE meta SET value=? WHERE key='sent_history_generation'", arrayOf(historyGeneration))
            db.execSQL("UPDATE meta SET value=0 WHERE key='sent_provider_id'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // A wiped or restored provider can reuse low ids; start the sent cursor over. Captured rows
    // keep their ledger keys, so a reused id that was already captured is not uploaded twice.
    fun observeSentHead(head: Long) {
        if (head < sentWatermark())
            writableDatabase.execSQL("UPDATE meta SET value=0 WHERE key='sent_provider_id'")
    }

    // Rows still being sent (outbox/queued) or failed may turn into sent rows under the same id.
    // They are revisited for a day, then given up: a message that never went out is not news.
    fun incompleteSentIds(now: Long = System.currentTimeMillis()): List<Long> {
        writableDatabase.execSQL(
            "DELETE FROM incomplete_sent WHERE first_seen<?",
            arrayOf(now - SENT_RETRY_WINDOW_MS),
        )
        return readableDatabase
            .rawQuery("SELECT id FROM incomplete_sent ORDER BY id LIMIT 100", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    // Records rows that are not (yet) sent messages and advances past them in one transaction.
    fun skipSentRows(pending: List<Long>, ignored: List<Long>, now: Long = System.currentTimeMillis()) {
        if (pending.isEmpty() && ignored.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in pending)
                db.execSQL("INSERT OR IGNORE INTO incomplete_sent VALUES (?,?)", arrayOf(id, now))
            for (id in ignored) db.delete("incomplete_sent", "id=?", arrayOf(id.toString()))
            advanceSent(db, (pending + ignored).max())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun advanceSent(db: SQLiteDatabase, id: Long) =
        db.execSQL("UPDATE meta SET value=MAX(value,?) WHERE key='sent_provider_id'", arrayOf(id))

    // Sent rows have exactly one capture interface (the provider), so they skip the
    // broadcast/notification pairing and dedupe on their ledger key alone.
    fun captureSent(records: List<Pair<Long, SmsRecord>>): Int {
        if (records.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for ((id, m) in records) {
                require(m.outgoing)
                val key = "sent:$id"
                db.delete("incomplete_sent", "id=?", arrayOf(id.toString()))
                val known = db.rawQuery("SELECT 1 FROM captures WHERE key=?", arrayOf(key)).use { it.moveToFirst() }
                if (known) continue
                db.insertOrThrow(
                    "captures",
                    null,
                    ContentValues().apply {
                        put("key", key)
                        put("receiver", m.receiver)
                        put("sender", m.sender)
                        put("body", m.body)
                        put("timestamp", m.timestamp)
                        put("sub", m.subscriptionId)
                        put("source", CaptureSource.SENT)
                    },
                )
                db.insertOrThrow(
                    "outbox",
                    null,
                    ContentValues().apply {
                        put("key", key)
                        put("receiver", m.receiver)
                        put("sender", m.sender)
                        put("body", m.body)
                        put("timestamp", m.timestamp)
                        put("sub", m.subscriptionId)
                        put("slot", -1)
                        put("historical", if (m.historical) 1 else 0)
                        put("direction", "out")
                    },
                )
                added++
            }
            advanceSent(db, records.maxOf { it.first })
            recordCaptureEvent(CaptureSource.SENT, "added", added)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) changes.value++
        return added
    }

    fun hubSupportsOutgoing(): Boolean = meta("hub_outgoing") != 0L

    fun rememberHubOutgoing(supported: Boolean) {
        writableDatabase.execSQL(
            "UPDATE meta SET value=? WHERE key='hub_outgoing'",
            arrayOf(if (supported) 1 else 0),
        )
    }

    // A recipient written the way this phone already files the conversation (a received
    // message or a synced one), so a reply lands in the same thread. Only exact matches under
    // sender normalisation count; anything else keeps the address as the SMS app stored it.
    fun knownSenderFor(address: String): String? {
        val digits = normalizeSender(address).takeLast(8)
        if (digits.length < 3) return null
        return readableDatabase
            .rawQuery(
                "SELECT sender FROM (SELECT sender,MAX(timestamp) t FROM outbox WHERE direction='in' AND sender LIKE ? GROUP BY sender UNION ALL SELECT sender,MAX(timestamp) t FROM inbox WHERE direction='in' AND sender LIKE ? GROUP BY sender) ORDER BY t DESC LIMIT 20",
                arrayOf("%$digits", "%$digits"),
            )
            .use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            .firstOrNull { sameSender(it, address) }
    }

    // v13: a bounded, content-free log of what each capture source did, so a device report can
    // show "notification arrived → inbox read paired it" without any sender or body.
    private fun createCaptureEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS capture_events (id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,source TEXT NOT NULL,outcome TEXT NOT NULL,count INTEGER NOT NULL DEFAULT 1)")
    }

    internal fun recordCaptureEvent(source: String, outcome: String, count: Int = 1, at: Long = System.currentTimeMillis()) {
        if (count <= 0) return
        val db = writableDatabase
        // A burst of the same outcome (a reposted notification, a paged inbox) folds into one line.
        val merged = db.rawQuery("SELECT id FROM capture_events WHERE id=(SELECT MAX(id) FROM capture_events) AND source=? AND outcome=? AND ABS(at-?)<=$CAPTURE_EVENT_FOLD_MS",
            arrayOf(source, outcome, at.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (merged != null) {
            db.execSQL("UPDATE capture_events SET count=count+?,at=MAX(at,?) WHERE id=?", arrayOf<Any>(count, at, merged))
            return
        }
        db.execSQL("INSERT INTO capture_events (at,source,outcome,count) VALUES (?,?,?,?)", arrayOf<Any>(at, source, outcome, count))
        db.execSQL("DELETE FROM capture_events WHERE id<=(SELECT MAX(id) FROM capture_events)-$CAPTURE_EVENT_LIMIT")
    }

    fun captureEvents(limit: Int = 30): List<CaptureEvent> =
        readableDatabase
            .rawQuery("SELECT at,source,outcome,count FROM capture_events ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            .use { c ->
                buildList { while (c.moveToNext()) add(CaptureEvent(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3))) }
            }

    // v12: before service placeholders and "[N条]" summaries were filtered, the notification
    // fallback queued them as SMS. They never were a single message, and nothing but the
    // notification ever produced them: drop the unsent, unpaired rows and their receipts.
    internal fun purgeNotificationNoise(db: SQLiteDatabase): Int {
        val keys =
            db.rawQuery(
                    "SELECT c.key,c.sender,c.body FROM captures c JOIN outbox o ON o.key=c.key WHERE c.source='notification' AND c.broadcast_key IS NULL AND c.provider_id IS NULL",
                    null,
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext())
                            if (notificationNoise(c.getString(1), c.getString(2))) add(c.getString(0))
                    }
                }
        if (keys.isEmpty()) return 0
        db.beginTransaction()
        try {
            for (key in keys) {
                db.delete("outbox", "key=?", arrayOf(key))
                db.delete("captures", "key=?", arrayOf(key))
                db.delete("notification_receipts", "capture_key=?", arrayOf(key))
                db.delete("upload_fingerprints", "capture_key=?", arrayOf(key))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return keys.size
    }

    // v11: durable verification-code intents and a bounded retry list for provider rows that
    // were incomplete when first scanned. Both are additive; no existing row is rewritten.
    private fun createReliabilityExtras(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS otp_intents (message_id INTEGER PRIMARY KEY,otp TEXT NOT NULL,expires_at INTEGER NOT NULL,sender TEXT NOT NULL,contact TEXT NOT NULL,epoch INTEGER NOT NULL,shown INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS incomplete_provider (id INTEGER PRIMARY KEY,last_attempt INTEGER NOT NULL DEFAULT 0)"
        )
    }

    data class OtpIntent(
        val messageId: Long,
        val otp: String,
        val expiresAt: Long,
        val sender: String,
        val contact: String,
        val epoch: Long,
    )

    fun pendingOtpIntents(now: Long = System.currentTimeMillis()): List<OtpIntent> =
        readableDatabase
            .rawQuery(
                "SELECT message_id,otp,expires_at,sender,contact,epoch FROM otp_intents WHERE shown=0 AND expires_at>? ORDER BY message_id",
                arrayOf(now.toString()),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext())
                        add(
                            OtpIntent(
                                c.getLong(0),
                                c.getString(1),
                                c.getLong(2),
                                c.getString(3),
                                c.getString(4),
                                c.getLong(5),
                            )
                        )
                }
            }

    fun markOtpShown(messageId: Long) {
        writableDatabase.execSQL(
            "UPDATE otp_intents SET shown=1 WHERE message_id=?",
            arrayOf(messageId),
        )
    }

    fun dropExpiredOtpIntents(now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            "DELETE FROM otp_intents WHERE shown=0 AND expires_at<=?",
            arrayOf(now),
        )
    }

    // Read state revokes both pending and already shown intents; the caller cancels the
    // matching system notifications with the same identity tag.
    private fun revokeOtpIntents(
        db: SQLiteDatabase,
        sender: String?,
        boundary: Long,
    ): List<OtpIntent> {
        val where = if (sender == null) "message_id<=?" else "sender=? AND message_id<=?"
        val args =
            if (sender == null) arrayOf(boundary.toString())
            else arrayOf(sender, boundary.toString())
        val revoked =
            db.rawQuery(
                    "SELECT message_id,otp,expires_at,sender,contact,epoch FROM otp_intents WHERE $where",
                    args,
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext())
                            add(
                                OtpIntent(
                                    c.getLong(0),
                                    c.getString(1),
                                    c.getLong(2),
                                    c.getString(3),
                                    c.getString(4),
                                    c.getLong(5),
                                )
                            )
                    }
                }
        if (revoked.isNotEmpty()) db.execSQL("DELETE FROM otp_intents WHERE $where", args)
        return revoked
    }

    private fun cancelOtpNotifications(intents: List<OtpIntent>) {
        if (intents.isEmpty()) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        for (intent in intents)
            manager.cancel(
                OtpNotificationManager.notificationTag(context, intent.messageId, intent.epoch),
                OtpNotificationManager.NOTIFICATION_ID,
            )
    }

    fun incompleteProviderIds(): List<Long> {
        val ids =
            readableDatabase
                .rawQuery(
                    "SELECT id FROM incomplete_provider ORDER BY last_attempt,id LIMIT 100",
                    null,
                )
                .use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
        for (id in ids)
            writableDatabase.execSQL(
                "UPDATE incomplete_provider SET last_attempt=? WHERE id=?",
                arrayOf(System.currentTimeMillis(), id),
            )
        return ids
    }

    fun rememberIncompleteProvider(id: Long) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO incomplete_provider (id) VALUES (?)", arrayOf(id))
    }

    // A wiped or restored SMS database can reuse low provider ids; comparing the current head
    // with the scan watermark detects the rewrite without scanning the whole inbox.
    fun observeProviderHead(head: Long) {
        if (head < maxOf(providerWatermark(), recoveryWatermark())) {
            writableDatabase.execSQL(
                "UPDATE meta SET value=0 WHERE key IN ('provider_id','recovery_provider_id')"
            )
        }
    }

    private fun createNotificationReceipts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notification_receipts (identity TEXT PRIMARY KEY,capture_key TEXT NOT NULL UNIQUE,uncertain INTEGER NOT NULL DEFAULT 0)")
        // No old row is rewritten: pre-v10 lacks the structured notification identity.
        db.execSQL("INSERT INTO notification_receipts SELECT key,key,1 FROM captures WHERE source='notification'")
        db.execSQL("CREATE TABLE upload_fingerprints (fingerprint TEXT PRIMARY KEY,capture_key TEXT NOT NULL)")
        // Preserve pre-v10 acknowledged evidence. Unresolved numbers cannot be guessed.
        db.rawQuery("SELECT key,receiver,sender,body,timestamp,sub FROM captures ORDER BY EXISTS (SELECT 1 FROM outbox o WHERE o.key=captures.key),rowid", null).use { c ->
            while (c.moveToNext()) {
                if (!confirmedNumber(c.getString(1))) continue
                val m = SmsRecord(c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5))
                db.execSQL("INSERT OR IGNORE INTO upload_fingerprints VALUES (?,?)", arrayOf(m.fingerprint(), c.getString(0)))
            }
        }
    }

    private fun createReadOutbox(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS read_outbox (sender TEXT PRIMARY KEY, message_id INTEGER NOT NULL)")
    }

    private fun createReadState(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_reads (sender TEXT PRIMARY KEY, last_read_message_id INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('global_watermark_id', 0)")
        // For existing messages on upgrade, initialize watermark to current max inbox ID so upgrade doesn't mark all as unread
        db.execSQL("UPDATE meta SET value=(SELECT COALESCE(MAX(id), 0) FROM inbox) WHERE key='global_watermark_id' AND value=0")
    }

    private fun createMessageAnalysis(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE message_analysis (message_id INTEGER PRIMARY KEY,otp TEXT NOT NULL DEFAULT '',expires_at INTEGER NOT NULL DEFAULT 0,category TEXT NOT NULL DEFAULT '',manual INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'pending',error TEXT NOT NULL DEFAULT '',revision INTEGER NOT NULL DEFAULT 0)")
        for (key in listOf("analysis_cursor", "ai_enabled", "local_rule_cursor")) db.execSQL("INSERT OR IGNORE INTO meta VALUES (?,0)", arrayOf(key))
    }

    fun aiEnabled(): Boolean = meta("ai_enabled") != 0L
    fun rememberAI(enabled: Boolean) {
        if (aiEnabled() == enabled) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE meta SET value=? WHERE key='ai_enabled'", arrayOf(if (enabled) 1 else 0))
            // The server's effective OTP and category suppression depend on
            // ai_enabled. Re-read derived metadata instead of keeping a stale code.
            db.execSQL("UPDATE meta SET value=0 WHERE key='analysis_cursor'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
    }
    fun analysisCursor(): Long = meta("analysis_cursor")

    private fun saveAnalysis(db: SQLiteDatabase, id: Long, a: MessageAnalysis) {
        // SQLite on API 26-28 predates UPSERT. Both statements run in the caller's transaction.
        db.execSQL("UPDATE message_analysis SET otp=?,expires_at=?,category=?,manual=?,state=?,error=?,revision=? WHERE message_id=? AND revision<=?",
            arrayOf<Any>(a.otp, a.expiresAt, a.category, if (a.manual) 1 else 0, a.state, a.error, a.revision, id, a.revision))
        db.execSQL("INSERT OR IGNORE INTO message_analysis (message_id,otp,expires_at,category,manual,state,error,revision) VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any>(id, a.otp, a.expiresAt, a.category, if (a.manual) 1 else 0, a.state, a.error, a.revision))
    }

    fun applyAnalysis(items: JSONArray, cursor: Long, pageEpoch: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(!epochReset(epochSeen(), epoch(), pageEpoch)) { "Analysis epoch changed" }
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                saveAnalysis(db, item.getLong("id"), MessageAnalysis.from(item.getJSONObject("analysis")))
            }
            db.execSQL("UPDATE meta SET value=? WHERE key='analysis_cursor'", arrayOf(cursor))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changes.value++
    }

    fun markRead(sender: String = "", messageId: Long = 0L, all: Boolean = false): Boolean {
        val db = writableDatabase
        var changed = false
        val revoked = mutableListOf<OtpIntent>()
        db.beginTransaction()
        try {
            if (all) {
                val head = db.rawQuery("SELECT COALESCE(MAX(id),0) FROM inbox", null).use {
                    it.moveToFirst(); it.getLong(0)
                }
                if (head > meta("global_watermark_id")) {
                    db.execSQL("UPDATE meta SET value=? WHERE key='global_watermark_id'", arrayOf(head))
                    changed = true
                }
                if (head > 0) revoked += revokeOtpIntents(db, null, head)
            } else if (sender.isNotEmpty() && messageId > 0) {
                // Only an actual message loaded for this sender may move its read watermark.
                val exists = db.rawQuery("SELECT 1 FROM inbox WHERE sender=? AND id=?",
                    arrayOf(sender, messageId.toString())).use { it.moveToFirst() }
                if (exists) {
                    val previous = db.rawQuery("SELECT last_read_message_id FROM conversation_reads WHERE sender=?",
                        arrayOf(sender)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    if (messageId > maxOf(previous, meta("global_watermark_id"))) {
                        db.execSQL("INSERT OR REPLACE INTO conversation_reads VALUES (?,?)", arrayOf<Any>(sender, messageId))
                        db.execSQL("INSERT OR REPLACE INTO read_outbox VALUES (?,?)", arrayOf<Any>(sender, messageId))
                        changed = true
                    }
                    revoked += revokeOtpIntents(db, sender, messageId)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cancelOtpNotifications(revoked)
        if (changed) changes.value++
        return changed
    }

    // Lock order is identityLock -> SQLite. Unlike identityMutex this fence is never held
    // over HTTP. The transaction gives all the local queries one consistent database view.
    internal fun conversationSnapshot(sender: String, limit: Int, group: String): ConversationSnapshot? =
        synchronized(identityLock) {
            if (config.identityChanging) return@synchronized null
            val db = readableDatabase
            db.beginTransactionNonExclusive()
            try {
                val result = ConversationSnapshot(config.deviceId, epoch(), sender,
                    messagesWithLocal(sender, "", limit = limit, group = group))
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        }

    internal fun markDisplayedRead(snapshot: ConversationSnapshot): Boolean = synchronized(identityLock) {
        if (config.identityChanging || config.deviceId != snapshot.deviceId || epoch() != snapshot.epoch)
            return@synchronized false
        val shownId = snapshot.messages.filterNot { it.local }.maxOfOrNull { it.id } ?: 0L
        markRead(snapshot.sender, shownId)
    }

    fun pendingReads(): List<Pair<String, Long>> = readableDatabase
        .rawQuery("SELECT sender,message_id FROM read_outbox ORDER BY sender LIMIT 100", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }

    fun acknowledgeRead(sender: String, messageId: Long) {
        writableDatabase.delete("read_outbox", "sender=? AND message_id<=?", arrayOf(sender, messageId.toString()))
    }

    fun updateGlobalWatermark(watermark: Long) {
        if (watermark <= 0) return
        val changed = writableDatabase.update("meta", ContentValues().apply { put("value", watermark) },
            "key='global_watermark_id' AND value<?", arrayOf(watermark.toString()))
        if (changed > 0) changes.value++
    }

    fun unreadCounts(): Map<String, Int> {
        val watermark = meta("global_watermark_id")
        return readableDatabase.rawQuery(
            """
            SELECT i.sender, COUNT(*)
            FROM inbox i
            LEFT JOIN conversation_reads cr ON cr.sender=i.sender
            WHERE i.historical=0
              AND i.direction='in'
              AND i.id > ?
              AND i.id > COALESCE(cr.last_read_message_id, 0)
            GROUP BY i.sender
            """.trimIndent(),
            arrayOf(watermark.toString())
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(0), c.getInt(1))
                }
            }
        }
    }

    fun resetAnalysis() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("message_analysis", null, null)
            db.execSQL("UPDATE meta SET value=0 WHERE key IN ('analysis_cursor','local_rule_cursor')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // Upgrade/old-server fallback, bounded and outside SQLiteOpenHelper migration/UI.
    fun backfillLocalRules() {
        val cursor = meta("local_rule_cursor")
        val batch = readableDatabase.rawQuery("SELECT id,CASE WHEN direction='out' THEN '' ELSE body END FROM inbox WHERE id>? ORDER BY id LIMIT 200", arrayOf(cursor.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, body) in batch) {
                val otp = extractOtp(body).orEmpty()
                db.execSQL("INSERT OR IGNORE INTO message_analysis (message_id,otp,state) VALUES (?,?,?)", arrayOf<Any>(id, otp, if (otp.isEmpty()) "pending" else "complete"))
            }
            batch.lastOrNull()?.let { db.execSQL("UPDATE meta SET value=? WHERE key='local_rule_cursor'", arrayOf(it.first)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (batch.isNotEmpty()) changes.value++
    }

    private fun createCaptureDiagnostics(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS outbox_timeline ON outbox(timestamp,key)")
        for (key in
            listOf(
                "realtime_broadcasts",
                "realtime_saved",
                "realtime_duplicates",
                "last_broadcast",
                "last_capture",
            )) {
            db.execSQL("INSERT OR IGNORE INTO meta VALUES (?,0)", arrayOf(key))
        }
    }

    fun captureDiagnostics() =
        CaptureDiagnostics(
            meta("realtime_broadcasts"),
            meta("realtime_saved"),
            meta("realtime_duplicates"),
            meta("last_broadcast"),
            meta("last_capture"),
        )

    // No SubscriptionManager calls here. Unknown subscription/slot is not an identity.
    fun realtimeReceiver(sub: Int): String =
        if (sub < 0 || (config.automaticSim && !config.simRecognitionHealthy)) ""
        else sims().find { it.sub == sub }?.phone.orEmpty()

    fun queueRealtime(m: SmsRecord, slot: Int, receivedAt: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val added = capture(m, slot, null, false)
            recordCaptureEvent(CaptureSource.BROADCAST, if (added) "saved" else "duplicate", at = receivedAt)
            db.execSQL("UPDATE meta SET value=value+1 WHERE key='realtime_broadcasts'")
            db.execSQL("UPDATE meta SET value=? WHERE key='last_broadcast'", arrayOf(receivedAt))
            db.execSQL(
                "UPDATE meta SET value=value+1 WHERE key=?",
                arrayOf(if (added) "realtime_saved" else "realtime_duplicates"),
            )
            if (added)
                db.execSQL("UPDATE meta SET value=? WHERE key='last_capture'", arrayOf(receivedAt))
            db.setTransactionSuccessful()
            return added
        } finally {
            db.endTransaction()
            changes.value++
        }
    }

    fun localPending(limit: Int = 100, offset: Int = 0, now: Long = System.currentTimeMillis()): List<LocalPendingMessage> {
        val resolver = receiverResolver(allowFallback = false)
        val hubOutgoing = hubSupportsOutgoing()
        return readableDatabase
            .rawQuery(
                "SELECT key,receiver,sender,body,timestamp,sub,slot,historical,blocked,COALESCE((SELECT source FROM captures WHERE captures.key=outbox.key),'legacy'),COALESCE((SELECT uncertain FROM notification_receipts n WHERE n.capture_key=outbox.key),0),$UNPAIRED_NOTIFICATION,$UNPAIRED_NOTIFICATION AND $NOTIFICATION_RELEASE_AT>CAST(? AS INTEGER),direction FROM outbox WHERE blocked<>'$AUTO_IGNORED' ORDER BY timestamp DESC,key DESC LIMIT ? OFFSET ?",
                arrayOf(now.toString(), limit.coerceIn(1, 200).toString(), offset.coerceAtLeast(0).toString()),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        LocalPendingMessage(
                            c.getString(0),
                            c.getString(1).ifEmpty {
                                if (c.getInt(5) < 0 && c.getString(9) == "broadcast") ""
                                else resolver(c.getInt(5))
                            },
                            c.getString(2),
                            c.getString(3),
                            c.getLong(4),
                            c.getInt(5),
                            c.getInt(6),
                            c.getInt(7) != 0,
                            c.getString(8),
                            c.getInt(10) != 0,
                            c.getInt(11) != 0,
                            c.getInt(12) != 0,
                            outgoing = c.getString(13) == "out",
                            awaitingHub = c.getString(13) == "out" && !hubOutgoing,
                        )
                    )
                }
            }
    }

    fun unresolvedCount(): Int {
        val resolver = receiverResolver()
        return readableDatabase
            .rawQuery(
                "SELECT o.receiver,o.sub,o.blocked,c.source,COUNT(*) FROM outbox o LEFT JOIN captures c ON c.key=o.key GROUP BY o.receiver,o.sub,o.blocked,c.source",
                null,
            )
            .use { c ->
                var count = 0
                while (c.moveToNext()) {
                    if (
                        c.getString(2) == "sim_not_confirmed" ||
                            (c.getString(0).isEmpty() &&
                                ((c.getInt(1) < 0 && c.getString(3) == "broadcast") ||
                                    resolver(c.getInt(1)).isEmpty()))
                    )
                        count += c.getInt(4)
                }
                count
            }
    }

    private fun createReliabilityState(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE captures (key TEXT PRIMARY KEY,broadcast_key TEXT UNIQUE,provider_id INTEGER UNIQUE,receiver TEXT NOT NULL,sender TEXT NOT NULL,body TEXT NOT NULL,timestamp INTEGER NOT NULL,sub INTEGER NOT NULL,source TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX capture_match ON captures(sender,sub,timestamp)")
        db.execSQL("CREATE INDEX outbox_ready ON outbox(blocked,timestamp,key)")
        db.execSQL("CREATE INDEX inbox_receiver ON inbox(receiver)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('initial_head',-1)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('initial_complete',0)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('notify_pending',0)")
        db.execSQL("INSERT OR IGNORE INTO meta VALUES ('provider_id',0)")
        db.execSQL("CREATE TABLE contact_snapshot (phone TEXT PRIMARY KEY,name TEXT NOT NULL)")
        db.execSQL(
            "INSERT INTO contact_snapshot SELECT sender,MAX(contact) FROM inbox WHERE contact<>'' GROUP BY sender"
        )
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE blocked<>'$AUTO_IGNORED'", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    fun queue(m: SmsRecord, slot: Int = -1) = capture(m, slot, null, false)

    fun captureProvider(id: Long, m: SmsRecord, advance: Boolean = false): Boolean =
        capture(m, -1, id, advance)

    fun captureProviders(
        records: List<Pair<Long, SmsRecord>>,
        advance: Boolean,
        recovery: Boolean = false,
    ): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = records.count { (id, message) -> captureProvider(id, message, advance) }
            if (recovery) records.lastOrNull()?.let { advanceRecoveryProvider(it.first) }
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    private fun capture(m: SmsRecord, slot: Int, providerId: Long?, advance: Boolean): Boolean {
        val db = writableDatabase
        val broadcastKey =
            sha256("${m.subscriptionId}\u0000${m.sender}\u0000${m.identityTime}\u0000${m.body}")
        val key = if (providerId == null) broadcastKey else "provider:$providerId"
        db.beginTransaction()
        try {
            if (advance && providerId != null) advanceProvider(providerId)
            // The row is complete now: stop retrying it, whatever the merge outcome is.
            if (providerId != null) db.delete("incomplete_provider", "id=?", arrayOf(providerId.toString()))
            val existing =
                if (providerId == null)
                    db.rawQuery(
                        "SELECT key FROM captures WHERE broadcast_key=?",
                        arrayOf(broadcastKey),
                    )
                else
                    db.rawQuery(
                        "SELECT key FROM captures WHERE provider_id=?",
                        arrayOf(providerId.toString()),
                    )
            if (existing.use { it.moveToFirst() }) {
                db.setTransactionSuccessful()
                return false
            }
            // Only pair two capture interfaces. Never collapse independent provider rows or
            // synchronized remote messages. One side may lack a SIM (ROMs omit the broadcast
            // subscription extra); it then inherits the other side's SIM instead of staying an
            // unresolvable duplicate.
            val counterpart =
                if (providerId == null) "source='provider' AND broadcast_key IS NULL"
                else "source='broadcast' AND provider_id IS NULL"
            val match =
                db.rawQuery(
                        "SELECT key,sender,sub,receiver FROM captures WHERE $counterpart AND body=? AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp-?),key",
                        arrayOf(
                            m.body,
                            (m.timestamp - 120000).toString(),
                            (m.timestamp + 120000).toString(),
                            m.timestamp.toString(),
                        ),
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                if (
                                    sameSender(c.getString(1), m.sender) &&
                                        compatibleSims(
                                            c.getInt(2),
                                            c.getString(3),
                                            m.subscriptionId,
                                            m.receiver,
                                        )
                                )
                                    add(CaptureMatch(c.getString(0), c.getInt(2), c.getString(3)))
                            }
                        }.singleOrNull()
                    }
            if (match != null) {
                val values =
                    ContentValues().apply {
                        if (providerId == null) put("broadcast_key", broadcastKey)
                        else put("provider_id", providerId)
                    }
                db.update("captures", values, "key=?", arrayOf(match.key))
                if (match.sub < 0 && m.subscriptionId >= 0) inheritSim(db, match.key, m, slot)
                db.setTransactionSuccessful()
                return false
            }
            if (linkNotificationCapture(db, m, slot, providerId, broadcastKey)) {
                db.setTransactionSuccessful()
                return false
            }
            db.insertOrThrow(
                "captures",
                null,
                ContentValues().apply {
                    put("key", key)
                    if (providerId == null) put("broadcast_key", broadcastKey)
                    else put("provider_id", providerId)
                    put("receiver", m.receiver)
                    put("sender", m.sender)
                    put("body", m.body)
                    put("timestamp", m.timestamp)
                    put("sub", m.subscriptionId)
                    put("source", if (providerId == null) "broadcast" else "provider")
                },
            )
            val cv =
                ContentValues().apply {
                    put("key", key)
                    put("receiver", m.receiver)
                    put("sender", m.sender)
                    put("body", m.body)
                    put("timestamp", m.timestamp)
                    put("sub", m.subscriptionId)
                    put("slot", slot)
                    put("historical", if (m.historical) 1 else 0)
                }
            db.insertOrThrow("outbox", null, cv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
        return true
    }

    private data class CaptureMatch(val key: String, val sub: Int, val receiver: String)

    private data class CaptureCandidate(
        val key: String,
        val sender: String,
        val sub: Int,
        val receiver: String,
        val timestamp: Long,
    )

    private data class UnknownCapture(
        val key: String,
        val source: String,
        val sender: String,
        val body: String,
        val timestamp: Long,
        val sub: Int,
        val receiver: String,
    )

    private data class AutoIgnoreRow(
        val key: String,
        val receiver: String,
        val sub: Int,
        val source: String,
    )

    private data class InboxMatch(
        val receiver: String,
        val sender: String,
        val body: String,
        val timestamp: Long,
    )

    // The SIM-less half of a pair learns the SIM from its counterpart. Rows already
    // acknowledged by the hub have no outbox entry and are left alone.
    private fun inheritSim(db: SQLiteDatabase, key: String, m: SmsRecord, slot: Int) {
        db.update(
            "captures",
            ContentValues().apply {
                put("sub", m.subscriptionId)
                if (m.receiver.isNotEmpty()) put("receiver", m.receiver)
            },
            "key=? AND sub<0",
            arrayOf(key),
        )
        db.update(
            "outbox",
            ContentValues().apply {
                put("sub", m.subscriptionId)
                if (slot >= 0) put("slot", slot)
                if (m.receiver.isNotEmpty()) put("receiver", m.receiver)
            },
            "key=? AND sub<0",
            arrayOf(key),
        )
        if (m.receiver.isNotEmpty())
            db.execSQL(
                "UPDATE outbox SET blocked='' WHERE key=? AND blocked='$AUTO_IGNORED'",
                arrayOf(key),
            )
        changes.value++
    }

    // Before pairing accepted a SIM-less side, a broadcast without a subscription extra and
    // its provider row were kept as two captures: the provider copy uploaded, the broadcast
    // copy waited forever for a number. Fold such leftovers into their counterpart.
    fun reconcileUnknownSimCaptures(): Int {
        val db = writableDatabase
        val orphans =
            db.rawQuery(
                    "SELECT o.key,c.source,c.sender,c.body,c.timestamp,c.sub,c.receiver FROM outbox o JOIN captures c ON c.key=o.key WHERE o.receiver='' AND o.sub<0 AND ((c.source='broadcast' AND c.provider_id IS NULL) OR (c.source='provider' AND c.broadcast_key IS NULL))",
                    null,
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext())
                            add(
                                UnknownCapture(
                                    c.getString(0),
                                    c.getString(1),
                                    c.getString(2),
                                    c.getString(3),
                                    c.getLong(4),
                                    c.getInt(5),
                                    c.getString(6),
                                )
                            )
                    }
                }
        var merged = 0
        for (orphan in orphans) {
            val free =
                if (orphan.source == "broadcast") "source='provider' AND broadcast_key IS NULL"
                else "source='broadcast' AND provider_id IS NULL"
            db.beginTransaction()
            try {
                val targets =
                    db.rawQuery(
                            "SELECT key,sender,sub,receiver,timestamp FROM captures WHERE $free AND body=? AND (sub>=0 OR receiver<>'') AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp-?),key",
                            arrayOf(
                                orphan.body,
                                (orphan.timestamp - 120000).toString(),
                                (orphan.timestamp + 120000).toString(),
                                orphan.timestamp.toString(),
                            ),
                        )
                        .use { c ->
                            buildList {
                                while (c.moveToNext()) {
                                    val candidate =
                                        CaptureCandidate(
                                            c.getString(0),
                                            c.getString(1),
                                            c.getInt(2),
                                            c.getString(3),
                                            c.getLong(4),
                                        )
                                    if (
                                        sameSender(candidate.sender, orphan.sender) &&
                                            compatibleSims(
                                                orphan.sub,
                                                orphan.receiver,
                                                candidate.sub,
                                                candidate.receiver,
                                            )
                                    )
                                        add(candidate)
                                }
                            }
                        }
                if (targets.size == 1) {
                    val target = targets.single().key
                    val identity =
                        db.rawQuery(
                            "SELECT broadcast_key,provider_id FROM captures WHERE key=?",
                            arrayOf(orphan.key),
                        )
                            .use { c ->
                                if (!c.moveToFirst()) null
                                else if (orphan.source == "broadcast") c.getString(0)
                                else c.getLong(1).toString()
                            }
                    db.delete("captures", "key=?", arrayOf(orphan.key))
                    db.delete("outbox", "key=?", arrayOf(orphan.key))
                    db.execSQL("UPDATE upload_fingerprints SET capture_key=? WHERE capture_key=?", arrayOf(target, orphan.key))
                    if (identity != null)
                        db.update(
                            "captures",
                            ContentValues().apply {
                                if (orphan.source == "broadcast") put("broadcast_key", identity)
                                else put("provider_id", identity.toLong())
                            },
                            "key=?",
                            arrayOf(target),
                        )
                    merged++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (merged > 0) changes.value++
        return merged
    }

    // Keep a durable one-to-one receipt, even after either side has been acknowledged.
    fun reconcileNotificationCaptures(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val orphans =
                db.rawQuery(
                        "SELECT c.key,c.sender,c.body,c.timestamp,c.sub,c.receiver FROM outbox o JOIN captures c ON c.key=o.key JOIN notification_receipts n ON n.capture_key=c.key WHERE c.source='notification' AND c.broadcast_key IS NULL AND c.provider_id IS NULL",
                        null,
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext())
                                add(
                                    UnknownCapture(
                                        c.getString(0),
                                        "notification",
                                        c.getString(1),
                                        c.getString(2),
                                        c.getLong(3),
                                        c.getInt(4),
                                        c.getString(5),
                                    )
                                )
                        }
                    }
            var merged = 0
            for (orphan in orphans) {
                val message = SmsRecord(orphan.receiver, orphan.sender, orphan.body, orphan.timestamp, orphan.sub)
                val targets = notificationCandidates(db, message, notifications = false)
                val target = targets.singleOrNull()
                val reverse = target?.let {
                    // A receipt already paired with another interface still competes
                    // for this interface, even after ACK removed its outbox row.
                    val free = db.rawQuery("SELECT broadcast_key,provider_id FROM captures WHERE key=?", arrayOf(it.key))
                        .use { row ->
                            if (!row.moveToFirst()) "0=1"
                            else buildList {
                                if (!row.isNull(0)) add("broadcast_key IS NULL")
                                if (!row.isNull(1)) add("provider_id IS NULL")
                            }.joinToString(" OR ").ifEmpty { "1=1" }
                        }
                    notificationCandidates(db, SmsRecord(it.receiver, it.sender, orphan.body, it.timestamp, it.sub),
                        notifications = true, free = "($free)")
                }
                if (target != null && reverse?.singleOrNull()?.key == orphan.key &&
                    !hasNotificationReceipt(db, target.key)) {
                    val targetMessage = SmsRecord(target.receiver.ifEmpty { orphan.receiver }, target.sender,
                        orphan.body, target.timestamp, target.sub)
                    val owner = db.rawQuery("SELECT capture_key FROM upload_fingerprints WHERE fingerprint=?",
                        arrayOf(targetMessage.fingerprint())).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (confirmedNumber(targetMessage.receiver) && owner != null && owner != target.key && owner != orphan.key) {
                        db.execSQL("UPDATE outbox SET blocked='notification_collision' WHERE key=?", arrayOf(orphan.key))
                        markNotificationUncertain(db, listOf(orphan.key))
                        continue
                    }
                    if (target.sub < 0 && orphan.sub >= 0) inheritSim(db, target.key, message, -1)
                    db.execSQL("UPDATE notification_receipts SET capture_key=? WHERE capture_key=?", arrayOf(target.key, orphan.key))
                    db.execSQL("UPDATE upload_fingerprints SET capture_key=? WHERE capture_key=?", arrayOf(target.key, orphan.key))
                    // Without a receipt the old capture is evidence only, never a fresh candidate.
                    db.delete("outbox", "key=?", arrayOf(orphan.key))
                    merged++
                } else if (notificationCandidates(db, message, notifications = false, requireEvidence = false).isNotEmpty()) {
                    markNotificationUncertain(db, listOf(orphan.key) + reverse.orEmpty().map { it.key })
                }
            }
            recordCaptureEvent(CaptureSource.NOTIFICATION, "paired_late", merged)
            db.setTransactionSuccessful()
            if (merged > 0) changes.value++
            return merged
        } finally { db.endTransaction() }
    }

    private fun hasNotificationReceipt(db: SQLiteDatabase, key: String): Boolean =
        db.rawQuery("SELECT 1 FROM notification_receipts WHERE capture_key=?", arrayOf(key)).use { it.moveToFirst() }

    private fun markNotificationUncertain(db: SQLiteDatabase, keys: List<String>) {
        for (key in keys) {
            if (db.update("notification_receipts", ContentValues().apply { put("uncertain", 1) },
                    "capture_key=? AND uncertain=0", arrayOf(key)) > 0) changes.value++
        }
    }

    private fun notificationCandidates(
        db: SQLiteDatabase,
        m: SmsRecord,
        notifications: Boolean,
        free: String = "1=1",
        requireEvidence: Boolean = true,
    ): List<CaptureCandidate> {
        val side = if (notifications)
            "EXISTS (SELECT 1 FROM notification_receipts n WHERE n.capture_key=captures.key)"
        else "source IN ('broadcast','provider')"
        return db.rawQuery(
            "SELECT key,sender,sub,receiver,timestamp FROM captures WHERE $side AND $free AND body=? AND timestamp BETWEEN ? AND ?",
            arrayOf(m.body, (m.timestamp - NOTIFICATION_MATCH_WINDOW).toString(),
                (m.timestamp + NOTIFICATION_MATCH_WINDOW).toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val candidate = CaptureCandidate(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getLong(4))
                    val senderMatches = if (notifications) notificationSenderMatches(candidate.sender, m.sender, m.body)
                        else notificationSenderMatches(m.sender, candidate.sender, m.body)
                    if (!requireEvidence || (senderMatches &&
                        compatibleSims(m.subscriptionId, m.receiver, candidate.sub, candidate.receiver))) add(candidate)
                }
            }
        }
    }

    // Keys of rows that cannot upload until a receiving number is known.
    fun unresolvedKeys(): List<String> {
        val resolver = receiverResolver(allowFallback = false)
        return readableDatabase
            .rawQuery("SELECT key,receiver,sub,blocked FROM outbox WHERE blocked<>'$AUTO_IGNORED'", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val blocked = c.getString(3)
                        if (
                            blocked == "sim_not_confirmed" ||
                                (blocked.isEmpty() &&
                                    !confirmedNumber(c.getString(1).ifEmpty { resolver(c.getInt(2)) }))
                        )
                            add(c.getString(0))
                    }
                }
            }
    }

    // The user states which card received these messages. Only a confirmed, mapped number
    // is accepted, so the hub registration that precedes upload covers it.
    fun assignReceiver(keys: List<String>, sim: SimMapping): Int {
        require(confirmedNumber(sim.phone)) { "接收号码无效" }
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (key in keys) {
                val updated =
                    db.update(
                        "outbox",
                        ContentValues().apply {
                            put("receiver", sim.phone)
                            put("sub", sim.sub)
                            put("slot", sim.slot)
                            put("blocked", "")
                        },
                        "key=? AND blocked<>'invalid_message'",
                        arrayOf(key),
                    )
                if (updated > 0) {
                    db.update(
                        "captures",
                        ContentValues().apply {
                            put("receiver", sim.phone)
                            put("sub", sim.sub)
                        },
                        "key=?",
                        arrayOf(key),
                    )
                    count += updated
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
        return count
    }

    // Removes rows from the upload queue only. The capture ledger keeps them so a later
    // inbox scan cannot queue the same SMS again.
    fun discard(keys: List<String>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (key in keys) count += db.delete("outbox", "key=?", arrayOf(key))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
        return count
    }

    fun retryInvalid(keys: List<String>? = null) {
        if (keys == null)
            writableDatabase.execSQL("UPDATE outbox SET blocked='' WHERE blocked='invalid_message'")
        else
            for (key in keys)
                writableDatabase.execSQL(
                    "UPDATE outbox SET blocked='' WHERE key=? AND blocked='invalid_message'",
                    arrayOf(key),
                )
        changes.value++
    }

    fun invalidKeys(): List<String> =
        readableDatabase
            .rawQuery("SELECT key FROM outbox WHERE blocked='invalid_message'", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // Require sender evidence, compatible SIM snapshots and one unique candidate.
    // An unresolved contact title must never be matched by body alone.
    private fun linkNotificationCapture(
        db: SQLiteDatabase,
        m: SmsRecord,
        slot: Int,
        providerId: Long?,
        broadcastKey: String,
    ): Boolean {
        val free = if (providerId == null) "broadcast_key IS NULL" else "provider_id IS NULL"
        val candidates = notificationCandidates(db, m, notifications = true, free = free)
        val candidate = candidates.singleOrNull()
        // The incoming row is not in captures yet. Any existing compatible counterpart
        // makes this a competing observation, not a license to pick the newest one.
        val competing = candidate?.let {
            notificationCandidates(db, SmsRecord(it.receiver, it.sender, m.body, it.timestamp, it.sub), notifications = false)
        }.orEmpty()
        if (candidate == null || competing.isNotEmpty()) {
            markNotificationUncertain(db, notificationCandidates(db, m, notifications = true,
                free = free, requireEvidence = false).map { it.key })
            return false
        }
        val key = candidate.key
        db.update(
            "captures",
            ContentValues().apply {
                if (providerId == null) put("broadcast_key", broadcastKey)
                else put("provider_id", providerId)
                put("sender", m.sender)
                if (candidate.sub < 0 && m.subscriptionId >= 0) put("sub", m.subscriptionId)
                if (candidate.receiver.isEmpty() && m.receiver.isNotEmpty()) put("receiver", m.receiver)
            },
            "key=?",
            arrayOf(key),
        )
        db.update(
            "outbox",
            ContentValues().apply {
                put("sender", m.sender)
                if (candidate.sub < 0 && m.subscriptionId >= 0) put("sub", m.subscriptionId)
                if (slot >= 0) put("slot", slot)
                if (candidate.receiver.isEmpty() && m.receiver.isNotEmpty()) put("receiver", m.receiver)
            },
            "key=?",
            arrayOf(key),
        )
        // A later broadcast/provider copy proves this row is a real SMS with a usable number;
        // do not leave it hidden by the user's auto-ignore policy.
        db.execSQL(
            "UPDATE outbox SET blocked='' WHERE key=? AND blocked='$AUTO_IGNORED'",
            arrayOf(key),
        )
        recordCaptureEvent(CaptureSource.NOTIFICATION, if (providerId == null) "paired_broadcast" else "paired_inbox")
        changes.value++
        return true
    }

    // Direct callers without a structured identity still get exact replay protection,
    // never the former same-source +/-120s content collapse.
    fun queueNotification(m: SmsRecord): Boolean =
        queueNotifications(listOf(NotificationCaptureInput(m))) > 0

    internal fun queueNotifications(messages: List<NotificationCaptureInput>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val added = mutableListOf<String>()
            for (input in messages) {
                val m = input.message
                val key = "notification:" + input.identity.ifEmpty {
                    sha256("${m.receiver}\u0000${m.subscriptionId}\u0000${m.sender}\u0000${m.timestamp}\u0000${m.body}")
                }
                val seen = db.rawQuery("SELECT 1 FROM notification_receipts WHERE identity=?", arrayOf(key)).use { it.moveToFirst() }
                if (seen) {
                    if (input.uncertain)
                        db.execSQL("UPDATE notification_receipts SET uncertain=1 WHERE identity=?", arrayOf(key))
                    continue
                }
                val ambiguous = db.rawQuery(
                    "SELECT key,sender,sub,receiver FROM captures WHERE (source='notification' OR EXISTS (SELECT 1 FROM notification_receipts n WHERE n.capture_key=captures.key)) AND body=? AND timestamp BETWEEN ? AND ?",
                    arrayOf(m.body, (m.timestamp - NOTIFICATION_MATCH_WINDOW).toString(), (m.timestamp + NOTIFICATION_MATCH_WINDOW).toString()),
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val noSimEvidence = m.subscriptionId < 0 && c.getInt(2) < 0 &&
                                (m.receiver.isEmpty() || c.getString(3).isEmpty() || m.receiver == c.getString(3))
                            if (sameSender(m.sender, c.getString(1)) && (noSimEvidence ||
                                    compatibleSims(m.subscriptionId, m.receiver, c.getInt(2), c.getString(3)))) add(c.getString(0))
                        }
                    }
                }
                markNotificationUncertain(db, ambiguous)
                db.execSQL("INSERT INTO notification_receipts (identity,capture_key,uncertain) VALUES (?,?,?)",
                    arrayOf<Any>(key, key, if (input.uncertain || ambiguous.isNotEmpty()) 1 else 0))
                db.insertOrThrow(
                    "captures",
                    null,
                    ContentValues().apply {
                        put("key", key)
                        put("receiver", m.receiver)
                        put("sender", m.sender)
                        put("body", m.body)
                        put("timestamp", m.timestamp)
                        put("sub", m.subscriptionId)
                        put("source", "notification")
                    },
                )
                db.insertOrThrow(
                    "outbox",
                    null,
                    ContentValues().apply {
                        put("key", key)
                        put("receiver", m.receiver)
                        put("sender", m.sender)
                        put("body", m.body)
                        put("timestamp", m.timestamp)
                        put("sub", m.subscriptionId)
                        put("slot", -1)
                        put("historical", 0)
                    },
                )
                added.add(key)
            }
            // All lines must exist before matching: two identical bodies are two
            // competitors, even when the SMS counterpart arrived before this snapshot.
            if (added.isNotEmpty()) reconcileNotificationCaptures()
            val queued = added.count { key ->
                db.rawQuery("SELECT 1 FROM outbox WHERE key=?", arrayOf(key)).use { it.moveToFirst() }
            }
            db.setTransactionSuccessful()
            if (added.isNotEmpty()) changes.value++
            return queued
        } finally {
            db.endTransaction()
        }
    }

    fun sims(): List<SimMapping> =
        readableDatabase
            .rawQuery("SELECT sub,slot,phone,label FROM sims ORDER BY slot", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        SimMapping(c.getInt(0), c.getInt(1), c.getString(2), c.getString(3))
                    )
                }
            }

    fun saveSim(s: SimMapping) {
        writableDatabase.insertWithOnConflict(
            "sims",
            null,
            ContentValues().apply {
                put("sub", s.sub)
                put("slot", s.slot)
                put("phone", s.phone)
                put("label", s.label)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.value++
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateSimMappings(active: List<SimMapping>, automatic: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // An unconfirmed number means "not mapped" in both modes: automatic recognition
            // lost it, or the user left that card blank on purpose.
            for (sim in active) {
                if (confirmedNumber(sim.phone)) saveSim(sim)
                else db.delete("sims", "sub=?", arrayOf(sim.sub.toString()))
            }
            // Retry only SIM failures; malformed messages must not be retried indefinitely.
            db.execSQL("UPDATE outbox SET blocked='' WHERE blocked='sim_not_confirmed'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        releaseAutoIgnoredIfResolved()
        changes.value++
    }

    @Suppress("UNUSED_PARAMETER")
    fun receiver(sub: Int, slot: Int): String {
        // Slot is not an identity. A replaced SIM in the same tray must not inherit
        // the previous number; only an exact subscription match, or an explicit
        // single-SIM confirmation, may fill the receiver.
        return receiverResolver()(sub)
    }

    fun receiverResolver(allowFallback: Boolean = true): (Int) -> String {
        val mappings =
            if (config.automaticSim && !config.simRecognitionHealthy) emptyList() else sims()
        val fallback = if (allowFallback) fallbackSubscription() else -1
        return { sub ->
            resolvedReceiver(
                if (sub < 0) mappings.filter { it.sub == fallback } else mappings,
                sub,
                fallback,
            )
        }
    }

    private fun fallbackSubscription(): Int {
        val expected = config.singleSimSubscription
        if (expected < 0) return -1
        if (
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_PHONE_STATE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            return -1
        return try {
            val active =
                context
                    .getSystemService(android.telephony.SubscriptionManager::class.java)
                    .activeSubscriptionInfoList
            if (active?.singleOrNull()?.subscriptionId == expected) expected
            else {
                config.singleSimSubscription = -1
                -1
            }
        } catch (_: SecurityException) {
            -1
        }
    }

    // Maintenance may verify the legacy single-SIM fallback. Upload itself never calls SIM
    // services.
    fun resolvePendingFallback() {
        val generation = config.captureGeneration
        val unresolved =
            "receiver='' AND sub<0 AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.key=outbox.key AND c.source='broadcast')"
        if (
            !readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE $unresolved LIMIT 1", null).use {
                it.moveToFirst()
            }
        )
            return
        val fallback = fallbackSubscription()
        if (fallback < 0) return
        synchronized(identityLock) {
            if (
                config.captureGeneration != generation ||
                    config.singleSimSubscription != fallback ||
                    (config.automaticSim && !config.simRecognitionHealthy)
            )
                return@synchronized
            val phone = sims().find { it.sub == fallback }?.phone.orEmpty()
            if (!confirmedNumber(phone)) return@synchronized
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "UPDATE captures SET receiver=? WHERE receiver='' AND key IN (SELECT key FROM outbox WHERE $unresolved)",
                    arrayOf(phone),
                )
                db.execSQL("UPDATE outbox SET receiver=? WHERE $unresolved", arrayOf(phone))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            releaseAutoIgnoredIfResolved()
            changes.value++
        }
    }

    fun pending(
        allowFallback: Boolean = true,
        historical: Boolean? = null,
        limit: Int = 100,
        now: Long = System.currentTimeMillis(),
    ): List<Pair<String, SmsRecord>> {
        val mappings =
            if (config.automaticSim && !config.simRecognitionHealthy) emptyList() else sims()
        val fallback = if (allowFallback) fallbackSubscription() else -1
        val subs = mappings.filter { it.sub >= 0 }.map { it.sub.toString() }
        val eligible =
            "receiver<>''" +
                (if (subs.isEmpty()) "" else " OR sub IN (${subs.joinToString(",") { "?" }})") +
                (if (fallback >= 0)
                    " OR (sub<0 AND NOT EXISTS (SELECT 1 FROM captures c WHERE c.key=outbox.key AND c.source='broadcast'))"
                else "")
        // Filter unknown mappings before limiting, so they cannot starve a known SIM.
        // Each cursor is bounded and ordered by the ready index; no N+1 SIM queries.
        val result = mutableListOf<Pair<String, SmsRecord>>()
        var lastTime = Long.MIN_VALUE
        var lastKey = ""
        val lane =
            if (historical == null) ""
            else if (historical) " AND historical<>0" else " AND historical=0"
        // Sent rows stay queued until the hub announces it can store their direction.
        val directions = if (hubSupportsOutgoing()) "" else " AND direction='in'"
        val invalid = mutableListOf<String>()
        while (result.size < limit) {
            var scanned = 0
            val page = mutableListOf<Pair<String, SmsRecord>>()
            readableDatabase
                .rawQuery(
                    "SELECT key,receiver,sender,body,timestamp,sub,slot,historical,direction FROM outbox WHERE blocked='' AND ($eligible)$lane$directions AND NOT ($HELD_NOTIFICATION) AND (timestamp,key)>(?,?) ORDER BY timestamp,key LIMIT 100",
                    (subs + listOf(now.toString(), lastTime.toString(), lastKey))
                        .toTypedArray(),
                )
                .use { c ->
                    while (c.moveToNext()) {
                        scanned++
                        lastTime = c.getLong(4)
                        lastKey = c.getString(0)
                        val phone =
                            c.getString(1).ifEmpty {
                                val sub = c.getInt(5)
                                resolvedReceiver(
                                    if (sub < 0) mappings.filter { it.sub == fallback }
                                    else mappings,
                                    sub,
                                    fallback,
                                )
                            }
                        // A mapping that is not a confirmed number is a SIM problem, not a bad
                        // message: leave the row waiting for a number instead of blocking it.
                        if (confirmedNumber(phone)) {
                            val m =
                                SmsRecord(
                                    phone,
                                    c.getString(2),
                                    c.getString(3),
                                    c.getLong(4),
                                    c.getInt(5),
                                    c.getInt(7) != 0,
                                    outgoing = c.getString(8) == "out",
                                )
                            val rawJsonSize = m.json().toString().toByteArray().size
                            if (
                                m.body.isEmpty() ||
                                    m.sender.isEmpty() ||
                                    m.body.toByteArray().size > 64000 ||
                                    m.sender.toByteArray().size > 100 ||
                                    m.timestamp < 0 ||
                                    m.timestamp > System.currentTimeMillis() + 86400000 ||
                                    m.body.contains('\u0000') ||
                                    m.sender.contains('\u0000') ||
                                    rawJsonSize > 900000
                            )
                                invalid.add(c.getString(0))
                            else page.add(c.getString(0) to m)
                        }
                    }
                }
            result.addAll(prepareUpload(page).take((limit - result.size).coerceAtLeast(0)))
            if (scanned < 100) break
        }
        // Never write while the read cursor is open.
        for (key in invalid) block(key, "invalid_message")
        return result
    }

    // A notification occurrence is not represented by the server fingerprint. Reserve
    // the actual wire fingerprint before sending, and retain conflicting observations.
    // Reservations survive ACK, restart, retries and sender/receiver enrichment.
    internal fun prepareUpload(batch: List<Pair<String, SmsRecord>>, fromSync: Boolean = false): List<Pair<String, SmsRecord>> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = batch.filter { (key, message) ->
                val ready = db.rawQuery("SELECT blocked FROM outbox WHERE key=?", arrayOf(key)).use {
                    it.moveToFirst() && it.getString(0) != "notification_collision" &&
                        it.getString(0) != AUTO_IGNORED &&
                        (fromSync || it.getString(0).isEmpty())
                }
                if (!ready) return@filter false
                val fingerprint = message.fingerprint()
                val owner = db.rawQuery("SELECT capture_key FROM upload_fingerprints WHERE fingerprint=?", arrayOf(fingerprint)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (owner == null) {
                    db.execSQL("INSERT INTO upload_fingerprints VALUES (?,?)", arrayOf(fingerprint, key))
                    true
                } else if (owner == key || (!hasNotificationReceipt(db, key) && !hasNotificationReceipt(db, owner))) {
                    true
                } else {
                    db.execSQL("UPDATE outbox SET blocked='notification_collision' WHERE key=?", arrayOf(key))
                    markNotificationUncertain(db, listOf(key, owner))
                    changes.value++
                    false
                }
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    // Delay until the next held notification row is released, or null when none is held.
    fun nextNotificationRelease(now: Long = System.currentTimeMillis()): Long? =
        readableDatabase
            .rawQuery(
                "SELECT MIN($NOTIFICATION_RELEASE_AT) FROM outbox WHERE blocked='' AND $HELD_NOTIFICATION",
                arrayOf(now.toString()),
            )
            .use { c ->
                if (!c.moveToFirst() || c.isNull(0)) null
                else (c.getLong(0) - now).coerceAtLeast(1000)
            }

    fun uploadCandidates(batch: Int): List<Pair<String, SmsRecord>> {
        val historyFirst = batch % 5 == 4
        val primary = pending(allowFallback = false, historical = historyFirst)
        val secondary =
            pending(
                allowFallback = false,
                historical = !historyFirst,
                limit = maxOf(20, 100 - primary.size),
            )
        return primary.take(100 - secondary.size) + secondary
    }

    fun pendingCounts(now: Long = System.currentTimeMillis()): PendingCounts {
        val resolver = receiverResolver(allowFallback = false)
        val hubOutgoing = hubSupportsOutgoing()
        return readableDatabase
            .rawQuery(
                "SELECT receiver,sub,blocked,COUNT(*),held,direction FROM (SELECT receiver,sub,blocked,($HELD_NOTIFICATION) AS held,direction FROM outbox) GROUP BY receiver,sub,blocked,held,direction",
                arrayOf(now.toString()),
            )
            .use { rows ->
                var ready = 0
                var needsSim = 0
                var invalid = 0
                var collisions = 0
                var held = 0
                var awaitingHub = 0
                var ignored = 0
                while (rows.moveToNext()) {
                    val count = rows.getInt(3)
                    when {
                        rows.getString(5) == "out" && !hubOutgoing && rows.getString(2).isEmpty() ->
                            awaitingHub += count
                        rows.getString(2) == "notification_collision" -> collisions += count
                        rows.getString(2) == AUTO_IGNORED -> ignored += count
                        rows.getString(2) == "invalid_message" -> invalid += count
                        rows.getInt(4) != 0 -> held += count
                        rows.getString(2).isNotEmpty() ||
                            !confirmedNumber(rows.getString(0).ifEmpty { resolver(rows.getInt(1)) }) ->
                            needsSim += count
                        else -> ready += count
                    }
                }
                PendingCounts(ready, needsSim, invalid, collisions, held, awaitingHub, ignored)
            }
    }

    fun blockedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE blocked<>''", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    // The user opted out of being prompted about rows that cannot name a receiving line. This
    // is a soft state, not a deletion: receipts/captures remain, so a later authoritative copy
    // or a saved SIM can restore the row. With no usable SIM configuration we only ignore
    // notification observations; real broadcast/provider SMS stay visible instead of being
    // silently hidden because setup is incomplete.
    fun autoIgnoreUnassigned(): Int {
        if (!config.autoIgnoreUnassigned) return 0
        val resolver = receiverResolver()
        val mappingsUsable =
            sims().any { confirmedNumber(it.phone) } &&
                (!config.automaticSim || config.simRecognitionHealthy)
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            val rows =
                db.rawQuery(
                        "SELECT o.key,o.receiver,o.sub,COALESCE(c.source,'') FROM outbox o LEFT JOIN captures c ON c.key=o.key WHERE o.blocked=''",
                        null,
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext())
                                add(
                                    AutoIgnoreRow(
                                        c.getString(0),
                                        c.getString(1),
                                        c.getInt(2),
                                        c.getString(3),
                                    )
                                )
                        }
                    }
            for (row in rows) {
                val phone =
                    if (confirmedNumber(row.receiver)) row.receiver else resolver(row.sub)
                if (confirmedNumber(phone)) continue
                if (row.source != "notification" && !mappingsUsable) continue
                count +=
                    db.update(
                        "outbox",
                        ContentValues().apply { put("blocked", AUTO_IGNORED) },
                        "key=? AND blocked=''",
                        arrayOf(row.key),
                    )
            }
            if (count > 0) recordCaptureEvent(CaptureSource.QUEUE, "auto_ignored", count)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (count > 0) changes.value++
        return count
    }

    fun autoIgnoredKeys(): List<String> =
        readableDatabase
            .rawQuery("SELECT key FROM outbox WHERE blocked='$AUTO_IGNORED' ORDER BY timestamp DESC,key DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun restoreAutoIgnored(): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            count =
                db.update(
                    "outbox",
                    ContentValues().apply { put("blocked", "") },
                    "blocked=?",
                    arrayOf(AUTO_IGNORED),
                )
            if (count > 0) recordCaptureEvent(CaptureSource.QUEUE, "restored", count)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (count > 0) changes.value++
        return count
    }

    fun discardAutoIgnored(): Int {
        val keys = autoIgnoredKeys()
        val count = discard(keys)
        if (count > 0) {
            recordCaptureEvent(CaptureSource.QUEUE, "discarded", count)
            changes.value++
        }
        return count
    }

    // Saving a SIM can make previously-unresolved rows uploadable again. Only clear the soft
    // state; invalid/collision rows keep their more specific reason.
    internal fun releaseAutoIgnoredIfResolved(): Int {
        val resolver = receiverResolver()
        val rows =
            readableDatabase
                .rawQuery(
                    "SELECT key,receiver,sub FROM outbox WHERE blocked='$AUTO_IGNORED'",
                    null,
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext())
                            add(Triple(c.getString(0), c.getString(1), c.getInt(2)))
                    }
                }
        if (rows.isEmpty()) return 0
        var count = 0
        val db = writableDatabase
        for ((key, receiver, sub) in rows) {
            val phone = if (confirmedNumber(receiver)) receiver else resolver(sub)
            if (!confirmedNumber(phone)) continue
            count +=
                db.update(
                    "outbox",
                    ContentValues().apply { put("blocked", "") },
                    "key=? AND blocked=?",
                    arrayOf(key, AUTO_IGNORED),
                )
        }
        if (count > 0) {
            recordCaptureEvent(CaptureSource.QUEUE, "restored", count)
            changes.value++
        }
        return count
    }

    fun retryBlocked() {
        writableDatabase.execSQL(
            "UPDATE outbox SET blocked='' WHERE blocked NOT IN ('notification_collision','$AUTO_IGNORED')"
        )
        changes.value++
    }

    fun block(key: String, reason: String) {
        writableDatabase.execSQL(
            "UPDATE outbox SET blocked=? WHERE key=? AND blocked=''",
            arrayOf(reason, key),
        )
        changes.value++
    }

    fun simRejectedReceivers(): Set<String> =
        readableDatabase
            .rawQuery("SELECT DISTINCT receiver FROM outbox WHERE blocked='sim_not_confirmed'", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun releaseSimRejections(receivers: Set<String>) {
        for (receiver in receivers)
            writableDatabase.execSQL(
                "UPDATE outbox SET blocked='' WHERE blocked='sim_not_confirmed' AND receiver=?",
                arrayOf(receiver),
            )
        changes.value++
    }

    // Once the hub has accepted the authoritative broadcast/provider copy, a still-unpaired
    // notification observation with the same body and a very close time is redundant. It is
    // safe to retire it now even when the notification's contact-name title never produced
    // sender evidence for the normal pairing path. The receipt and fingerprint ownership move
    // to the authoritative capture so notification replays stay idempotent and later provider
    // rows cannot be swallowed by a stale notification capture with no outbox row.
    private fun absorbAuthoritativeDuplicate(
        db: SQLiteDatabase,
        key: String,
        receiver: String,
        body: String,
        timestamp: Long,
        outgoing: Boolean,
    ): Int {
        if (outgoing || !confirmedNumber(receiver)) return 0
        val source =
            db.rawQuery(
                    "SELECT source FROM captures WHERE key=? AND (broadcast_key IS NOT NULL OR provider_id IS NOT NULL)",
                    arrayOf(key),
                )
                .use { if (it.moveToFirst()) it.getString(0) else null } ?: return 0
        if (source != "broadcast" && source != "provider") return 0
        if (hasNotificationReceipt(db, key)) return 0
        val candidates =
            db.rawQuery(
                    "SELECT o.key,c.key FROM outbox o JOIN captures c ON c.key=o.key JOIN notification_receipts n ON n.capture_key=c.key WHERE c.source='notification' AND c.broadcast_key IS NULL AND c.provider_id IS NULL AND o.receiver='' AND o.sub<0 AND o.blocked<>'notification_collision' AND o.body=? AND ABS(o.timestamp-CAST(? AS INTEGER))<=? LIMIT 2",
                    arrayOf(body, timestamp.toString(), NOTIFICATION_ABSORB_WINDOW.toString()),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(c.getString(0) to c.getString(1))
                    }
                }
        if (candidates.size != 1) return 0
        val (orphanOutbox, orphanCapture) = candidates.single()
        if (orphanOutbox == key) return 0
        db.execSQL(
            "UPDATE notification_receipts SET capture_key=? WHERE capture_key=?",
            arrayOf(key, orphanCapture),
        )
        db.execSQL(
            "UPDATE upload_fingerprints SET capture_key=? WHERE capture_key=?",
            arrayOf(key, orphanCapture),
        )
        db.delete("outbox", "key=?", arrayOf(orphanOutbox))
        return 1
    }

    // A sync page is durable proof that the hub already stored a message from this device, even
    // when the original HTTP ACK was lost. Use the cached inbox row to find the local
    // authoritative capture, then retire any redundant notification observation.
    private fun absorbNotificationCopiesFromInbox(db: SQLiteDatabase, self: String): Int {
        if (self.isEmpty()) return 0
        val orphans =
            db.rawQuery(
                    "SELECT o.key,o.body,o.timestamp FROM outbox o JOIN captures c ON c.key=o.key JOIN notification_receipts n ON n.capture_key=c.key WHERE c.source='notification' AND c.broadcast_key IS NULL AND c.provider_id IS NULL AND o.receiver='' AND o.sub<0 AND o.blocked<>'notification_collision' ORDER BY o.timestamp DESC LIMIT 20",
                    null,
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext())
                            add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
                    }
                }
        var absorbed = 0
        for ((_, body, timestamp) in orphans) {
            val matches =
                db.rawQuery(
                        "SELECT receiver,sender,body,timestamp FROM inbox WHERE device=? AND direction='in' AND body=? AND ABS(timestamp-CAST(? AS INTEGER))<=?",
                        arrayOf(self, body, timestamp.toString(), NOTIFICATION_ABSORB_WINDOW.toString()),
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext())
                                add(
                                    InboxMatch(
                                        c.getString(0),
                                        c.getString(1),
                                        c.getString(2),
                                        c.getLong(3),
                                    )
                                )
                        }
                    }
            if (matches.size != 1) continue
            val match = matches.single()
            val captures =
                db.rawQuery(
                        "SELECT key FROM captures WHERE source IN ('broadcast','provider') AND receiver=? AND sender=? AND body=? AND timestamp=?",
                        arrayOf(match.receiver, match.sender, match.body, match.timestamp.toString()),
                    )
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            if (captures.size != 1) continue
            absorbed +=
                absorbAuthoritativeDuplicate(
                    db,
                    captures.single(),
                    match.receiver,
                    match.body,
                    match.timestamp,
                    outgoing = false,
                )
        }
        return absorbed
    }

    fun ack(keys: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val resolver = receiverResolver(allowFallback = false)
            var notificationOnly = 0
            var absorbed = 0
            for (key in keys) {
                // Enrichment may race an HTTP request. Recheck the current wire identity
                // before ACK can remove a newly colliding observation.
                val current = db.rawQuery("SELECT receiver,sender,body,timestamp,sub,direction FROM outbox WHERE key=?", arrayOf(key)).use { c ->
                    if (!c.moveToFirst()) null else SmsRecord(c.getString(0).ifEmpty { resolver(c.getInt(4)) },
                        c.getString(1), c.getString(2), c.getLong(3), c.getInt(4), outgoing = c.getString(5) == "out")
                }
                val source =
                    db.rawQuery("SELECT source FROM captures WHERE key=?", arrayOf(key)).use {
                        if (it.moveToFirst()) it.getString(0) else ""
                    }
                if (current != null && confirmedNumber(current.receiver) && prepareUpload(listOf(key to current), fromSync = true).isEmpty()) continue
                val unpaired = db.rawQuery("SELECT 1 FROM outbox WHERE key=? AND $UNPAIRED_NOTIFICATION", arrayOf(key)).use { it.moveToFirst() }
                if (db.delete("outbox", "key=? AND blocked<>'notification_collision'", arrayOf(key)) > 0) {
                    if (unpaired) notificationOnly++
                    if (current != null && (source == "broadcast" || source == "provider"))
                        absorbed += absorbAuthoritativeDuplicate(
                            db,
                            key,
                            current.receiver,
                            current.body,
                            current.timestamp,
                            current.outgoing,
                        )
                }
            }
            recordCaptureEvent(CaptureSource.NOTIFICATION, "uploaded_unpaired", notificationOnly)
            if (absorbed > 0)
                recordCaptureEvent(CaptureSource.NOTIFICATION, "absorbed_duplicate", absorbed)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
    }

    fun cursor(): Long = meta("cursor")

    fun epoch(): Long = meta("epoch")

    fun epochSeen(): Boolean = meta("epoch_seen") != 0L

    private fun meta(key: String): Long =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
            if (!it.moveToFirst()) 0L else it.getLong(0)
        }

    fun inboxWatermark(): Pair<Long, Long> = meta("inbox_date") to meta("inbox_id")

    fun providerWatermark(): Long = meta("provider_id")

    fun recoveryWatermark(): Long = meta("recovery_provider_id")

    fun advanceRecoveryProvider(id: Long) {
        // v6's extensible meta table permits additive state without a schema downgrade barrier.
        writableDatabase.execSQL("INSERT OR IGNORE INTO meta VALUES ('recovery_provider_id',0)")
        writableDatabase.execSQL(
            "UPDATE meta SET value=MAX(value,?) WHERE key='recovery_provider_id'",
            arrayOf(id),
        )
    }

    fun historyBoundary(): Long = meta("history_boundary")

    fun initializeHistoryBoundary(now: Long) {
        writableDatabase.execSQL(
            "UPDATE meta SET value=? WHERE key='history_boundary' AND value=0",
            arrayOf(now),
        )
    }

    // History visits every provider row up to its watermark (incomplete ones are remembered in
    // the shared retry list), so new-SMS recovery never needs to read those rows again: the two
    // forward cursors converge instead of scanning the same inbox twice. Recovery's own cursor
    // never moves history's, because recovery skips rows dated before its consent.
    fun advanceProvider(id: Long) {
        writableDatabase.execSQL(
            "UPDATE meta SET value=MAX(value,?) WHERE key='provider_id'",
            arrayOf(id),
        )
        advanceRecoveryProvider(id)
    }

    fun advanceInboxWatermark(date: Long, id: Long) {
        val current = inboxWatermark()
        if (date < current.first || (date == current.first && id <= current.second)) return
        val db = writableDatabase
        db.execSQL("UPDATE meta SET value=? WHERE key='inbox_date'", arrayOf(date))
        db.execSQL("UPDATE meta SET value=? WHERE key='inbox_id'", arrayOf(id))
    }

    // Pairing time is retained for compatibility; it is no longer a scan filter.
    fun captureSince(): Long {
        return meta("capture_since")
    }

    fun initializeCaptureSince(connectedAt: Long) {
        require(connectedAt > 0)
        writableDatabase.execSQL(
            "UPDATE meta SET value=? WHERE key='capture_since' AND value=0",
            arrayOf(connectedAt),
        )
    }

    fun initialComplete(): Boolean = meta("initial_complete") != 0L

    fun initializeSyncHead(head: Long) {
        writableDatabase.execSQL(
            "UPDATE meta SET value=? WHERE key='initial_head' AND value=-1",
            arrayOf(head),
        )
    }

    fun pendingNotification(): Long = meta("notify_pending")

    fun acknowledgeNotification(count: Long) {
        writableDatabase.execSQL(
            "UPDATE meta SET value=MAX(0,value-?) WHERE key='notify_pending'",
            arrayOf(count),
        )
    }

    // An upload can commit at the hub before the HTTP response reaches the phone. The normal
    // retry is idempotent, but a sync page may be the first durable evidence the client sees.
    // Only reconcile messages written by this device; an identical message from another device
    // must not delete this phone's local capture.
    private fun reconcileSyncedOutbox(
        db: SQLiteDatabase,
        message: JSONObject,
        self: String,
        resolveReceiver: ((Int) -> String)?,
    ) {
        if (self.isEmpty() || message.getString("device_id") != self) return
        val receiver = message.getString("receiver")
        val sender = message.getString("sender")
        val body = message.getString("body")
        val timestamp = message.getLong("timestamp")
        val outgoing = message.optString("direction") == "out"
        val keys =
            db.rawQuery(
                    "SELECT key,receiver,sub FROM outbox WHERE sender=? AND body=? AND timestamp=? AND direction=?",
                    arrayOf(sender, body, timestamp.toString(), if (outgoing) "out" else "in"),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val localReceiver =
                                c.getString(1).ifEmpty {
                                    resolveReceiver?.invoke(c.getInt(2)).orEmpty()
                                }
                            if (localReceiver == receiver) add(c.getString(0))
                        }
                    }
                }
        val wireMessage = SmsRecord(receiver, sender, body, timestamp, -1, outgoing = outgoing)
        // A sync copy proves only the fingerprint owner's delivery, not another
        // notification occurrence with the same wire fields.
        val allowed = prepareUpload(keys.map { it to wireMessage }, fromSync = true).map { it.first }.toSet()
        for (key in keys) if (key in allowed) db.delete("outbox", "key=?", arrayOf(key))
    }

    fun applySync(
        messages: JSONArray,
        cursor: Long,
        self: String = "",
        pairedAt: Long = 0,
        pageEpoch: Long? = null,
        preview: Boolean = false,
    ): List<CachedMessage> = synchronized(identityLock) {
        val db = writableDatabase
        val added = mutableListOf<CachedMessage>()
        val resolveReceiver = self.takeIf { it.isNotEmpty() }?.let {
            receiverResolver(allowFallback = false)
        }
        db.beginTransaction()
        try {
            check(pageEpoch == null || !epochReset(epochSeen(), epoch(), pageEpoch)) {
                "Sync page epoch changed before commit"
            }
            if (pageEpoch != null) {
                db.execSQL("UPDATE meta SET value=? WHERE key='epoch'", arrayOf(pageEpoch))
                db.execSQL("UPDATE meta SET value=1 WHERE key='epoch_seen'")
            }
            // Read the epoch through this transaction's connection; a separate read connection
            // can still observe the pre-page snapshot in WAL mode.
            val intentEpoch =
                db.rawQuery("SELECT value FROM meta WHERE key='epoch'", null).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
            var fresh = 0
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val outgoing = m.optString("direction") == "out"
                if (!preview) reconcileSyncedOutbox(db, m, self, resolveReceiver)
                val cv =
                    ContentValues().apply {
                        put("id", m.getLong("id"))
                        put("device", m.getString("device_id"))
                        put("receiver", m.getString("receiver"))
                        put("sender", m.getString("sender"))
                        put("body", m.getString("body"))
                        put("timestamp", m.getLong("timestamp"))
                        put("contact", m.optString("contact"))
                        put("historical", if (m.optBoolean("historical")) 1 else 0)
                        put("direction", if (outgoing) "out" else "in")
                    }
                val inserted =
                    db.insertWithOnConflict("inbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                val analysis = m.optJSONObject("analysis")?.let { MessageAnalysis.from(it) }
                    ?: MessageAnalysis(otp = if (outgoing) "" else extractOtp(m.getString("body")).orEmpty())
                saveAnalysis(db, m.getLong("id"), analysis)
                db.execSQL("INSERT OR REPLACE INTO local_conversation_heads SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM local_conversation_heads WHERE sender=? AND (timestamp>? OR (timestamp=? AND message_id>?)))",
                    arrayOf(m.getString("sender"), m.getLong("id"), m.getLong("timestamp"), m.getString("sender"), m.getLong("timestamp"), m.getLong("timestamp"), m.getLong("id")))
                if (preview) {
                    if (inserted != -1L) db.execSQL("INSERT OR IGNORE INTO preview_messages VALUES (?)", arrayOf(m.getLong("id")))
                    continue
                }
                val wasPreview = db.delete("preview_messages", "id=?", arrayOf(m.getLong("id").toString())) > 0
                if (inserted == -1L && !wasPreview) continue
                added.add(
                    CachedMessage(
                        m.getLong("id"),
                        m.getString("device_id"),
                        m.getString("receiver"),
                        m.getString("sender"),
                        m.getString("body"),
                        m.getLong("timestamp"),
                        m.optString("contact"),
                        m.optBoolean("historical"),
                        analysis = analysis,
                        outgoing = outgoing,
                    )
                )
                // The user wrote it: never a notification or a verification code prompt.
                if (outgoing) continue
                if (!shouldNotify(
                        m.getString("device_id"),
                        self,
                        m.optBoolean("historical"),
                        initialComplete(),
                        m.getLong("timestamp"),
                        pairedAt,
                    )
                )
                    continue
                // A verification code occupies exactly one channel: a durable intent when it
                // can be shown while valid, otherwise the ordinary summary. The intent is
                // committed with the page, so a process death still shows the code later.
                val otp = analysis.otp.ifEmpty {
                    if (analysis.revision == 0L) extractOtp(m.getString("body")).orEmpty() else ""
                }
                val expiresAt = otpNotificationExpiry(m.getLong("timestamp"), analysis.expiresAt)
                if (
                    otp.isNotEmpty() &&
                        expiresAt > System.currentTimeMillis() &&
                        config.notify &&
                        config.liveOtpIsland &&
                        OtpNotificationManager.otpNotificationsAvailable(context)
                )
                    db.execSQL(
                        "INSERT OR REPLACE INTO otp_intents (message_id,otp,expires_at,sender,contact,epoch,shown) VALUES (?,?,?,?,?,?,0)",
                        arrayOf(
                            m.getLong("id"),
                            otp,
                            expiresAt,
                            m.getString("sender"),
                            m.optString("contact"),
                            intentEpoch,
                        ),
                    )
                else fresh++
            }
            if (fresh > 0)
                db.execSQL(
                    "UPDATE meta SET value=value+? WHERE key='notify_pending'",
                    arrayOf(fresh),
                )
            if (preview) db.execSQL("UPDATE meta SET value=? WHERE key='preview_head'", arrayOf(cursor))
            else {
              db.execSQL("UPDATE meta SET value=MAX(value,?) WHERE key='cursor'", arrayOf(cursor))
              db.execSQL(
                "UPDATE meta SET value=1 WHERE key='initial_complete' AND EXISTS (SELECT 1 FROM meta WHERE key='initial_head' AND value>=0 AND value<=?)",
                arrayOf(cursor),
              )
            }
            if (!preview && self.isNotEmpty()) {
                val absorbed = absorbNotificationCopiesFromInbox(db, self)
                if (absorbed > 0)
                    recordCaptureEvent(CaptureSource.NOTIFICATION, "absorbed_duplicate", absorbed)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (messages.length() > 0) changes.value++
        added
    }

    fun receivers(): List<String> =
        readableDatabase
            .rawQuery("SELECT DISTINCT receiver FROM inbox ORDER BY receiver", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun contactSearchSenders(): List<String> = readableDatabase
        .rawQuery("SELECT sender FROM inbox UNION SELECT sender FROM outbox", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // Escaped literals avoid SQLite's bind-variable limit for large contact search results.
    // These are original local SMS addresses, never written to any persistent contact table.
    private fun contactSearchClause(column: String, senders: Set<String>): String =
        if (senders.isEmpty()) "" else " OR $column IN (${senders.joinToString(",") { android.database.DatabaseUtils.sqlEscapeString(it) }})"

    // Mirrors the server's effectiveGroup: a manual decision is always final;
    // a completed AI category is final only while AI is enabled; otherwise the
    // rule OTP (or pending) wins. The `enriched` CTE and message_analysis table
    // expose `manual/state/category/otp` under these names.
    private fun effectiveGroupSQL(prefix: String): String {
        val finalCategory =
            "((${prefix}manual<>0" +
                (if (aiEnabled()) " OR ${prefix}state='complete'" else "") +
                ") AND COALESCE(${prefix}category,'')<>'')"
        return "CASE WHEN $finalCategory THEN ${prefix}category " +
            "WHEN COALESCE(${prefix}otp,'')<>'' THEN 'otp' ELSE 'pending' END"
    }

    fun groupCounts(q: String = "", sim: String = "", localContactSenders: Set<String> = emptySet()): Map<String, Int> {
        val result = messageGroupLabels.keys.associateWith { 0 }.toMutableMap()
        val args = mutableListOf<String>()
        var where = "1=1"
        if (sim.isNotEmpty()) { where += " AND i.receiver=?"; args += sim }
        if (q.isNotEmpty()) {
            where += " AND (instr(lower(i.body),lower(?))>0 OR instr(lower(i.sender),lower(?))>0 OR instr(lower(i.contact),lower(?))>0${contactSearchClause("i.sender", localContactSenders)})"
            args.addAll(listOf(q, q, q))
        }
        fun add(group: String, n: Int) {
            result[group] = (result[group] ?: 0) + n
            result["all"] = result.getValue("all") + n
            if (group != "otp") result["other"] = result.getValue("other") + n
        }
        readableDatabase.rawQuery("SELECT ${effectiveGroupSQL("a.")} g,COUNT(*) FROM inbox i LEFT JOIN message_analysis a ON a.message_id=i.id WHERE $where GROUP BY g", args.toTypedArray()).use { c ->
            while (c.moveToNext()) add(c.getString(0), c.getInt(1))
        }
        val local = localMessages(q = q, sim = sim, localContactSenders = localContactSenders)
        val synced = syncedLocalIds(local)
        for (m in local.filterNot { it.id in synced }) add(if (messageOtp(m) != null) "otp" else "pending", 1)
        return result
    }

    fun messages(
        sender: String = "",
        q: String = "",
        sim: String = "",
        before: Long = Long.MAX_VALUE,
        limit: Int = 200,
        group: String = "all",
        localContactSenders: Set<String> = emptySet(),
    ): List<CachedMessage> {
        var where = "1=1"
        val args = mutableListOf<String>()
        val effectiveGroup = effectiveGroupSQL("")
        when (group) {
            "", "all" -> Unit
            "otp" -> where += " AND $effectiveGroup='otp'"
            "other" -> where += " AND $effectiveGroup<>'otp'"
            "pending" -> where += " AND $effectiveGroup='pending'"
            else -> { where += " AND $effectiveGroup=?"; args.add(group) }
        }
        if (before != Long.MAX_VALUE) {
            val timestamp =
                readableDatabase
                    .rawQuery("SELECT timestamp FROM inbox WHERE id=?", arrayOf(before.toString()))
                    .use { if (it.moveToFirst()) it.getLong(0) else return emptyList() }
            where += " AND (timestamp<? OR (timestamp=? AND id<?))"
            args.addAll(listOf(timestamp.toString(), timestamp.toString(), before.toString()))
        }
        if (sender.isNotEmpty()) {
            where += " AND sender=?"
            args.add(sender)
        }
        if (sim.isNotEmpty()) {
            where += " AND receiver=?"
            args.add(sim)
        }
        if (q.isNotEmpty()) {
            where +=
                " AND (instr(lower(body),lower(?))>0 OR instr(lower(sender),lower(?))>0 OR instr(lower(contact),lower(?))>0${contactSearchClause("sender", localContactSenders)})"
            args.addAll(listOf(q, q, q))
        }
        val enriched = "WITH enriched AS (SELECT inbox.*,COALESCE(a.otp,'') otp,COALESCE(a.expires_at,0) expires_at,COALESCE(a.category,'') category,COALESCE(a.manual,0) manual,COALESCE(a.state,'pending') state,COALESCE(a.error,'') error,COALESCE(a.revision,0) revision FROM inbox LEFT JOIN message_analysis a ON a.message_id=inbox.id)"
        val columns = "id,device,receiver,sender,body,timestamp,contact,historical,otp,expires_at,category,manual,state,error,revision,direction"
        val sql = enriched +
            if (sender.isNotEmpty())
                " SELECT $columns FROM enriched WHERE $where ORDER BY timestamp DESC,id DESC LIMIT ?"
            else if (q.isEmpty() && sim.isEmpty() && before == Long.MAX_VALUE && group in listOf("", "all"))
                " SELECT " + columns.split(',').joinToString(",") { "e.$it" } + " FROM local_conversation_heads h JOIN enriched e ON e.id=h.message_id ORDER BY h.timestamp DESC,h.message_id DESC LIMIT ?"
            else
                ", matched AS (SELECT * FROM enriched WHERE $where), latest AS (SELECT sender,MAX(timestamp) event_time FROM matched GROUP BY sender), chosen AS (SELECT MAX(m.id) id FROM matched m JOIN latest l ON m.sender=l.sender AND m.timestamp=l.event_time GROUP BY m.sender) SELECT $columns FROM enriched WHERE id IN (SELECT id FROM chosen) ORDER BY timestamp DESC,id DESC LIMIT ?"
        args.add(limit.coerceAtLeast(1).toString())
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    CachedMessage(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getLong(5),
                        c.getString(6),
                        c.getInt(7) != 0,
                        analysis = MessageAnalysis(c.getString(8), c.getLong(9), c.getString(10), c.getInt(11) != 0, c.getString(12), c.getString(13), c.getLong(14)),
                        outgoing = c.getString(15) == "out",
                    )
                )
            }
        }
    }

    // Captured SMS still waiting for the hub, shaped like synced messages so the conversation
    // views show them immediately. After the ACK the synced copy replaces them.
    fun localMessages(sender: String = "", q: String = "", sim: String = "", localContactSenders: Set<String> = emptySet()): List<CachedMessage> {
        val resolver = receiverResolver(allowFallback = false)
        var where = "o.blocked<>'$AUTO_IGNORED'"
        val args = mutableListOf<String>()
        if (sender.isNotEmpty()) {
            where += " AND o.sender=?"
            args.add(sender)
        }
        if (q.isNotEmpty()) {
            where +=
                " AND (instr(lower(o.body),lower(?))>0 OR instr(lower(o.sender),lower(?))>0 OR instr(lower(COALESCE(n.name,'')),lower(?))>0${contactSearchClause("o.sender", localContactSenders)})"
            args.addAll(listOf(q, q, q))
        }
        return readableDatabase
            .rawQuery(
                "SELECT o.rowid,o.receiver,o.sender,o.body,o.timestamp,o.sub,o.historical,COALESCE(n.name,''),o.direction FROM outbox o LEFT JOIN contact_snapshot n ON n.phone=o.sender WHERE $where ORDER BY o.timestamp DESC LIMIT 500",
                args.toTypedArray(),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val receiver = c.getString(1).ifEmpty { resolver(c.getInt(5)) }
                        if (sim.isNotEmpty() && receiver != sim) continue
                        add(
                            CachedMessage(
                                -c.getLong(0),
                                config.deviceId,
                                receiver,
                                c.getString(2),
                                c.getString(3),
                                c.getLong(4),
                                c.getString(7),
                                c.getInt(6) != 0,
                                local = true,
                                outgoing = c.getString(8) == "out",
                            )
                        )
                    }
                }
            }
    }

    fun messagesWithLocal(
        sender: String = "",
        q: String = "",
        sim: String = "",
        limit: Int = 200,
        group: String = "all",
        localContactSenders: Set<String> = emptySet(),
    ): List<CachedMessage> {
        val remote = messages(sender, q, sim, limit = limit, group = group, localContactSenders = localContactSenders)
        val candidates = localMessages(sender, q, sim, localContactSenders).filter { it.matchesGroup(group, aiEnabled()) }
        val synced = syncedLocalIds(candidates)
        val local = candidates.filterNot { it.id in synced }
        val unreadMap = if (sender.isEmpty()) unreadCounts() else emptyMap()
        if (local.isEmpty()) {
            return if (unreadMap.isEmpty()) remote else remote.map {
                val u = unreadMap[it.sender] ?: 0
                if (u > 0) it.copy(unreadCount = u) else it
            }
        }
        val newest = compareByDescending<CachedMessage> { it.timestamp }.thenByDescending { it.id }
        if (sender.isNotEmpty()) return (remote + local).sortedWith(newest).take(limit)
        // Conversation list: one head per sender, whichever receiving phone is newer.
        return (remote + local)
            .groupBy { it.sender }
            .values
            .map {
                val top = it.sortedWith(newest).first()
                val u = unreadMap[top.sender] ?: 0
                if (u > 0) top.copy(unreadCount = u) else top
            }
            .sortedWith(newest)
            .take(limit)
    }

    private fun syncedLocalIds(messages: List<CachedMessage>): Set<Long> = buildSet {
        // Six bindings per row, below API 26 SQLite's 999-variable limit. Use
        // resolved receiver snapshots, not an unresolved outbox.receiver value.
        for (batch in messages.chunked(100)) {
            val args = batch.flatMap { listOf(it.id.toString(), it.receiver, it.sender, it.body, it.timestamp.toString(), if (it.outgoing) "out" else "in") } + config.deviceId
            val values = batch.joinToString(",") { "(?,?,?,?,?,?)" }
            readableDatabase.rawQuery("WITH candidates(id,receiver,sender,body,timestamp,direction) AS (VALUES $values) SELECT c.id FROM candidates c JOIN inbox i ON i.receiver=c.receiver AND i.sender=c.sender AND i.body=c.body AND i.timestamp=CAST(c.timestamp AS INTEGER) AND i.direction=c.direction WHERE i.device=? AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.rowid=-CAST(c.id AS INTEGER) AND o.blocked='notification_collision')", args.toTypedArray()).use { c ->
                while (c.moveToNext()) add(c.getLong(0))
            }
        }
    }

    fun resetForNewIdentity(
        connectedAt: Long = System.currentTimeMillis(),
        clearCaptures: Boolean = false,
    ) = synchronized(identityLock) {
        val db = writableDatabase
        val revoked = mutableListOf<OtpIntent>()
        db.beginTransaction()
        try {
            db.delete("inbox", null, null)
            db.delete("local_conversation_heads", null, null)
            db.delete("preview_messages", null, null)
            db.delete("message_analysis", null, null)
            db.delete("contact_snapshot", null, null)
            db.delete("conversation_reads", null, null)
            db.delete("read_outbox", null, null)
            revoked += revokeOtpIntents(db, null, Long.MAX_VALUE)
            db.execSQL("UPDATE meta SET value=0")
            db.execSQL("UPDATE meta SET value=-1 WHERE key='initial_head'")
            db.execSQL("UPDATE meta SET value=? WHERE key='capture_since'", arrayOf(connectedAt))
            if (clearCaptures) {
                db.delete("upload_fingerprints", null, null)
                db.delete("notification_receipts", null, null)
                db.delete("captures", null, null)
                // Calls queued for the old hub are not diverted to a different one; they are
                // re-read from the system call log once upload is enabled for the new hub.
                db.delete("call_outbox", null, null)
                db.delete("call_ledger", null, null)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cancelOtpNotifications(revoked)
        changes.value++
    }

    // Drop the synced inbox and its cursor. Outbox, SIM mappings and the local
    // capture watermark stay: a database rewind must not discard unsent SMS or
    // start importing history from before this phone was paired.
    fun resetSyncedCache(epoch: Long) = synchronized(identityLock) {
        val db = writableDatabase
        val revoked = mutableListOf<OtpIntent>()
        db.beginTransaction()
        try {
            db.delete("inbox", null, null)
            db.delete("local_conversation_heads", null, null)
            db.delete("preview_messages", null, null)
            db.delete("message_analysis", null, null)
            db.delete("conversation_reads", null, null)
            db.delete("read_outbox", null, null)
            revoked += revokeOtpIntents(db, null, Long.MAX_VALUE)
            db.execSQL("UPDATE meta SET value=0 WHERE key='global_watermark_id'")
            db.execSQL("UPDATE meta SET value=0 WHERE key='read_state_seen'")
            db.execSQL("UPDATE meta SET value=0 WHERE key IN ('analysis_cursor','local_rule_cursor','ai_enabled','preview_head','contacts_revision')")
            db.execSQL("UPDATE meta SET value=0 WHERE key='cursor'")
            db.execSQL("UPDATE meta SET value=? WHERE key='epoch'", arrayOf(epoch))
            db.execSQL("UPDATE meta SET value=1 WHERE key='epoch_seen'")
            db.execSQL("UPDATE meta SET value=-1 WHERE key='initial_head'")
            db.execSQL("UPDATE meta SET value=0 WHERE key IN ('initial_complete','notify_pending')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        cancelOtpNotifications(revoked)
        changes.value++
    }

    fun rememberEpoch(epoch: Long) = synchronized(identityLock) {
        if (epochSeen()) return@synchronized
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE meta SET value=? WHERE key='epoch'", arrayOf(epoch))
            db.execSQL("UPDATE meta SET value=1 WHERE key='epoch_seen'")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun applyContacts(names: JSONObject, revision: Long? = null, checkedAt: Long = 0) {
        val db = writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            val desired = names.keys().asSequence().associateWith { names.getString(it) }
            val current =
                db.rawQuery("SELECT phone,name FROM contact_snapshot", null).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                }
            for (phone in current.keys + desired.keys) {
                val next = desired[phone] ?: ""
                if (current[phone].orEmpty() != next) {
                    changed = true
                    db.execSQL(
                        "UPDATE inbox SET contact=? WHERE sender=? AND contact<>?",
                        arrayOf(next, phone, next),
                    )
                    if (next.isEmpty()) db.delete("contact_snapshot", "phone=?", arrayOf(phone))
                    else
                        db.insertWithOnConflict(
                            "contact_snapshot",
                            null,
                            ContentValues().apply {
                                put("phone", phone)
                                put("name", next)
                            },
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                }
            }
            if (revision != null) db.execSQL("UPDATE meta SET value=? WHERE key='contacts_revision'", arrayOf(revision))
            // A local/restored snapshot is not evidence of a successful network
            // read. Invalidate an earlier TTL unless this caller supplies one.
            db.execSQL("UPDATE meta SET value=? WHERE key='contacts_checked_at'", arrayOf(checkedAt))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (changed) changes.value++
    }
}

fun SmsRecord.json(): JSONObject =
    JSONObject()
        .put("receiver", receiver)
        .put("sender", sender)
        .put("body", body)
        .put("timestamp", timestamp)
        .put("subscription_id", subscriptionId)
        .put("historical", historical)
        .apply { if (outgoing) put("direction", "out") }
