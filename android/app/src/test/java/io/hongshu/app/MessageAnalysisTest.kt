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

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [28, 35], application = Application::class)
class MessageAnalysisTest {
    @Test fun conservativeRulesAndVisibleGroups() {
        assertEquals("A2B3C4", extractOtp("Your verification code is A2B3C4."))
        assertEquals("482913", extractOtp("482913 is your verification code."))
        assertNull(extractOtp("验证码 1234，验证码 5678"))
        assertNull(extractOtp("验证码：2026-10-16"))
        assertNull(extractOtp("验证码：1234.50元"))
        assertEquals(listOf("all", "otp", "other"), visibleMessageGroups(false))
        assertFalse(visibleMessageGroups(true).contains("other"))
        assertTrue(visibleMessageGroups(true).contains("pending"))
    }

    @Test fun metadataChangesOldMessagesWithoutNotificationsAndFiltersBeforeLimit() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "analysis-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                fun message(id: Long, body: String) = JSONObject().put("id", id).put("device_id", "remote")
                    .put("receiver", "+8613800000000").put("sender", "10086").put("body", body)
                    .put("timestamp", id).put("historical", true)
                store.applySync(JSONArray().put(message(1, "验证码为 482913")).put(message(2, "synthetic notice")), 2, pageEpoch = 1)
                assertEquals(1L, store.messages(group = "otp", limit = 1).single().id)
                assertEquals(2L, store.messages(group = "other", limit = 1).single().id)
                val update = JSONObject().put("id", 2).put("analysis", JSONObject().put("category", "spam").put("manual", true).put("state", "complete").put("revision", 5))
                store.applyAnalysis(JSONArray().put(update), 5, 1)
                assertEquals(2L, store.messages(group = "spam").single().id)
                assertTrue(store.messages(group = "pending").isEmpty())
                assertEquals(0L, store.pendingNotification())
                assertEquals(2L, store.cursor())
                assertEquals(5L, store.analysisCursor())
                // An older /sync page must not overwrite a newer metadata correction.
                store.applySync(JSONArray().put(message(2, "synthetic notice")), 2, pageEpoch = 1)
                assertTrue(store.messages(group = "spam").single().analysis.manual)
                store.queue(SmsRecord("+8613800000000", "10010", "验证码 88231", 100, 1))
                assertEquals(2, store.messagesWithLocal(group = "otp").size)
                store.resetSyncedCache(2)
                assertEquals(1, store.pendingCount())
                assertEquals(0L, store.analysisCursor())
                assertTrue(store.messages().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionSixUpgradePreservesCapturedAndSyncedMessages() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "analysis-upgrade-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                store.queue(SmsRecord("+8613800000000", "10086", "synthetic", 100, 1))
                store.writableDatabase.execSQL("DROP TABLE message_analysis")
                store.writableDatabase.execSQL("DELETE FROM meta WHERE key IN ('analysis_cursor','ai_enabled','local_rule_cursor')")
                store.writableDatabase.version = 6
            }
            Store(context, name).use { store ->
                assertEquals(1, store.pendingCount())
                assertFalse(store.aiEnabled())
                assertEquals(0L, store.analysisCursor())
                store.applyAnalysis(JSONArray(), 1, 0)
            }
        } finally { context.deleteDatabase(name) }
    }
}
