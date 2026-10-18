package io.hongshu.app

import org.junit.Assert.*
import org.junit.Test

class CaptureStateTest {
    @Test
    fun pendingReasonsDoNotHideUnknownOrInvalidRecords() {
        assertEquals(PendingState.NEEDS_HUB, pendingState("known", "", true, true, true, false))
        assertEquals(PendingState.NEEDS_SIM, pendingState("", "", true, true, true))
        assertEquals(
            PendingState.NEEDS_SIM,
            pendingState("known", "sim_not_confirmed", true, true, true),
        )
        assertEquals(PendingState.INVALID, pendingState("", "invalid_message", true, true, true))
        // A notification row first waits for its SMS copy, then names its real limitation.
        assertEquals(PendingState.NOTIFICATION_HELD,
            pendingState("", "", true, true, true, notificationOnly = true, notificationHeld = true))
        assertEquals(PendingState.NOTIFICATION_ONLY, pendingState("", "", true, true, true, notificationOnly = true))
        assertEquals(PendingState.WAITING_UPLOAD, pendingState("known", "", true, true, true, notificationOnly = true))
        assertEquals("未上传 2 条：1 条等待上传 · 1 条正在与收件箱核对", PendingCounts(ready = 1, held = 1).label())
        assertEquals("有 1 条本机短信尚未上传到中枢，正在与收件箱核对", PendingCounts(held = 1).banner())
        assertEquals(PendingState.PAUSED, pendingState("known", "", false, true, true))
        assertEquals(PendingState.SERVER_DENIED, pendingState("known", "", true, false, true))
        assertEquals(PendingState.WAITING_NETWORK, pendingState("known", "", true, true, false))
        assertEquals(PendingState.WAITING_UPLOAD, pendingState("known", "", true, true, true))
    }
}
