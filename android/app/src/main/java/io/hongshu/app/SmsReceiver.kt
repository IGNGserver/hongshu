package io.hongshu.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors

internal data class IncomingSms(
    val sender: String,
    val body: String,
    val timestamp: Long,
    val subscription: Int,
    val slot: Int,
)

internal fun parseIncomingSms(intent: Intent): IncomingSms? {
    val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return null
    if (parts.isEmpty()) return null
    fun hint(keys: List<String>): Int =
        keys.firstNotNullOfOrNull { key ->
            if (intent.hasExtra(key)) intent.getIntExtra(key, -1).takeIf { it >= 0 } else null
        } ?: -1
    val first = parts.first()
    return IncomingSms(
        first.displayOriginatingAddress ?: first.originatingAddress ?: "未知发件人",
        combineParts(parts.map { it.messageBody ?: "" }),
        first.timestampMillis,
        hint(listOf("subscription", "subscription_id", "sub_id")),
        hint(listOf("slot", "slot_id", "simSlot")),
    )
}

// Shared by the receiver and device tests. Never queries the SMS provider or SIM service.
internal fun persistIncomingSms(
    context: Context,
    message: IncomingSms,
    generation: Long,
    receivedAt: Long,
    databaseName: String = "hongshu.db",
): Boolean =
    synchronized(identityLock) {
        val config = Config(context)
        if (
            !config.upload ||
                config.captureGeneration != generation ||
                !hasPermission(context, Manifest.permission.RECEIVE_SMS)
        )
            return@synchronized false
        Store(context, databaseName).use { store ->
            store.queueRealtime(
                SmsRecord(
                    store.realtimeReceiver(message.subscription),
                    message.sender,
                    message.body,
                    message.timestamp,
                    message.subscription,
                ),
                message.slot,
                receivedAt,
            )
        }
        config.captureFailure = CaptureFailure.NONE
        true
    }

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext
        val generation =
            synchronized(identityLock) {
                val config = Config(app)
                if (!config.upload || !hasPermission(app, Manifest.permission.RECEIVE_SMS)) return
                config.captureGeneration
            }
        val receivedAt = System.currentTimeMillis()
        val message =
            try {
                parseIncomingSms(intent) ?: error("Empty SMS broadcast")
            } catch (_: Exception) {
                synchronized(identityLock) {
                    if (Config(app).captureGeneration == generation)
                        Config(app).captureFailure = CaptureFailure.PARSE
                }
                return
            }
        val pending = goAsync()
        executor.execute {
            try {
                if (persistIncomingSms(app, message, generation, receivedAt)) syncNow(app)
            } catch (_: Exception) {
                synchronized(identityLock) {
                    if (Config(app).captureGeneration == generation)
                        Config(app).captureFailure = CaptureFailure.STORAGE
                }
            } finally {
                pending.finish()
            }
        }
    }
}
