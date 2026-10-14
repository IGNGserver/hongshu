package io.hongshu.app

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.concurrent.TimeUnit

// MIUI/HyperOS add private AppOps on top of the runtime permissions. When they deny,
// SMS_RECEIVED is not delivered to a stopped process and service-number ("notification")
// SMS is hidden from both the broadcast and the provider, while RECEIVE_SMS/READ_SMS still
// look granted. The codes are not public SDK constants; read them best-effort only.
internal object XiaomiOps {
    const val BOOT_COMPLETED = 10007
    const val AUTO_START = 10008
    const val READ_NOTIFICATION_SMS = 10018
}

internal enum class VendorPermission {
    ALLOWED,
    DENIED,
    ASK,
    UNKNOWN,
}

internal fun isXiaomiDevice(manufacturer: String, brand: String): Boolean =
    listOf(manufacturer, brand).any { it.lowercase() in setOf("xiaomi", "redmi", "poco") }

internal fun isXiaomiDevice(): Boolean = isXiaomiDevice(Build.MANUFACTURER, Build.BRAND)

// 0/4 are AOSP allowed/foreground, 1/2 ignored/errored. MIUI uses 5 for "ask".
internal fun vendorPermission(mode: Int?): VendorPermission =
    when (mode) {
        AppOpsManager.MODE_ALLOWED,
        4 -> VendorPermission.ALLOWED
        AppOpsManager.MODE_IGNORED,
        AppOpsManager.MODE_ERRORED -> VendorPermission.DENIED
        5 -> VendorPermission.ASK
        else -> VendorPermission.UNKNOWN
    }

internal fun vendorPermissionLabel(state: VendorPermission): String =
    when (state) {
        VendorPermission.ALLOWED -> "已允许"
        VendorPermission.DENIED -> "未允许"
        VendorPermission.ASK -> "每次询问（后台视为未允许）"
        VendorPermission.UNKNOWN -> "无法读取，请人工确认"
    }

internal fun checkXiaomiOp(context: Context, op: Int): VendorPermission =
    vendorPermission(
        runCatching {
                AppOpsManager::class
                    .java
                    .getMethod(
                        "checkOpNoThrow",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                    )
                    .invoke(
                        context.getSystemService(AppOpsManager::class.java),
                        op,
                        Process.myUid(),
                        context.packageName,
                    ) as Int
            }
            .getOrNull()
    )

// HyperOS exposes ro.mi.os.version.name; MIUI exposes ro.miui.ui.version.name.
internal fun xiaomiRomName(): String {
    fun prop(name: String): String =
        runCatching {
                val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroy()
                    ""
                } else process.inputStream.bufferedReader().use { it.readText().trim() }
            }
            .getOrDefault("")
    val hyper = prop("ro.mi.os.version.name")
    if (hyper.isNotEmpty()) return "澎湃 OS $hyper"
    val miui = prop("ro.miui.ui.version.name")
    return if (miui.isNotEmpty()) "MIUI $miui" else "小米系统"
}

internal data class XiaomiSmsAccess(
    val autoStart: VendorPermission,
    val notificationSms: VendorPermission,
    val bootCompleted: VendorPermission,
)

internal fun xiaomiSmsAccess(context: Context): XiaomiSmsAccess =
    XiaomiSmsAccess(
        checkXiaomiOp(context, XiaomiOps.AUTO_START),
        checkXiaomiOp(context, XiaomiOps.READ_NOTIFICATION_SMS),
        checkXiaomiOp(context, XiaomiOps.BOOT_COMPLETED),
    )

internal fun xiaomiSmsStatus(rom: String, access: XiaomiSmsAccess): String =
    "$rom：自启动${vendorPermissionLabel(access.autoStart)}；" +
        "通知类短信${vendorPermissionLabel(access.notificationSms)}；" +
        "开机启动${vendorPermissionLabel(access.bootCompleted)}。" +
        "这些厂商授权未允许时，系统不会把短信广播投递给后台的鸿枢，验证码等服务号短信也会对收件箱读取隐藏。"

private const val SECURITY_CENTER = "com.miui.securitycenter"

// Security-center screens are undocumented and moved between MIUI/HyperOS releases.
// Every list ends with the public app-details page.
internal fun xiaomiPermissionIntents(packageName: String): List<Intent> =
    listOf(
        "com.miui.permcenter.permissions.PermissionsEditorActivity",
        "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
    )
        .map { activity ->
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setComponent(ComponentName(SECURITY_CENTER, activity))
                .putExtra("extra_pkgname", packageName)
        } +
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setPackage(SECURITY_CENTER)
            .putExtra("extra_pkgname", packageName) +
        appDetailsIntent(packageName)

internal fun xiaomiAutoStartIntents(packageName: String): List<Intent> =
    listOf(
        Intent()
            .setComponent(
                ComponentName(
                    SECURITY_CENTER,
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            ),
        appDetailsIntent(packageName),
    )

internal fun appDetailsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

internal fun startFirstAvailable(activity: Activity, intents: List<Intent>): Boolean =
    intents.any { intent ->
        runCatching {
                activity.startActivity(intent)
                true
            }
            .getOrDefault(false)
    }
