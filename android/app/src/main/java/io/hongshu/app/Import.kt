package io.hongshu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONObject

private val inboxScanLock = Any()

fun catchUpInbox(context: Context): String =
    synchronized(identityLock) {
        synchronized(inboxScanLock) {
            if (!Config(context).upload) return "未启用本机采集"
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
                    PackageManager.PERMISSION_GRANTED
            )
                return "补扫不可用：缺少读取短信权限"
            refreshAutomaticSims(context)
            Store(context).use { store ->
                store.initializeHistoryBoundary(System.currentTimeMillis())
                val count = scanInbox(context, store, historical = false, incremental = true)
                return "短信读取正常 · 本次采集 $count 条（历史自动同步）"
            }
        }
    }

internal fun scanInbox(
    context: Context,
    store: Store,
    historical: Boolean,
    incremental: Boolean,
): Int {
    // Provider IDs, not SMS dates, order insertion; a late older date remains eligible.
    // Do not assume provider insertion order follows wall-clock dates.
    val selection = if (incremental) "_id>?" else null
    val args = if (incremental) arrayOf(store.providerWatermark().toString()) else null
    var count = 0
    val receiver = store.receiverResolver()
    val batch = mutableListOf<Pair<Long, SmsRecord>>()
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
            if (incremental && !Config(context).upload) break
            if (incremental && visited++ == 1000) break
            val sub = if (subIndex >= 0) c.getInt(subIndex) else -1
            val text = c.getString(body)
            val sender = c.getString(address)
            if (text.isNullOrEmpty() || sender.isNullOrEmpty()) {
                if (incremental) {
                    count += store.captureProviders(batch, true)
                    batch.clear()
                    store.advanceProvider(c.getLong(idIndex))
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
                        historical || isHistoricalSMS(c.getLong(date), store.historyBoundary()),
                )
            batch.add(c.getLong(idIndex) to record)
            if (batch.size == 100) {
                count += store.captureProviders(batch, incremental)
                batch.clear()
            }
        }
        count += store.captureProviders(batch, incremental)
        if (incremental) Config(context).inboxScanPending = visited > 1000
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
