package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

/**
 * A phone that no longer reports a subscription must stop presenting it as a current line.
 * The mapping row is dropped, but receiver snapshots already captured on old messages are
 * never rewritten: the display layer turns the now-unmapped line into its tail digits.
 */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SimMappingLifecycleTest {
    private fun withStore(test: (Store) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "sim-lifecycle-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun authoritativeUpdateRemovesAnUnpluggedSimWithoutRewritingSnapshots() = withStore { store ->
        val first = SimMapping(7, 0, "+8613800000000", "SIM1")
        val second = SimMapping(8, 1, "+8613900000000", "SIM2")
        store.updateSimMappings(listOf(first, second), automatic = true, authoritative = true)
        assertEquals(listOf(first, second), store.sims())
        store.queue(SmsRecord(second.phone, "10086", "before removal", 100, 8))

        // The system now reports only the card still in the tray.
        store.updateSimMappings(listOf(first), automatic = true, authoritative = true)
        assertEquals(listOf(first), store.sims())
        // Captured receiver snapshots are not rewritten by removing a mapping.
        assertEquals(second.phone, store.pending().single().second.receiver)
    }

    @Test
    fun emptyScanNeverPrunesTheMappings() = withStore { store ->
        val first = SimMapping(7, 0, "+8613800000000", "SIM1")
        store.updateSimMappings(listOf(first), automatic = true, authoritative = true)
        // A transient empty or telephony-unavailable scan is not an authoritative set.
        store.updateSimMappings(emptyList(), automatic = true, authoritative = true)
        assertEquals(listOf(first), store.sims())
        store.updateSimMappings(emptyList(), automatic = true, authoritative = false)
        assertEquals(listOf(first), store.sims())
    }

    @Test
    fun manualPruneDropsOnlySubscriptionsTheSystemNoLongerReports() = withStore { store ->
        val first = SimMapping(7, 0, "+8613800000000", "SIM1")
        val second = SimMapping(8, 1, "+8613900000000", "SIM2")
        store.updateSimMappings(listOf(first, second), automatic = false, authoritative = true)

        assertEquals(1, store.pruneInactiveSimMappings(setOf(7)))
        assertEquals(listOf(first), store.sims())
        assertEquals(0, store.pruneInactiveSimMappings(setOf(7)))
        assertEquals(listOf(first), store.sims())
        // An empty active set never means "no cards": it means the scan is unusable.
        assertEquals(0, store.pruneInactiveSimMappings(emptySet()))
        assertEquals(listOf(first), store.sims())
    }

    @Test
    fun unmappedLineFallsBackToItsTailDigits() {
        val first = SimMapping(7, 0, "+8613800000000", "SIM1")
        assertEquals("卡1 · 0000", simLabel(first.phone, listOf(first)))
        // After the card is removed the line is still real history, but no longer a card slot.
        assertEquals("尾号 5678", simLabel("+8613956785678", listOf(first)))
    }
}
