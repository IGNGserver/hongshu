package io.hongshu.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CachedMessage(
    val id: Long,
    val deviceId: String,
    val receiver: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val contact: String,
    val historical: Boolean = false,
)

data class SimMapping(val sub: Int, val slot: Int, val phone: String, val label: String)

class Store(context: Context, databaseName: String = "hongshu.db") :
    SQLiteOpenHelper(context, databaseName, null, 3) {
    private val config = Config(context)

    companion object {
        val changes = MutableStateFlow(0L)
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
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    fun queue(m: SmsRecord, slot: Int = -1) {
        val cv =
            ContentValues().apply {
                put(
                    "key",
                    sha256(
                        "${m.subscriptionId}\u0000${m.sender}\u0000${m.timestamp}\u0000${m.body}"
                    ),
                )
                put("receiver", m.receiver)
                put("sender", m.sender)
                put("body", m.body)
                put("timestamp", m.timestamp)
                put("sub", m.subscriptionId)
                put("slot", slot)
                put("historical", if (m.historical) 1 else 0)
            }
        writableDatabase.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        changes.value++
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
    fun receiver(sub: Int, slot: Int): String {
        val all = sims()
        // Slot is not an identity. A replaced SIM in the same tray must not inherit
        // the previous number; only an exact subscription match, or an explicit
        // single-SIM confirmation, may fill the receiver.
        return all.find { it.sub == sub && sub >= 0 }?.phone
            ?: if (all.size == 1 && config.singleSimConfirmed) all.first().phone else ""
    }

    fun pending(): List<Pair<String, SmsRecord>> =
        readableDatabase
            .rawQuery(
                "SELECT key,receiver,sender,body,timestamp,sub,slot,historical FROM outbox WHERE blocked='' ORDER BY timestamp",
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext() && size < 100) {
                        val phone = c.getString(1).ifEmpty { receiver(c.getInt(5), c.getInt(6)) }
                        if (phone.isNotEmpty()) {
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
                                    !confirmedNumber(phone) ||
                                    m.body.toByteArray().size > 64000 ||
                                    m.sender.toByteArray().size > 100 ||
                                    m.timestamp < 0 ||
                                    m.timestamp > System.currentTimeMillis() + 86400000 ||
                                    m.body.contains('\u0000') ||
                                    m.sender.contains('\u0000') ||
                                    rawJsonSize > 900000
                            )
                                writableDatabase.execSQL(
                                    "UPDATE outbox SET blocked='invalid_message' WHERE key=?",
                                    arrayOf(c.getString(0)),
                                )
                            else add(c.getString(0) to m)
                        }
                    }
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

    fun advanceInboxWatermark(date: Long, id: Long) {
        val current = inboxWatermark()
        if (date < current.first || (date == current.first && id <= current.second)) return
        val db = writableDatabase
        db.execSQL("UPDATE meta SET value=? WHERE key='inbox_date'", arrayOf(date))
        db.execSQL("UPDATE meta SET value=? WHERE key='inbox_id'", arrayOf(id))
    }

    // First automatic scan starts at pairing time. Older inbox rows stay out of the
    // live queue; the user can still import them explicitly as history.
    fun captureSince(): Long {
        val existing = meta("capture_since")
        if (existing > 0) return existing
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "UPDATE meta SET value=? WHERE key='capture_since'",
            arrayOf(now),
        )
        return now
    }

    fun hasNearDuplicate(record: SmsRecord): Boolean =
        readableDatabase
            .rawQuery(
                "SELECT sender,body,timestamp FROM outbox WHERE sender=? AND ABS(timestamp-?)<=120000 UNION ALL SELECT sender,body,timestamp FROM inbox WHERE sender=? AND ABS(timestamp-?)<=120000",
                arrayOf(
                    record.sender,
                    record.timestamp.toString(),
                    record.sender,
                    record.timestamp.toString(),
                ),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val other =
                        SmsRecord(
                            record.receiver,
                            c.getString(0),
                            c.getString(1),
                            c.getLong(2),
                            record.subscriptionId,
                        )
                    if (sameMessage(record, other)) return true
                }
                false
            }

    fun applySync(messages: JSONArray, cursor: Long): List<CachedMessage> {
        val db = writableDatabase
        val added = mutableListOf<CachedMessage>()
        db.beginTransaction()
        try {
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
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
            db.execSQL("UPDATE meta SET value=MAX(value,?) WHERE key='cursor'", arrayOf(cursor))
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

    fun messages(
        sender: String = "",
        q: String = "",
        sim: String = "",
        before: Long = Long.MAX_VALUE,
        limit: Int = 200,
    ): List<CachedMessage> {
        var where = "1=1"
        val args = mutableListOf<String>()
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
        val sql =
            if (sender.isNotEmpty())
                "SELECT id,device,receiver,sender,body,timestamp,contact,historical FROM inbox WHERE $where ORDER BY timestamp DESC,id DESC LIMIT ?"
            else
                "WITH matched AS (SELECT * FROM inbox WHERE $where), latest AS (SELECT sender,receiver,MAX(timestamp) event_time FROM matched GROUP BY sender,receiver), chosen AS (SELECT MAX(m.id) id FROM matched m JOIN latest l ON m.sender=l.sender AND m.receiver=l.receiver AND m.timestamp=l.event_time GROUP BY m.sender,m.receiver) SELECT id,device,receiver,sender,body,timestamp,contact,historical FROM inbox WHERE id IN (SELECT id FROM chosen) ORDER BY timestamp DESC,id DESC LIMIT ?"
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
                    )
                )
            }
        }
    }

    fun resetForNewIdentity() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("inbox", null, null)
            db.execSQL("UPDATE meta SET value=0")
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
            db.execSQL("UPDATE meta SET value=0 WHERE key='cursor'")
            db.execSQL("UPDATE meta SET value=? WHERE key='epoch'", arrayOf(epoch))
            db.execSQL("UPDATE meta SET value=1 WHERE key='epoch_seen'")
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
            db.execSQL("UPDATE inbox SET contact=''")
            for (phone in names.keys()) db.execSQL(
                "UPDATE inbox SET contact=? WHERE sender=?",
                arrayOf(names.getString(phone), phone),
            )
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
