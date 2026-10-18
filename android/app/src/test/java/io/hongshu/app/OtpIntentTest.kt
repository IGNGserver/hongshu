package io.hongshu.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

// Verification-code intents are the durable half of the OTP notification path: committed with
// the message page, shown once while valid, revoked by reads and never double-notified.
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OtpIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun database(test: (String) -> Unit) {
        val name = "otp-intent-${UUID.randomUUID()}.db"
        try { test(name) } finally { context.deleteDatabase(name) }
    }

    private fun page(id: Long, body: String, timestamp: Long = System.currentTimeMillis()) =
        JSONArray().put(
            JSONObject().put("id", id).put("device_id", "remote")
                .put("receiver", "10000").put("sender", "10086").put("body", body)
                .put("timestamp", timestamp).put("historical", false)
        )

    private fun grantNotifications() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        channels(context)
    }

    @Test fun validCodeBecomesOneDurableIntentExcludedFromTheSummary() = database { name ->
        grantNotifications()
        Store(context, name).use { store ->
            store.applySync(page(7, "验证码 482913"), 7, self = "self", pairedAt = 1)
            val intent = store.pendingOtpIntents().single()
            assertEquals(7L, intent.messageId)
            assertEquals("482913", intent.otp)
            // The code owns its dedicated channel; it is not also counted for the summary.
            assertEquals(0L, store.pendingNotification())
            // Replaying the same page must not duplicate the intent.
            store.applySync(page(7, "验证码 482913"), 7, self = "self", pairedAt = 1)
            assertEquals(1, store.pendingOtpIntents().size)
        }
    }

    @Test fun readingRevokesPendingAndShownIntents() = database { name ->
        grantNotifications()
        Store(context, name).use { store ->
            store.applySync(page(9, "验证码 111222"), 9, self = "self", pairedAt = 1)
            assertTrue(store.markRead("10086", 9))
            assertTrue(store.pendingOtpIntents().isEmpty())
            // A read that revokes an already shown intent keeps the row count stable.
            store.applySync(page(9, "验证码 111222"), 9, self = "self", pairedAt = 1)
            assertTrue(store.pendingOtpIntents().isEmpty())
        }
    }

    @Test fun epochResetDropsIntents() = database { name ->
        grantNotifications()
        Store(context, name).use { store ->
            store.applySync(page(11, "验证码 333444"), 11, self = "self", pairedAt = 1)
            assertEquals(1, store.pendingOtpIntents().size)
            store.resetSyncedCache(2)
            assertTrue(store.pendingOtpIntents().isEmpty())
        }
    }

    @Test fun expiredCodesFallBackToTheSummary() = database { name ->
        grantNotifications()
        Store(context, name).use { store ->
            val stale = System.currentTimeMillis() - 30 * 60 * 1000L
            store.applySync(page(13, "验证码 555666", stale), 13, self = "self", pairedAt = 1)
            assertTrue(store.pendingOtpIntents().isEmpty())
            // Without a dedicated channel the code falls back to the ordinary summary.
            assertEquals(1L, store.pendingNotification())
        }
    }

    @Test fun disabledIslandKeepsCodesInTheSummary() = database { name ->
        grantNotifications()
        Config(context).liveOtpIsland = false
        Store(context, name).use { store ->
            store.applySync(page(15, "验证码 777888"), 15, self = "self", pairedAt = 1)
            assertTrue(store.pendingOtpIntents().isEmpty())
            assertEquals(1L, store.pendingNotification())
        }
    }

    @Test fun expiryHonoursTheServerWindowButNeverOutlivesTenMinutes() {
        assertEquals(61_000L, otpNotificationExpiry(1_000L, 61_000L))
        assertEquals(601_000L, otpNotificationExpiry(1_000L, 0))
        assertEquals(601_000L, otpNotificationExpiry(1_000L, 900_000L))
    }
}
