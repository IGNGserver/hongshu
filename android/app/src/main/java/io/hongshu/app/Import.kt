package io.hongshu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

private val inboxScanLock = Any()

// A bounded tail read independent of the reliable forward scan and its lock. It neither
// advances scan watermarks nor queries SIM services: old/backlogged rows remain for the
// maintenance worker and exact cached subscription mappings are the only realtime identity.
internal fun captureRecentInbox(
    context: Context,
    cancellation: android.os.CancellationSignal,
    databaseName: String = "hongshu.db",
    now: Long = System.currentTimeMillis(),
): Int {
    val config = Config(context)
    val generation = config.captureGeneration
    val recoveryGeneration = config.recoveryGeneration
    val historyGeneration = config.historyGeneration
    fun current() = config.upload && config.captureGeneration == generation &&
        config.recoveryGeneration == recoveryGeneration && config.historyGeneration == historyGeneration &&
        (config.recoveryEnabled || config.historyEnabled)
    if (!current() || !hasPermission(context, Manifest.permission.READ_SMS)) return 0
    return Store(context, databaseName).use { store ->
        val since = if (config.recoveryEnabled) config.recoverySince else
            store.historyBoundary().takeIf { it > 0 } ?: store.captureSince().takeIf { it > 0 } ?: now
        if (since <= 0) return@use 0
        val receiver = store.receiverResolver(allowFallback = false)
        fun query(withSub: Boolean) = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            if (withSub) arrayOf("_id", "address", "body", "date", "sub_id")
                else arrayOf("_id", "address", "body", "date"),
            "date>=?", arrayOf(since.toString()), "_id DESC", cancellation,
        )
        val cursor = try { query(true) } catch (_: IllegalArgumentException) { query(false) }
            ?: error("SMS provider unavailable")
        val rows = cursor.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val address = c.getColumnIndexOrThrow("address")
            val body = c.getColumnIndexOrThrow("body")
            val date = c.getColumnIndexOrThrow("date")
            val subIndex = c.getColumnIndex("sub_id")
            buildList {
                var visited = 0
                while (visited < 100 && c.moveToNext()) {
                    visited++
                    cancellation.throwIfCanceled()
                    val sender = c.getString(address)
                    val text = c.getString(body)
                    if (sender.isNullOrEmpty() || text.isNullOrEmpty()) continue
                    // Providers may ignore selection; never cross the consent boundary.
                    if (c.getLong(date) < since) continue
                    val sub = if (subIndex >= 0) c.getInt(subIndex) else -1
                    add(c.getLong(id) to SmsRecord(receiver(sub), sender, text, c.getLong(date), sub))
                }
            }
        }
        synchronized(identityLock) {
            if (!current()) return@synchronized 0
            store.captureProviders(rows, advance = false).also {
                store.recordCaptureEvent(CaptureSource.RECOVERY, "added", it)
            }
        }
    }
}

fun recoverRecentInbox(context: Context, databaseName: String = "hongshu.db"): String =
    synchronized(inboxScanLock) {
        val config = Config(context)
        val generation: Long
        val recoveryGeneration: Long
        val since: Long
        synchronized(identityLock) {
            if (!config.upload || !config.recoveryEnabled) {
                config.recoveryState = HistoryState.DISABLED
                config.recoveryPending = false
                return "新短信补漏已关闭"
            }
            generation = config.captureGeneration
            recoveryGeneration = config.recoveryGeneration
            since = config.recoverySince
        }
        fun current() =
            config.upload &&
                config.recoveryEnabled &&
                config.captureGeneration == generation &&
                config.recoveryGeneration == recoveryGeneration
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            synchronized(identityLock) {
                if (current()) {
                    config.recoveryState = HistoryState.NO_PERMISSION
                    config.recoveryPending = false
                }
            }
            return "缺少读取短信权限 · 无法补漏未收到广播的新短信"
        }
        try {
            check(since > 0) { "Missing recovery consent boundary" }
            synchronized(identityLock) {
                if (!current()) return "新短信补漏已取消"
                config.recoveryState = HistoryState.SCANNING
            }
            Store(context, databaseName).use { store ->
                val count =
                    scanInbox(
                        context,
                        store,
                        generation = generation,
                        recoverySince = since,
                        recoveryGeneration = recoveryGeneration,
                    )
                synchronized(identityLock) {
                    if (!current()) return "新短信补漏已取消"
                    config.recoveryState =
                        if (config.recoveryPending) HistoryState.SCANNING else HistoryState.COMPLETE
                }
                return "最近补漏 ${formatTimestamp(System.currentTimeMillis())} · 新增 $count 条（仅系统可访问的新短信）"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(identityLock) {
                if (current()) {
                    config.recoveryState = HistoryState.FAILED
                    config.recoveryPending = false
                }
            }
            return "收件箱补漏查询失败 · 请检查系统读取/验证码权限"
        }
    }

