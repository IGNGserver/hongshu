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
    var count = 0
    store.use {
        context.contentResolver
            .query(Telephony.Sms.Inbox.CONTENT_URI, null, null, null, "date ASC")
            ?.use { c ->
                val address = c.getColumnIndexOrThrow("address")
                val body = c.getColumnIndexOrThrow("body")
                val date = c.getColumnIndexOrThrow("date")
                val subIndex = c.getColumnIndex("sub_id")
                val sentIndex = c.getColumnIndex("date_sent")
                while (c.moveToNext()) {
                    val sub = if (subIndex >= 0) c.getInt(subIndex) else -1
                    val text = c.getString(body) ?: continue
                    val sender = c.getString(address) ?: continue
                    if (text.isEmpty()) continue
                    store.queue(
                        SmsRecord(
                            store.receiver(sub, -1),
                            sender,
                            text,
                            if (sentIndex >= 0 && c.getLong(sentIndex) > 0) c.getLong(sentIndex)
                            else c.getLong(date),
                            sub,
                            historical = true,
                        )
                    )
                    count++
                }
            }
    }
    syncNow(context)
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
                api.request("/contacts", "PUT", JSONObject().put("phone", phone).put("name", name))
                count++
            }
        }
    return count
}
