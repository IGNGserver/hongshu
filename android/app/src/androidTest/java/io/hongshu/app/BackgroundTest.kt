package io.hongshu.app

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundTest {
    // Real loopback HTTP/WebSocket transport: no production server, SMS or credentials.
    private class Hub(private val unauthorized: Boolean = false, private val withMessage: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val clients = CopyOnWriteArrayList<Socket>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        val connections = AtomicInteger()
        val requests = AtomicInteger()
        private val arrived = java.util.concurrent.atomic.AtomicBoolean()
        val url = "http://127.0.0.1:${server.localPort}"
        private val acceptor =
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client =
                        try {
                            server.accept()
                        } catch (_: Exception) {
                            break
                        }
                    clients.add(client)
                    thread(isDaemon = true) {
                        client.use {
                            // close() may shut this socket before the thread starts reading;
                            // an uncaught exception here would crash the instrumented app.
                            val input =
                                runCatching { client.getInputStream().buffered() }.getOrNull()
                            if (input == null) {
                                clients.remove(client)
                                return@use
                            }
                            fun line(): String {
                                val bytes = java.io.ByteArrayOutputStream()
                                while (true) {
                                    val b = input.read()
                                    check(b >= 0)
                                    if (b == 10) return bytes.toString("UTF-8")
                                    if (b != 13) bytes.write(b)
                                }
                            }
                            try {
                                val path = line().split(" ")[1]
                                val headers = mutableMapOf<String, String>()
                                while (true) {
                                    val header = line()
                                    if (header.isEmpty()) break
                                    headers[header.substringBefore(':').lowercase()] =
                                        header.substringAfter(':').trim()
                                }
                                val output = client.getOutputStream()
                                if (unauthorized) {
                                    requests.incrementAndGet()
                                    output.write(
                                        "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                            .toByteArray()
                                    )
                                    output.flush()
                                    return@use
                                }
                                if (path == "/api/ws") {
                                    val digest =
                                        MessageDigest.getInstance("SHA-1")
                                            .digest(
                                                (headers.getValue("sec-websocket-key") +
                                                        "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                    .toByteArray()
                                            )
                                    val accept = Base64.getEncoder().encodeToString(digest)
                                    output.write(
                                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                                            .toByteArray()
                                    )
                                    output.flush()
                                    sockets.add(client)
                                    connections.incrementAndGet()
                                    while (input.read() >= 0) {
                                        /* Tests complete before a ping is due. */
                                    }
                                } else {
                                    val body =
                                        (if (path.startsWith("/api/read-state?"))
                                            """{"items":[],"global_watermark_id":0,"revision":0,"head":0,"epoch":1,"more":false,"next":""}"""
                                        else when (path) {
                                            "/api/me" ->
                                                """{"cursor":${if (arrived.get()) 1 else 0},"epoch":1,"device":{"notify":$withMessage,"upload":false,"created_at":1}}"""
                                            "/api/contacts" -> """{"contacts":{}}"""
                                            else -> {
                                                val messages = if (arrived.get() && path.contains("after=0&"))
                                                    """[{"id":1,"device_id":"remote-fixture","receiver":"+123456789","sender":"background-fixture","body":"synthetic ordinary SMS","timestamp":${System.currentTimeMillis()},"historical":false}]"""
                                                else "[]"
                                                """{"messages":$messages,"cursor":${if (arrived.get()) 1 else 0},"more":false,"epoch":1}"""
                                            }
                                        }).toByteArray()
                                    output.write(
                                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                            .toByteArray()
                                    )
                                    output.write(body)
                                    output.flush()
                                }
                            } catch (_: java.io.IOException) {
                                // Closing the fixture/forcing a disconnect interrupts a socket
                                // read.
                            } finally {
                                sockets.remove(client)
                                clients.remove(client)
                            }
                        }
                    }
                }
            }

        fun disconnect() {
            sockets.forEach { it.close() }
        }

        fun publishMessage() {
            arrived.set(true)
            sockets.forEach { client ->
                client.getOutputStream().apply {
                    write(byteArrayOf(0x81.toByte(), 2, '{'.code.toByte(), '}'.code.toByte()))
                    flush()
                }
            }
        }

        override fun close() {
            server.close()
            clients.forEach { it.close() }
            acceptor.join(3000)
        }
    }

    private fun awaitState(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("expected background state was not reached: ${RuntimeHealth.connection.value}")
    }

    private fun withConfig(test: (Context, Config) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
        val saved = prefs.all
        val manager = WorkManager.getInstance(context)
        context.stopService(Intent(context, RealtimeService::class.java))
        awaitState { !RuntimeHealth.connection.value.running }
        runBlocking { identityMutex.withLock { /* drain an already in-flight service pass */ } }
        manager.cancelUniqueWork("hongshu-sync").result.get()
        try {
            prefs.edit().clear().commit()
            test(context, Config(context))
        } finally {
            Config(context).keepAlive = false
            context.stopService(Intent(context, RealtimeService::class.java))
            awaitState { !RuntimeHealth.connection.value.running }
            runBlocking { identityMutex.withLock { /* do not restore prefs under a live request */ } }
            manager.cancelUniqueWork("hongshu-sync").result.get()
            val editor = prefs.edit().clear()
            for ((key, value) in saved) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
            editor.commit()
        }
    }

    @Test
    fun taskExclusionIsReversibleAndPersistedWithoutEnablingService() =
        withConfig { context, config ->
            assertFalse(config.hideFromRecents)
            assertFalse(config.restoreRealtimeOnBoot)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val manager = activity.getSystemService(ActivityManager::class.java)
                    fun excluded(): Boolean =
                        manager.appTasks
                            .first { recentTaskId(it.taskInfo) == activity.taskId }
                            .taskInfo
                            .baseIntent
                            .flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0
                    assertTrue(updateRecentTaskVisibility(activity, true))
                    config.hideFromRecents = true
                    assertTrue(excluded())
                    assertTrue(Config(context).hideFromRecents)
                    assertFalse(config.keepAlive)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val task =
                        activity.getSystemService(ActivityManager::class.java).appTasks.first {
                            recentTaskId(it.taskInfo) == activity.taskId
                        }
                    assertTrue(
                        task.taskInfo.baseIntent.flags and
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0
                    )
                    assertTrue(updateRecentTaskVisibility(activity, false))
                    config.hideFromRecents = false
                    assertEquals(
                        0,
                        task.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    )
                }
            }
        }

    @Test
    fun manifestAndNotificationIntentsUseOneMainTaskAndOnlySelectedServiceType() =
        withConfig { context, _ ->
            val flags = mainActivityIntent(context).flags
            assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            val info =
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
                    .services
                    .orEmpty()
                    .first { it.name == RealtimeService::class.java.name }
            assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                assertEquals(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
                    realtimeServiceType(),
                )
                assertTrue(
                    info.foregroundServiceType and
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING != 0
                )
            } else
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, realtimeServiceType())
        }

    @Test
    fun hiddenBackgroundTaskKeepsSocketAndNotificationThenStopIsPersistent() =
        withConfig { context, config ->
            if (Build.VERSION.SDK_INT >= 33) {
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(
                        "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                    )
                    .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                assertEquals(
                    PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS),
                )
            }
            Hub().use { hub ->
                config.url = hub.url
                config.token = "s".repeat(64)
                config.keepAlive = true
                config.hideFromRecents = true
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitState { RuntimeHealth.connection.value.connected }
                    val count = hub.connections.get()
                    scenario.onActivity { activity ->
                        activity.startService(Intent(activity, RealtimeService::class.java))
                    }
                    Thread.sleep(300)
                    assertEquals(count, hub.connections.get())
                    awaitState {
                        context
                            .getSystemService(NotificationManager::class.java)
                            .activeNotifications
                            .any {
                                it.id == 1 &&
                                    it.notification.extras
                                        .getCharSequence("android.text")
                                        .toString()
                                        .contains("已建立")
                            }
                    }
                    scenario.onActivity { it.leaveForBackground() }
                    assertTrue(RuntimeHealth.connection.value.connected)
                    // Removing our own UI task must not stop its independent foreground service.
                    scenario.onActivity { it.finishAndRemoveTask() }
                    assertTrue(RuntimeHealth.connection.value.connected)
                    hub.disconnect()
                    awaitState {
                        hub.connections.get() > count && RuntimeHealth.connection.value.connected
                    }
                    val stop =
                        context
                            .getSystemService(NotificationManager::class.java)
                            .activeNotifications
                            .first { it.id == 1 }
                            .notification
                            .actions
                            .first()
                            .actionIntent
                    stop.send()
                    awaitState { !RuntimeHealth.connection.value.running && !config.keepAlive }
                    assertFalse(startRealtimeIfEnabled(context))
                    awaitState {
                        context
                            .getSystemService(NotificationManager::class.java)
                            .activeNotifications
                            .none { it.id == 1 }
                    }
                }
            }
        }

    @Test
    fun aMessageArrivingAfterLeavingTheActivityIsDownloadedAndNotified() =
        withConfig { context, config ->
            if (Build.VERSION.SDK_INT >= 33) {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                    .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            }
            context.getSystemService(NotificationManager::class.java).cancel(2)
            Store(context).use { it.resetSyncedCache(1) }
            Hub(withMessage = true).use { hub ->
                config.url = hub.url
                config.token = "s".repeat(64)
                config.deviceId = "viewer-fixture"
                config.keepAlive = true
                config.lastSync = 0
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitState { RuntimeHealth.connection.value.connected && config.lastSync > 0 }
                    scenario.onActivity { it.leaveForBackground() }
                    hub.publishMessage()
                    awaitState {
                        Store(context).use { it.messages(sender = "background-fixture").any { m -> m.id == 1L } }
                    }
                    awaitState {
                        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 2 }
                    }
                }
            }
        }

    @Test
    fun missingCredentialsCannotStartRealtimeEvenWithBothSwitchesEnabled() =
        withConfig { context, config ->
            config.keepAlive = true
            config.restoreRealtimeOnBoot = true
            assertFalse(startRealtimeIfEnabled(context))
            assertFalse(
                restoreRealtimeOnBoot(config.restoreRealtimeOnBoot, config.keepAlive, false, true)
            )
            assertFalse(RuntimeHealth.connection.value.running)
        }

    @Test
    fun unauthorizedConnectionStopsWithoutRestartingOrLeavingANotification() =
        withConfig { context, config ->
            Hub(unauthorized = true).use { hub ->
                config.url = hub.url
                config.token = "s".repeat(64)
                config.keepAlive = true
                ActivityScenario.launch(MainActivity::class.java).use {
                    awaitState {
                        hub.requests.get() > 0 &&
                            config.token.isEmpty() &&
                            !RuntimeHealth.connection.value.running
                    }
                    assertFalse(startRealtimeIfEnabled(context))
                    awaitState {
                        context
                            .getSystemService(NotificationManager::class.java)
                            .activeNotifications
                            .none { it.id == 1 }
                    }
                }
            }
        }
}