fun catchUpInbox(context: Context, databaseName: String = "hongshu.db"): String =
    synchronized(inboxScanLock) {
        val config = Config(context)
        val generation: Long
        val historyGeneration: Long
        synchronized(identityLock) {
            if (!config.upload || !config.historyEnabled) {
                config.historyState = HistoryState.DISABLED
                config.inboxScanPending = false
                return if (!config.upload) "未启用本机采集" else "历史读取已关闭"
            }
            generation = config.captureGeneration
            historyGeneration = config.historyGeneration
        }
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            config.historyState = HistoryState.NO_PERMISSION
            config.inboxScanPending = false
            return "补扫不可用：缺少读取短信权限"
        }
        fun current() =
            config.upload &&
                config.historyEnabled &&
                config.captureGeneration == generation &&
                config.historyGeneration == historyGeneration
        try {
            synchronized(identityLock) {
                if (!current()) return "历史扫描已取消"
                config.historyState = HistoryState.SCANNING
            }
            Store(context, databaseName).use { store ->
                synchronized(identityLock) {
                    if (!current()) return "历史扫描已取消"
                    store.initializeHistoryBoundary(System.currentTimeMillis())
                }
                val count =
                    scanInbox(
                        context,
                        store,
                        generation = generation,
                        historyGeneration = historyGeneration,
                    )
                synchronized(identityLock) {
                    if (!current()) return "历史扫描已取消"
                    config.historyState =
                        if (config.inboxScanPending) HistoryState.SCANNING
                        else HistoryState.COMPLETE
                    config.lastHistoryScan = System.currentTimeMillis()
                }
                return "最近查询 ${formatTimestamp(System.currentTimeMillis())} · 新增 $count 条（仅系统可访问收件箱，0 条不代表无短信）"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(identityLock) {
                if (current()) {
                    config.historyState = HistoryState.FAILED
                    config.inboxScanPending = false
                }
            }
            return "历史查询失败 · 不影响实时接收和已保存队列"
        }
    }

// A wiped or restored SMS database can reuse low provider ids. Comparing the current head
// with the scan watermark detects that rewrite without scanning the whole inbox; a provider
// that keeps its highest id but replaces every row is out of scope and needs an explicit
// full re-check. Runs on the maintenance worker, not on the per-scan path.
internal fun verifyProviderRebuild(context: Context, store: Store) {
    if (maxOf(store.providerWatermark(), store.recoveryWatermark()) <= 0) return
    val head =
        try {
            context.contentResolver
                .query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), null, null, "_id DESC")
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) {
            null
        } ?: return
    store.observeProviderHead(head)
}

