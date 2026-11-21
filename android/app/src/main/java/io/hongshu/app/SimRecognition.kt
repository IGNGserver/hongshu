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
                store.updateSimMappings(mappings, automatic, authoritative = true)
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
                    val scannedSubs = scanned.map { it.sub }.toSet()
                    // A pulled card is only visible as a stored row missing from the scan, so
                    // it would never trigger the per-row comparison below on its own.
                    val removed = stored.any { it.sub !in scannedSubs }
                    if (
                        removed ||
                            merged.any { next -> stored.find { it.sub == next.sub } != next }
                    ) {
                        store.updateSimMappings(
                            merged,
                            automatic = true,
                            authoritative = scanned.isNotEmpty(),
                        )
                    }
                    merged
                }
            config.singleSimSubscription =
                active.singleOrNull()?.takeIf { confirmedNumber(it.phone) }?.sub ?: -1
            config.simRecognitionHealthy = true
        }
    }

// Manual mode only writes numbers when the user saves, but a card pulled from the tray must
// still stop claiming a slot on the home screen and in the pending-assignment list. This
// deletes mappings for vanished subscriptions only; saved numbers for surviving cards are
// left exactly as they are. Call it when the app becomes visible and when permissions change.
fun reconcileRemovedSims(context: Context) =
    synchronized(simLock) {
        val config = Config(context)
        if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) return@synchronized
        val scanned = runCatching { scanSims(context) }.getOrNull() ?: return@synchronized
        // An empty result is not authoritative: telephony can be briefly unavailable.
        if (scanned.isEmpty()) return@synchronized
        val generation = config.captureGeneration
        synchronized(identityLock) {
            if (config.captureGeneration != generation) return@synchronized
            val remaining =
                Store(context).use { store ->
                    if (store.pruneInactiveSimMappings(scanned.map { it.sub }.toSet()) == 0) null
                    else store.sims()
                } ?: return@synchronized
            config.simRecognitionHealthy = true
            config.singleSimSubscription =
                remaining.singleOrNull()?.takeIf { confirmedNumber(it.phone) }?.sub ?: -1
        }
        // A removed card may have been the only thing keeping single-SIM fallback off.
        Store(context).use { it.resolvePendingFallback() }
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
