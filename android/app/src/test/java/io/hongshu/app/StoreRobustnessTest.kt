package io.hongshu.app

import android.app.Application
import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreRobustnessTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun withName(test: (String) -> Unit) {
        val name = "robust-${java.util.UUID.randomUUID()}.db"
        try {
            test(name)
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun connectionsUseWalAndWaitForConcurrentWriters() = withName { name ->
        Store(context, name).use { store ->
            assertTrue(store.readableDatabase.isWriteAheadLoggingEnabled)
            // Writers share the primary connection; a transaction pins the query to it.
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                db.rawQuery("PRAGMA busy_timeout", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(10000, it.getInt(0))
                }
            } finally {
                db.endTransaction()
            }
        }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writers =
            (0 until 3).map { writer ->
                thread {
                    try {
                        Store(context, name).use { store ->
                            repeat(100) { i ->
                                store.queue(SmsRecord("+8613800000000", "10086", "w$writer-$i", 1000L + i, 7))
                            }
                        }
                    } catch (e: Throwable) {
                        failures.add(e)
                    }
                }
            }
        writers.forEach { it.join(30_000) }
        assertEquals(emptyList<Throwable>(), failures)
        Store(context, name).use { assertEquals(300, it.pendingCount()) }
    }

    @Test
    fun lockedDatabaseIsRetriedBeforeFailing() {
        var calls = 0
        assertEquals(
            "saved",
            retryLockedDatabase {
                if (++calls < 3) throw SQLiteDatabaseLockedException("synthetic")
                "saved"
            },
        )
        assertEquals(3, calls)
        calls = 0
        try {
            retryLockedDatabase { calls++; throw SQLiteDatabaseLockedException("synthetic") }
            fail("persistent lock reported success")
        } catch (_: SQLiteDatabaseLockedException) {}
        assertEquals(3, calls)
    }

    @Test
    fun implausibleNetworkTimeIsReplacedButStillIdentifiesRedelivery() = withName { name ->
        val receivedAt = 1_700_000_000_000L
        assertEquals(receivedAt - 60_000, effectiveSmsTime(receivedAt - 60_000, receivedAt))
        assertEquals(receivedAt, effectiveSmsTime(receivedAt - 3 * 3600_000L, receivedAt))
        assertEquals(receivedAt, effectiveSmsTime(0, receivedAt))
        val skewed = IncomingSms("10086", "synthetic", receivedAt - 3 * 3600_000L, 7, 0)
        val corrected = skewed.withReceiveTime(receivedAt)
        assertEquals(receivedAt, corrected.timestamp)
        assertEquals(skewed.timestamp, corrected.networkTime)
        assertSame(corrected, corrected.withReceiveTime(receivedAt))
        val near = skewed.copy(timestamp = receivedAt - 5_000)
        assertSame(near, near.withReceiveTime(receivedAt))
        Store(context, name).use { store ->
            fun record(received: Long) =
                SmsRecord("", "10086", "synthetic", received, 7, networkTime = skewed.timestamp)
            assertTrue(store.queue(record(receivedAt)))
            // The same broadcast delivered again later is a duplicate, not a second SMS.
            assertFalse(store.queue(record(receivedAt + 5_000)))
            assertEquals(receivedAt, store.localPending().single().timestamp)
            // copy(timestamp = ...) without a network time gets its own identity.
            assertTrue(store.queue(SmsRecord("", "10086", "synthetic", receivedAt + 9_000, 7)))
        }
    }
}
