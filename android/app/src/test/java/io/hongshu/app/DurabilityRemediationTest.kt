package io.hongshu.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DurabilityRemediationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun database(test: (String) -> Unit) {
        val name = "durability-${UUID.randomUUID()}.db"
        try { test(name) } finally { context.deleteDatabase(name) }
    }

    @Test fun broadcastRecoverySurvivesRestartAndAcknowledgesOnlyHandledRows() = database { name ->
        val first = IncomingSms("10086", "synthetic broadcast", 100, 7, 0, null)
        val second = first.copy(body = "synthetic second", timestamp = 101)
        Store(context, name).use { store ->
            assertTrue(store.enqueueBroadcast(first, 1, 100) > 0)
            assertTrue(store.enqueueBroadcast(second, 1, 101) > 0)
            assertEquals(2, store.broadcastQueueCount())
        }
        Store(context, name).use { store ->
            val queued = store.queuedBroadcasts()
            assertEquals(listOf(100L, 101L), queued.map { it.message.timestamp })
            store.deleteBroadcast(queued.first().id)
            assertEquals(listOf(101L), store.queuedBroadcasts().map { it.message.timestamp })
            store.retryBroadcast(999)
            assertEquals(1, store.broadcastQueueCount())
        }
    }

    @Test fun versionSeventeenUpgradeAddsGenerationAndRecoveryStructures() = database { name ->
        Store(context, name).use { store ->
            store.writableDatabase.execSQL("ALTER TABLE outbox DROP COLUMN sent_fingerprint")
            store.writableDatabase.execSQL("DROP TABLE broadcast_inbox")
            store.writableDatabase.execSQL("DROP INDEX outbox_receiver_timeline")
            store.writableDatabase.execSQL("DROP INDEX outbox_subscription_timeline")
            store.writableDatabase.version = 17
        }
        Store(context, name).use { store ->
            assertEquals(18, store.readableDatabase.version)
            store.readableDatabase.rawQuery("PRAGMA table_info(outbox)", null).use { cursor ->
                val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toSet()
                assertTrue("sent_fingerprint" in columns)
            }
            assertTrue(store.enqueueBroadcast(IncomingSms("10086", "after upgrade", 1, 7, 0, null), 1, 1) > 0)
            assertEquals(1, store.broadcastQueueCount())
        }
    }

    @Test fun identityResetClearsBothUploadAndBroadcastQueues() = database { name ->
        Store(context, name).use { store ->
            store.queue(SmsRecord("+1", "10086", "private", 1, 7))
            store.enqueueBroadcast(IncomingSms("10086", "broadcast", 2, 7, 0, null), 1, 2)
            store.resetForNewIdentity(clearCaptures = true)
            assertEquals(0, store.pendingCount())
            assertEquals(0, store.broadcastQueueCount())
            assertTrue(store.pending(false).isEmpty())
        }
    }

    @Test fun simFilterScansPastFiveHundredUnrelatedRows() = database { name ->
        Store(context, name).use { store ->
            repeat(550) { index ->
                store.queue(SmsRecord("+1111111111", "10086", "unrelated-$index", index + 10L, 7))
            }
            store.queue(SmsRecord("+2222222222", "10086", "matching", 1, 7))
            val messages = store.localMessages(sim = "+2222222222")
            assertEquals(1, messages.size)
            assertEquals("matching", messages.single().body)
        }
    }
}
