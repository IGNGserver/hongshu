package io.hongshu.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

// Call log sync (docs/CALL_LOG.md): read-only records copied from CallLog.Calls to the hub's
// own call stream. No notification, unread or analysis side effects; deletions on the phone
// are not propagated.

// Optional hub capability (GET /me "features"). An older hub has no /api/calls, so captured
// calls wait in the local queue until the hub announces it.
internal const val HUB_FEATURE_CALL_LOG = "call_log"
internal const val MAX_CALL_DURATION = 30L * 24 * 3600
private const val CALL_PAGE = 100

data class CallRecord(
    // This phone's confirmed line, or "" when the system did not say which SIM was used.
    val receiver: String,
    // The other party; "" only when the network withheld it (presentation != "allowed").
    val number: String,
    val type: String,
    val timestamp: Long,
    val duration: Long,
    val subscriptionId: Int,
    val video: Boolean = false,
    val presentation: String = "allowed",
    val location: String = "",
    val historical: Boolean = false,
) {
    // Same identity as the hub. The line is left out so a call whose SIM is only learned
    // later (or turned out unregistered) is still the same call.
    fun fingerprint(): String = sha256("call\u0000$number\u0000$timestamp\u0000$type\u0000$duration")

    fun json(): JSONObject =
        JSONObject()
            .put("receiver", receiver)
            .put("number", number)
            .put("type", type)
            .put("timestamp", timestamp)
            .put("duration", duration)
            .put("subscription_id", subscriptionId)
            .put("video", video)
            .put("presentation", presentation)
            .put("location", location)
            .put("historical", historical)
}

internal fun callType(type: Int): String =
    when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_externally"
        // Vendor-specific values (e.g. extra "missed" flavours) keep the record without guessing.
        else -> "unknown"
    }

internal fun callPresentation(presentation: Int): String =
    when (presentation) {
        CallLog.Calls.PRESENTATION_RESTRICTED -> "restricted"
        CallLog.Calls.PRESENTATION_PAYPHONE -> "payphone"
        // PRESENTATION_UNKNOWN, and PRESENTATION_UNAVAILABLE (5, API 33).
        CallLog.Calls.PRESENTATION_UNKNOWN, 5 -> "unknown"
        else -> "allowed"
    }

// One provider row, independent of the cursor so the scan can be exercised without a provider.
internal data class CallRow(
    val id: Long,
    val number: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val presentation: Int,
    val features: Int,
    val location: String?,
    val component: String?,
    val account: String?,
)

// The record to upload, or null for a row the hub would reject (it is then only skipped).
internal fun callRecord(
    row: CallRow,
    subscription: Int,
    receiver: (Int) -> String,
    boundary: Long,
    now: Long = System.currentTimeMillis(),
): CallRecord? {
    var presentation = callPresentation(row.presentation)
    val raw = row.number.orEmpty().trim()
    // Before presentation columns, "-1"/"-2"/"-3" stood for unknown/private/payphone.
    val number = if (raw.startsWith("-")) "" else normalizeSimNumber(raw)
    if (number.isEmpty() && presentation == "allowed") presentation = "unknown"
    if (number.length > 100 || '\u0000' in number) return null
    if (row.date < 0 || row.date > now + 24 * 3600 * 1000L) return null
    val location =
        row.location.orEmpty().replace("\u0000", "").let {
            if (it.codePointCount(0, it.length) <= 100) it else it.substring(0, it.offsetByCodePoints(0, 100))
        }
    return CallRecord(
        receiver = receiver(subscription).takeIf { confirmedNumber(it) }.orEmpty(),
        number = number,
        type = callType(row.type),
        timestamp = row.date,
        duration = row.duration.coerceIn(0, MAX_CALL_DURATION),
        subscriptionId = subscription,
        video = row.features and CallLog.Calls.FEATURES_VIDEO != 0,
        presentation = presentation,
        location = location,
        historical = isHistoricalSMS(row.date, boundary),
    )
}

internal interface CallSource {
    // Highest _ID in the provider; lower than the stored watermark means it was rebuilt.
    fun head(): Long

    fun rows(after: Long, since: Long, limit: Int): List<CallRow>

    fun subscription(row: CallRow): Int
}

// Reads CallLog.Calls with READ_CALL_LOG. Columns missing on a vendor provider degrade to
// "unknown" instead of failing the scan.
internal class ProviderCallSource(private val context: Context) : CallSource {
    private val resolver = context.contentResolver

