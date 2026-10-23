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

// While AI is pending the rule code already makes the message a verification
// message; show that instead of the unclassified placeholder.
fun MessageAnalysis.groupLabel(): String =
    messageGroupLabels[category] ?: if (otp.isNotEmpty()) messageGroupLabels.getValue("otp") else messageGroupLabels.getValue("pending")

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

// A manual decision is final even when AI is off. Otherwise only an enabled hub
// treats a completed AI category as the final group, matching the server.
internal fun MessageAnalysis.finalCategory(aiEnabled: Boolean): String =
    if ((manual || (aiEnabled && state == "complete")) && category.isNotEmpty()) category else ""

// A code the user sent is not one they are waiting for. A server analysis row
// (revision > 0) already carries the effective code: it hides the rule value when
// AI moved the message away, so local extraction must not resurrect it.
internal fun messageOtp(message: CachedMessage): String? {
    if (message.outgoing) return null
    val analysis = message.analysis
    val code = analysis.otp.ifEmpty {
        if (analysis.revision == 0L) extractOtp(message.body).orEmpty() else ""
    }
    return code.ifEmpty { null }
}

fun CachedMessage.matchesGroup(group: String, aiEnabled: Boolean = false): Boolean {
    val category = analysis.finalCategory(aiEnabled)
    val otp = messageOtp(this).orEmpty()
    val isOtp = category == "otp" || (category.isEmpty() && otp.isNotEmpty())
    return when (group) {
        "", "all" -> true
        "otp" -> isOtp
        "other" -> !isOtp
        "pending" -> category.isEmpty() && !isOtp
        else -> category == group
    }
}
