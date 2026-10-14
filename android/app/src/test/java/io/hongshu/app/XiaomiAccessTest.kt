package io.hongshu.app

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class XiaomiAccessTest {
    @Test
    fun xiaomiBrandsAreDetectedCaseInsensitively() {
        assertTrue(isXiaomiDevice("Xiaomi", "Redmi"))
        assertTrue(isXiaomiDevice("unknown", "POCO"))
        assertFalse(isXiaomiDevice("Google", "google"))
    }

    @Test
    fun appOpsModesNeverClaimAccessTheyCannotProve() {
        assertEquals(VendorPermission.ALLOWED, vendorPermission(0))
        assertEquals(VendorPermission.ALLOWED, vendorPermission(4))
        assertEquals(VendorPermission.DENIED, vendorPermission(1))
        assertEquals(VendorPermission.DENIED, vendorPermission(2))
        assertEquals(VendorPermission.ASK, vendorPermission(5))
        assertEquals(VendorPermission.UNKNOWN, vendorPermission(3))
        assertEquals(VendorPermission.UNKNOWN, vendorPermission(null))
    }

    @Test
    fun vendorOpProbeNeverThrowsWithoutMiuiOps() {
        // Robolectric has no MIUI ops; reflection failures must map to a state, not throw.
        assertNotNull(checkXiaomiOp(ApplicationProvider.getApplicationContext(), XiaomiOps.AUTO_START))
    }

    @Test
    fun statusNamesEveryVendorGate() {
        val text =
            xiaomiSmsStatus(
                "澎湃 OS 3.0",
                XiaomiSmsAccess(
                    VendorPermission.DENIED,
                    VendorPermission.UNKNOWN,
                    VendorPermission.ALLOWED,
                ),
            )
        assertTrue(text.startsWith("澎湃 OS 3.0"))
        assertTrue(text.contains("自启动未允许"))
        assertTrue(text.contains("通知类短信无法读取"))
        assertTrue(text.contains("开机启动已允许"))
    }

    @Test
    fun settingsIntentsAlwaysEndWithPublicAppDetails() {
        for (intents in listOf(xiaomiPermissionIntents("io.hongshu.app"), xiaomiAutoStartIntents("io.hongshu.app"))) {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
            assertEquals("package:io.hongshu.app", intents.last().dataString)
        }
        val editor = xiaomiPermissionIntents("io.hongshu.app").first()
        assertEquals("com.miui.securitycenter", editor.component?.packageName)
        assertEquals("io.hongshu.app", editor.getStringExtra("extra_pkgname"))
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            xiaomiAutoStartIntents("io.hongshu.app").first().component?.className,
        )
    }

    @Test
    fun permissionSummaryPointsXiaomiUsersAtVendorGates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(vendorSmsStatus(context))
        assertTrue(smsPermissionStatus(context).contains("不代表厂商已允许验证码"))
        ShadowBuild.setManufacturer("Xiaomi")
        assertTrue(smsPermissionStatus(context).contains("通知类短信"))
        assertNotNull(vendorSmsStatus(context))
    }
}