    override fun head(): Long =
        resolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} DESC")
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            ?: error("Call log provider unavailable")

    override fun rows(after: Long, since: Long, limit: Int): List<CallRow> {
        val cursor =
            resolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                "${CallLog.Calls._ID}>? AND ${CallLog.Calls.DATE}>=?",
                arrayOf(after.toString(), since.toString()),
                "${CallLog.Calls._ID} ASC",
            ) ?: error("Call log provider unavailable")
        return cursor.use { c ->
            fun index(name: String) = c.getColumnIndex(name)
            val id = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val number = index(CallLog.Calls.NUMBER)
            val type = index(CallLog.Calls.TYPE)
            val date = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val duration = index(CallLog.Calls.DURATION)
            val presentation = index(CallLog.Calls.NUMBER_PRESENTATION)
            val features = index(CallLog.Calls.FEATURES)
            val location = index(CallLog.Calls.GEOCODED_LOCATION)
            val component = index(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)
            val account = index(CallLog.Calls.PHONE_ACCOUNT_ID)
            fun string(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i) else null
            fun int(i: Int, fallback: Int) = if (i >= 0 && !c.isNull(i)) c.getInt(i) else fallback
            buildList {
                while (size < limit && c.moveToNext()) {
                    add(
                        CallRow(
                            id = c.getLong(id),
                            number = string(number),
                            type = int(type, 0),
                            date = c.getLong(date),
                            duration = if (duration >= 0 && !c.isNull(duration)) c.getLong(duration) else 0L,
                            presentation = int(presentation, CallLog.Calls.PRESENTATION_ALLOWED),
                            features = int(features, 0),
                            location = string(location),
                            component = string(component),
                            account = string(account),
                        )
                    )
                }
            }
        }
    }

    // API 30+ asks Telephony which subscription a phone account is. Older systems often store
    // the subscription ID itself; anything else (an ICCID, an unknown account) stays unknown
    // rather than being guessed from a SIM slot.
    override fun subscription(row: CallRow): Int {
        val account = row.account?.takeIf { it.isNotBlank() } ?: return -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = row.component?.let(ComponentName::unflattenFromString) ?: return -1
            // The lookup needs READ_PHONE_STATE (SIM settings); without it the line stays unknown.
            if (
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            )
                return -1
            return runCatching {
                    context.getSystemService(TelephonyManager::class.java)
                        .getSubscriptionId(PhoneAccountHandle(component, account))
                }
                .getOrNull()
                ?.takeIf { it >= 0 && it != SubscriptionManager.INVALID_SUBSCRIPTION_ID } ?: -1
        }
        return account.toIntOrNull()?.takeIf { it >= 0 } ?: -1
    }
}

private val callScanLock = Any()

// Scans forward from the stored provider watermark in pages, committing each page with its
// watermark. Returns the number of newly queued calls and whether more rows remain.
internal fun scanCalls(
    store: Store,
    source: CallSource,
    since: Long,
    boundary: Long,
    receiver: (Int) -> String,
    current: () -> Boolean,
    maxRows: Int = 1000,
): Pair<Int, Boolean> {
    if (!current()) return 0 to false
    store.observeCallHead(source.head())
    var count = 0
    var visited = 0
    while (true) {
        val rows = source.rows(store.callWatermark(), since, CALL_PAGE)
        if (rows.isEmpty()) return count to false
        val records = rows.mapNotNull { row -> callRecord(row, source.subscription(row), receiver, boundary) }
        val added =
            synchronized(identityLock) { if (current()) store.captureCalls(records, rows.last().id) else null }
                ?: return count to false
        count += added
        visited += rows.size
        if (rows.size < CALL_PAGE) return count to false
        if (visited >= maxRows) return count to true
    }
}

fun scanCallLog(context: Context, databaseName: String = "hongshu.db"): String =
    synchronized(callScanLock) {
        val config = Config(context)
        val generation: Long
        val callGeneration: Long
        val historyGeneration: Long
        val boundary: Long
        val since: Long
        synchronized(identityLock) {
            if (!config.upload || !config.callLogEnabled) {
                config.callLogPending = false
                return if (!config.upload) "未启用本机采集" else "通话记录同步已关闭"
            }
            generation = config.captureGeneration
            callGeneration = config.callLogGeneration
            historyGeneration = config.historyGeneration
            boundary = config.callLogSince
            // Without history only calls from the moment the switch was turned on are read.
            since = if (config.historyEnabled) 0L else boundary
        }
        fun current() =
            config.upload &&
                config.callLogEnabled &&
                config.captureGeneration == generation &&
                config.callLogGeneration == callGeneration &&
                config.historyGeneration == historyGeneration
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            config.callLogPending = false
            return "缺少读取通话记录权限"
        }
        try {
            Store(context, databaseName).use { store ->
                if (store.callHistoryGeneration() != historyGeneration) store.restartCallScan(historyGeneration)
                val (count, more) =
                    scanCalls(store, ProviderCallSource(context), since, boundary, store.receiverResolver(), ::current)
                synchronized(identityLock) { if (current()) config.callLogPending = more }
                return "最近读取 ${formatTimestamp(System.currentTimeMillis())} · 新增 $count 条通话记录"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            config.callLogPending = false
            return "通话记录读取失败 · 不影响短信"
        }
    }

