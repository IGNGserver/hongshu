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
}
