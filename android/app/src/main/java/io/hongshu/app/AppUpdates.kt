package io.hongshu.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class AppUpdateState(
    val message: String = "尚未检查更新", val candidate: AppUpdateCandidate? = null,
    val preview: Boolean = false, val busy: Boolean = false,
)

internal class AppUpdates internal constructor(
    context: Context,
    private val checkClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hongshu_updates", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val channelLock = Any()
    private val installed get() = app.packageManager.getPackageInfo(app.packageName, signatureFlags())
    private val currentVersion get() = installed.versionName ?: error("无法读取当前版本")
    private val currentCode get() = PackageInfoCompat.getLongVersionCode(installed)
    private val mutable = MutableStateFlow(AppUpdateState(preview = prefs.getBoolean("preview", currentVersion.contains('-'))))
    val state = mutable.asStateFlow()

    init {
        if (!prefs.contains("preview")) prefs.edit().putBoolean("preview", currentVersion.contains('-')).commit()
        val cached = prefs.getString("feed", null)
        if (cached != null) runCatching { candidate(cached) }.onSuccess { selected ->
            mutable.value = mutable.value.copy(candidate = selected, message = selected?.let { "已缓存新版本 ${it.version}" } ?: "已缓存上次检查结果")
        }.onFailure { prefs.edit().remove("feed").remove("etag").putLong("nextCheck", 0).commit() }
    }

    fun setPreview(value: Boolean) = synchronized(channelLock) {
        kotlin.check(prefs.edit().putBoolean("preview", value).commit()) { "无法保存更新通道" }
        val cached = runCatching { candidate(prefs.getString("feed", "")!!) }.getOrNull()
        mutable.value = mutable.value.copy(message = if (cached != null) "发现新版本 ${cached.version}" else "已切换到${if (value) "预发布" else "正式"}通道", candidate = cached, preview = value)
    }

    private fun candidate(text: String) = AppUpdatePolicy.select(text, currentVersion, currentCode,
        prefs.getBoolean("preview", currentVersion.contains('-')))

    suspend fun check(manual: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = clock()
            val retryAt = prefs.getLong("retryAt", 0)
            val lastAttempt = prefs.getLong("lastAttempt", 0)
            val nextCheck = prefs.getLong("nextCheck", 0)
            if (now < retryAt || (lastAttempt > 0 && now < lastAttempt + AppUpdatePolicy.MANUAL_INTERVAL) || (!manual && now < nextCheck)) {
                if (manual) mutable.value = mutable.value.copy(message = if (now < retryAt) "检查暂时受限，请稍后重试" else "刚刚已检查，请稍后再试")
                return@withLock
            }
            prefs.edit().putLong("lastAttempt", now).commit()
            mutable.value = mutable.value.copy(busy = true, message = "正在检查更新")
            try {
                val cached = prefs.getString("feed", "")!!
                val request = Request.Builder().url(AppUpdatePolicy.FEED_URL).header("Accept", "application/json")
                if (cached.isNotEmpty()) prefs.getString("etag", null)?.let { request.header("If-None-Match", it) }
                checkClient.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 304) {
                        val failures = prefs.getInt("failures", 0)
                        prefs.edit().putLong("retryAt", AppUpdatePolicy.retryAt(now, failures, response.header("Retry-After"), response.header("X-RateLimit-Reset"))).commit()
                        throw IOException(when {
                            response.code == 429 || response.header("X-RateLimit-Remaining") == "0" -> "仓库请求受限，请稍后重试"
                            response.code == 404 -> "更新清单暂未就绪"
                            response.code == 403 -> "仓库访问被拒绝，请稍后重试"
                            else -> "检查失败（HTTP ${response.code}）"
                        })
                    }
                    val text = if (response.code == 304 && cached.isNotEmpty()) cached else {
                        if (!response.isSuccessful) throw IOException(if (response.code == 404) "更新清单暂未就绪" else "检查失败（HTTP ${response.code}）")
                        val body = response.body ?: throw IOException("更新清单为空")
                        body.byteStream().use { stream ->
                            val bytes = stream.readBytesBounded(AppUpdatePolicy.MAX_FEED_BYTES)
                            bytes.toString(Charsets.UTF_8)
                        }
                    }
                    synchronized(channelLock) {
                        val selected = candidate(text)
                        kotlin.check(prefs.edit().putString("feed", text).putString("etag", if (response.code == 304) prefs.getString("etag", null) else response.header("ETag"))
                            .putLong("nextCheck", now + AppUpdatePolicy.CHECK_INTERVAL + kotlin.random.Random.nextLong(0, 30 * 60 * 1000L))
                            .putLong("retryAt", 0).putInt("failures", 0).commit()) { "无法保存检查结果" }
                        mutable.value = mutable.value.copy(candidate = selected, message = selected?.let { "发现新版本 ${it.version}" }
                            ?: if (!mutable.value.preview && currentVersion.contains('-')) "没有更高的正式版，等待正式通道更新" else "当前通道没有可安装的新版本")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failures = prefs.getInt("failures", 0)
                prefs.edit().putInt("failures", (failures + 1).coerceAtMost(8))
                    .putLong("retryAt", maxOf(prefs.getLong("retryAt", 0), AppUpdatePolicy.retryAt(now, failures, null, null))).commit()
                mutable.value = mutable.value.copy(message = error.message ?: "检查更新失败，请稍后重试")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    suspend fun downloadAndInstall() = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = mutable.value.candidate ?: return@withLock
            mutable.value = mutable.value.copy(busy = true, message = "正在下载 ${target.version}")
            val directory = File(app.cacheDir, "app-updates").apply { mkdirs() }
            val file = File(directory, "Hongshu-${target.version}.apk")
            try {
                kotlin.check(UpdateVersion.parse(target.version)!! > UpdateVersion.parse(currentVersion)!!) { "只允许安装更高版本" }
                val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.MINUTES).followRedirects(false).build()
                if (!file.isFile || file.length() != target.size || sha256(file) != target.sha256) {
                    file.delete()
                    openDownload(client, target.url).use { response ->
                        kotlin.check(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
                        val body = response.body ?: error("安装包为空")
                        body.byteStream().use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    kotlin.check(total <= target.size) { "安装包大小异常" }
                                    output.write(buffer, 0, count)
                                }
                                kotlin.check(total == target.size) { "安装包下载不完整" }
                            }
                        }
                    }
                }
                kotlin.check(sha256(file) == target.sha256) { "安装包校验失败" }
                validateApk(file, target)
                kotlin.check(!target.preview || prefs.getBoolean("preview", currentVersion.contains('-'))) { "更新通道已变更，请重新检查" }
                withContext(Dispatchers.Main) {
                    if (!app.packageManager.canRequestPackageInstalls()) {
                        app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        mutable.value = mutable.value.copy(message = "允许安装应用后，再次点击安装")
                    } else {
                        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        mutable.value = mutable.value.copy(message = "安装包已校验，请在系统安装器中完成安装")
                    }
                }
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (error: Exception) {
                file.delete()
                mutable.value = mutable.value.copy(message = error.message ?: "下载或安装失败")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    private fun validateApk(file: File, target: AppUpdateCandidate) {
        val archive = app.packageManager.getPackageArchiveInfo(file.path, signatureFlags()) ?: error("无法读取安装包")
        kotlin.check(archive.packageName == app.packageName && archive.versionName == target.version) { "安装包身份或版本不符" }
        kotlin.check(PackageInfoCompat.getLongVersionCode(archive) > currentCode) { "安装包版本码未递增，已拒绝安装" }
        target.versionCode?.let { kotlin.check(PackageInfoCompat.getLongVersionCode(archive) == it) { "安装包版本码与清单不符" } }
        val installedSigners = signatures(installed, history = false)
        val downloadedSigners = signatures(archive, history = true)
        kotlin.check(installedSigners.isNotEmpty() && installedSigners.all { it in downloadedSigners }) { "安装包签名与当前应用不兼容" }
        if (Build.VERSION.SDK_INT >= 28 && installed.signingInfo?.hasMultipleSigners() == true) {
            kotlin.check(installedSigners == signatures(archive, history = false)) { "安装包签名集合不符" }
        }
    }

    private fun openDownload(client: OkHttpClient, initial: String): Response {
        var url = initial
        repeat(6) {
            val parsed = url.toHttpUrlOrNull() ?: error("下载地址异常")
            kotlin.check(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "下载跳转地址不可信" }
            val response = client.newCall(Request.Builder().url(parsed).build()).execute()
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let { parsed.resolve(it)?.toString() }
            response.close()
            url = next ?: error("下载跳转地址缺失")
        }
        error("下载跳转次数过多")
    }

    companion object {
        @Volatile private var instance: AppUpdates? = null
        fun get(context: Context): AppUpdates = instance ?: synchronized(this) { instance ?: AppUpdates(context).also { instance = it } }
        private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        private fun signatures(info: PackageInfo, history: Boolean): Set<String> {
            val values = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
                if (history && !it.hasMultipleSigners()) it.signingCertificateHistory else it.apkContentsSigners
            } else info.signatures
            return values.orEmpty().map { digest(it.toByteArray()) }.toSet()
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "更新清单过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
