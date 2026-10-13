package io.hongshu.app

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmsPermissionDiagnosticsTest {
    private fun context(granted: Boolean): Context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }

    @Test
    fun missingPermissionsDoNotClaimAccess() {
        val text = smsPermissionStatus(context(false))
        assertTrue(text.contains("接收：未授予"))
        assertTrue(text.contains("读取：未授予"))
    }

    @Test
    fun grantedPermissionWithDeniedAppOpsIsReportedSeparately() {
        val c = context(true)
        Shadows.shadowOf(c.getSystemService(AppOpsManager::class.java))
            .setMode(
                AppOpsManager.permissionToOp(Manifest.permission.READ_SMS)!!,
                Process.myUid(),
                c.packageName,
                AppOpsManager.MODE_IGNORED,
            )
        assertTrue(smsPermissionStatus(c).contains("读取：权限已授予但系统访问策略拒绝"))
    }

    @Test
    fun allowedAppOpsDoNotClaimVerificationCodeVisibility() {
        val c = context(true)
        for (permission in
            listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) Shadows.shadowOf(
                c.getSystemService(AppOpsManager::class.java)
            )
            .setMode(
                AppOpsManager.permissionToOp(permission)!!,
                Process.myUid(),
                c.packageName,
                AppOpsManager.MODE_ALLOWED,
            )
        val text = smsPermissionStatus(c)
        assertTrue(text.contains("系统策略允许"))
        assertTrue(text.contains("不代表厂商已允许验证码"))
    }
}
