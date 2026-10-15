package io.hongshu.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HongshuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        channels(this)
        schedule(this)
        SimMonitor.start(this)
        SmsInboxObserver.ensure(this)
    }
}

fun channels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel("messages", "跨设备短信", NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
    )
    manager.createNotificationChannel(
        NotificationChannel("connection", "实时同步连接", NotificationManager.IMPORTANCE_LOW)
    )
}

private val syncSchedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val schedulingLock = Any()

// `manual` marks triggers someone is waiting for: a user action, a newly captured SMS or a
// hub change notice. They replace work parked in failure backoff or a delayed retry instead
// of waiting behind it; a running worker is never cancelled.
internal fun syncWorkPolicy(
    pending: List<Pair<WorkInfo.State, Int>>,
    manual: Boolean,
): ExistingWorkPolicy? {
    val active = pending.filterNot { it.first.isFinished }
    val running = active.any { it.first == WorkInfo.State.RUNNING }
    if (manual && !running && active.any { it.first == WorkInfo.State.ENQUEUED })
        return ExistingWorkPolicy.REPLACE
    // One successor covers arrivals after the running worker has read its last batch.
    if (active.any { it.first != WorkInfo.State.RUNNING }) return null
    return ExistingWorkPolicy.APPEND_OR_REPLACE
}

internal const val SYNC_RETRY_ATTEMPTS = 4
internal const val SYNC_RETRY_CEILING_MS = 15 * 60 * 1000L

// WorkManager backoff doubles up to five hours. After a few quick retries, keep retrying at a
// fixed ceiling instead; 0 means "let WorkManager back off".
internal fun syncRetryDelay(runAttemptCount: Int): Long =
    if (runAttemptCount < SYNC_RETRY_ATTEMPTS) 0L else SYNC_RETRY_CEILING_MS

private fun syncRequest(urgent: Boolean, delayMillis: Long = 0): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .apply {
            // Expedited work cannot be delayed; quota exhaustion falls back to normal work.
            if (urgent && delayMillis == 0L)
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            if (delayMillis > 0) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        }
        .build()

fun syncNow(context: Context, manual: Boolean = false): Deferred<Unit> {
    val app = context.applicationContext
    return syncSchedulingScope.async<Unit> {
        synchronized(schedulingLock) {
            val manager = WorkManager.getInstance(app)
            enqueueCaptureMaintenance(app)
            val pending =
                manager.getWorkInfosForUniqueWork("hongshu-sync").get().filter {
                    !it.state.isFinished
                }
            val policy =
                syncWorkPolicy(pending.map { it.state to it.runAttemptCount }, manual)
                    ?: return@synchronized
            manager
                .enqueueUniqueWork("hongshu-sync", policy, syncRequest(manual))
                .result
                .get()
            Unit
        }
    }
}

