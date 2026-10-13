package io.hongshu.app

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class BackgroundPolicyTest {
    @Test
    fun websocketIdentityMatchesNormalizedURLButNotChangedHubOrToken() {
        for (url in
            listOf(
                "http://example.test:80",
                "https://example.test:443",
                "http://example.test:8123",
            )) {
            val request =
                Request.Builder()
                    .url(realtimeURL(url))
                    .header("Authorization", "Bearer synthetic")
                    .build()
            assertTrue(realtimeRequestMatches(request, url, "synthetic"))
            assertFalse(realtimeRequestMatches(request, url, "replacement"))
            assertFalse(realtimeRequestMatches(request, "https://other.test", "synthetic"))
            assertFalse(realtimeRequestMatches(request, "", "synthetic"))
        }
    }

    @Test
    fun stickyNullIntentRespectsPersistedUserIntent() {
        assertEquals(RealtimeStart.CONNECT, realtimeStart(null, true, true, true))
        assertEquals(RealtimeStart.DISABLED, realtimeStart(null, false, true, true))
        assertEquals(RealtimeStart.UNAUTHORIZED, realtimeStart(null, true, false, true))
        assertEquals(RealtimeStart.UNAUTHORIZED, realtimeStart(null, true, true, false))
    }

    @Test
    fun stopAlwaysWinsEvenWithoutCredentials() {
        for (enabled in listOf(false, true)) for (authorized in listOf(false, true)) {
            assertEquals(
                RealtimeStart.STOP,
                realtimeStart(STOP_REALTIME, enabled, authorized, false),
            )
            assertEquals(
                RealtimeStart.STOP,
                realtimeStart(STOP_REALTIME, enabled, authorized, true),
            )
        }
    }

    @Test
    fun bootRequiresAllFourConditionsNotJustOptIn() {
        for (optedIn in listOf(false, true)) for (enabled in listOf(false, true)) for (authorized in
            listOf(false, true)) for (configured in listOf(false, true)) {
            assertEquals(
                optedIn && enabled && authorized && configured,
                restoreRealtimeOnBoot(optedIn, enabled, authorized, configured),
            )
        }
    }

    @Test
    fun outageRetriesBackOffWithoutOverflow() {
        assertEquals(1000L, realtimeRetryDelay(0))
        assertEquals(2000L, realtimeRetryDelay(1))
        assertEquals(64000L, realtimeRetryDelay(6))
        assertEquals(64000L, realtimeRetryDelay(Int.MAX_VALUE))
        assertEquals(1000L, realtimeRetryDelay(-1))
    }
}
