package io.hongshu.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !Config(context).upload)
            return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val pending = goAsync()
        executor.execute {
            try {
                val store = Store(context)
                store.use {
                    val extras = intent.extras
                    var sub =
                        listOf("subscription", "subscription_id", "sub_id").firstNotNullOfOrNull {
                            key ->
                            if (extras?.containsKey(key) == true)
                                extras.getInt(key, -1).takeIf { it >= 0 }
                            else null
                        } ?: -1
                    val slot =
                        listOf("slot", "slot_id", "simSlot").firstNotNullOfOrNull { key ->
                            if (extras?.containsKey(key) == true)
                                extras.getInt(key, -1).takeIf { it >= 0 }
                            else null
                        } ?: -1
                    if (
                        sub < 0 &&
                            slot >= 0 &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_PHONE_STATE,
                            ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        sub =
                            context
                                .getSystemService(SubscriptionManager::class.java)
                                .activeSubscriptionInfoList
                                ?.find { it.simSlotIndex == slot }
                                ?.subscriptionId ?: -1
                    }
                    val first = parts.first()
                    val sender =
                        first.displayOriginatingAddress ?: first.originatingAddress ?: "未知发件人"
                    store.queue(
                        SmsRecord(
                            store.receiver(sub, slot),
                            sender,
                            combineParts(parts.map { it.messageBody ?: "" }),
                            first.timestampMillis,
                            sub,
                        ),
                        slot,
                    )
                    syncNow(context)
                }
            } catch (_: Exception) {
                Config(context).status = "短信采集失败，请检查本地存储/权限"
            } finally {
                pending.finish()
            }
        }
    }
}