private fun enqueueCaptureMaintenance(context: Context) {
    val manager = WorkManager.getInstance(context)
    val pending = manager.getWorkInfosForUniqueWork("hongshu-capture").get()
    val policy =
        syncWorkPolicy(pending.map { it.state to it.runAttemptCount }, manual = false) ?: return
    manager
        .enqueueUniqueWork(
            "hongshu-capture",
            policy,
            OneTimeWorkRequestBuilder<CaptureWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        .result
        .get()
}

// No network constraint. A blocked provider/SIM service must not serialize uploads.
class CaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val config = Config(applicationContext)
            try {
                refreshAutomaticSims(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                config.simRecognitionHealthy = false
            }
            try {
                Store(applicationContext).use {
                    it.reconcileUnknownSimCaptures()
                    it.resolvePendingFallback()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext Result.retry()
            }
            try {
                config.recoveryStatus = recoverRecentInbox(applicationContext)
                config.captureStatus = catchUpInbox(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                config.historyState = HistoryState.FAILED
            }
            // Wake upload without enqueueing another capture worker in a completion loop.
            Store(applicationContext).use {
                if (config.upload && it.pendingCount() > 0) enqueueSyncOnly(applicationContext)
            }
            // Paging through a large inbox is progress, not failure: a retry would back off
            // exponentially and hold the shared capture chain (and new-SMS recovery) for hours.
            if (
                config.upload &&
                    ((config.historyEnabled && config.inboxScanPending) ||
                        (config.recoveryEnabled && config.recoveryPending))
            )
                enqueueCaptureContinuation(applicationContext)
            Result.success()
        }
}

private fun enqueueSyncOnly(context: Context, delayMillis: Long = 0) =
    synchronized(schedulingLock) {
        val manager = WorkManager.getInstance(context)
        val pending =
            manager.getWorkInfosForUniqueWork("hongshu-sync").get().filter { !it.state.isFinished }
        // At most one successor to a running sync; offline/backoff work already covers new rows.
        val policy =
            syncWorkPolicy(pending.map { it.state to it.runAttemptCount }, manual = false)
                ?: return@synchronized
        manager
            .enqueueUniqueWork("hongshu-sync", policy, syncRequest(false, delayMillis))
            .result
            .get()
    }

// Called from inside a running worker: append a delayed successor to the same chain.
private fun enqueueCaptureContinuation(context: Context) =
    synchronized(schedulingLock) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "hongshu-capture",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CaptureWorker>()
                    .setInitialDelay(CAPTURE_CONTINUATION_DELAY_MS, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
            .result
            .get()
    }

internal const val CAPTURE_CONTINUATION_DELAY_MS = 10_000L

fun schedule(context: Context) {
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            "hongshu-capture-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CaptureWorker>(15, TimeUnit.MINUTES).build(),
        )
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            "hongshu-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        schedule(context)
        val pending = goAsync()
        syncSchedulingScope.launch {
            try {
                syncNow(context).await()
                val config = Config(context)
                if (
                    restoreRealtimeOnBoot(
                        config.restoreRealtimeOnBoot,
                        config.keepAlive,
                        config.token.isNotEmpty(),
                        config.url.isNotEmpty(),
                    )
                )
                    startRealtimeIfEnabled(context)
            } catch (_: Exception) {
                RuntimeHealth.connection.value = ConnectionHealth(detail = "开机恢复实时连接失败；周期同步仍启用")
            } finally {
                pending.finish()
            }
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Expedited work runs as a foreground service before Android 12 and needs a notification.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            3,
            NotificationCompat.Builder(applicationContext, "connection")
                .setSmallIcon(R.drawable.ic_hongshu)
                .setContentTitle("鸿枢 · 正在同步短信")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build(),
        )

    private fun fail(config: Config, reason: String) {
        config.syncHealthy = false
        config.status = reason
        config.lastSyncError = reason
    }

    private fun retry(): Result {
        val delay = syncRetryDelay(runAttemptCount)
        if (delay == 0L) return Result.retry()
        enqueueSyncOnly(applicationContext, delay)
        return Result.success()
    }

    override suspend fun doWork(): Result {
        val config = Config(applicationContext)
        if (config.token.isEmpty()) return Result.success()
        return try {
            if (!SyncEngine.run(applicationContext)) enqueueSyncOnly(applicationContext)
            Store(applicationContext).use { it.nextNotificationRelease() }?.let {
                enqueueSyncOnly(applicationContext, it)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            fail(config, e.message ?: "同步失败")
            if (e.status == 401) Result.failure() else retry()
        } catch (_: java.io.IOException) {
            fail(config, "无法连接中枢：请检查网络或中枢地址，将自动重试")
            retry()
        } catch (_: Exception) {
            fail(config, "同步失败：协议或本地存储异常，将自动重试")
            retry()
        }
    }
}

object SyncEngine {
    private val mutex = identityMutex

    suspend fun run(context: Context): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val repo = Repository(context)
                val c = repo.config
                val store = repo.store
                try {
                    if (c.token.isEmpty()) return@withContext true
                    c.lastSyncAttempt = System.currentTimeMillis()
                    val session = repo.api.request("/me")
                    val me = session.getJSONObject("device")
                    val serverUpload = reconcileUploadSwitch(repo.api, c, me.getBoolean("upload"))
                    c.serverUploadAllowed = serverUpload
                    if (me.has("name")) c.deviceName = me.getString("name")
                    if (store.captureSince() == 0L) {
                        store.initializeCaptureSince(me.getLong("created_at"))
                    }
                    val epoch = session.optLong("epoch")
                    if (epochReset(store.epochSeen(), store.epoch(), epoch))
                        store.resetSyncedCache(epoch)
                    else store.rememberEpoch(epoch)
                    store.initializeSyncHead(session.getLong("cursor"))
                    c.notify = me.getBoolean("notify")
                    // Local capture remains independent; server can deny upload without
                    // deleting
                    // its queue.
                    if (c.upload && serverUpload) {
                        val registrations = SimRegistrations(repo.api, c)
                        for (sim in store.sims()) registrations.ensure(sim.phone, sim.label, sim.sub)
                        for (batch in 0 until 20) {
                            if (!c.upload || !c.serverUploadAllowed) break
                            val candidates = store.uploadCandidates(batch)
                            val pending =
                                candidates.take(
                                    uploadBatchCount(
                                        candidates.map {
                                            it.second.json().toString().toByteArray().size
                                        }
                                    )
                                )
                            if (pending.isEmpty()) break
                            // Queued captures retain the number used before an offline SIM
                            // change. Register those snapshots too; never rewrite them to the
                            // new number.
                            for (message in
                                pending
                                    .map { it.second }
                                    .distinctBy { it.receiver to it.subscriptionId }) {
                                registrations.ensure(message.receiver, "SIM", message.subscriptionId)
                            }
                            uploadPending(
                                repo.api,
                                store,
                                pending,
                                enabled = { c.upload && c.serverUploadAllowed },
                            )
                            // A cached registration the hub no longer knows (restored database,
                            // removed SIM) is re-sent once; a fresh rejection stays blocked.
                            registrations.forgetRejected(store.simRejectedReceivers())
                                .takeIf { it.isNotEmpty() }
                                ?.let { store.releaseSimRejections(it) }
                        }
                    }
                    val contactsHealthy =
                        try {
                            store.applyContacts(
                                repo.api.request("/contacts").getJSONObject("contacts")
                            )
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ApiException) {
                            if (e.status == 401) throw e
                            false
                        } catch (_: Exception) {
                            false
                        }
                    // Contact names are cosmetic: a failure keeps the cached names and never
                    // turns an otherwise successful upload/download into a failed sync.
                    c.contactsHealthy = contactsHealthy
                    val pairedAt = me.optLong("created_at")
                    for (page in 0 until 100) {
                        val data =
                            try {
                                repo.api.request("/sync?after=${store.cursor()}&limit=200")
                            } catch (e: ApiException) {
                                if (e.code != "epoch_changed") throw e
                                val recovered = repo.api.request("/me")
                                store.resetSyncedCache(recovered.getLong("epoch"))
                                store.initializeSyncHead(recovered.getLong("cursor"))
                                continue
                            }
                        if (epochReset(store.epochSeen(), store.epoch(), data.getLong("epoch"))) {
                            store.resetSyncedCache(data.getLong("epoch"))
                            store.initializeSyncHead(repo.api.request("/me").getLong("cursor"))
                            continue
                        }
                        store.applySync(
                            data.getJSONArray("messages"),
                            data.getLong("cursor"),
                            c.deviceId,
                            pairedAt,
                            data.getLong("epoch"),
                        )
                        val pendingNotice = store.pendingNotification()
                        if (!c.notify || (pendingNotice > 0 && notifyMessage(context)))
                            store.acknowledgeNotification(pendingNotice)
                        if (!data.getBoolean("more")) {
                            c.lastSync = System.currentTimeMillis()
                            c.lastSyncError = ""
                            c.syncHealthy = true
                            if (c.captureFailure == CaptureFailure.SCHEDULING)
                                c.captureFailure = CaptureFailure.NONE
                            c.status =
                                // Queue counts are shown by the pending views; the sync status
                                // only describes the sync itself.
                                if (c.upload && c.historyEnabled && c.inboxScanPending)
                                    "同步完成 · 历史短信采集中，自动继续"
                                else "同步完成"
                            if (!contactsHealthy) c.status += " · 联系人名称未刷新，下次同步重试"
                            // Successful bounded work gets a successor, not failure backoff.
                            return@withContext !(c.upload &&
                                (serverUpload &&
                                    store.pending(allowFallback = false).isNotEmpty()))
                        }
                    }
                    false
                } finally {
                    store.close()
                    Store.changes.value++
                }
            }
        }
}

