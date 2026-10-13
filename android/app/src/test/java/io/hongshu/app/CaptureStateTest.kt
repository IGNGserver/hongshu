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
        assertEquals(PendingState.PAUSED, pendingState("known", "", false, true, true))
        assertEquals(PendingState.SERVER_DENIED, pendingState("known", "", true, false, true))
        assertEquals(PendingState.WAITING_NETWORK, pendingState("known", "", true, true, false))
        assertEquals(PendingState.WAITING_UPLOAD, pendingState("known", "", true, true, true))
    }
}