internal fun scanInbox(
    context: Context,
    store: Store,
    generation: Long = Config(context).captureGeneration,
    historyGeneration: Long = Config(context).historyGeneration,
    recoverySince: Long? = null,
    recoveryGeneration: Long = Config(context).recoveryGeneration,
): Int {
    val config = Config(context)
    fun current() =
        config.upload &&
            config.captureGeneration == generation &&
            (if (recoverySince != null)
                config.recoveryEnabled && config.recoveryGeneration == recoveryGeneration
            else config.historyEnabled && config.historyGeneration == historyGeneration)
    if (!current()) return 0
    val incomplete = store.incompleteProviderIds()
    // Provider IDs, not SMS dates, order insertion; a late older date remains eligible.
    // Do not assume provider insertion order follows wall-clock dates. Incomplete rows are
    // revisited by id in rotation; the watermark still advances so they cannot block later
    // complete messages.
    val retryClause =
        if (incomplete.isEmpty()) "" else " OR _id IN (${incomplete.joinToString(",")})"
    val selection =
        if (recoverySince != null)
            (if (incomplete.isEmpty()) "_id>? AND date>=?" else "(_id>?$retryClause) AND date>=?")
        else (if (incomplete.isEmpty()) "_id>?" else "(_id>?$retryClause)")
    val args =
        if (recoverySince != null)
            arrayOf(store.recoveryWatermark().toString(), recoverySince.toString())
        else arrayOf(store.providerWatermark().toString())
    var count = 0
    val receiver = store.receiverResolver()
    val batch = mutableListOf<Pair<Long, SmsRecord>>()
    fun flush(): Boolean =
        synchronized(identityLock) {
            if (!current()) return@synchronized false
            val added =
                store.captureProviders(
                    batch,
                    recoverySince == null,
                    recovery = recoverySince != null,
                )
            store.recordCaptureEvent(
                if (recoverySince != null) CaptureSource.RECOVERY else CaptureSource.HISTORY, "added", added)
            count += added
            batch.clear()
            true
        }
    val cursor = try {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "sub_id"),
            selection,
            args,
            "_id ASC",
        )
    } catch (_: IllegalArgumentException) {
        // Some vendor providers do not expose sub_id; keep the source unresolved.
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("_id", "address", "body", "date"),
            selection,
            args,
            "_id ASC",
        )
    } ?: error("SMS provider unavailable")
    cursor.use { c ->
        val idIndex = c.getColumnIndexOrThrow("_id")
        val address = c.getColumnIndexOrThrow("address")
        val body = c.getColumnIndexOrThrow("body")
        val date = c.getColumnIndexOrThrow("date")
        val subIndex = c.getColumnIndex("sub_id")
        var visited = 0
        while (c.moveToNext()) {
            if (!current()) break
            if (visited++ == 1000) break
            val sub = if (subIndex >= 0) c.getInt(subIndex) else -1
            val text = c.getString(body)
            val sender = c.getString(address)
            if (text.isNullOrEmpty() || sender.isNullOrEmpty()) {
                if (!flush()) break
                synchronized(identityLock) {
                    if (current()) {
                        store.rememberIncompleteProvider(c.getLong(idIndex))
                        if (recoverySince != null)
                            store.advanceRecoveryProvider(c.getLong(idIndex))
                        else store.advanceProvider(c.getLong(idIndex))
                    }
                }
                continue
            }
            val record =
                SmsRecord(
                    receiver(sub),
                    sender,
                    text,
                    c.getLong(date),
                    sub,
                    historical =
                        recoverySince == null &&
                            isHistoricalSMS(c.getLong(date), store.historyBoundary()),
                )
            batch.add(c.getLong(idIndex) to record)
            if (batch.size == 100) {
                if (!flush()) break
            }
        }
        flush()
        synchronized(identityLock) {
            if (current()) {
                if (recoverySince != null) config.recoveryPending = visited > 1000
                else config.inboxScanPending = visited > 1000
            }
        }
    }
    return count
}

// Telephony.Sms.TYPE values a sending app writes. Only a sent row is a message that left the
// phone; outbox/queued rows are still sending and failed rows may be retried under the same id.
internal enum class SentRow { CAPTURE, PENDING, SKIP }

internal fun classifySentRow(type: Int): SentRow =
    when (type) {
        Telephony.Sms.MESSAGE_TYPE_SENT -> SentRow.CAPTURE
        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        Telephony.Sms.MESSAGE_TYPE_QUEUED,
        Telephony.Sms.MESSAGE_TYPE_FAILED -> SentRow.PENDING
        else -> SentRow.SKIP
    }

private const val SENT_TYPES = "type IN (2,4,5,6)"

// The recipient written the way this phone already files the conversation: the inbox row of
// the same provider thread, else a sender already queued or synced, else the stored address.
internal fun sentRecipient(address: String, threadAddress: String?, known: () -> String?): String =
    threadAddress?.takeIf { sameSender(it, address) } ?: known() ?: normalizeSimNumber(address)

// Sent messages are not broadcast and post no notification to other apps, so the system SMS
// database is the only source. Default SMS apps write the row; READ_SMS reads it. Older rows
// (before the switch was first seen) are read only when history import is on.
fun scanSentBox(context: Context, databaseName: String = "hongshu.db"): String =
    synchronized(inboxScanLock) {
        val config = Config(context)
        val generation: Long
        val outgoingGeneration: Long
        val historyGeneration: Long
        val since: Long
        val boundary: Long
        synchronized(identityLock) {
            if (!config.upload || !config.outgoingEnabled) {
                config.outgoingPending = false
                return if (!config.upload) "未启用本机采集" else "已发送短信同步已关闭"
            }
            generation = config.captureGeneration
            outgoingGeneration = config.outgoingGeneration
            historyGeneration = config.historyGeneration
            boundary = config.outgoingSince
            since = if (config.historyEnabled) 0L else boundary
        }
        fun current() =
            config.upload &&
                config.outgoingEnabled &&
                config.captureGeneration == generation &&
                config.outgoingGeneration == outgoingGeneration &&
                config.historyGeneration == historyGeneration
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            config.outgoingPending = false
            return "缺少读取短信权限 · 无法读取已发送短信"
        }
        try {
            Store(context, databaseName).use { store ->
                // Turning history on must revisit rows the dated scan stepped over.
                if (store.sentHistoryGeneration() != historyGeneration)
                    store.restartSentScan(historyGeneration)
                val count = scanSent(context, store, since, boundary, ::current)
                return "最近读取 ${formatTimestamp(System.currentTimeMillis())} · 新增 $count 条已发送短信"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            config.outgoingPending = false
            return "已发送短信读取失败 · 不影响接收短信"
        }
    }

