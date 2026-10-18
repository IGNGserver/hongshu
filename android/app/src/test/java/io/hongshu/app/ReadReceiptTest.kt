package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadReceiptTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun withStore(test: (String) -> Unit) {
        val name = "reads-${java.util.UUID.randomUUID()}.db"
        try { test(name) } finally { context.deleteDatabase(name) }
    }
    private fun message(id: Long, timestamp: Long = id) = JSONObject().put("id", id).put("device_id", "remote")
        .put("sender", "10086").put("receiver", "+1234567890").put("body", "synthetic notice")
        .put("timestamp", timestamp).put("historical", false)

    @Test fun repeatedReadDoesNotInvalidateUiAndPendingReceiptSurvivesRestart() = withStore { name ->
        Store(context, name).use { s ->
            s.applySync(JSONArray().put(message(1)).put(message(2)), 2)
            assertTrue(s.markRead("10086", 1))
            val changes = Store.changes.value
            repeat(20) { assertFalse(s.markRead("10086", 1)) }
            assertFalse(s.markRead("10086", 999))
            assertEquals(changes, Store.changes.value)
            assertEquals(1, s.unreadCounts()["10086"])
        }
        Store(context, name).use { s ->
            assertEquals(listOf("10086" to 1L), s.pendingReads())
            assertTrue(s.markRead("10086", 2))
            s.acknowledgeRead("10086", 1)
            assertEquals(listOf("10086" to 2L), s.pendingReads())
            s.acknowledgeRead("10086", 2)
            assertTrue(s.pendingReads().isEmpty())
        }
    }

    @Test fun epochAndIdentityResetDiscardReadStateWithoutLosingSmsQueue() = withStore { name ->
        Store(context, name).use { s ->
            s.queue(SmsRecord("+1234567890", "10086", "queued", 100, 7))
            for (identityReset in listOf(false, true)) {
                s.applySync(JSONArray().put(message(1000)), 1000)
                s.markRead("10086", 1000)
                s.markRead(all = true)
                if (identityReset) s.resetForNewIdentity() else s.resetSyncedCache(2)
                assertTrue(s.pendingReads().isEmpty())
                s.applySync(JSONArray().put(message(1)), 1)
                assertEquals(1, s.unreadCounts()["10086"])
                assertEquals(1, s.pendingCount())
            }
        }
    }

    @Test fun versionEightUpgradePreservesMessagesAndWatermarks() = withStore { name ->
        Store(context, name).use { s ->
            s.applySync(JSONArray().put(message(1)).put(message(2)), 2)
            s.markRead("10086", 1)
            s.writableDatabase.execSQL("DROP TABLE read_outbox")
            s.writableDatabase.version = 8
            s.writableDatabase.execSQL("DROP TABLE notification_receipts")
            s.writableDatabase.execSQL("DROP TABLE upload_fingerprints")
        }
        Store(context, name).use { s ->
            assertEquals(11, s.readableDatabase.version)
            assertEquals(1, s.unreadCounts()["10086"])
            assertTrue(s.pendingReads().isEmpty())
            assertTrue(s.markRead("10086", 2))
        }
    }
}
