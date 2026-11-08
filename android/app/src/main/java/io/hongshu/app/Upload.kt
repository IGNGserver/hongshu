package io.hongshu.app

import org.json.JSONArray
import org.json.JSONObject

internal data class UploadAck(val key: String, val error: String, val fingerprint: String = "")

// Validate the entire response before mutating the queue. Old hubs guarantee array order;
// new hubs echo fingerprints, allowing safe correlation even if a proxy reorders ACKs.
internal fun uploadAcks(pending: List<Pair<String, SmsRecord>>, acks: JSONArray): List<UploadAck> {
    check(acks.length() == pending.size) { "上传确认数量不一致" }
    val correlated = (0 until acks.length()).any { acks.getJSONObject(it).has("fingerprint") }
    val remaining =
        pending.groupBy { it.second.fingerprint() }.mapValues { it.value.toMutableList() }
    return (0 until acks.length()).map { index ->
        val ack = acks.getJSONObject(index)
        val message =
            if (correlated) {
                val fingerprint = ack.get("fingerprint")
                check(fingerprint is String) { "上传确认指纹无效" }
                val matches = remaining[fingerprint]
                check(!matches.isNullOrEmpty()) { "上传确认指纹不匹配" }
                matches.removeAt(0)
            } else pending[index]
        val error = if (ack.has("error")) ack.get("error") else ""
        check(error is String && error in setOf("", "sim_not_confirmed", "invalid_message")) {
            "上传确认错误类型无效"
        }
        val id = ack.get("id")
        val duplicate = ack.get("duplicate")
        check(id is Int || id is Long) { "上传确认 ID 无效" }
        val ackId = (id as Number).toLong()
        check(ackId >= 0) { "上传确认 ID 无效" }
        check(duplicate is Boolean) { "上传确认重复标记无效" }
        check(if (error.isEmpty()) ackId > 0 else ackId == 0L && !duplicate) { "上传确认字段冲突" }
        UploadAck(message.first, error, message.second.fingerprint())
    }
}

internal suspend fun uploadPending(
    api: Api,
    store: Store,
    pending: List<Pair<String, SmsRecord>>,
    enabled: () -> Boolean = { true },
    request: suspend (List<Pair<String, SmsRecord>>) -> JSONObject = { batch ->
        api.requestCancellable(
            "/messages",
            "POST",
            JSONObject()
                .put("messages", JSONArray().apply { batch.forEach { put(it.second.json()) } }),
        )
    },
): Boolean {
    if (pending.isEmpty() || !enabled()) return false
    val safe = store.prepareUpload(pending)
    if (safe.isEmpty()) return false
    val response = try {
        request(safe)
    } catch (e: ApiException) {
        if (e.status != 400 || e.code != "invalid_message") throw e
        // An old hub rejects the whole batch. Isolate the rejected row without
        // discarding valid neighbours, or treating a network/protocol failure as bad SMS.
        if (safe.size == 1) {
            store.block(safe.single().first, "invalid_message", safe.single().second.fingerprint())
            return true
        }
        val middle = safe.size / 2
        val first = uploadPending(api, store, safe.take(middle), enabled, request)
        val second = uploadPending(api, store, safe.drop(middle), enabled, request)
        return first || second
    }
    val acks = uploadAcks(safe, response.getJSONArray("acks"))
    store.acknowledgeUploads(acks.filter { it.error.isEmpty() })
    acks.filter { it.error.isNotEmpty() }.forEach { store.block(it.key, it.error, it.fingerprint) }
    return true
}
