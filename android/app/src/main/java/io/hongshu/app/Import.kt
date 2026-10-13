package io.hongshu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONObject

private val inboxScanLock = Any()

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
                        historical = false,
                        incremental = true,
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
                        historical = false,
                        incremental = true,
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

internal fun scanInbox(
    context: Context,
    store: Store,
    historical: Boolean,
    incremental: Boolean,
    generation: Long = Config(context).captureGeneration,
    historyGeneration: Long = Config(context).historyGeneration,
    recoverySince: Long? = null,
    recoveryGeneration: Long = Config(context).recoveryGeneration,
): Int {
    val config = Config(context)
    fun current() =
        !incremental ||
            (config.upload &&
                config.captureGeneration == generation &&
                (if (recoverySince != null)
                    config.recoveryEnabled && config.recoveryGeneration == recoveryGeneration
                else config.historyEnabled && config.historyGeneration == historyGeneration))
    if (!current()) return 0
    // Provider IDs, not SMS dates, order insertion; a late older date remains eligible.
    // Do not assume provider insertion order follows wall-clock dates.
    val selection =
        if (!incremental) null else if (recoverySince != null) "_id>? AND date>=?" else "_id>?"
    val args =
        if (!incremental) null
        else if (recoverySince != null)
            arrayOf(store.recoveryWatermark().toString(), recoverySince.toString())
        else arrayOf(store.providerWatermark().toString())
    var count = 0
    val receiver = store.receiverResolver()
    val batch = mutableListOf<Pair<Long, SmsRecord>>()
    fun flush(): Boolean =
        synchronized(identityLock) {
            if (!current()) return@synchronized false
            count +=
                store.captureProviders(
                    batch,
                    incremental && recoverySince == null,
                    recovery = recoverySince != null,
                )
            batch.clear()
            true
        }
    val cursor =
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            null,
            selection,
            args,
            "_id ASC",
        ) ?: error("SMS provider unavailable")
    cursor.use { c ->
        val idIndex = c.getColumnIndexOrThrow("_id")
        val address = c.getColumnIndexOrThrow("address")
        val body = c.getColumnIndexOrThrow("body")
        val date = c.getColumnIndexOrThrow("date")
        val subIndex = c.getColumnIndex("sub_id")
        var visited = 0
        while (c.moveToNext()) {
            if (!current()) break
            if (incremental && visited++ == 1000) break
            val sub = if (subIndex >= 0) c.getInt(subIndex) else -1
            val text = c.getString(body)
            val sender = c.getString(address)
            if (text.isNullOrEmpty() || sender.isNullOrEmpty()) {
                if (incremental) {
                    if (!flush()) break
                    synchronized(identityLock) {
                        if (current()) {
                            if (recoverySince != null)
                                store.advanceRecoveryProvider(c.getLong(idIndex))
                            else store.advanceProvider(c.getLong(idIndex))
                        }
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
                        historical ||
                            (recoverySince == null &&
                                isHistoricalSMS(c.getLong(date), store.historyBoundary())),
                )
            batch.add(c.getLong(idIndex) to record)
            if (batch.size == 100) {
                if (!flush()) break
            }
        }
        flush()
        synchronized(identityLock) {
            if (incremental && current()) {
                if (recoverySince != null) config.recoveryPending = visited > 1000
                else config.inboxScanPending = visited > 1000
            }
        }
    }
    return count
}

fun importContacts(context: Context): Int {
    check(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    ) {
        "请先授予通讯录权限"
    }
    val api = Api(Config(context))
    var count = 0
    val pendingBatch = org.json.JSONArray()
    context.contentResolver
        .query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )
        ?.use { c ->
            while (c.moveToNext()) {
                val phone = c.getString(0) ?: c.getString(1) ?: continue
                val name = c.getString(2) ?: continue
                pendingBatch.put(JSONObject().put("phone", phone).put("name", name))
                count++
                if (pendingBatch.length() >= 500) {
                    api.request("/contacts", "PUT", JSONObject().put("contacts", pendingBatch))
                    while (pendingBatch.length() > 0) pendingBatch.remove(0)
                }
            }
        }
    if (pendingBatch.length() > 0) {
        api.request("/contacts", "PUT", JSONObject().put("contacts", pendingBatch))
    }
    return count
}
