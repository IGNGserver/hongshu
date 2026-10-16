package io.hongshu.app

import org.json.JSONObject

val messageGroupLabels = linkedMapOf(
    "all" to "全部短信", "otp" to "验证码", "other" to "其他短信",
    "personal" to "个人", "notification" to "通知", "promotion" to "推广",
    "spam" to "垃圾", "pending" to "待分析",
)

fun visibleMessageGroups(enabled: Boolean): List<String> =
    if (enabled) listOf("all", "otp", "personal", "notification", "promotion", "spam", "pending")
    else listOf("all", "otp", "other")

data class MessageAnalysis(
    val otp: String = "",
    val expiresAt: Long = 0,
    val category: String = "",
    val manual: Boolean = false,
    val state: String = "pending",
    val error: String = "",
    val revision: Long = 0,
) {
    fun label(): String = when {
        state == "failed" && error == "needs_review" -> "待确认分类"
        state == "failed" -> "分析失败，可重试"
        state == "processing" -> "正在分析"
        state == "pending" -> "等待分析"
        manual -> "手动分类"
        else -> "已分析"
    }

    companion object {
        fun from(json: JSONObject) = MessageAnalysis(
            json.optString("otp"), json.optLong("expires_at"), json.optString("category"),
            json.optBoolean("manual"), json.optString("state", "pending"),
            json.optString("error"), json.optLong("revision"),
        )
    }
}

fun CachedMessage.matchesGroup(group: String): Boolean {
    val otp = analysis.otp.ifEmpty { extractOtp(body).orEmpty() }
    return when (group) {
        "", "all" -> true
        "otp" -> otp.isNotEmpty()
        "other" -> otp.isEmpty()
        "pending" -> otp.isEmpty() && analysis.category.isEmpty()
        else -> otp.isEmpty() && analysis.category == group
    }
}