private fun scanSent(
    context: Context,
    store: Store,
    since: Long,
    boundary: Long,
    current: () -> Boolean,
): Int {
    if (!current()) return 0
    val resolver = context.contentResolver
    val head =
        // Head of the whole table: deleting the newest sent message alone must not restart.
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, "_id DESC")
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            ?: error("SMS provider unavailable")
    store.observeSentHead(head)
    val retry = store.incompleteSentIds()
    val idClause = if (retry.isEmpty()) "_id>?" else "(_id>? OR _id IN (${retry.joinToString(",")}))"
    val selection = "$SENT_TYPES AND $idClause AND date>=?"
    val args = arrayOf(store.sentWatermark().toString(), since.toString())
    val cursor =
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type", "thread_id", "sub_id"),
                selection,
                args,
                "_id ASC",
            )
        } catch (_: IllegalArgumentException) {
            // Some vendor providers do not expose sub_id; keep the line unresolved.
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type", "thread_id"),
                selection,
                args,
                "_id ASC",
            )
        } ?: error("SMS provider unavailable")
    val receiver = store.receiverResolver()
    val threads = mutableMapOf<Long, String?>()
    fun threadAddress(thread: Long): String? =
        threads.getOrPut(thread) {
            if (thread <= 0) null
            else
                runCatching {
                        resolver.query(
                            Telephony.Sms.Inbox.CONTENT_URI,
                            arrayOf("address"),
                            "thread_id=?",
                            arrayOf(thread.toString()),
                            "_id DESC",
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }
                    .getOrNull()
        }
    var count = 0
    val batch = mutableListOf<Pair<Long, SmsRecord>>()
    val pending = mutableListOf<Long>()
    val ignored = mutableListOf<Long>()
    fun flush(): Boolean =
        synchronized(identityLock) {
            if (!current()) return@synchronized false
            store.skipSentRows(pending, ignored)
            count += store.captureSent(batch)
            batch.clear()
            pending.clear()
            ignored.clear()
            true
        }
    cursor.use { c ->
        val idIndex = c.getColumnIndexOrThrow("_id")
        val addressIndex = c.getColumnIndexOrThrow("address")
        val bodyIndex = c.getColumnIndexOrThrow("body")
        val dateIndex = c.getColumnIndexOrThrow("date")
        val typeIndex = c.getColumnIndexOrThrow("type")
        val threadIndex = c.getColumnIndexOrThrow("thread_id")
        val subIndex = c.getColumnIndex("sub_id")
        var visited = 0
        var stopped = false
        while (c.moveToNext()) {
            if (!current()) {
                stopped = true
                break
            }
            if (visited++ == 1000) break
            val id = c.getLong(idIndex)
            val address = c.getString(addressIndex)
            val body = c.getString(bodyIndex)
            when (classifySentRow(c.getInt(typeIndex))) {
                SentRow.PENDING -> pending += id
                SentRow.SKIP -> ignored += id
                SentRow.CAPTURE ->
                    if (address.isNullOrBlank() || body.isNullOrEmpty()) ignored += id
                    else {
                        val sub = if (subIndex >= 0 && !c.isNull(subIndex)) c.getInt(subIndex) else -1
                        val date = c.getLong(dateIndex)
                        val recipient =
                            sentRecipient(
                                address,
                                threadAddress(c.getLong(threadIndex)),
                                { store.knownSenderFor(address) },
                            )
                        batch +=
                            id to
                                SmsRecord(
                                    receiver(sub),
                                    recipient,
                                    body,
                                    date,
                                    sub,
                                    historical = date < boundary,
                                    outgoing = true,
                                )
                    }
            }
            if (batch.size + pending.size + ignored.size >= 100 && !flush()) {
                stopped = true
                break
            }
        }
        if (!stopped) flush()
        synchronized(identityLock) {
            if (current()) Config(context).outgoingPending = visited > 1000
        }
    }
    return count
}
