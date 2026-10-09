package io.hongshu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONObject

fun importHistory(context: Context): Int {
    check(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
    ) {
        "请先授予历史短信读取权限"
    }
    val store = Store(context)
    val count = store.use { scanInbox(context, store, historical = true, incremental = false) }
    syncNow(context)
    return count
}

fun catchUpInbox(context: Context) {
    if (
        !Config(context).upload ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
                PackageManager.PERMISSION_GRANTED
    )
        return
    val store = Store(context)
    store.use { scanInbox(context, store, historical = false, incremental = true) }
}

internal fun scanInbox(
    context: Context,
    store: Store,
    historical: Boolean,
    incremental: Boolean,
): Int {
    val mark = if (incremental) store.inboxWatermark() else 0L to 0L
    val floor = if (incremental) store.captureSince() else 0L
    val selection = if (incremental) "date>=? AND (date>? OR (date=? AND _id>?))" else null
    val args =
        if (incremental)
            arrayOf(
                floor.toString(),
                mark.first.toString(),
                mark.first.toString(),
                mark.second.toString(),
            )
        else null
    var count = 0
    context.contentResolver
        .query(Telephony.Sms.Inbox.CONTENT_URI, null, selection, args, "date ASC, _id ASC")
        ?.use { c ->
            val idIndex = c.getColumnIndexOrThrow("_id")
            val address = c.getColumnIndexOrThrow("address")
            val body = c.getColumnIndexOrThrow("body")
            val date = c.getColumnIndexOrThrow("date")
            val subIndex = c.getColumnIndex("sub_id")
            while (c.moveToNext()) {
                val sub = if (subIndex >= 0) c.getInt(subIndex) else -1
                val text = c.getString(body) ?: continue
                val sender = c.getString(address) ?: continue
                if (text.isEmpty()) continue
                val record =
                    SmsRecord(
                        store.receiver(sub, -1),
                        sender,
                        text,
                        c.getLong(date),
                        sub,
                        historical = historical,
                    )
                if (!store.hasNearDuplicate(record)) store.queue(record)
                if (incremental) store.advanceInboxWatermark(c.getLong(date), c.getLong(idIndex))
                count++
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
