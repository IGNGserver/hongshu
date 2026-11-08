package io.hongshu.app

import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class RealtimeResourceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun leaseIsRenewedAndReleasedWithoutReferenceCounting() = runBlocking {
        ShadowPowerManager.clearWakeLocks()
        val result = withDeliveryWakeLock(
            context,
            leaseMillis = 100,
            renewMillis = 10,
            timeoutMillis = 1_000,
        ) {
            delay(55)
            val lock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(lock.isHeld)
            assertFalse(shadowOf(lock).isReferenceCounted)
            assertTrue(shadowOf(lock).timesHeld >= 3)
            "done"
        }
        assertEquals("done", result)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun cancellationReleasesTheCurrentLease() = runBlocking {
        ShadowPowerManager.clearWakeLocks()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withDeliveryWakeLock(context, leaseMillis = 100, renewMillis = 20, timeoutMillis = 1_000) {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        withTimeout(1_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun totalTimeoutCancelsWorkAndReleasesTheLease() = runBlocking {
        ShadowPowerManager.clearWakeLocks()
        try {
            withDeliveryWakeLock(context, leaseMillis = 100, renewMillis = 20, timeoutMillis = 40) {
                delay(1_000)
            }
            fail("the bounded delivery task must time out")
        } catch (_: TimeoutCancellationException) {
            // Expected: the block and its renewal child share the same deadline.
        }
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun retryBackoffSurvivesConfigRecreationAndSuccessClearsIt() {
        val first = Config(context)
        first.clearSyncRetry()
        assertEquals(30_000L, first.noteSyncFailure(now = 1_000, jitter = 0.5))
        assertEquals(60_000L, first.noteSyncFailure(now = 2_000, jitter = 0.5))
        val restored = Config(context)
        assertEquals(51_000L, restored.syncRetryDelay(now = 11_000))
        restored.clearSyncRetry()
        assertEquals(0L, Config(context).syncRetryDelay(now = 11_000))
    }
}
