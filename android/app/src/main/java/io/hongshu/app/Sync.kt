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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HongshuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        channels(this)
        schedule(this)
        SimMonitor.start(this)
        SmsInboxObserver.ensure(this)
        CallLogObserver.ensure(this)
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
    manager.createNotificationChannel(
        NotificationChannel("otp_island", "验证码实时状态", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            description = "跨设备验证码通知，在支持的系统上以灵动岛/状态栏胶囊形式突出显示"
        }
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

// Every "an SMS may have arrived" signal (broadcast, inbox change, SMS app notification) funnels
// into one coalesced capture pass: inbox read, notification pairing, then upload. No source
// schedules its own work.
internal fun requestCapture(context: Context): Deferred<Unit> = syncNow(context, manual = true)

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
            // Installs that enabled the notification fallback before it implied recovery.
            if (linkRecoveryToNotificationCapture(applicationContext))
                SmsInboxObserver.ensure(applicationContext)
            try {
                Store(applicationContext).use {
                    it.reconcileNotificationCaptures()
                    it.reconcileUnknownSimCaptures()
                    it.resolvePendingFallback()
                    verifyProviderRebuild(applicationContext, it)
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
            // Independent of the inbox passes: a failure there must not hold back sent messages.
            try {
                config.outgoingStatus = scanSentBox(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                config.outgoingPending = false
            }
            // Calls are their own source and queue; a failure here never holds back SMS.
            try {
                CallLogObserver.ensure(applicationContext)
                config.callLogStatus = scanCallLog(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                config.callLogPending = false
            }
            // Wake upload without enqueueing another capture worker in a completion loop.
            Store(applicationContext).use {
                // Apply the user's queue policy after pairing/scanning, so a row that was just
                // enriched by this pass is not hidden before it had a chance to resolve.
                it.autoIgnoreUnassigned()
                if (config.upload && (it.pendingCount() > 0 || it.callQueueCounts().pending > 0))
                    enqueueSyncOnly(applicationContext)
            }
            // Paging through a large inbox is progress, not failure: a retry would back off
            // exponentially and hold the shared capture chain (and new-SMS recovery) for hours.
            if (
                config.upload &&
                    ((config.historyEnabled && config.inboxScanPending) ||
                        (config.recoveryEnabled && config.recoveryPending) ||
                        (config.outgoingEnabled && config.outgoingPending) ||
                        (config.callLogEnabled && config.callLogPending))
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

// A second inbox read shortly before a held notification is released, for providers that store
// the row after posting the notification. Runs outside the shared capture chain, like the
// periodic worker, so a long history page cannot delay it. Notifications whose re-reads fall in
// the same 10-second slot share one pass, and the first one keeps its time: a burst of
// notifications cannot keep postponing the re-read of an earlier row.
internal fun scheduleNotificationReread(context: Context, delayMillis: Long) =
    synchronized(schedulingLock) {
        val slot = (System.currentTimeMillis() + delayMillis) / NOTIFICATION_REREAD_SLOT_MS
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "hongshu-notification-reread-$slot",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CaptureWorker>()
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .build(),
            )
            .result
            .get()
    }

internal const val NOTIFICATION_REREAD_SLOT_MS = 10_000L

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
                    val session = repo.api.request("/me", timeoutMillis = 15_000)
                    val me = session.getJSONObject("device")
                    val serverUpload = reconcileUploadSwitch(repo.api, c, me.getBoolean("upload"))
                    c.serverUploadAllowed = serverUpload
                    store.rememberHubOutgoing(hubFeatures(session).contains(HUB_FEATURE_OUTGOING))
                    store.rememberHubCallLog(hubFeatures(session).contains(HUB_FEATURE_CALL_LOG))
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
                    // Display preview rows never acknowledge the reliable history log.
                    if (hubFeatures(session).contains("recent_snapshot") &&
                        session.getLong("cursor") > store.cursor() &&
                        (!store.initialComplete() || session.getLong("cursor") - store.cursor() > 200) &&
                        store.previewHead() != session.getLong("cursor")) {
                        try {
                            store.applyRecent(repo.api.request("/recent?limit=50", timeoutMillis = 8_000))
                        } catch (e: CancellationException) { throw e }
                        catch (e: ApiException) { if (e.status == 401) throw e }
                        catch (_: Exception) { /* reliable sync remains authoritative */ }
                    }
                    // Local capture remains independent; denial never deletes its queue.
                    var uploadFailure: Exception? = null
                    var uploadProgress = false
                    fun uploadSlice(): Exception? {
                        if (!c.upload || !serverUpload || !c.serverUploadAllowed) return null
                        try {
                            val registrations = SimRegistrations(repo.api, c)
                            for (sim in store.sims())
                                registrations.ensure(sim.phone, sim.label, sim.sub)
                            val candidates = store.uploadCandidates(0)
                            val pending = candidates.take(uploadBatchCount(
                                candidates.map { it.second.json().toString().toByteArray().size },
                                budget = if (c.lowSpeed || loadingNetwork.slow) 128_000 else 900_000,
                            ))
                            if (pending.isEmpty()) return null
                            // Register historical receiver snapshots without rewriting them.
                            for (message in pending.map { it.second }.distinctBy { it.receiver to it.subscriptionId }) {
                                registrations.ensure(message.receiver, "SIM", message.subscriptionId)
                            }
                            uploadPending(repo.api, store, pending, enabled = { c.upload && c.serverUploadAllowed })
                            uploadProgress = true
                            // Retry stale cached registrations once; fresh rejections stay blocked.
                            registrations.forgetRejected(store.simRejectedReceivers())
                                .takeIf { it.isNotEmpty() }?.let { store.releaseSimRejections(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ApiException) {
                            if (e.status == 401) throw e else return e
                        } catch (e: Exception) {
                            return e
                        }
                        return null
                    }
                    // Calls never fail the SMS sync: an error is reported and retried on the next
                    // sync instead of backing off message delivery. True only when a bounded
                    // pass made progress and left more queued.
                    fun uploadCallSlice(): Boolean {
                        val queued = store.callQueueCounts().pending
                        if (queued == 0) {
                            c.callLogUploadStatus = ""
                            return false
                        }
                        if (!c.upload || !serverUpload || !c.serverUploadAllowed) return false
                        if (!store.hubSupportsCallLog()) {
                            c.callLogUploadStatus = "$queued 条在本机排队 · 当前中枢版本还不支持通话记录"
                            return false
                        }
                        return try {
                            val registrations = SimRegistrations(repo.api, c)
                            val sims = store.sims()
                            val more = uploadCalls(
                                repo.api,
                                store,
                                // Keep the user's SIM label; only a line no longer mapped gets a generic one.
                                ensure = { call ->
                                    val sim = sims.find { it.phone == call.receiver }
                                    registrations.ensure(call.receiver, sim?.label ?: "SIM", sim?.sub ?: call.subscriptionId)
                                },
                                enabled = { c.upload && c.serverUploadAllowed },
                            )
                            val left = store.callQueueCounts()
                            c.callLogUploadStatus =
                                when {
                                    left.pending > 0 -> "待上传 ${left.pending} 条"
                                    left.blocked > 0 -> "${left.blocked} 条被中枢拒收"
                                    else -> "已全部上传"
                                }
                            more
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ApiException) {
                            if (e.status == 401) throw e
                            c.callLogUploadStatus = "上传失败，下次同步重试：${e.message}"
                            false
                        } catch (_: Exception) {
                            c.callLogUploadStatus = "上传失败，下次同步重试"
                            false
                        }
                    }
                    var uploaded = false
                    if (session.has("global_watermark_id")) {
                        store.updateGlobalWatermark(session.optLong("global_watermark_id"))
                    }
                    if (session.optLong("unread_count", -1L) == 0L) {
                        context.getSystemService(NotificationManager::class.java).cancel(2)
                    }
                    val pairedAt = me.optLong("created_at")
                    for (page in 0 until 4) {
                        val data =
                            try {
                                repo.api.request("/sync?after=${store.cursor()}&limit=200")
                            } catch (e: ApiException) {
                                if (e.code != "epoch_changed") {
                                    if (e.status != 401 && !uploaded) { uploadSlice(); uploaded = true }
                                    throw e
                                }
                                val recovered = repo.api.request("/me")
                                store.resetSyncedCache(recovered.getLong("epoch"))
                                store.initializeSyncHead(recovered.getLong("cursor"))
                                continue
                            } catch (e: java.io.IOException) {
                                if (!uploaded) { uploadSlice(); uploaded = true }
                                throw e
                            }
                        if (epochReset(store.epochSeen(), store.epoch(), data.getLong("epoch"))) {
                            store.resetSyncedCache(data.getLong("epoch"))
                            store.initializeSyncHead(repo.api.request("/me").getLong("cursor"))
                            continue
                        }
                        applyMessagePage(context, c, store, data, pairedAt)
                        val pendingNotice = store.pendingNotification()
                        if (!c.notify || (pendingNotice > 0 && notifyMessage(context)))
                            store.acknowledgeNotification(pendingNotice)
                        if (!uploaded) {
                            uploadFailure = uploadSlice()
                            uploaded = true
                        }
                        if (!data.getBoolean("more")) {
                            val contactsHealthy = refreshContactSnapshot(repo, session)
                            c.contactsHealthy = contactsHealthy
                            // Cosmetic analysis has a separate cursor and never creates notifications.
                            val analysisMore = syncMessageAnalysis(repo, session)
                            uploadReadReceipts(repo.api, store)
                            val readsComplete = syncReadState(store) { repo.api.request(it) }
                            val callsMore = uploadCallSlice()
                            // Same isolation as call uploads: a failure is retried next sync.
                            val callPagesMore =
                                try {
                                    syncCallLog(store, { repo.api.request(it) })
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: ApiException) {
                                    if (e.status == 401) throw e
                                    false
                                } catch (_: Exception) {
                                    false
                                }
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
                            // Download completed; report the upload failure so the worker backs
                            // off and retries without having blocked receiving.
                            uploadFailure?.let { throw it }
                            // Successful bounded work gets a successor, not failure backoff.
                            return@withContext !uploadProgress && !analysisMore && !callsMore && !callPagesMore && readsComplete && store.pendingReads().isEmpty() && !(c.upload &&
                                (serverUpload &&
                                    store.pending(allowFallback = false).isNotEmpty()))
                        }
                    }
                    uploadFailure?.let { throw it }
                    false
                } finally {
                    store.close()
                }
            }
        }
}

internal fun refreshContactSnapshot(repo: Repository, session: JSONObject): Boolean {
    val store = repo.store
    val revision = session.optLong("metadata_revision", -1)
    if (revision >= 0 && revision == store.contactsRevision()) return true
    if (revision < 0 && System.currentTimeMillis() - store.contactsCheckedAt() < 60_000) return true
    return try {
        if (hubFeatures(session).contains("metadata_revision")) {
            // Our cached revision may be zero after recovery; do not request an
            // unchanged snapshot unless the contact bytes are actually present.
            val data = repo.api.request("/metadata", timeoutMillis = 8_000)
            check(data.getLong("epoch") == store.epoch()) { "Contact snapshot epoch changed" }
            store.applyContacts(data.getJSONObject("contacts"), data.getLong("revision"), System.currentTimeMillis())
        } else store.applyContacts(repo.api.request("/contacts", timeoutMillis = 8_000).getJSONObject("contacts"), checkedAt = System.currentTimeMillis())
        true
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { if (e.status == 401) throw e; false }
    catch (_: Exception) { false }
}

internal fun hubFeatures(session: JSONObject): Set<String> {
    val features = session.optJSONArray("features") ?: return emptySet()
    return (0 until features.length()).mapNotNull { features.optString(it).takeIf(String::isNotEmpty) }.toSet()
}

internal fun applyMessagePage(context: Context, config: Config, store: Store, data: JSONObject, pairedAt: Long) {
    store.applySync(data.getJSONArray("messages"), data.getLong("cursor"),
        config.deviceId, pairedAt, data.getLong("epoch"))
    if (!config.notify || !config.liveOtpIsland || config.deviceId.isEmpty()) return
    // Intents were committed with the message pages, so a process death between the commit
    // and this loop still shows a valid code on the next sync. Consume every pending intent,
    // not just this page's, while it is still valid.
    store.dropExpiredOtpIntents()
    for (intent in store.pendingOtpIntents()) {
        if (OtpNotificationManager.showOtpNotification(context, intent.messageId, intent.otp,
                intent.sender, intent.contact, epoch = intent.epoch, expiresAt = intent.expiresAt))
            store.markOtpShown(intent.messageId)
    }
}

internal fun syncMessageAnalysis(repo: Repository, session: JSONObject): Boolean {
    val store = repo.store
    try {
        store.rememberAI(session.optBoolean("ai_enabled"))
        store.backfillLocalRules()
        if (!session.has("analysis_revision")) return false // Older hub: rule-only UI.
        for (page in 0 until 2) {
            if (store.analysisCursor() == session.getLong("analysis_revision")) break
            val data = try {
                repo.api.request("/analysis?after=${store.analysisCursor()}&limit=200")
            } catch (e: ApiException) {
                if (e.code != "analysis_cursor_changed") throw e
                store.resetAnalysis()
                continue
            }
            if (epochReset(store.epochSeen(), store.epoch(), data.getLong("epoch"))) return false
            store.applyAnalysis(data.getJSONArray("items"), data.getLong("cursor"), data.getLong("epoch"))
            if (!data.getBoolean("more")) return false
        }
        return store.analysisCursor() < session.getLong("analysis_revision")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.status == 401) throw e
        // Analysis retry must not make an otherwise successful SMS upload/download fail.
    } catch (_: Exception) { /* retry independently on the next sync */ }
    return false // A cosmetic failure waits for the next normal trigger, not a hot retry loop.
}

// Called under identityMutex, after checking the hub epoch. Failed requests leave durable
// receipts for WorkManager retry; an ACK cannot consume a newer local read watermark.
internal fun uploadReadReceipts(api: Api, store: Store) {
    for ((sender, id) in store.pendingReads().take(20)) {
        api.request("/messages/read", "POST", JSONObject().put("sender", sender).put("message_id", id))
        store.acknowledgeRead(sender, id)
    }
}

// The hub keeps its own copy of the upload switch. A toggle that never reached it (offline,
// or a re-login that created a fresh hub device) is re-sent; otherwise the hub's value is an
// administrator's decision and wins.
internal fun reconcileUploadSwitch(api: Api, c: Config, server: Boolean): Boolean {
    val (version, desired, pending) = synchronized(identityLock) {
        Triple(c.uploadVersion, c.upload, c.uploadPatchPending)
    }
    if (!pending) return server
    if (server == desired) {
        c.confirmUpload(version, server)
        return server
    }
    return try {
        api.request("/devices/${c.deviceId}", "PATCH", JSONObject().put("upload", desired))
        c.confirmUpload(version, desired)
        desired
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

/**
 * Requests a manual sync and waits until the sync work has actually finished, for UI that shows
 * progress. Returns false when it is still queued after [timeoutMillis] (for example offline).
 */
suspend fun syncAndWait(context: Context, timeoutMillis: Long = 15_000): Boolean {
    return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
        syncNow(context, manual = true).await()
        val finished = WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow("hongshu-sync")
            .first { infos -> infos.all { it.state.isFinished } }
        finished.isNotEmpty() && finished.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED }
    } ?: false
}
