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
    SQLiteOpenHelper(context, databaseName, null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported database version")
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

    fun receiver(sub: Int, slot: Int): String {
        val all = sims()
        return all.find { it.sub == sub && sub >= 0 }?.phone
            ?: all.find { it.slot == slot && slot >= 0 }?.phone
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
                            if (
                                m.body.isEmpty() ||
                                    m.sender.isEmpty() ||
                                    !confirmedNumber(phone) ||
                                    m.body.toByteArray().size > 64000 ||
                                    m.sender.toByteArray().size > 100 ||
                                    m.timestamp < 0 ||
                                    m.timestamp > System.currentTimeMillis() + 86400000 ||
                                    m.body.contains('\u0000') ||
                                    m.sender.contains('\u0000')
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

    fun cursor(): Long =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key='cursor'", null).use {
            it.moveToFirst()
            it.getLong(0)
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
                "WITH matched AS (SELECT * FROM inbox WHERE $where), latest AS (SELECT sender,MAX(timestamp) event_time FROM matched GROUP BY sender), chosen AS (SELECT MAX(m.id) id FROM matched m JOIN latest l ON m.sender=l.sender AND m.timestamp=l.event_time GROUP BY m.sender) SELECT id,device,receiver,sender,body,timestamp,contact,historical FROM inbox WHERE id IN (SELECT id FROM chosen) ORDER BY timestamp DESC,id DESC LIMIT ?"
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
