package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreClosedReadTest {
    @Test
    fun readsAfterCloseReturnFallbackWithoutTouchingTheDatabase() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "closed-${java.util.UUID.randomUUID()}.db"
        val store = Store(context, name)
        assertEquals(0, store.readUnlessClosed(0) { pendingCount() })
        store.close()
        var ran = false
        assertEquals(
            -1,
            store.readUnlessClosed(-1) {
                ran = true
                pendingCount()
            },
        )
        assertFalse(ran)
        context.deleteDatabase(name)
    }

    @Test
    fun failureAfterCloseIsDroppedButOtherFailuresStillPropagate() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "closed-${java.util.UUID.randomUUID()}.db"
        val store = Store(context, name)
        assertEquals(
            "fallback",
            store.readUnlessClosed("fallback") {
                close()
                throw IllegalStateException("Cannot perform this operation because the connection pool has been closed.")
            },
        )
        val live = Store(context, name)
        try {
            live.readUnlessClosed(Unit) { throw IllegalStateException("synthetic") }
            fail("unrelated failure was hidden")
        } catch (_: IllegalStateException) {
        } finally {
            live.close()
            context.deleteDatabase(name)
        }
    }
}
