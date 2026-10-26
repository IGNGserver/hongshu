package io.hongshu.app

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RealtimeDeliveryTest {
    private fun exercise(test: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { coroutineScope { test() } }
    }

    @Test fun eventsDuringAnInFlightPassCoalesceButAreNeverLost() = exercise {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val passes = AtomicInteger()
        val runner = RealtimeDeliveryRunner(this, synchronize = {
            if (passes.incrementAndGet() == 1) { entered.complete(Unit); finish.await() }
            true
        }, capture = {}, nextRelease = { null }, fallback = {}, clock = { 100L })
        try {
            runner.request()
            entered.await()
            repeat(100) { runner.request() }
            finish.complete(Unit)
            runner.health.first { it.lastSuccess > 0 && it.pendingSince == 0L }
            yield()
            assertEquals(2, passes.get())
        } finally { runner.close() }
    }

    @Test fun blockedCaptureDoesNotBlockDownloadingAndItsCompletionGetsASuccessor() = exercise {
        val captureEntered = CompletableDeferred<Unit>()
        val finishCapture = CompletableDeferred<Unit>()
        val downloaded = CompletableDeferred<Unit>()
        val passes = AtomicInteger()
        val runner = RealtimeDeliveryRunner(this, synchronize = {
            passes.incrementAndGet(); downloaded.complete(Unit); true
        }, capture = { captureEntered.complete(Unit); finishCapture.await() },
            nextRelease = { null }, fallback = {}, clock = { 100L })
        try {
            runner.request(capture = true)
            captureEntered.await()
            downloaded.await()
            assertFalse(finishCapture.isCompleted)
            finishCapture.complete(Unit)
            while (passes.get() < 2) yield()
        } finally { runner.close() }
    }

    @Test fun failureRetriesWithoutAnyWorkerAndKeepsOnlyASafeFailureCategory() = exercise {
        val failed = CompletableDeferred<Unit>()
        val passes = AtomicInteger()
        val runner = RealtimeDeliveryRunner(this, synchronize = {
            if (passes.incrementAndGet() == 1) throw IOException("sensitive transport detail")
            true
        }, capture = {}, nextRelease = { null }, fallback = { failed.complete(Unit) },
            clock = { 100L }, retryMillis = 20)
        try {
            runner.request()
            failed.await()
            assertTrue(runner.health.value.pendingSince > 0)
            assertFalse(runner.health.value.error.contains("sensitive"))
            runner.health.first { it.lastSuccess > 0 && it.pendingSince == 0L }
            assertEquals(2, passes.get())
            assertEquals("", runner.health.value.error)
        } finally { runner.close() }
    }

    @Test fun captureFailureIsIndependentOfHealthyNetworkDelivery() = exercise {
        val runner = RealtimeDeliveryRunner(this, synchronize = { true },
            capture = { throw IllegalStateException("private provider detail") },
            nextRelease = { null }, fallback = {}, clock = { 100L })
        try {
            runner.request(capture = true)
            runner.health.first { it.captureError.isNotEmpty() && it.lastSuccess > 0 && it.pendingSince == 0L }
            assertFalse(runner.health.value.captureError.contains("private"))
            assertEquals("", runner.health.value.error)
        } finally { runner.close() }
    }

    @Test fun continuousEventsDoNotPostponeTheFirstNotificationRelease() = exercise {
        val reread = CompletableDeferred<Unit>()
        var hold = true
        val runner = RealtimeDeliveryRunner(this, synchronize = { true },
            capture = { hold = false; reread.complete(Unit) },
            nextRelease = { if (hold) 80L else null }, fallback = {}, clock = { System.nanoTime() / 1_000_000 })
        try {
            runner.request()
            val burst = launch { repeat(30) { delay(15); runner.request() } }
            withTimeout(300) { reread.await() }
            burst.cancelAndJoin()
        } finally { runner.close() }
    }

    @Test fun closeCancelsRetryAndOldServiceCannotDetachItsReplacement() = exercise {
        val failed = CompletableDeferred<Unit>()
        val passes = AtomicInteger()
        val first = RealtimeDeliveryRunner(this, synchronize = {
            passes.incrementAndGet(); throw IOException()
        }, capture = {}, nextRelease = { null }, fallback = { failed.complete(Unit) },
            clock = { 100L }, retryMillis = 20)
        val replacement = RealtimeDeliveryRunner(this, synchronize = { true }, capture = {},
            nextRelease = { null }, fallback = {}, clock = { 100L })
        try {
            RealtimeDelivery.attach(first)
            RealtimeDelivery.request()
            failed.await()
            first.close()
            RealtimeDelivery.attach(replacement)
            RealtimeDelivery.detach(first)
            RealtimeDelivery.request()
            replacement.health.first { it.lastSuccess > 0 }
            delay(50)
            first.request()
            assertEquals(1, passes.get())
        } finally {
            RealtimeDelivery.detach(replacement)
            first.close(); replacement.close()
        }
    }

    @Test fun notificationRereadIsNotDependentOnNetworkSuccessOrAWorkerTimer() = exercise {
        val reread = CompletableDeferred<Unit>()
        val runner = RealtimeDeliveryRunner(this,
            synchronize = { throw IOException() }, capture = { reread.complete(Unit) },
            nextRelease = { null }, fallback = {}, clock = { System.nanoTime() / 1_000_000 })
        try {
            runner.request()
            runner.rereadAfter(20)
            reread.await()
        } finally { runner.close() }
    }

    @Test fun handshakeAloneIsNotHealthyAndPendingWorkBecomesStalled() {
        val connection = ConnectionHealth(true, true, "实时连接已建立")
        assertTrue(deliveryDetail(connection, DeliveryHealth(), 1).contains("等待首次"))
        assertTrue(deliveryDetail(connection, DeliveryHealth(pendingSince = 100), 200).contains("正在同步"))
        assertTrue(deliveryDetail(connection, DeliveryHealth(pendingSince = 100), 30_000).contains("同步延迟"))
        assertTrue(deliveryDetail(connection, DeliveryHealth(lastSuccess = 100), 200).contains("同步正常"))
        assertTrue(deliveryDetail(connection, DeliveryHealth(error = "failed"), 200).contains("同步失败"))
        assertEquals("无网络", deliveryDetail(ConnectionHealth(true, false, "无网络"), DeliveryHealth(), 200))
    }
}
