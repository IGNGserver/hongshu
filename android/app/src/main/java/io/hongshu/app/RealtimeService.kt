package io.hongshu.app

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RealtimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var connection: Job? = null
    @Volatile private var destroyed = false

    private fun reportHealth(value: ConnectionHealth) {
        if (!destroyed) RuntimeHealth.connection.value = value
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RealtimeService::class.java).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(this, "connection")
                .setSmallIcon(R.drawable.ic_hongshu)
                .setContentTitle("鸿枢实时同步")
                .setContentText("用户开启的后台连接；系统可能限制运行时长")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "停止", stop)
                .build()
        ServiceCompat.startForeground(
            this,
            1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (intent?.action == "stop") {
            Config(this).keepAlive = false
            stopSelf()
            return START_NOT_STICKY
        }
        reportHealth(ConnectionHealth(running = true, detail = "服务运行中，正在连接中枢"))
        if (connection == null)
            connection =
                scope.launch {
                    var attempts = 0
                    while (isActive) {
                        val c = Config(this@RealtimeService)
                        if (c.token.isEmpty() || !c.keepAlive) break
                        val disconnected = CompletableDeferred<Unit>()
                        val client =
                            Api.client.newBuilder().pingInterval(25, TimeUnit.SECONDS).build()
                        val request =
                            synchronized(identityLock) {
                                if (c.token.isEmpty() || !c.keepAlive) null
                                else
                                    Request.Builder()
                                        .url(realtimeURL(c.url))
                                        .header("Authorization", "Bearer ${c.token}")
                                        .header("Cookie", "hongshu=${c.token}")
                                        .build()
                            } ?: break
                        socket =
                            client.newWebSocket(
                                request,
                                object : WebSocketListener() {
                                    override fun onOpen(webSocket: WebSocket, response: Response) {
                                        reportHealth(
                                            ConnectionHealth(true, true, "服务运行中 · 实时连接已建立")
                                        )
                                        attempts = 0
                                        syncNow(this@RealtimeService)
                                    }

                                    override fun onMessage(webSocket: WebSocket, text: String) {
                                        syncNow(this@RealtimeService)
                                    }

                                    override fun onFailure(
                                        webSocket: WebSocket,
                                        t: Throwable,
                                        response: Response?,
                                    ) {
                                        reportHealth(ConnectionHealth(true, false, "连接断开，自动重连中"))
                                        synchronized(identityLock) {
                                            if (
                                                !destroyed &&
                                                    response?.code == 401 &&
                                                    request.header("Authorization") ==
                                                        "Bearer ${c.token}" &&
                                                    request.url.toString() == realtimeURL(c.url)
                                            ) {
                                                c.token = ""
                                                c.status = "授权已撤销"
                                                c.syncHealthy = false
                                            }
                                        }
                                        disconnected.complete(Unit)
                                    }

                                    override fun onClosed(
                                        webSocket: WebSocket,
                                        code: Int,
                                        reason: String,
                                    ) {
                                        reportHealth(ConnectionHealth(true, false, "连接断开，自动重连中"))
                                        disconnected.complete(Unit)
                                    }

                                    override fun onClosing(
                                        webSocket: WebSocket,
                                        code: Int,
                                        reason: String,
                                    ) {
                                        reportHealth(ConnectionHealth(true, false, "连接断开，自动重连中"))
                                        webSocket.close(code, null)
                                        disconnected.complete(Unit)
                                    }
                                },
                            )
                        try {
                            disconnected.await()
                        } finally {
                            socket?.cancel()
                        }
                        if (c.token.isEmpty() || !c.keepAlive) break
                        delay((1000L shl attempts.coerceAtMost(6)) + Random.nextLong(1000))
                        attempts++
                    }
                    stopSelf()
                }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        destroyed = true
        RuntimeHealth.connection.value = ConnectionHealth(detail = "系统后台运行时限已到，服务已停止")
        syncNow(this)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        val last = RuntimeHealth.connection.value
        RuntimeHealth.connection.value =
            last.copy(
                running = false,
                connected = false,
                detail = if (last.running) "服务已停止" else last.detail,
            )
        socket?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
