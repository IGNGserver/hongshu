package io.hongshu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun newInstallStartsWithTheHubAndCannotBeHidden() {
        val progress = setupProgress(SetupFacts(), simsConfigured = false, dismissed = true)

        assertEquals(listOf("hub", "capture"), progress.steps.map { it.key })
        assertTrue(progress.visible)
        assertFalse(progress.dismissible)
    }

    @Test
    fun captureAddsSimAndRealtimeSteps() {
        val facts = SetupFacts(connected = true, upload = true)

        val progress = setupProgress(facts, simsConfigured = false, dismissed = false)

        assertEquals(listOf("hub", "capture", "sims", "realtime"), progress.steps.map { it.key })
        assertEquals(2, progress.completed)
    }

    @Test
    fun dismissingHidesTheGuideOnceConnected() {
        val facts = SetupFacts(connected = true)

        assertTrue(setupProgress(facts, false, dismissed = false).visible)
        assertFalse(setupProgress(facts, false, dismissed = true).visible)
    }

    @Test
    fun finishedSetupHidesItself() {
        val facts = SetupFacts(connected = true, upload = true, keepAlive = true)

        assertFalse(setupProgress(facts, simsConfigured = true, dismissed = false).visible)
    }
}
