package io.hongshu.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val MAX_FOREGROUND_ATTEMPTS = 3
private const val RECOVERY_NOTIFICATION_ID = 4

// OkHttp normalizes ws/wss to http/https in Request.url, including default ports.
internal fun realtimeRequestMatches(request: Request, url: String, token: String): Boolean =
    request.header("Authorization") == "Bearer $token" &&
        runCatching { request.url == Request.Builder().url(realtimeURL(url)).build().url }
            .getOrDefault(false)

class RealtimeService : Service() {
    // Service state and socket callbacks are serialized on the main dispatcher.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val network = MutableStateFlow<Network?>(null)
    private val client by lazy {
        Api.client.newBuilder().pingInterval(25, TimeUnit.SECONDS).build()
    }
    private var socket: WebSocket? = null
    private var connection: Job? = null
    private var generation = 0L
    private var destroyed = false
    private var foreground = false
    private var watchingNetwork = false
    private var delivery: RealtimeDeliveryRunner? = null
    private var transportHealth = ConnectionHealth()
    private var foregroundAttempts = 0
    private var foregroundRetry: Job? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNetwork()

            override fun onLost(network: Network) = refreshNetwork()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = refreshNetwork()
        }

    private fun refreshNetwork() {
        scope.launch { network.value = connectivity.activeNetwork }
    }

    private fun watchNetwork() {
        if (watchingNetwork) return
        network.value = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(networkCallback)
        watchingNetwork = true
    }

    private fun notification(detail: String): Notification {
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RealtimeService::class.java).setAction(STOP_REALTIME),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                mainActivityIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, "connection")
            .setSmallIcon(R.drawable.ic_hongshu)
            .setContentTitle("鸿枢实时同步")
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "停止实时连接", stop)
            .build()
    }

    private fun reportHealth(value: ConnectionHealth) {
        if (destroyed) return
        transportHealth = value
        refreshHealth()
    }

    private fun refreshHealth() {
        if (destroyed) return
        val value = transportHealth.copy(detail = deliveryDetail(transportHealth,
            delivery?.health?.value ?: DeliveryHealth(), android.os.SystemClock.elapsedRealtime() + 1))
        if (RuntimeHealth.connection.value == value) return
        RuntimeHealth.connection.value = value
        if (
            foreground &&
                (Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED)
        ) {
            getSystemService(NotificationManager::class.java).notify(1, notification(value.detail))
        }
    }

    private fun startDelivery() {
        if (delivery != null) return
        val app = applicationContext
        val runner = RealtimeDeliveryRunner(scope,
            synchronize = {
                withDeliveryWakeLock(app) { SyncEngine.run(app, realtime = true) }
            },
            capture = {
                // CancellationSignal also bounds a cooperative provider query. Its actor is
                // independent of networking even on ROMs that ignore cancellation.
                val cancellation = android.os.CancellationSignal()
                val timeout = scope.launch { delay(5_000); cancellation.cancel() }
                timeout.invokeOnCompletion { cancellation.cancel() }
                try {
                    withContext(Dispatchers.IO) {
                        withDeliveryWakeLock(app) { captureRecentInbox(app, cancellation) }
                    }
                } finally { timeout.cancel() }
            },
            nextRelease = { withContext(Dispatchers.IO) { Store(app).use { it.nextNotificationRelease() } } },
            fallback = { error ->
                withContext(Dispatchers.IO) {
                    val config = Config(app)
                    config.syncHealthy = false
                    config.status = syncFailureReason(error)
                    config.lastSyncError = config.status
                    enqueueSyncOnly(app)
                }
                if (error is ApiException && error.status == 401 && Config(app).token.isEmpty())
                    stopConnection("凭据失效，请重新连接中枢；周期同步等待重新授权")
            },
            initialRetryMillis = Config(app).syncRetryDelay(),
            retryStarted = { Config(app).noteSyncFailure() },
            retryCleared = { Config(app).clearSyncRetry() },
        )
        delivery = runner
        RealtimeDelivery.attach(runner)
        scope.launch { runner.health.collect { refreshHealth() } }
        scope.launch {
            while (isActive) {
                delay(5_000)
                // Status watchdog only; no polling requests or repeated service starts.
                refreshHealth()
            }
        }
        if (Config(app).syncRetryDelay() == 0L) runner.request(capture = true)
    }

    private fun stopDelivery() {
        delivery?.let {
            RealtimeDelivery.detach(it)
            it.close()
        }
        delivery = null
    }

    private fun stopConnection(detail: String) {
        reportHealth(ConnectionHealth(detail = detail))
        destroyed = true
        foregroundRetry?.cancel()
        foregroundRetry = null
        stopDelivery()
        generation++
        socket?.cancel()
        connection?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = Config(this)
        when (
            realtimeStart(
                intent?.action,
                config.keepAlive,
                config.token.isNotEmpty(),
                config.url.isNotEmpty(),
            )
        ) {
            RealtimeStart.STOP -> {
                config.keepAlive = false
                clearRecoveryNotice()
                stopConnection("实时连接已主动停止；周期同步仍启用")
                return START_NOT_STICKY
            }
            RealtimeStart.DISABLED -> {
                clearRecoveryNotice()
                stopConnection("实时连接已关闭；周期同步仍启用")
                return START_NOT_STICKY
            }
            RealtimeStart.UNAUTHORIZED -> {
                clearRecoveryNotice()
                stopConnection("凭据不可用，请重新连接中枢")
                return START_NOT_STICKY
            }
            RealtimeStart.CONNECT -> Unit
        }
        if (destroyed) {
            // A start queued during shutdown must not leave a non-foreground service alive.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        foregroundRetry?.cancel()
        foregroundRetry = null
        foregroundAttempts = 0
        try {
            promoteToForeground()
            if (connection?.isActive != true) connection = scope.launch { connectLoop() }
        } catch (e: Exception) {
            if (
                Build.VERSION.SDK_INT >= 31 &&
                    e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                retryForeground()
                return START_STICKY
            }
            stopConnection("系统不允许运行实时服务；周期同步仍启用")
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        val alreadyForeground = foreground
        channels(this)
        // Call startForeground even when already foreground: a startForegroundService() on a
        // running service sets fgRequired again, and only this call clears its 30s deadline.
        val detail =
            if (alreadyForeground)
                RuntimeHealth.connection.value.detail.ifEmpty { "正在连接中枢" }
            else "正在连接中枢"
        ServiceCompat.startForeground(
            this,
            1,
            notification(detail),
            realtimeServiceType(),
        )
        foreground = true
        foregroundAttempts = 0
        clearRecoveryNotice()
        val config = Config(this)
        if (config.lastTaskRemovedAt > 0 && config.lastTaskRemovedAt >= config.lastReviveAt) {
            config.lastReviveAt = System.currentTimeMillis()
            config.lastReviveResult = "已恢复实时连接"
        }
        if (!alreadyForeground) {
            reportHealth(ConnectionHealth(running = true, detail = "正在连接中枢"))
            watchNetwork()
            startDelivery()
        }
    }

    // Android 12+ can deny the first startForeground() after a process kill. Keep the service
    // start request alive briefly instead of permanently giving up; the platform's own 30s
    // allowlist usually makes one of these attempts succeed.
    private fun retryForeground() {
        if (foregroundAttempts >= MAX_FOREGROUND_ATTEMPTS) {
            Config(this).lastReviveResult = "系统拒绝后台恢复"
            notifyRecoveryNeeded()
            stopConnection("系统不允许在后台恢复实时连接；周期同步仍启用")
            return
        }
        val delayMillis = 1000L shl foregroundAttempts
        foregroundAttempts++
        reportHealth(ConnectionHealth(detail = "系统暂不允许后台恢复，等待重试"))
        Config(this).lastReviveResult = "等待系统允许恢复"
        foregroundRetry?.cancel()
        foregroundRetry =
            scope.launch {
                delay(delayMillis)
                if (destroyed) return@launch
                try {
                    promoteToForeground()
                    if (connection?.isActive != true) connection = scope.launch { connectLoop() }
                } catch (e: Exception) {
                    if (
                        Build.VERSION.SDK_INT >= 31 &&
                            e is android.app.ForegroundServiceStartNotAllowedException
                    ) {
                        retryForeground()
                    } else {
                        stopConnection("系统不允许运行实时服务；周期同步仍启用")
                    }
                }
            }
    }

    private fun clearRecoveryNotice() {
        runCatching { NotificationManagerCompat.from(this).cancel(RECOVERY_NOTIFICATION_ID) }
    }

    private fun notifyRecoveryNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return
        val open =
            PendingIntent.getActivity(
                this,
                2,
                mainActivityIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notice =
            NotificationCompat.Builder(this, "connection")
                .setSmallIcon(R.drawable.ic_hongshu)
                .setContentTitle("鸿枢实时连接未恢复")
                .setContentText("点按打开鸿枢恢复实时连接；周期同步仍在工作")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        runCatching { NotificationManagerCompat.from(this).notify(RECOVERY_NOTIFICATION_ID, notice) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (destroyed) return
        val config = Config(this)
        if (
            realtimeStart(
                REVIVE_REALTIME,
                config.keepAlive,
                config.token.isNotEmpty(),
                config.url.isNotEmpty(),
            ) != RealtimeStart.CONNECT
        )
            return
        val now = System.currentTimeMillis()
        config.lastTaskRemovedAt = now
        // The service is still a non-short foreground service here, so Android 12+ allows this
        // background start. AMS keeps the request and allowlists the UID for the next
        // startForeground() if the process is killed right after this callback.
        if (startRealtimeService(this, REVIVE_REALTIME)) {
            config.lastReviveAt = now
            config.lastReviveResult = "已请求系统恢复"
        } else {
            config.lastReviveResult = "恢复请求被系统拒绝"
            if (!foreground) notifyRecoveryNeeded()
        }
    }

    private suspend fun connectLoop() {
        var attempts = 0
        try {
            while (currentCoroutineContext().isActive) {
                val config = Config(this)
                if (!config.keepAlive) {
                    stopConnection("实时连接已关闭；周期同步仍启用")
                    return
                }
                if (config.token.isEmpty()) {
                    stopConnection("凭据不可用，请重新连接中枢")
                    return
                }
                if (network.value == null) {
                    reportHealth(ConnectionHealth(true, false, "网络不可用，等待恢复"))
                    network.first { it != null }
                    attempts = 0
                }
                val activeNetwork = network.value
                val disconnected = CompletableDeferred<Unit>()
                val session = ++generation
                val request =
                    synchronized(identityLock) {
                        if (config.token.isEmpty() || !config.keepAlive) null
                        else
                            Request.Builder()
                                .url(realtimeURL(config.requireOriginAccess(config.url)))
                                .header("Authorization", "Bearer ${config.token}")
                                .header("Cookie", "hongshu=${config.token}")
                                .build()
                    } ?: continue
                reportHealth(ConnectionHealth(true, false, "正在连接中枢"))
                fun callback(block: () -> Unit) {
                    scope.launch { if (!destroyed && generation == session) block() }
                }
                socket =
                    client.newWebSocket(
                        request,
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                callback {
                                    attempts = 0
                                    reportHealth(ConnectionHealth(true, true, "实时连接已建立"))
                                    syncNow(this@RealtimeService, capture = false)
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                callback {
                                    Config(this@RealtimeService).lastHubHint = System.currentTimeMillis()
                                    syncNow(this@RealtimeService, manual = true, capture = false)
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                callback {
                                    synchronized(identityLock) {
                                        if (
                                            response?.code == 401 &&
                                                realtimeRequestMatches(
                                                    request,
                                                    config.url,
                                                    config.token,
                                                )
                                        ) {
                                            config.token = ""
                                            config.status = "授权已撤销"
                                            config.syncHealthy = false
                                            reportHealth(
                                                ConnectionHealth(true, false, "凭据失效，请重新连接中枢")
                                            )
                                        } else {
                                            reportHealth(
                                                ConnectionHealth(true, false, "连接断开，自动重连中")
                                            )
                                        }
                                    }
                                    disconnected.complete(Unit)
                                }
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                callback { disconnected.complete(Unit) }
                            }

                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                webSocket.close(code, null)
                                callback { disconnected.complete(Unit) }
                            }
                        },
                    )
                // Network identity changes wake the loop even during an established connection.
                val networkChange =
                    scope.launch {
                        network.first { it != activeNetwork }
                        disconnected.complete(Unit)
                    }
                try {
                    disconnected.await()
                } finally {
                    generation++
                    networkChange.cancel()
                    socket?.cancel()
                    socket = null
                }
                if (!config.keepAlive || config.token.isEmpty()) continue
                reportHealth(
                    ConnectionHealth(
                        true,
                        false,
                        if (network.value == null) "网络不可用，等待恢复" else "连接断开，自动重连中",
                    )
                )
                if (network.value == activeNetwork) {
                    withTimeoutOrNull(realtimeRetryDelay(attempts) + Random.nextLong(1000)) {
                        network.first { it != activeNetwork }
                    }
                    attempts++
                } else attempts = 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            stopConnection("实时连接异常，请重新开启；周期同步仍启用")
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopConnection("系统后台运行时限已到；周期同步仍启用")
        syncNow(this)
    }

    override fun onDestroy() {
        destroyed = true
        foregroundRetry?.cancel()
        foregroundRetry = null
        stopDelivery()
        generation++
        socket?.cancel()
        scope.cancel()
        if (watchingNetwork) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            watchingNetwork = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val last = RuntimeHealth.connection.value
        RuntimeHealth.connection.value =
            last.copy(
                running = false,
                connected = false,
                detail = if (last.running) "服务已停止；周期同步仍启用" else last.detail,
            )
        super.onDestroy()
    }
}
