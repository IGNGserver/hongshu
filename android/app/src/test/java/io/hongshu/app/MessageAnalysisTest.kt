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
        assertNull(extractOtp("Your order code is 123456"))
        assertNull(extractOtp("验证码 1234 或 5678"))
        assertNull(extractOtp("Your code is 1234 or 5678"))
        assertEquals("482913", extractOtp("code 482913. ".repeat(5000)))
        assertNull(extractOtp("验证码已发送，登录口令是 654321"))
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

    @Test fun aiCategoryIsFinalAndRuleReturnsWhenAIOff() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "analysis-ai-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                fun message(id: Long, sender: String, body: String) =
                    JSONObject().put("id", id).put("device_id", "remote")
                        .put("receiver", "+8613800000000").put("sender", sender).put("body", body)
                        .put("timestamp", id).put("historical", false)
                store.applySync(
                    JSONArray()
                        .put(message(1, "10086", "验证码为 482913"))
                        .put(message(2, "10010", "验证码已发送，登录口令是 654321")),
                    2,
                    pageEpoch = 1,
                )
                store.rememberAI(true)
                // The server suppresses the rule code when AI moves a message away
                // and sends an AI-rescued code the rule did not extract.
                store.applyAnalysis(
                    JSONArray()
                        .put(JSONObject().put("id", 1).put("analysis", JSONObject()
                            .put("otp", "").put("category", "promotion").put("manual", false)
                            .put("state", "complete").put("revision", 5)))
                        .put(JSONObject().put("id", 2).put("analysis", JSONObject()
                            .put("otp", "654321").put("category", "otp").put("manual", false)
                            .put("state", "complete").put("revision", 6))),
                    6,
                    1,
                )
                assertEquals(listOf(2L), store.messages(group = "otp").map { it.id })
                assertEquals(listOf(1L), store.messages(group = "promotion").map { it.id })
                val counts = store.groupCounts()
                assertEquals(2, counts["all"])
                assertEquals(1, counts["otp"])
                assertEquals(1, counts["promotion"])
                // AI off returns the rule result; the cached body still has the rule code.
                store.rememberAI(false)
                store.applyAnalysis(
                    JSONArray().put(JSONObject().put("id", 1).put("analysis", JSONObject()
                        .put("otp", "482913").put("category", "promotion").put("manual", false)
                        .put("state", "complete").put("revision", 7))),
                    7,
                    1,
                )
                assertEquals(listOf(2L, 1L), store.messages(group = "otp").map { it.id })
                assertTrue(store.messages(group = "promotion").isEmpty())
                // Manual otp restores the code; manual promotion hides it again.
                store.rememberAI(true)
                store.applyAnalysis(
                    JSONArray().put(JSONObject().put("id", 1).put("analysis", JSONObject()
                        .put("otp", "482913").put("category", "otp").put("manual", true)
                        .put("state", "complete").put("revision", 8))),
                    8,
                    1,
                )
                assertEquals(listOf(2L, 1L), store.messages(group = "otp").map { it.id })
                store.applyAnalysis(
                    JSONArray().put(JSONObject().put("id", 1).put("analysis", JSONObject()
                        .put("otp", "").put("category", "promotion").put("manual", true)
                        .put("state", "complete").put("revision", 9))),
                    9,
                    1,
                )
                assertEquals(listOf(2L), store.messages(group = "otp").map { it.id })
                assertEquals(listOf(1L), store.messages(group = "promotion").map { it.id })
            }
        } finally {
            context.deleteDatabase(name)
        }
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
                store.writableDatabase.execSQL("DROP TABLE notification_receipts")
                store.writableDatabase.execSQL("DROP TABLE upload_fingerprints")
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
