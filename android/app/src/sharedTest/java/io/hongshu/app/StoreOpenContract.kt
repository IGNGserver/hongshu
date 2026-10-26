package io.hongshu.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

abstract class StoreOpenContract {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun concurrentFirstOpenCreatesOneCompleteSchema() = concurrentOpen(upgrade = false)

    @Test
    fun concurrentUpgradeKeepsExistingQueueAndCursor() = concurrentOpen(upgrade = true)

    private fun concurrentOpen(upgrade: Boolean) {
        repeat(3) {
            val name = "open-${UUID.randomUUID()}.db"
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            if (upgrade) {
                Store(context, name).use { store ->
                    store.queue(SmsRecord("+123456789", "fixture", "existing synthetic SMS", 1, 7))
                    store.writableDatabase.execSQL("UPDATE meta SET value=42 WHERE key='cursor'")
                    // v17 adds only derived call-cache state; v16 SMS data must survive.
                    store.writableDatabase.version = 16
                }
            }
            val openers = (0 until 8).map { index ->
                thread(isDaemon = true) {
                    try {
                        Store(context, name).use { store ->
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS)) { "open barrier timed out" }
                            val db = if (index % 2 == 0) store.readableDatabase else store.writableDatabase
                            assertEquals(17, db.version)
                            assertEquals(if (upgrade) 42L else 0L, store.cursor())
                            assertEquals(if (upgrade) 1 else 0, store.pendingCount())
                            // Check a late-created table as well as the first table.
                            db.rawQuery("SELECT COUNT(*) FROM read_outbox", null).use {
                                assertTrue(it.moveToFirst())
                                assertEquals(0, it.getInt(0))
                            }
                        }
                    } catch (error: Throwable) {
                        failures.add(error)
                    }
                }
            }
            try {
                assertTrue("all helpers reached first open", ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                openers.forEach { it.join(20_000) }
                assertFalse("database open deadlocked", openers.any { it.isAlive })
                assertEquals(emptyList<Throwable>(), failures)
                Store(context, name).use {
                    assertEquals(if (upgrade) 1 else 0, it.pendingCount())
                    assertEquals(if (upgrade) 42L else 0L, it.cursor())
                }
            } finally {
                start.countDown()
                openers.forEach { it.join(20_000) }
                if (openers.none { it.isAlive }) context.deleteDatabase(name)
            }
        }
    }
}
