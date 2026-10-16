package io.hongshu.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class SyncReconcileTest {
    private lateinit var server: ServerSocket
    private val requests = CopyOnWriteArrayList<String>()
    private val status = AtomicInteger(200)
    private lateinit var config: Config

    // Minimal one-request-per-connection HTTP stub; the Android test classpath has no JDK server.
    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = it.getInputStream().bufferedReader()
                    val line = input.readLine() ?: return@use
                    var length = 0
                    while (true) {
                        val header = input.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.lowercase().startsWith("content-length:"))
                            length = header.substringAfter(':').trim().toInt()
                    }
                    if (length > 0) input.read(CharArray(length))
                    val parts = line.split(' ')
                    requests.add("${parts[0]} ${parts[1]}")
                    val code = status.get()
                    val body =
                        if (code == 200) "{\"ok\":true}" else "{\"error\":\"database_unavailable\"}"
                    val bytes = body.toByteArray()
                    it.getOutputStream().apply {
                        write(
                            "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("hongshu", Context.MODE_PRIVATE).edit().clear().commit()
        config = Config(context)
        config.url = "http://127.0.0.1:${server.localPort}"
        config.deviceId = "device-a"
    }

    @After fun stop() = server.close()

    @Test
    fun hubValueWinsUnlessLocalSwitchIsUnconfirmed() {
        config.upload = true
        assertFalse(reconcileUploadSwitch(Api(config), config, false))
        assertTrue(requests.isEmpty())

        config.uploadPatchPending = true
        status.set(503)
        assertFalse(reconcileUploadSwitch(Api(config), config, false))
        assertTrue(config.uploadPatchPending)

        status.set(200)
        assertTrue(reconcileUploadSwitch(Api(config), config, false))
        assertFalse(config.uploadPatchPending)
        assertEquals(listOf("PATCH /api/devices/device-a"), requests.distinct())

        config.uploadPatchPending = true
        requests.clear()
        assertTrue(reconcileUploadSwitch(Api(config), config, true))
        assertFalse(config.uploadPatchPending)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun registrationsAreCachedPerDeviceAndRetriedAfterRejection() {
        SimRegistrations(Api(config), config).apply {
            ensure("+8613800000000", "SIM1", 7)
            ensure("+8613800000000", "SIM1", 7)
        }
        assertEquals(1, requests.size)
        val next = SimRegistrations(Api(config), config)
        next.ensure("+8613800000000", "SIM1", 7)
        assertEquals(1, requests.size)
        assertEquals(setOf("+8613800000000"), next.forgetRejected(setOf("+8613800000000", "+1999")))
        next.ensure("+8613800000000", "SIM1", 7)
        assertEquals(2, requests.size)
        // A fresh registration that the hub still rejects is not released again.
        assertTrue(next.forgetRejected(setOf("+8613800000000")).isEmpty())
        config.deviceId = "device-b"
        SimRegistrations(Api(config), config).ensure("+8613800000000", "SIM1", 7)
        assertEquals(3, requests.size)
    }
}
