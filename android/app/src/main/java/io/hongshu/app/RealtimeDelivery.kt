package io.hongshu.app

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val REALTIME_STALLED_MS = 20_000L
internal const val REALTIME_RETRY_MS = 30_000L

internal data class DeliveryHealth(
    val pendingSince: Long = 0,
    val startedAt: Long = 0,
    val lastSuccess: Long = 0,
    val error: String = "",
    val captureError: String = "",
)

internal fun deliveryDetail(transport: ConnectionHealth, delivery: DeliveryHealth, now: Long): String {
    if (!transport.connected) return transport.detail
    val detail = when {
        delivery.error.isNotEmpty() -> "同步失败，正在重试"
        delivery.pendingSince > 0 && now - delivery.pendingSince >= REALTIME_STALLED_MS ->
            "同步延迟，请查看诊断"
        delivery.pendingSince > 0 -> "正在同步短信"
        delivery.lastSuccess > 0 -> "短信同步正常"
        else -> "等待首次短信同步"
    }
    val capture = if (delivery.captureError.isNotEmpty()) " · 收件箱补读异常" else ""
    return "${transport.detail} · $detail$capture"
}

// Only an already-started foreground service registers here. Capture/WS/UI triggers never
// start a service behind the user's back; their durable WorkManager request is independent.
internal object RealtimeDelivery {
    private val active = AtomicReference<RealtimeDeliveryRunner?>()

    val pending: Boolean get() = active.get()?.health?.value?.let {
        it.pendingSince > 0 && it.error.isEmpty()
    } == true

    fun attach(runner: RealtimeDeliveryRunner) { active.set(runner) }

    fun detach(runner: RealtimeDeliveryRunner) { active.compareAndSet(runner, null) }

    fun request(capture: Boolean = false) { active.get()?.request(capture) }

    fun rereadAfter(delayMillis: Long) { active.get()?.rereadAfter(delayMillis) }
}

// Separate actors: a slow/blocked SMS provider cannot hold up downloading other devices'
// messages. Each channel retains one successor, including arrivals during the last batch.
internal class RealtimeDeliveryRunner(
    private val scope: CoroutineScope,
    private val synchronize: suspend () -> Boolean,
    private val capture: suspend () -> Unit,
    private val nextRelease: suspend () -> Long?,
    private val fallback: suspend (Exception) -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() + 1 },
    private val retryMillis: Long = REALTIME_RETRY_MS,
) {
    private val syncSignals = Channel<Unit>(Channel.CONFLATED)
    private val captureSignals = Channel<Unit>(Channel.CONFLATED)
    private val version = AtomicLong()
    private val closed = AtomicBoolean()
    private val mutableHealth = MutableStateFlow(DeliveryHealth())
    val health = mutableHealth.asStateFlow()
    private var retry: Job? = null
    private var release: Job? = null
    private var releaseAt = Long.MAX_VALUE
    private var reread: Job? = null
    private var rereadAt = Long.MAX_VALUE

    private val syncJob = scope.launch {
        for (signal in syncSignals) {
            val ticket = version.get()
            mutableHealth.update { it.copy(startedAt = clock()) }
            try {
                val complete = synchronize()
                retry?.cancel()
                mutableHealth.update {
                    it.copy(lastSuccess = clock(), error = "",
                        pendingSince = if (complete && version.get() == ticket) 0 else it.pendingSince)
                }
                scheduleRelease(nextRelease())
                if (!complete) {
                    // Bounded pages yield instead of spinning through a large history backlog.
                    delay(250)
                    request()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableHealth.update { it.copy(error = syncFailureReason(e)) }
                // Scheduling failure must not kill the live executor or erase durable captures.
                try { fallback(e) } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* periodic work remains a second fallback */ }
                if (!isActive) return@launch
                retry?.cancel()
                retry = scope.launch { delay(retryMillis); request() }
            }
        }
    }

    private val captureJob = scope.launch {
        for (signal in captureSignals) {
            try {
                capture()
                mutableHealth.update { it.copy(captureError = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableHealth.update { it.copy(captureError = "收件箱补读失败，等待补漏任务重试") }
            } finally {
                if (isActive) request()
            }
        }
    }

    fun request(capture: Boolean = false) {
        if (closed.get()) return
        version.incrementAndGet()
        mutableHealth.update {
            if (it.pendingSince == 0L) it.copy(pendingSince = clock()) else it
        }
        syncSignals.trySend(Unit)
        if (capture) captureSignals.trySend(Unit)
    }

    // Keep the earliest deadline: bursts must not postpone a held notification forever.
    private fun scheduleRelease(delayMillis: Long?) {
        if (delayMillis == null) {
            release?.cancel()
            release = null
            releaseAt = Long.MAX_VALUE
            return
        }
        val deadline = clock() + delayMillis
        if (release?.isActive == true && releaseAt <= deadline) return
        release?.cancel()
        releaseAt = deadline
        release = scope.launch {
            delay(delayMillis)
            releaseAt = Long.MAX_VALUE
            request(capture = true)
        }
    }

    fun rereadAfter(delayMillis: Long) {
        scope.launch {
            if (closed.get()) return@launch
            val deadline = clock() + delayMillis
            if (reread?.isActive == true && rereadAt <= deadline) return@launch
            reread?.cancel()
            rereadAt = deadline
            reread = scope.launch {
                delay(delayMillis)
                rereadAt = Long.MAX_VALUE
                request(capture = true)
            }
        }
    }

    fun close() {
        closed.set(true)
        syncSignals.close()
        captureSignals.close()
        syncJob.cancel()
        captureJob.cancel()
        retry?.cancel()
        release?.cancel()
        reread?.cancel()
    }
}

internal fun syncFailureReason(error: Exception): String = when (error) {
    is ApiException -> error.message ?: "中枢拒绝同步，将自动重试"
    is java.io.IOException -> "无法连接中枢：请检查网络或中枢地址，将自动重试"
    else -> "同步失败：协议或本地存储异常，将自动重试"
}

internal suspend fun <T> withDeliveryWakeLock(context: Context, block: suspend () -> T): T {
    val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hongshu:delivery")
    lock.acquire(60_000)
    return try { block() } finally { if (lock.isHeld) lock.release() }
}