internal data class CallAck(val fingerprint: String, val error: String)

// Validate the whole response before touching the queue, correlating by fingerprint.
internal fun callAcks(batch: List<CallRecord>, acks: JSONArray): List<CallAck> {
    check(acks.length() == batch.size) { "上传确认数量不一致" }
    val expected = batch.map { it.fingerprint() }.toMutableList()
    return (0 until acks.length()).map { index ->
        val ack = acks.getJSONObject(index)
        val fingerprint = ack.get("fingerprint")
        check(fingerprint is String && expected.remove(fingerprint)) { "上传确认指纹不匹配" }
        val error = if (ack.has("error")) ack.get("error") else ""
        check(error is String && error in setOf("", "sim_not_confirmed", "invalid_call")) { "上传确认错误类型无效" }
        val id = ack.get("id")
        check(id is Int || id is Long) { "上传确认 ID 无效" }
        check(if (error.isEmpty()) (id as Number).toLong() > 0 else (id as Number).toLong() == 0L) { "上传确认字段冲突" }
        CallAck(fingerprint, error)
    }
}

// Uploads queued calls in batches of 100. Returns true when a bounded pass left more queued.
internal fun uploadCalls(
    api: Api,
    store: Store,
    ensure: (CallRecord) -> Unit,
    enabled: () -> Boolean = { true },
    maxBatches: Int = 5,
    request: (List<CallRecord>) -> JSONObject = { batch ->
        api.request(
            "/calls",
            "POST",
            JSONObject().put("calls", JSONArray().apply { batch.forEach { put(it.json()) } }),
        )
    },
): Boolean {
    repeat(maxBatches) {
        if (!enabled()) return false
        val batch = store.callUploadCandidates(CALL_PAGE)
        if (batch.isEmpty()) return false
        batch.filter { it.receiver.isNotEmpty() }.distinctBy { it.receiver to it.subscriptionId }.forEach(ensure)
        store.applyCallAcks(callAcks(batch, request(batch).getJSONArray("acks")))
    }
    return store.callUploadCandidates(1).isNotEmpty()
}

// ---- Local queue (schema v16) ----

// call_outbox holds captured calls until the hub confirms them; call_ledger remembers every
// fingerprint ever queued so a rescan (history switch, provider rebuild) never re-uploads.
internal fun createCallLog(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS call_outbox (fingerprint TEXT PRIMARY KEY,receiver TEXT NOT NULL,number TEXT NOT NULL,type TEXT NOT NULL,timestamp INTEGER NOT NULL,duration INTEGER NOT NULL,sub INTEGER NOT NULL,video INTEGER NOT NULL DEFAULT 0,presentation TEXT NOT NULL,location TEXT NOT NULL DEFAULT '',historical INTEGER NOT NULL DEFAULT 0,blocked TEXT NOT NULL DEFAULT '')"
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS call_outbox_time ON call_outbox(blocked,timestamp)")
    db.execSQL("CREATE TABLE IF NOT EXISTS call_ledger (fingerprint TEXT PRIMARY KEY)")
    db.execSQL("INSERT OR IGNORE INTO meta VALUES ('call_provider_id',0)")
    db.execSQL("INSERT OR IGNORE INTO meta VALUES ('call_history_generation',0)")
    db.execSQL("INSERT OR IGNORE INTO meta VALUES ('hub_call_log',0)")
}

private fun Store.callMeta(key: String): Long =
    readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

internal fun Store.callWatermark(): Long = callMeta("call_provider_id")

internal fun Store.callHistoryGeneration(): Long = callMeta("call_history_generation")

internal fun Store.hubSupportsCallLog(): Boolean = callMeta("hub_call_log") != 0L

internal fun Store.rememberHubCallLog(supported: Boolean) {
    writableDatabase.execSQL("UPDATE meta SET value=? WHERE key='hub_call_log'", arrayOf(if (supported) 1 else 0))
}

