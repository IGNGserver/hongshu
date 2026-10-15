package io.hongshu.app

import android.content.Context
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiException(val status: Int, val code: String) : IOException("中枢返回 $status ($code)")

// Serialize identity replacement with capture and sync, so a concurrent worker cannot
// send a prior hub's queue or bearer to the newly selected hub.
internal val identityLock = Any()
internal val identityMutex = Mutex()

internal fun hubOrigin(url: String): String {
    val base = URI(url)
    val scheme = base.scheme?.lowercase()
    require(
        (scheme == "http" || scheme == "https") &&
            base.host != null &&
            base.userInfo == null &&
            (base.path.isNullOrEmpty() || base.path == "/") &&
            base.query == null &&
            base.fragment == null
    ) {
        "中枢地址必须使用 HTTP 或 HTTPS，且不能包含路径或凭据"
    }
    val authority = requireNotNull(base.rawAuthority)
    return "$scheme://$authority"
}

internal fun realtimeURL(url: String): String {
    val origin = hubOrigin(url)
    val scheme = if (origin.startsWith("https://")) "wss" else "ws"
    return "$scheme://${URI(origin).rawAuthority}/api/ws"
}

class Api(private val config: Config, private val originOverride: String? = null) {
    companion object {
        val client =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }

    fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        authorized: Boolean = true,
    ): JSONObject {
        val (origin, token) =
            synchronized(identityLock) { hubOrigin(originOverride ?: config.url) to config.token }
        val builder = Request.Builder().url(origin + "/api" + path)
        if (authorized) {
            builder.header("Authorization", "Bearer $token")
            builder.header("Cookie", "hongshu=$token")
        } else {
            builder.header("Origin", origin)
        }
        builder.method(
            method,
            if (method == "GET" || method == "DELETE") null
            else
                (body ?: JSONObject())
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
        )
        client.newCall(builder.build()).execute().use { response ->
            val json =
                try {
                    JSONObject(response.body?.string() ?: "{}")
                } catch (_: Exception) {
                    JSONObject()
                }
            if (!response.isSuccessful) {
                synchronized(identityLock) {
                    if (
                        response.code == 401 &&
                            authorized &&
                            config.token == token &&
                            config.url == origin
                    ) {
                        config.token = ""
                        config.status = "授权已撤销，请重新连接"
                    }
                }
                throw ApiException(response.code, json.optString("error", "network_error"))
            }
            return json
        }
    }
}

class Repository(val context: Context) {
    val config = Config(context)
    val store = Store(context)
    val api = Api(config)

    fun saveConnection(url: String, password: String, name: String) = runBlocking {
        identityMutex.withLock {
            withContext(Dispatchers.IO) {
                val origin = hubOrigin(url.trim())
                val savedName = name.trim()
                check(
                    savedName.isNotEmpty() &&
                        savedName.toByteArray().size <= 100 &&
                        !savedName.contains('\u0000')
                ) {
                    "请输入有效设备名称（最多 100 字节）"
                }
                if (config.token.isNotEmpty() && origin == config.url && password.isEmpty()) {
                    api.request(
                        "/devices/${config.deviceId}",
                        "PATCH",
                        JSONObject().put("name", savedName),
                    )
                    config.deviceName = savedName
                } else {
                    check(password.isNotEmpty()) { "首次连接或更换中枢时请填写中枢密码" }
                    loginUnlocked(origin, password, savedName)
                }
                syncNow(context)
            }
        }
    }

    fun login(url: String, password: String, name: String) = runBlocking {
        identityMutex.withLock {
            withContext(Dispatchers.IO) { loginUnlocked(url, password, name) }
        }
    }

    private fun loginUnlocked(url: String, password: String, name: String) {
        val oldUrl = config.url
        check(oldUrl.isEmpty() || oldUrl == url.trimEnd('/') || store.pendingCount() == 0) {
            "旧中枢尚有未上传短信，不能更换中枢以免泄露内容；可在“本机待同步短信”中上传或放弃这些记录"
        }
        val newUrl = hubOrigin(url)
        try {
            val r =
                Api(config, newUrl)
                    .request(
                        "/login",
                        "POST",
                        JSONObject()
                            .put("password", password)
                            .put("name", name.trim())
                            .put("kind", "android"),
                        false,
                    )
            synchronized(identityLock) {
                check(oldUrl.isEmpty() || oldUrl == newUrl || store.pendingCount() == 0) {
                    "连接期间采集了尚未上传的短信，不能更换中枢"
                }
                // Re-authorizing the same hub keeps local capture on, so SMS arriving after the
                // login are not dropped; the new hub device record starts with upload disabled,
                // so the switch is re-sent on the next sync. A different hub always starts off.
                val keepUpload = oldUrl == newUrl && config.upload
                config.invalidateCaptureSession()
                store.resetForNewIdentity(
                    connectedAt =
                        r.getJSONObject("device").optLong("created_at", System.currentTimeMillis()),
                    clearCaptures = oldUrl.isNotEmpty() && oldUrl != newUrl,
                )
                config.url = newUrl
                config.token = r.getString("token")
                config.deviceId = r.getJSONObject("device").getString("id")
                config.deviceName = r.getJSONObject("device").optString("name", name.trim())
                config.upload = keepUpload
                config.uploadPatchPending = keepUpload
                config.registeredSims = emptySet()
                config.notify = r.getJSONObject("device").optBoolean("notify", true)
                config.syncHealthy = false
                config.status = "已连接"
            }
        } catch (e: Exception) {
            throw e
        }
    }
}
