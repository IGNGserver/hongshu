package io.hongshu.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [28], application = Application::class)
class AppUpdatesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hongshu_updates", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, 0).apply { versionName = "0.1.0"; versionCode = 1 }
        shadowOf(app.packageManager).installPackage(info)
    }

    private fun release(version: String, preview: Boolean = false, checksum: Boolean = true): JSONObject {
        val name = "Hongshu-Android-$version.apk"
        return JSONObject().put("tag_name", "v$version").put("prerelease", preview).put("draft", false)
            .put("published_at", "2026-10-26T00:00:00Z").put("body", "test")
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("size", 12).put("state", "uploaded")
                .put("browser_download_url", "https://github.com/IGNGserver/hongshu/releases/download/v$version/$name")
                .put("digest", if (checksum) "sha256:" + "a".repeat(64) else "")))
    }
    private fun feed(vararg releases: JSONObject) = JSONObject().put("schemaVersion", 1)
        .put("repository", AppUpdatePolicy.REPOSITORY).put("generatedAt", "2026-10-26T00:00:00Z").put("releases", JSONArray(releases.toList())).toString()

    @Test fun semanticOrderingAndChannelSwitchCannotDowngrade() {
        assertTrue(UpdateVersion.parse("1.0.0-rc.10")!! > UpdateVersion.parse("1.0.0-rc.9")!!)
        assertTrue(UpdateVersion.parse("1.0.0")!! > UpdateVersion.parse("1.0.0-rc.10")!!)
        assertEquals(UpdateVersion.parse("1.0.0+one"), UpdateVersion.parse("1.0.0+two"))
        assertNull(UpdateVersion.parse("1.0.0-rc.01"))
        assertNull(UpdateVersion.parse("1.0.0+a..b"))
        val source = feed(release("1.4.9"), release("1.5.0-rc.3", false))
        assertNull(AppUpdatePolicy.select(source, "1.5.0-rc.2", 1, false))
        assertEquals("1.5.0-rc.3", AppUpdatePolicy.select(source, "1.5.0-rc.2", 1, true)?.version)
        assertEquals("1.5.0", AppUpdatePolicy.select(feed(release("1.5.0")), "1.5.0-rc.2", 1, false)?.version)
    }

    @Test fun picksHighestCompleteCompatibleAssetAndRejectsForeignFeed() {
        val blocked = release("1.2.0").also { it.getJSONArray("assets").getJSONObject(0).put("versionCode", 1) }
        val source = feed(release("2.0.0", checksum = false), blocked, release("1.1.0"))
        assertEquals("1.1.0", AppUpdatePolicy.select(source, "1.0.0", 1, false)?.version)
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdatePolicy.select(JSONObject(source).put("repository", "other/repo").toString(), "1.0.0", 1, true)
        }
    }

    @Test fun rateLimitSurvivesRecreationAndManualCheck() = runBlocking {
        var requests = 0
        var now = 1_000_000L
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("limited")
                .header("Retry-After", "1800").body("limited".toResponseBody()).build()
        }.build()
        AppUpdates(app, client) { now }.check(true)
        assertEquals(1, requests)
        now += 61_000
        val restarted = AppUpdates(app, client) { now }
        restarted.check(true)
        assertEquals(1, requests)
        assertTrue(restarted.state.value.message.contains("受限"))
        assertTrue(prefs.getLong("retryAt", 0) >= 2_800_000L)
    }

    @Test fun etagCacheAndManualCooldownAvoidRepeatedRequests() = runBlocking {
        var now = 1_000_000L
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (requests > 1) assertEquals("feed-v1", chain.request().header("If-None-Match"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (requests == 1) 200 else 304).message("OK").header("ETag", "feed-v1")
                .body((if (requests == 1) feed(release("0.2.0")) else "").toResponseBody()).build()
        }.build()
        val updater = AppUpdates(app, client) { now }
        updater.check(true)
        updater.check(true)
        assertEquals(1, requests)
        now += 61_000
        AppUpdates(app, client) { now }.check(false)
        assertEquals(1, requests)
        val restarted = AppUpdates(app, client) { now }
        restarted.check(true)
        assertEquals(2, requests)
        assertEquals("0.2.0", restarted.state.value.candidate?.version)
    }

    @Test fun channelChangeReevaluatesCacheWithoutNetworkOrDowngrade() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(feed(release("0.3.0-rc.1", true), release("0.2.0")).toResponseBody()).build()
        }.build()
        val updater = AppUpdates(app, client) { 1_000_000L }
        updater.setPreview(true)
        updater.check(true)
        assertEquals("0.3.0-rc.1", updater.state.value.candidate?.version)
        updater.setPreview(false)
        assertEquals("0.2.0", updater.state.value.candidate?.version)
        assertEquals(1, requests)
        assertFalse(AppUpdates(app, client) { 1_000_000L }.state.value.preview)
    }

    @Test fun corruptPersistedCacheDropsValidatorAndNormalTtl() = runBlocking {
        prefs.edit().putString("feed", "broken").putString("etag", "bad-validator")
            .putLong("nextCheck", Long.MAX_VALUE).commit()
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertNull(chain.request().header("If-None-Match"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(feed(release("0.2.0")).toResponseBody()).build()
        }.build()
        val updater = AppUpdates(app, client) { 1_000_000L }
        updater.check()
        assertEquals(1, requests)
        assertEquals("0.2.0", updater.state.value.candidate?.version)
    }
    @Test fun retryDeadlineHonorsHeadersAndBoundedExponentialDelay() {
        val now = 1_000_000L
        assertEquals(now + 1800_000, AppUpdatePolicy.retryAt(now, 0, "1800", null))
        assertEquals(10_000_000L, AppUpdatePolicy.retryAt(now, 0, null, "10000"))
        assertTrue(AppUpdatePolicy.retryAt(now, 500, null, null) <= now + AppUpdatePolicy.CHECK_INTERVAL)
    }
}
