package io.hongshu.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

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
        // Framework extras first (SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX /
        // EXTRA_SLOT_INDEX), then the legacy and vendor spellings.
        hint(
            listOf(
                "android.telephony.extra.SUBSCRIPTION_INDEX",
                "subscription",
                "subscription_id",
                "sub_id",
            )
        ),
        hint(listOf("android.telephony.extra.SLOT_INDEX", "slot", "slot_id", "simSlot")),
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

internal suspend fun saveAndScheduleIncomingSms(
    context: Context,
    message: IncomingSms,
    generation: Long,
    receivedAt: Long,
    databaseName: String = "hongshu.db",
    enqueue: suspend () -> Unit = { syncNow(context, manual = true).await() },
) {
    fun failure(value: CaptureFailure) =
        synchronized(identityLock) {
            if (Config(context).captureGeneration == generation)
                Config(context).captureFailure = value
        }
    val saved =
        try {
            persistIncomingSms(context, message, generation, receivedAt, databaseName)
        } catch (_: Exception) {
            failure(CaptureFailure.STORAGE)
            return
        }
    if (!saved) return
    try {
        enqueue()
    } catch (_: Exception) {
        // The SMS is already durable. A scheduling failure must not report lost storage.
        failure(CaptureFailure.SCHEDULING)
    }
}

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext
        val receivedAt = System.currentTimeMillis()
        Config(app).noteReceivedBroadcast(receivedAt)
        val generation =
            synchronized(identityLock) {
                val config = Config(app)
                if (!config.upload || !hasPermission(app, Manifest.permission.RECEIVE_SMS)) return
                config.captureGeneration
            }
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
                runBlocking { saveAndScheduleIncomingSms(app, message, generation, receivedAt) }
            } finally {
                pending.finish()
            }
        }
    }
}
