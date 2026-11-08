package io.hongshu.app

import java.io.EOFException
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCancellationTest {
    @Test fun coroutineCancellationCancelsTheRealCallAndClosesItsSocket() = runBlocking {
        ServerSocket(0).use { server ->
            val requestReceived = CountDownLatch(1)
            // -1 means the peer saw the socket close; Int.MIN_VALUE means it never got there.
            val peerRead = AtomicInteger(Int.MIN_VALUE)
            val responder = thread(isDaemon = true, name = "cancellation-test-server") {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next == -1) throw EOFException("request ended before headers")
                        header.append(next.toChar())
                    }
                    requestReceived.countDown()
                    peerRead.set(try { input.read() } catch (_: SocketException) { -1 })
                }
            }
            val started = AtomicReference<Call>()
            val client = OkHttpClient.Builder()
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { started.set(call) }
                })
                .build()
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/api/sync").build()
            val cancellationObserved = AtomicBoolean()
            // Dispatchers.IO keeps the blocking latch wait below off the call's own thread.
            val job = launch(Dispatchers.IO) {
                try {
                    client.newCall(request).awaitResponse()
                } catch (e: CancellationException) {
                    cancellationObserved.set(true)
                    throw e
                }
            }
            try {
                assertTrue("server never received the request", requestReceived.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) { while (started.get() == null) delay(10) }
                job.cancelAndJoin()
                assertTrue("cancellation must surface through the suspend call", cancellationObserved.get())
                assertTrue("Coroutine cancellation must cancel the OkHttp call", started.get().isCanceled())
                responder.join(5_000)
                assertFalse("server stayed blocked after Call.cancel()", responder.isAlive)
                assertEquals("cancelled call must close the peer socket", -1, peerRead.get())
            } finally {
                job.cancel()
                started.get()?.cancel()
            }
        }
    }
}
