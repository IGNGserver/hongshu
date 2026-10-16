package io.hongshu.app

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.*
import org.junit.Test

class SyncSchedulingTest {
    @Test
    fun idleAndFinishedWorkAllowNewSync() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, syncWorkPolicy(emptyList(), false))
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncWorkPolicy(listOf(WorkInfo.State.SUCCEEDED to 0, WorkInfo.State.FAILED to 1), false),
        )
    }

    @Test
    fun arrivalDuringRunningSyncGetsOneSuccessor() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncWorkPolicy(listOf(WorkInfo.State.RUNNING to 0), false),
        )
        assertNull(
            syncWorkPolicy(listOf(WorkInfo.State.RUNNING to 0, WorkInfo.State.BLOCKED to 0), false)
        )
    }

    @Test
    fun offlineAndBackoffWorkCoalesceWithoutReplacement() {
        assertNull(syncWorkPolicy(listOf(WorkInfo.State.ENQUEUED to 0), false))
        assertNull(syncWorkPolicy(listOf(WorkInfo.State.ENQUEUED to 3), false))
    }

    @Test
    fun urgentTriggerReplacesParkedWorkButNeverCancelsRunningSync() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            syncWorkPolicy(listOf(WorkInfo.State.ENQUEUED to 3), true),
        )
        // Also a delayed ceiling retry or offline wait (attempt 0): the new SMS should not
        // sit behind it.
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            syncWorkPolicy(listOf(WorkInfo.State.ENQUEUED to 0), true),
        )
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncWorkPolicy(listOf(WorkInfo.State.RUNNING to 3), true),
        )
        assertNull(
            syncWorkPolicy(listOf(WorkInfo.State.RUNNING to 0, WorkInfo.State.BLOCKED to 0), true)
        )
    }

    @Test
    fun retriesStopDoublingAtTheCeiling() {
        for (attempt in 0 until SYNC_RETRY_ATTEMPTS) assertEquals(0L, syncRetryDelay(attempt))
        assertEquals(SYNC_RETRY_CEILING_MS, syncRetryDelay(SYNC_RETRY_ATTEMPTS))
        assertEquals(SYNC_RETRY_CEILING_MS, syncRetryDelay(50))
    }
}
