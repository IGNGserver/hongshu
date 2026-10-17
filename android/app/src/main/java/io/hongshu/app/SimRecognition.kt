package io.hongshu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
fun scanSims(context: Context): List<SimMapping> {
    check(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    ) {
        "缺少 SIM 识别权限"
    }
    val manager = context.getSystemService(SubscriptionManager::class.java)
    return manager.activeSubscriptionInfoList
        .orEmpty()
        .map { sim ->
            val number =
                if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_NUMBERS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        if (Build.VERSION.SDK_INT >= 33) manager.getPhoneNumber(sim.subscriptionId)
                        else sim.number.orEmpty()
                    } catch (_: SecurityException) {
                        ""
                    }
                } else ""
            val normalized = normalizeSimNumber(number)
            SimMapping(
                sim.subscriptionId,
                sim.simSlotIndex,
                normalized.takeIf(::confirmedNumber).orEmpty(),
                "SIM${sim.simSlotIndex + 1}",
            )
        }
        .sortedBy { it.slot }
}

private val simLock = Any()

fun saveSimSelection(context: Context, automatic: Boolean, mappings: List<SimMapping>) =
    synchronized(simLock) {
        val config = Config(context)
        synchronized(identityLock) {
            Store(context).use { store ->
                store.updateSimMappings(mappings, automatic)
                store.reconcileNotificationCaptures()
                store.reconcileUnknownSimCaptures()
            }
            config.automaticSim = automatic
            config.simRecognitionHealthy = true
            config.singleSimSubscription =
                mappings.singleOrNull()?.takeIf { confirmedNumber(it.phone) }?.sub ?: -1
        }
        // The user is present and SIM services are allowed here; resolve SIM-less rows now
        // instead of waiting for the next maintenance run.
        Store(context).use { it.resolvePendingFallback() }
    }

fun refreshAutomaticSims(context: Context) =
    synchronized(simLock) {
        val config = Config(context)
        val generation = config.captureGeneration
        if (!config.automaticSim) return@synchronized
        config.simRecognitionHealthy = false
        if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
            config.singleSimSubscription = -1
            return@synchronized
        }
        val scanned = scanSims(context)
        synchronized(identityLock) {
            if (config.captureGeneration != generation) return@synchronized
            val active =
                Store(context).use { store ->
                    val stored = store.sims()
                    val merged = mergeAutomaticSims(stored, scanned)
                    if (merged.any { next -> stored.find { it.sub == next.sub } != next }) {
                        store.updateSimMappings(merged, automatic = true)
                    }
                    merged
                }
            config.singleSimSubscription =
                active.singleOrNull()?.takeIf { confirmedNumber(it.phone) }?.sub ?: -1
            config.simRecognitionHealthy = true
        }
    }

// Registered again after permissions are granted; periodic scans also cover process death
// and carriers that update the number without a subscription-change callback.
object SimMonitor {
    private var listener: SubscriptionManager.OnSubscriptionsChangedListener? = null

    @Synchronized
    @Suppress("DEPRECATION")
    fun start(context: Context) {
        val app = context.applicationContext
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { start(app) }
            return
        }
        if (
            listener != null ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val next =
            object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    syncNow(app)
                }
            }
        app.getSystemService(SubscriptionManager::class.java)
            .addOnSubscriptionsChangedListener(next)
        listener = next
    }
}
