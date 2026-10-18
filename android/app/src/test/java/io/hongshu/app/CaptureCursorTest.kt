package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

// The history and new-SMS recovery passes read the same inbox; they must not read it twice.
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CaptureCursorTest {
    private val phone = "+8613800000000"

    private fun withStore(test: (Store) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "cursor-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun historyProgressAlsoAdvancesRecoveryButNotTheReverse() = withStore { store ->
        store.advanceRecoveryProvider(5)
        store.captureProviders(listOf(10L to SmsRecord(phone, "10086", "synthetic a", 1000, 7)), advance = true)
        assertEquals(10L, store.providerWatermark())
        assertEquals(10L, store.recoveryWatermark())
        // Recovery skips rows dated before its consent, so it cannot vouch for history.
        store.captureProviders(listOf(20L to SmsRecord(phone, "10086", "synthetic b", 2000, 7)), advance = false, recovery = true)
        assertEquals(10L, store.providerWatermark())
        assertEquals(20L, store.recoveryWatermark())
        // An incomplete row that history skips past still moves both cursors.
        store.advanceProvider(30)
        assertEquals(30L, store.providerWatermark())
        assertEquals(30L, store.recoveryWatermark())
    }
}