// Turning history on must revisit rows a dated scan stepped over; the ledger prevents repeats.
internal fun Store.restartCallScan(historyGeneration: Long) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.execSQL("UPDATE meta SET value=? WHERE key='call_history_generation'", arrayOf(historyGeneration))
        db.execSQL("UPDATE meta SET value=0 WHERE key='call_provider_id'")
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal fun Store.observeCallHead(head: Long) {
    if (head < callWatermark()) writableDatabase.execSQL("UPDATE meta SET value=0 WHERE key='call_provider_id'")
}

// Queues the new calls of one provider page and advances the watermark in the same transaction.
internal fun Store.captureCalls(records: List<CallRecord>, watermark: Long): Int {
    val db = writableDatabase
    var added = 0
    db.beginTransaction()
    try {
        for (record in records) {
            val fingerprint = record.fingerprint()
            val known =
                db.insertWithOnConflict(
                    "call_ledger",
                    null,
                    ContentValues().apply { put("fingerprint", fingerprint) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) == -1L
            if (known) continue
            val queued = db.insertWithOnConflict(
                "call_outbox",
                null,
                ContentValues().apply {
                    put("fingerprint", fingerprint)
                    put("receiver", record.receiver)
                    put("number", record.number)
                    put("type", record.type)
                    put("timestamp", record.timestamp)
                    put("duration", record.duration)
                    put("sub", record.subscriptionId)
                    put("video", if (record.video) 1 else 0)
                    put("presentation", record.presentation)
                    put("location", record.location)
                    put("historical", if (record.historical) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            // Still queued from before a ledger reset (new hub): not a second copy.
            if (queued != -1L) added++
        }
        db.execSQL("UPDATE meta SET value=MAX(value,?) WHERE key='call_provider_id'", arrayOf(watermark))
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    if (added > 0) Store.changes.value++
    return added
}

internal fun Store.callUploadCandidates(limit: Int): List<CallRecord> =
    readableDatabase
        .rawQuery(
            "SELECT receiver,number,type,timestamp,duration,sub,video,presentation,location,historical FROM call_outbox WHERE blocked='' ORDER BY timestamp,fingerprint LIMIT ?",
            arrayOf(limit.toString()),
        )
        .use { c ->
            buildList {
                while (c.moveToNext())
                    add(
                        CallRecord(
                            receiver = c.getString(0),
                            number = c.getString(1),
                            type = c.getString(2),
                            timestamp = c.getLong(3),
                            duration = c.getLong(4),
                            subscriptionId = c.getInt(5),
                            video = c.getInt(6) != 0,
                            presentation = c.getString(7),
                            location = c.getString(8),
                            historical = c.getInt(9) != 0,
                        )
                    )
            }
        }

// Confirmed (including duplicate) calls leave the queue. An unregistered line is retried as an
// unknown line, which keeps the same fingerprint; a call the hub calls invalid is parked.
internal fun Store.applyCallAcks(acks: List<CallAck>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        for (ack in acks)
            when (ack.error) {
                "" -> db.delete("call_outbox", "fingerprint=?", arrayOf(ack.fingerprint))
                "sim_not_confirmed" -> db.execSQL("UPDATE call_outbox SET receiver='' WHERE fingerprint=?", arrayOf(ack.fingerprint))
                else -> db.execSQL("UPDATE call_outbox SET blocked=? WHERE fingerprint=?", arrayOf(ack.error, ack.fingerprint))
            }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    Store.changes.value++
}

data class CallQueueCounts(val pending: Int, val blocked: Int)

internal fun Store.callQueueCounts(): CallQueueCounts =
    readableDatabase.rawQuery("SELECT COALESCE(SUM(blocked=''),0),COALESCE(SUM(blocked<>''),0) FROM call_outbox", null).use {
        it.moveToFirst()
        CallQueueCounts(it.getInt(0), it.getInt(1))
    }

// ---- Change trigger ----

// While the process lives, a call log change requests one capture pass within seconds; the
// periodic worker and every sync cover the rest.
internal object CallLogObserver {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var registered = false

    private val scan = Runnable {
        val context = app ?: return@Runnable
        val config = Config(context)
        if (config.upload && config.callLogEnabled) requestCapture(context)
    }

    private val observer by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(scan)
                handler.postDelayed(scan, INBOX_CHANGE_DEBOUNCE_MS)
            }
        }
    }

    // Idempotent; registration needs READ_CALL_LOG, so callers retry after a grant.
    fun ensure(context: Context) =
        synchronized(this) {
            app = context.applicationContext
            if (registered || !hasPermission(context, Manifest.permission.READ_CALL_LOG)) return@synchronized
            registered =
                runCatching {
                        context.applicationContext.contentResolver.registerContentObserver(
                            CallLog.Calls.CONTENT_URI,
                            true,
                            observer,
                        )
                        true
                    }
                    .getOrDefault(false)
        }
}
