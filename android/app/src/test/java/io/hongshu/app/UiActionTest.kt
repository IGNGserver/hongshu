package io.hongshu.app

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A SnackbarHostState without a composed SnackbarHost never completes showSnackbar, exactly like
// the full-screen settings destination that does not compose the main host.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UiActionTest {
    private class Run(val busy: MutableList<Boolean>, val finished: CompletableDeferred<Unit>)

    private fun start(
        scope: CoroutineScope,
        snackbar: SnackbarHostState,
        announce: Boolean,
        task: suspend () -> String,
    ): Pair<Job, Run> {
        val run = Run(mutableListOf(), CompletableDeferred())
        val job =
            scope.launchUiAction(
                snackbar,
                announce,
                setBusy = { run.busy += it },
                onFinished = { run.finished.complete(Unit) },
                task,
            )
        return job to run
    }

    @Test
    fun busyIsReleasedEvenWhenNoSnackbarHostIsOnScreen() = runBlocking {
        val snackbar = SnackbarHostState()
        val (job, run) = start(this, snackbar, announce = true) { "完成" }
        withTimeout(5000) { run.finished.await() }
        assertEquals(listOf(false), run.busy)
        // The message is pending on the absent host and must not hold the action open.
        withTimeout(5000) { while (snackbar.currentSnackbarData == null) yield() }
        assertEquals("完成", snackbar.currentSnackbarData?.visuals?.message)
        job.cancel()
    }

    @Test
    fun settingsActionsNeverQueueOnTheMainHost() = runBlocking {
        val snackbar = SnackbarHostState()
        val (job, run) = start(this, snackbar, announce = false) { "完成" }
        withTimeout(5000) { run.finished.await() }
        delay(100)
        assertEquals(listOf(false), run.busy)
        assertNull(snackbar.currentSnackbarData)
        job.join()
    }

    @Test
    fun failureReleasesBusyAndReportsTheMessage() = runBlocking {
        val snackbar = SnackbarHostState()
        val (job, run) = start(this, snackbar, announce = true) { error("请先连接中枢") }
        withTimeout(5000) { run.finished.await() }
        assertEquals(listOf(false), run.busy)
        withTimeout(5000) { while (snackbar.currentSnackbarData == null) yield() }
        assertEquals("请先连接中枢", snackbar.currentSnackbarData?.visuals?.message)
        job.cancel()
    }

    @Test
    fun cancelledActionStillReleasesBusy() = runBlocking {
        val snackbar = SnackbarHostState()
        val started = CompletableDeferred<Unit>()
        val (job, run) =
            start(this, snackbar, announce = true) {
                started.complete(Unit)
                delay(60_000)
                "不会到达"
            }
        withTimeout(5000) { started.await() }
        job.cancel()
        job.join()
        assertEquals(listOf(false), run.busy)
        assertTrue(run.finished.isCompleted)
        assertNull(snackbar.currentSnackbarData)
    }
}
