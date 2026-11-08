package io.hongshu.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class IdentityStateTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    private fun response() = JSONObject().put("token", "synthetic-token").put("device",
        JSONObject().put("id", "device-b").put("name", "phone").put("created_at", 123L).put("notify", true))

    @Test fun malformedLoginFieldsAreRejectedBeforeAnyIdentityWrite() {
        Config(context).url = "https://old.example"
        val saved = prefs.all.toMap()
        val bad = listOf(
            response().apply { remove("token") },
            response().put("token", JSONObject.NULL),
            response().put("token", "token\r\nheader"),
            response().apply { getJSONObject("device").remove("id") },
            response().apply { getJSONObject("device").put("created_at", "123") },
            response().apply { getJSONObject("device").put("notify", "true") },
        )
        for (r in bad) {
            assertThrows(Exception::class.java) { validatedLogin("https://new.example", r) }
            assertEquals(saved, prefs.all)
        }
    }

    @Test fun interruptedTransitionStaysClosedAcrossConfigInstancesAndAtomicCommitReopensIt() {
        val config = Config(context)
        config.url = "https://old.example"
        config.deviceId = "device-a"
        config.upload = true
        config.beginIdentityChange()
        val restarted = Config(context)
        assertEquals("", restarted.token)
        assertFalse(restarted.upload)
        assertThrows(IllegalStateException::class.java) { Api(restarted).request("/me") }

        val identity = validatedLogin("https://new.example", response())
        val observations = mutableListOf<Boolean>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            observations += p.getString("url", "") == identity.origin &&
                p.getString("device", "") == identity.deviceId &&
                p.getString("token", "") == "prepared-ciphertext" &&
                !p.getBoolean("identity_changing", true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        restarted.completeIdentity(identity, "prepared-ciphertext", false)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        assertTrue(observations.isNotEmpty())
        assertTrue(observations.all { it })
        assertFalse(restarted.identityChanging)
    }

    @Test fun cleartextAuthorizationIsExactOriginAndSurvivesIdentityRecreation() {
        val config = Config(context)
        assertThrows(IllegalArgumentException::class.java) { config.requireOriginAccess("http://192.168.1.2:8080") }
        assertEquals("http://192.168.1.2:8080", config.requireOriginAccess("http://192.168.1.2:8080/", true))
        val identity = validatedLogin("http://192.168.1.2:8080/", response())
        assertEquals("http://192.168.1.2:8080", identity.origin)
        config.completeIdentity(identity, "prepared-ciphertext", false)
        val restarted = Config(context)
        assertEquals(identity.origin, restarted.cleartextOrigin)
        assertEquals(identity.origin, hubOrigin(identity.origin))
        assertTrue(restarted.cleartextOrigin == hubOrigin(identity.origin))
        assertEquals(identity.origin, restarted.requireOriginAccess(identity.origin))
        assertThrows(IllegalArgumentException::class.java) { restarted.requireOriginAccess("http://192.168.1.2:8081") }
        assertThrows(IllegalArgumentException::class.java) { restarted.requireOriginAccess("http://192.168.1.3:8080") }
        assertEquals("https://example.com", restarted.requireOriginAccess("https://example.com/"))
    }

    @Test fun offlineToggleAndLateAckKeepNewestPendingVersion() {
        val config = Config(context)
        config.upload = true
        val first = config.uploadVersion
        assertTrue(Config(context).uploadPatchPending)
        config.upload = false
        config.upload = true
        assertFalse(config.confirmUpload(first, true))
        assertTrue(Config(context).uploadPatchPending)
        assertTrue(config.confirmUpload(config.uploadVersion, true))
        assertFalse(Config(context).uploadPatchPending)
    }
}