// The hub keeps its own copy of the upload switch. A toggle that never reached it (offline,
// or a re-login that created a fresh hub device) is re-sent; otherwise the hub's value is an
// administrator's decision and wins.
internal fun reconcileUploadSwitch(api: Api, c: Config, server: Boolean): Boolean {
    if (!c.uploadPatchPending) return server
    if (server == c.upload) {
        c.uploadPatchPending = false
        return server
    }
    return try {
        api.request("/devices/${c.deviceId}", "PATCH", JSONObject().put("upload", c.upload))
        c.uploadPatchPending = false
        c.upload
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.status == 401) throw e
        server
    } catch (_: Exception) {
        server
    }
}

internal fun simRegistrationKey(deviceId: String, phone: String, label: String, sub: Int) =
    "$deviceId\u0000$phone\u0000$sub\u0000$label"

// Registers each number once per hub device instead of before every batch, remembering what
// the hub accepted across runs.
internal class SimRegistrations(private val api: Api, private val c: Config) {
    private val cached = c.registeredSims.toMutableSet()
    private val sent = mutableSetOf<String>()
    private val reused = mutableMapOf<String, String>()

    fun ensure(phone: String, label: String, sub: Int) {
        val key = simRegistrationKey(c.deviceId, phone, label, sub)
        if (key in sent) return
        if (key in cached) {
            reused[key] = phone
            return
        }
        api.request(
            "/sims",
            "PUT",
            JSONObject().put("phone", phone).put("label", label).put("subscription_id", sub),
        )
        sent += key
        cached += key
        c.registeredSims = cached
    }

    // Returns receivers to release for another attempt within this run.
    fun forgetRejected(receivers: Set<String>): Set<String> {
        val stale = reused.filterValues { it in receivers }
        if (stale.isEmpty()) return emptySet()
        cached -= stale.keys
        stale.keys.forEach { reused.remove(it) }
        c.registeredSims = cached
        return stale.values.toSet()
    }
}

fun notifyMessage(context: Context): Boolean {
    if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        return false
    if (
        context
            .getSystemService(NotificationManager::class.java)
            .getNotificationChannel("messages")
            ?.importance == NotificationManager.IMPORTANCE_NONE
    )
        return false
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
    )
        return false
    val open =
        PendingIntent.getActivity(
            context,
            0,
            mainActivityIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    context
        .getSystemService(NotificationManager::class.java)
        .notify(
            2,
            NotificationCompat.Builder(context, "messages")
                .setSmallIcon(R.drawable.ic_hongshu)
                .setContentTitle("鸿枢 · 有新短信")
                .setContentText("打开应用查看，通知中不显示短信内容")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build(),
        )
    return true
}
