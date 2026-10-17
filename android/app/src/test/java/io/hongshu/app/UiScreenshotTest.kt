package io.hongshu.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders key screens to PNG for visual review. Skipped unless HONGSHU_SCREENSHOT_DIR is set,
 * so CI and the normal unit-test run are unaffected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xhdpi")
class UiScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = System.getenv("HONGSHU_SCREENSHOT_DIR")

    @Before
    fun requireOutputDirectory() {
        assumeTrue(dir != null)
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        // Loading indicators animate forever; drive the clock by hand so idling still works.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HongshuTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
        repeat(10) {
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(50) // let IO-dispatched loads land
        }
        compose.mainClock.advanceTimeBy(1000)
        // captureToImage waits for a hardware redraw Robolectric never delivers; drawing the
        // decor view into a software canvas captures the same frame.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Settings(page: SettingsPage) {
        val activity = compose.activity
        SettingsScreen(
            activity = activity,
            repo = Repository(activity),
            busy = false,
            page = page,
            onNavigate = {},
            onBack = {},
            onRequestPermissions = { _, _ -> },
            onAction = {},
            onMessage = {},
        )
    }

    @Test fun settingsOverview() = shot("settings-overview") { Settings(SettingsPage.Overview) }

    @Test fun settingsOverviewDark() = shot("settings-overview-dark", dark = true) { Settings(SettingsPage.Overview) }

    @Test fun settingsHub() = shot("settings-hub") { Settings(SettingsPage.Hub) }

    @Test fun settingsCapture() = shot("settings-capture") { Settings(SettingsPage.Capture) }

    @Test fun settingsSims() = shot("settings-sims") { Settings(SettingsPage.Sims) }

    @Test fun settingsBackground() = shot("settings-background") { Settings(SettingsPage.Background) }

    @Test fun settingsPending() = shot("settings-pending") { Settings(SettingsPage.Pending) }

    private val now = System.currentTimeMillis()
    private val hour = 60 * 60 * 1000L
    private val sims =
        listOf(SimMapping(1, 0, "+8613800138000", "SIM1"), SimMapping(2, 1, "+8613900139999", "SIM2"))
    private val sample =
        listOf(
            CachedMessage(5, "d1", "+8613800138000", "95588", "【工商银行】您的验证码为 482913，5 分钟内有效，请勿泄露。", now - hour / 6, ""),
            CachedMessage(4, "d1", "+8613900139999", "13812345678", "晚上一起吃饭吗？我在老地方等你", now - 2 * hour, "张三"),
            CachedMessage(3, "d1", "+8613800138000", "10086", "尊敬的客户，您本月套餐剩余流量 12.3GB。", now - 26 * hour, ""),
            CachedMessage(2, "d2", "+8613900139999", "1069012345678", "【快递】您的包裹已到驿站，取件码 6-2-1031。", now - 50 * hour, ""),
            CachedMessage(1, "d1", "+8613800138000", "+8613711112222", "收到，谢谢", now - 300 * hour, "Alice"),
        )

    private fun model(
        messages: List<CachedMessage>?,
        facts: SetupFacts,
        simsConfigured: Boolean,
    ) =
        HomeModel(
            repo = Repository(compose.activity),
            messages = messages,
            searchQuery = "",
            simFilter = "",
            receivers = sims.map { it.phone },
            sims = sims,
            facts = facts,
            messageLimit = 200,
            changes = 0,
            busy = false,
            refreshing = false,
            setup = setupProgress(facts, simsConfigured, dismissed = false),
        )

    private val actions = HomeActions({}, {}, {}, { _ -> }, {}, {}, {}, {}, {}, {})

    @Test
    fun homeFirstRun() =
        shot("home-first-run") {
            MainSurface(AppDestination.Home, model(emptyList(), SetupFacts(), false), actions, twoPane = false)
        }

    @Test
    fun homeConnected() {
        val facts =
            SetupFacts(connected = true, upload = true, receiveSms = true, keepAlive = true, realtimeRunning = true,
                pending = PendingCounts(ready = 2))
        shot("home-connected") {
            MainSurface(AppDestination.Home, model(sample, facts, true), actions, twoPane = false)
        }
    }

    @Test
    fun homeConnectedDark() {
        val facts = SetupFacts(connected = true, upload = true, receiveSms = true)
        shot("home-connected-dark", dark = true) {
            MainSurface(AppDestination.Home, model(sample, facts, false), actions, twoPane = false)
        }
    }

    @Test
    fun conversation() {
        val messages = org.json.JSONArray()
        listOf(
                Triple(11L, "【工商银行】您尾号 1234 的储蓄卡消费 56.00 元。", now - 30 * hour),
                Triple(12L, "【工商银行】您的验证码为 482913，5 分钟内有效，请勿泄露。", now - hour / 6),
                Triple(13L, "【工商银行】您正在登录手机银行，验证码 730215。", now - hour / 12),
            )
            .forEach { (id, body, time) ->
                messages.put(
                    org.json.JSONObject()
                        .put("id", id)
                        .put("device_id", "d1")
                        .put("receiver", "+8613800138000")
                        .put("sender", "95588")
                        .put("body", body)
                        .put("timestamp", time)
                )
            }
        Store(compose.activity).use { it.applySync(messages, 13) }
        val facts = SetupFacts(connected = true)
        shot("conversation") {
            MainSurface(
                AppDestination.Conversation("95588"),
                model(sample, facts, true),
                actions,
                twoPane = false,
            )
        }
    }
}
