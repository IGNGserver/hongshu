package io.hongshu.app

import android.content.Context
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiException(val status: Int, val code: String) : IOException("中枢返回 $status ($code)")

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

class Api(private val config: Config) {
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
        val origin = hubOrigin(config.url)
        val builder = Request.Builder().url(origin + "/api" + path)
        if (authorized) {
            builder.header("Authorization", "Bearer ${config.token}")
            builder.header("Cookie", "hongshu=${config.token}")
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
                if (response.code == 401) {
                    config.token = ""
                    config.status = "授权已撤销，请重新连接"
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

    fun login(url: String, password: String, name: String) {
        val oldUrl = config.url
        check(oldUrl.isEmpty() || oldUrl == url.trimEnd('/') || store.pendingCount() == 0) {
            "旧中枢尚有未上传短信，不能更换中枢以免泄露内容"
        }
        config.url = hubOrigin(url)
        try {
            val r =
                api.request(
                    "/login",
                    "POST",
                    JSONObject()
                        .put("password", password.trim())
                        .put("name", name.trim())
                        .put("kind", "android"),
                    false,
                )
            store.resetForNewIdentity()
            config.token = r.getString("token")
            config.deviceId = r.getJSONObject("device").getString("id")
            config.upload = false
            config.status = "已连接"
        } catch (e: Exception) {
            config.url = oldUrl
            throw e
        }
    }

    // 兼容可能遗留的调用
    fun pair(url: String, password: String, name: String) = login(url, password, name)
}
