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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HongshuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        channels(this)
        schedule(this)
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

fun syncNow(context: Context) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "hongshu-sync",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
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

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = Config(applicationContext)
        if (config.token.isEmpty()) return Result.success()
        return try {
            if (SyncEngine.run(applicationContext)) Result.success() else Result.retry()
        } catch (e: ApiException) {
            config.status = e.message ?: "同步失败"
            if (e.status == 401) Result.failure() else Result.retry()
        } catch (_: Exception) {
            config.status = "离线，等待网络恢复后重试"
            Result.retry()
        }
    }
}

object SyncEngine {
    private val mutex = Mutex()

    suspend fun run(context: Context): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val repo = Repository(context)
                val c = repo.config
                val store = repo.store
                try {
                    if (c.token.isEmpty()) return@withContext true
                    val me = repo.api.request("/me").getJSONObject("device")
                    c.notify = me.getBoolean("notify")
                    store.applyContacts(repo.api.request("/contacts").getJSONObject("contacts"))
                    // Local capture remains independent; server can deny upload without deleting
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
                            check(result.getJSONArray("acks").length() == pending.size) {
                                "上传确认数量不一致"
                            }
                            store.ack(pending.map { it.first })
                        }
                    }
                    val notifyHistory = store.cursor() > 0
                    var fresh = false
                    for (page in 0 until 100) {
                        val data = repo.api.request("/sync?after=${store.cursor()}&limit=200")
                        val added =
                            store.applySync(data.getJSONArray("messages"), data.getLong("cursor"))
                        if (
                            added.any {
                                shouldNotify(it.deviceId, c.deviceId, it.historical, notifyHistory)
                            }
                        )
                            fresh = true
                        if (!data.getBoolean("more")) {
                            c.lastSync = System.currentTimeMillis()
                            c.status =
                                if (store.pendingCount() > 0)
                                    "已同步 · ${store.pendingCount()} 条等待上传或 SIM 配置"
                                else "同步完成"
                            if (fresh && c.notify) notifyMessage(context)
                            if (c.upload && me.getBoolean("upload") && store.pending().isNotEmpty())
                                syncNow(context)
                            return@withContext true
                        }
                    }
                    if (fresh && c.notify) notifyMessage(context)
                    false
                } finally {
                    store.close()
                    Store.changes.value++
                }
            }
        }
}

fun notifyMessage(context: Context) {
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
    )
        return
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
}
