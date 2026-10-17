package io.hongshu.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

// Notification post time and SMS provider/PDU time differ by seconds, rarely minutes.
internal const val NOTIFICATION_MATCH_WINDOW = 120_000L

// A notification titled with a contact name waits this long for the broadcast or inbox copy to
// supply the number; otherwise the hub would file it as a separate, name-keyed conversation.
internal const val NOTIFICATION_NAME_HOLD = 150_000L

// SQL predicate (one `?`: the hold boundary) for notification rows still waiting for a number.
private const val HELD_NOTIFICATION =
    "timestamp>CAST(? AS INTEGER) AND sender GLOB '*[^0-9+]*' AND EXISTS (SELECT 1 FROM captures c WHERE c.key=outbox.key AND c.source='notification')"

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
)

data class SimMapping(val sub: Int, val slot: Int, val phone: String, val label: String)

class Store(private val context: Context, databaseName: String = "hongshu.db") :
    SQLiteOpenHelper(context, databaseName, null, 7), Closeable {
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
    }

    private fun createMessageAnalysis(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE message_analysis (message_id INTEGER PRIMARY KEY,otp TEXT NOT NULL DEFAULT '',expires_at INTEGER NOT NULL DEFAULT 0,category TEXT NOT NULL DEFAULT '',manual INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'pending',error TEXT NOT NULL DEFAULT '',revision INTEGER NOT NULL DEFAULT 0)")
        for (key in listOf("analysis_cursor", "ai_enabled", "local_rule_cursor")) db.execSQL("INSERT OR IGNORE INTO meta VALUES (?,0)", arrayOf(key))
    }

    fun aiEnabled(): Boolean = meta("ai_enabled") != 0L
    fun rememberAI(enabled: Boolean) {
        if (aiEnabled() == enabled) return
        writableDatabase.execSQL("UPDATE meta SET value=? WHERE key='ai_enabled'", arrayOf(if (enabled) 1 else 0))
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
        val batch = readableDatabase.rawQuery("SELECT id,body FROM inbox WHERE id>? ORDER BY id LIMIT 200", arrayOf(cursor.toString())).use { c ->
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

    fun localPending(limit: Int = 100, offset: Int = 0): List<LocalPendingMessage> {
        val resolver = receiverResolver(allowFallback = false)
        return readableDatabase
            .rawQuery(
                "SELECT key,receiver,sender,body,timestamp,sub,slot,historical,blocked,COALESCE((SELECT source FROM captures WHERE captures.key=outbox.key),'legacy') FROM outbox ORDER BY timestamp DESC,key DESC LIMIT ? OFFSET ?",
                arrayOf(limit.coerceIn(1, 200).toString(), offset.coerceAtLeast(0).toString()),
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
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use {
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
            if (advance && providerId != null)
                db.execSQL(
                    "UPDATE meta SET value=MAX(value,?) WHERE key='provider_id'",
                    arrayOf(providerId),
                )
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

    // A notification may use a brand/contact title while the provider or broadcast exposes a
    // numeric sender. Once a unique compatible local counterpart exists, remove only the
    // provisional outbox row—even if that counterpart was already ACKed—and retain the
    // notification capture as replay/dedup evidence.
    fun reconcileNotificationCaptures(): Int {
        val db = writableDatabase
        val orphans =
            db.rawQuery(
                    "SELECT o.key,c.sender,c.body,c.timestamp,c.sub,c.receiver FROM outbox o JOIN captures c ON c.key=o.key WHERE c.source='notification'",
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
            db.beginTransaction()
            try {
                val targets =
                    db.rawQuery(
                            "SELECT key,sender,sub,receiver,timestamp FROM captures WHERE source<>'notification' AND body=? AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp-?),key",
                            arrayOf(
                                orphan.body,
                                (orphan.timestamp - NOTIFICATION_MATCH_WINDOW).toString(),
                                (orphan.timestamp + NOTIFICATION_MATCH_WINDOW).toString(),
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
                                        notificationSenderMatches(
                                            orphan.sender,
                                            candidate.sender,
                                            orphan.body,
                                        ) &&
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
                    db.delete("outbox", "key=?", arrayOf(orphan.key))
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

    // Keys of rows that cannot upload until a receiving number is known.
    fun unresolvedKeys(): List<String> {
        val resolver = receiverResolver(allowFallback = false)
        return readableDatabase
            .rawQuery("SELECT key,receiver,sub,blocked FROM outbox", null)
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
        val candidates =
            db.rawQuery(
                    "SELECT key,sender,sub,receiver,timestamp FROM captures WHERE source='notification' AND $free AND body=? AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp-?),key",
                    arrayOf(
                        m.body,
                        (m.timestamp - NOTIFICATION_MATCH_WINDOW).toString(),
                        (m.timestamp + NOTIFICATION_MATCH_WINDOW).toString(),
                        m.timestamp.toString(),
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
                                notificationSenderMatches(
                                    candidate.sender,
                                    m.sender,
                                    m.body,
                                ) &&
                                    compatibleSims(
                                        candidate.sub,
                                        candidate.receiver,
                                        m.subscriptionId,
                                        m.receiver,
                                    )
                            )
                                add(candidate)
                        }
                    }
                }
        val key = candidates.singleOrNull()?.key ?: return false
        db.update(
            "captures",
            ContentValues().apply {
                if (providerId == null) put("broadcast_key", broadcastKey)
                else put("provider_id", providerId)
                put("sender", m.sender)
                if (m.subscriptionId >= 0) put("sub", m.subscriptionId)
                if (m.receiver.isNotEmpty()) put("receiver", m.receiver)
            },
            "key=?",
            arrayOf(key),
        )
        db.update(
            "outbox",
            ContentValues().apply {
                put("sender", m.sender)
                if (m.subscriptionId >= 0) put("sub", m.subscriptionId)
                if (slot >= 0) put("slot", slot)
                if (m.receiver.isNotEmpty()) put("receiver", m.receiver)
            },
            "key=?",
            arrayOf(key),
        )
        changes.value++
        return true
    }

    // Reposts may repeat earlier lines, but identical bodies are not an identity.
    fun queueNotification(m: SmsRecord): Boolean {
        val key =
            "notification:" +
                sha256(
                    "${m.receiver}\u0000${m.subscriptionId}\u0000${m.sender}\u0000${m.timestamp}\u0000${m.body}"
                )
        val db = writableDatabase
        db.beginTransaction()
        try {
            val seen =
                db.rawQuery(
                        "SELECT key,sender,sub,receiver,timestamp FROM captures WHERE source='notification' AND body=? AND timestamp BETWEEN ? AND ?",
                        arrayOf(
                            m.body,
                            (m.timestamp - NOTIFICATION_MATCH_WINDOW).toString(),
                            (m.timestamp + NOTIFICATION_MATCH_WINDOW).toString(),
                        ),
                    )
                    .use { c ->
                        var found = false
                        while (!found && c.moveToNext()) {
                            val candidate =
                                CaptureCandidate(
                                    c.getString(0),
                                    c.getString(1),
                                    c.getInt(2),
                                    c.getString(3),
                                    c.getLong(4),
                                )
                            found =
                                candidate.key == key ||
                                    (notificationSenderMatches(
                                        m.sender,
                                        candidate.sender,
                                        m.body,
                                    ) &&
                                        (compatibleSims(
                                            m.subscriptionId,
                                            m.receiver,
                                            candidate.sub,
                                            candidate.receiver,
                                        ) ||
                                            (m.subscriptionId < 0 &&
                                                candidate.sub < 0 &&
                                                m.receiver.isEmpty() &&
                                                candidate.receiver.isEmpty())))
                        }
                        found
                    }
            val counterpart =
                db.rawQuery(
                        "SELECT key,sender,sub,receiver,timestamp FROM captures WHERE source<>'notification' AND body=? AND timestamp BETWEEN ? AND ?",
                        arrayOf(
                            m.body,
                            (m.timestamp - NOTIFICATION_MATCH_WINDOW).toString(),
                            (m.timestamp + NOTIFICATION_MATCH_WINDOW).toString(),
                        ),
                    )
                    .use { c ->
                        var matches = 0
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
                                notificationSenderMatches(m.sender, candidate.sender, m.body) &&
                                    compatibleSims(
                                        m.subscriptionId,
                                        m.receiver,
                                        candidate.sub,
                                        candidate.receiver,
                                    )
                            )
                                matches++
                        }
                        matches == 1
                    }
            if (seen || counterpart) {
                db.setTransactionSuccessful()
                return false
            }
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
        return true
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
        val invalid = mutableListOf<String>()
        while (result.size < limit) {
            var scanned = 0
            readableDatabase
                .rawQuery(
                    "SELECT key,receiver,sender,body,timestamp,sub,slot,historical FROM outbox WHERE blocked='' AND ($eligible)$lane AND NOT ($HELD_NOTIFICATION) AND (timestamp,key)>(?,?) ORDER BY timestamp,key LIMIT 100",
                    (subs + listOf((now - NOTIFICATION_NAME_HOLD).toString(), lastTime.toString(), lastKey))
                        .toTypedArray(),
                )
                .use { c ->
                    while (result.size < limit && c.moveToNext()) {
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
                            else result.add(c.getString(0) to m)
                        }
                    }
                }
            if (scanned < 100) break
        }
        // Never write while the read cursor is open.
        for (key in invalid) block(key, "invalid_message")
        return result
    }

    // When a held notification row becomes uploadable, or null when none is held.
    fun nextNotificationRelease(now: Long = System.currentTimeMillis()): Long? =
        readableDatabase
            .rawQuery(
                "SELECT MAX(timestamp) FROM outbox WHERE blocked='' AND $HELD_NOTIFICATION",
                arrayOf((now - NOTIFICATION_NAME_HOLD).toString()),
            )
            .use { c ->
                if (!c.moveToFirst() || c.isNull(0)) null
                else (c.getLong(0) + NOTIFICATION_NAME_HOLD - now).coerceAtLeast(1000)
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

    fun pendingCounts(): PendingCounts {
        val resolver = receiverResolver(allowFallback = false)
        return readableDatabase
            .rawQuery(
                "SELECT receiver,sub,blocked,COUNT(*) FROM outbox GROUP BY receiver,sub,blocked",
                null,
            )
            .use { rows ->
                var ready = 0
                var needsSim = 0
                var invalid = 0
                while (rows.moveToNext()) {
                    val count = rows.getInt(3)
                    when {
                        rows.getString(2) == "invalid_message" -> invalid += count
                        rows.getString(2).isNotEmpty() ||
                            !confirmedNumber(rows.getString(0).ifEmpty { resolver(rows.getInt(1)) }) ->
                            needsSim += count
                        else -> ready += count
                    }
                }
                PendingCounts(ready, needsSim, invalid)
            }
    }

    fun blockedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE blocked<>''", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    fun retryBlocked() {
        writableDatabase.execSQL("UPDATE outbox SET blocked=''")
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

    fun ack(keys: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (key in keys) db.delete("outbox", "key=?", arrayOf(key))
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

    fun advanceProvider(id: Long) {
        writableDatabase.execSQL(
            "UPDATE meta SET value=MAX(value,?) WHERE key='provider_id'",
            arrayOf(id),
        )
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
        val keys =
            db.rawQuery(
                    "SELECT key,receiver,sub FROM outbox WHERE sender=? AND body=? AND timestamp=?",
                    arrayOf(sender, body, timestamp.toString()),
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
        for (key in keys) db.delete("outbox", "key=?", arrayOf(key))
    }

    fun applySync(
        messages: JSONArray,
        cursor: Long,
        self: String = "",
        pairedAt: Long = 0,
        pageEpoch: Long? = null,
    ): List<CachedMessage> {
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
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                reconcileSyncedOutbox(db, m, self, resolveReceiver)
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
                    }
                val inserted =
                    db.insertWithOnConflict("inbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                val analysis = m.optJSONObject("analysis")?.let { MessageAnalysis.from(it) }
                    ?: MessageAnalysis(otp = extractOtp(m.getString("body")).orEmpty())
                saveAnalysis(db, m.getLong("id"), analysis)
                if (inserted != -1L)
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
                        )
                    )
            }
            val fresh =
                added.count {
                    self.isNotEmpty() &&
                        shouldNotify(
                            it.deviceId,
                            self,
                            it.historical,
                            initialComplete(),
                            it.timestamp,
                            pairedAt,
                        )
                }
            if (fresh > 0)
                db.execSQL(
                    "UPDATE meta SET value=value+? WHERE key='notify_pending'",
                    arrayOf(fresh),
                )
            db.execSQL("UPDATE meta SET value=MAX(value,?) WHERE key='cursor'", arrayOf(cursor))
            db.execSQL(
                "UPDATE meta SET value=1 WHERE key='initial_complete' AND EXISTS (SELECT 1 FROM meta WHERE key='initial_head' AND value>=0 AND value<=?)",
                arrayOf(cursor),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
        return added
    }

    fun receivers(): List<String> =
        readableDatabase
            .rawQuery("SELECT DISTINCT receiver FROM inbox ORDER BY receiver", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun groupCounts(q: String = "", sim: String = ""): Map<String, Int> {
        val result = messageGroupLabels.keys.associateWith { 0 }.toMutableMap()
        val args = mutableListOf<String>()
        var where = "1=1"
        if (sim.isNotEmpty()) { where += " AND i.receiver=?"; args += sim }
        if (q.isNotEmpty()) {
            where += " AND (instr(lower(i.body),lower(?))>0 OR instr(lower(i.sender),lower(?))>0 OR instr(lower(i.contact),lower(?))>0)"
            args.addAll(listOf(q, q, q))
        }
        fun add(group: String, n: Int) {
            result[group] = (result[group] ?: 0) + n
            result["all"] = result.getValue("all") + n
            if (group != "otp") result["other"] = result.getValue("other") + n
        }
        readableDatabase.rawQuery("SELECT CASE WHEN COALESCE(a.otp,'')<>'' THEN 'otp' WHEN COALESCE(a.category,'')<>'' THEN a.category ELSE 'pending' END g,COUNT(*) FROM inbox i LEFT JOIN message_analysis a ON a.message_id=i.id WHERE $where GROUP BY g", args.toTypedArray()).use { c ->
            while (c.moveToNext()) add(c.getString(0), c.getInt(1))
        }
        for (m in localMessages(q = q, sim = sim)) add(if (extractOtp(m.body) != null) "otp" else "pending", 1)
        return result
    }

    fun messages(
        sender: String = "",
        q: String = "",
        sim: String = "",
        before: Long = Long.MAX_VALUE,
        limit: Int = 200,
        group: String = "all",
    ): List<CachedMessage> {
        var where = "1=1"
        val args = mutableListOf<String>()
        when (group) {
            "", "all" -> Unit
            "otp" -> where += " AND otp<>''"
            "other" -> where += " AND otp=''"
            "pending" -> where += " AND otp='' AND category=''"
            else -> { where += " AND otp='' AND category=?"; args.add(group) }
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
                " AND (instr(lower(body),lower(?))>0 OR instr(lower(sender),lower(?))>0 OR instr(lower(contact),lower(?))>0)"
            args.addAll(listOf(q, q, q))
        }
        val enriched = "WITH enriched AS (SELECT inbox.*,COALESCE(a.otp,'') otp,COALESCE(a.expires_at,0) expires_at,COALESCE(a.category,'') category,COALESCE(a.manual,0) manual,COALESCE(a.state,'pending') state,COALESCE(a.error,'') error,COALESCE(a.revision,0) revision FROM inbox LEFT JOIN message_analysis a ON a.message_id=inbox.id)"
        val columns = "id,device,receiver,sender,body,timestamp,contact,historical,otp,expires_at,category,manual,state,error,revision"
        val sql = enriched +
            if (sender.isNotEmpty())
                " SELECT $columns FROM enriched WHERE $where ORDER BY timestamp DESC,id DESC LIMIT ?"
            else
                ", matched AS (SELECT * FROM enriched WHERE $where), latest AS (SELECT sender,receiver,MAX(timestamp) event_time FROM matched GROUP BY sender,receiver), chosen AS (SELECT MAX(m.id) id FROM matched m JOIN latest l ON m.sender=l.sender AND m.receiver=l.receiver AND m.timestamp=l.event_time GROUP BY m.sender,m.receiver) SELECT $columns FROM enriched WHERE id IN (SELECT id FROM chosen) ORDER BY timestamp DESC,id DESC LIMIT ?"
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
                    )
                )
            }
        }
    }

    // Captured SMS still waiting for the hub, shaped like synced messages so the conversation
    // views show them immediately. After the ACK the synced copy replaces them.
    fun localMessages(sender: String = "", q: String = "", sim: String = ""): List<CachedMessage> {
        val resolver = receiverResolver(allowFallback = false)
        var where = "1=1"
        val args = mutableListOf<String>()
        if (sender.isNotEmpty()) {
            where += " AND o.sender=?"
            args.add(sender)
        }
        if (q.isNotEmpty()) {
            where +=
                " AND (instr(lower(o.body),lower(?))>0 OR instr(lower(o.sender),lower(?))>0 OR instr(lower(COALESCE(n.name,'')),lower(?))>0)"
            args.addAll(listOf(q, q, q))
        }
        return readableDatabase
            .rawQuery(
                "SELECT o.rowid,o.receiver,o.sender,o.body,o.timestamp,o.sub,o.historical,COALESCE(n.name,'') FROM outbox o LEFT JOIN contact_snapshot n ON n.phone=o.sender WHERE $where ORDER BY o.timestamp DESC LIMIT 500",
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
    ): List<CachedMessage> {
        val remote = messages(sender, q, sim, limit = limit, group = group)
        val local =
            localMessages(sender, q, sim)
                .filter { it.matchesGroup(group) }
                .filterNot(::hasSyncedLocalCopy)
        if (local.isEmpty()) return remote
        val newest = compareByDescending<CachedMessage> { it.timestamp }.thenByDescending { it.id }
        if (sender.isNotEmpty()) return (remote + local).sortedWith(newest).take(limit)
        // Conversation list: one head per sender and receiver, whichever copy is newer.
        return (remote + local)
            .groupBy { it.sender to it.receiver }
            .values
            .map { it.sortedWith(newest).first() }
            .sortedWith(newest)
            .take(limit)
    }

    private fun hasSyncedLocalCopy(local: CachedMessage): Boolean =
        readableDatabase
            .rawQuery(
                "SELECT 1 FROM inbox WHERE device=? AND receiver=? AND sender=? AND body=? AND timestamp=? LIMIT 1",
                arrayOf(
                    config.deviceId,
                    local.receiver,
                    local.sender,
                    local.body,
                    local.timestamp.toString(),
                ),
            )
            .use { it.moveToFirst() }

    fun resetForNewIdentity(
        connectedAt: Long = System.currentTimeMillis(),
        clearCaptures: Boolean = false,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("inbox", null, null)
            db.delete("message_analysis", null, null)
            db.delete("contact_snapshot", null, null)
            db.execSQL("UPDATE meta SET value=0")
            db.execSQL("UPDATE meta SET value=-1 WHERE key='initial_head'")
            db.execSQL("UPDATE meta SET value=? WHERE key='capture_since'", arrayOf(connectedAt))
            if (clearCaptures) db.delete("captures", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
    }

    // Drop the synced inbox and its cursor. Outbox, SIM mappings and the local
    // capture watermark stay: a database rewind must not discard unsent SMS or
    // start importing history from before this phone was paired.
    fun resetSyncedCache(epoch: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("inbox", null, null)
            db.delete("message_analysis", null, null)
            db.execSQL("UPDATE meta SET value=0 WHERE key IN ('analysis_cursor','local_rule_cursor','ai_enabled')")
            db.execSQL("UPDATE meta SET value=0 WHERE key='cursor'")
            db.execSQL("UPDATE meta SET value=? WHERE key='epoch'", arrayOf(epoch))
            db.execSQL("UPDATE meta SET value=1 WHERE key='epoch_seen'")
            db.execSQL("UPDATE meta SET value=-1 WHERE key='initial_head'")
            db.execSQL("UPDATE meta SET value=0 WHERE key IN ('initial_complete','notify_pending')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
    }

    fun rememberEpoch(epoch: Long) {
        if (epochSeen()) return
        val db = writableDatabase
        db.execSQL("UPDATE meta SET value=? WHERE key='epoch'", arrayOf(epoch))
        db.execSQL("UPDATE meta SET value=1 WHERE key='epoch_seen'")
    }

    fun applyContacts(names: JSONObject) {
        val db = writableDatabase
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value++
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
