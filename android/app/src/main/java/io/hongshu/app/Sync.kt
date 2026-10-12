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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HongshuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        channels(this)
        schedule(this)
        SimMonitor.start(this)
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

fun syncNow(context: Context, manual: Boolean = false): Job {
    val app = context.applicationContext
    return syncSchedulingScope.launch {
        synchronized(schedulingLock) {
            val manager = WorkManager.getInstance(app)
            val pending =
                manager.getWorkInfosForUniqueWork("hongshu-sync").get().filter {
                    !it.state.isFinished
                }
            val replaceBackoff =
                manual &&
                    pending.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
            if (!replaceBackoff && pending.isNotEmpty()) return@synchronized
            manager
                .enqueueUniqueWork(
                    "hongshu-sync",
                    if (replaceBackoff) ExistingWorkPolicy.REPLACE
                    else ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build(),
                )
                .result
                .get()
        }
    }
}

fun schedule(context: Context) {
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
        syncNow(context)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = Config(applicationContext)
        if (config.token.isEmpty()) return Result.success()
        return try {
            try {
                config.captureStatus = catchUpInbox(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                config.captureStatus = "收件箱补扫失败；请检查读取权限与本地存储"
            }
            if (SyncEngine.run(applicationContext)) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            config.syncHealthy = false
            config.status = e.message ?: "同步失败"
            if (e.status == 401) Result.failure() else Result.retry()
        } catch (_: Exception) {
            config.syncHealthy = false
            config.status = "同步失败：网络、协议或本地存储；将重试"
            Result.retry()
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
                    val session = repo.api.request("/me")
                    val me = session.getJSONObject("device")
                    c.serverUploadAllowed = me.getBoolean("upload")
                    if (me.has("name")) c.deviceName = me.getString("name")
                    if (store.captureSince() == 0L) {
                        store.initializeCaptureSince(me.getLong("created_at"))
                        try {
                            c.captureStatus = catchUpInbox(context)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            c.captureStatus = "收件箱补扫失败；请检查读取权限与本地存储"
                        }
                    }
                    val epoch = session.optLong("epoch")
                    if (epochReset(store.epochSeen(), store.epoch(), epoch))
                        store.resetSyncedCache(epoch)
                    else store.rememberEpoch(epoch)
                    store.initializeSyncHead(session.getLong("cursor"))
                    c.notify = me.getBoolean("notify")
                    store.applyContacts(repo.api.request("/contacts").getJSONObject("contacts"))
                    // Local capture remains independent; server can deny upload without
                    // deleting
                    // its queue.
                    if (c.upload && me.getBoolean("upload")) {
                        for (sim in store.sims()) repo.api.request(
                            "/sims",
                            "PUT",
                            JSONObject()
                                .put("phone", sim.phone)
                                .put("label", sim.label)
                                .put("subscription_id", sim.sub),
                        )
                        for (batch in 0 until 20) {
                            val candidates = store.pending()
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
                            // change.
                            // Register those snapshots too; never rewrite them to the new
                            // number.
                            for (message in
                                pending
                                    .map { it.second }
                                    .distinctBy { it.receiver to it.subscriptionId }) {
                                repo.api.request(
                                    "/sims",
                                    "PUT",
                                    JSONObject()
                                        .put("phone", message.receiver)
                                        .put("label", "SIM")
                                        .put("subscription_id", message.subscriptionId),
                                )
                            }
                            val result =
                                repo.api.request(
                                    "/messages",
                                    "POST",
                                    JSONObject()
                                        .put(
                                            "messages",
                                            JSONArray().apply {
                                                pending.forEach { put(it.second.json()) }
                                            },
                                        ),
                                )
                            val acks = result.getJSONArray("acks")
                            check(acks.length() == pending.size) { "上传确认数量不一致" }
                            val accepted = mutableListOf<String>()
                            for (i in 0 until acks.length()) {
                                val ack = acks.getJSONObject(i)
                                when (ack.optString("error")) {
                                    "" -> accepted.add(pending[i].first)
                                    "sim_not_confirmed" ->
                                        store.block(pending[i].first, "sim_not_confirmed")
                                    else -> store.block(pending[i].first, "invalid_message")
                                }
                            }
                            store.ack(accepted)
                            if (accepted.isEmpty()) break
                        }
                    }
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
                            c.syncHealthy = true
                            c.status =
                                if (c.inboxScanPending) "历史短信采集中，自动继续"
                                else if (store.pendingCount() > 0)
                                    "已同步 · ${store.pendingCount()} 条等待上传或 SIM 配置"
                                else "同步完成"
                            // Enqueuing from inside the current worker is coalesced. Retry the
                            // worker itself so queues over 20 batches and history pages drain.
                            return@withContext !(c.upload &&
                                (c.inboxScanPending ||
                                    (me.getBoolean("upload") && store.pending().isNotEmpty())))
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
            Intent(context, MainActivity::class.java),
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
