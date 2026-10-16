package io.hongshu.app

enum class HistoryState {
    NOT_SCANNED,
    DISABLED,
    NO_PERMISSION,
    SCANNING,
    COMPLETE,
    FAILED,
}

enum class CaptureFailure {
    NONE,
    PARSE,
    STORAGE,
    SCHEDULING,
}

data class CaptureDiagnostics(
    val broadcasts: Long = 0,
    val saved: Long = 0,
    val duplicates: Long = 0,
    val lastBroadcast: Long = 0,
    val lastSaved: Long = 0,
)

enum class PendingState {
    NEEDS_SIM,
    INVALID,
    SERVER_DENIED,
    NEEDS_HUB,
    PAUSED,
    WAITING_NETWORK,
    WAITING_UPLOAD,
}

data class PendingCounts(val ready: Int = 0, val needsSim: Int = 0, val invalid: Int = 0) {
    val total: Int get() = ready + needsSim + invalid

    // "Pending" means captured on this phone and not yet acknowledged by the hub.
    fun label(): String =
        if (total == 0) "没有未上传的短信"
        else
            "未上传 $total 条：" +
                listOfNotNull(
                        "$ready 条等待上传".takeIf { ready > 0 },
                        "$needsSim 条需确认接收号码".takeIf { needsSim > 0 },
                        "$invalid 条无法上传".takeIf { invalid > 0 },
                    )
                    .joinToString(" · ")

    // One line for the home screen; names the part that needs the user, if any.
    fun banner(): String =
        "有 $total 条本机短信尚未上传到中枢" +
            when {
                needsSim > 0 && invalid > 0 -> "，其中 ${needsSim + invalid} 条需要你处理"
                needsSim > 0 -> "，其中 $needsSim 条需要确认接收号码"
                invalid > 0 -> "，其中 $invalid 条无法上传"
                else -> ""
            }
}

data class LocalPendingMessage(
    val key: String,
    val receiver: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val subscriptionId: Int,
    val slot: Int,
    val historical: Boolean,
    val blocked: String,
)

fun pendingState(
    receiver: String,
    blocked: String,
    upload: Boolean,
    serverAllowed: Boolean,
    connected: Boolean,
    authorized: Boolean = true,
): PendingState =
    when {
        blocked == "invalid_message" -> PendingState.INVALID
        receiver.isEmpty() || blocked == "sim_not_confirmed" -> PendingState.NEEDS_SIM
        !upload -> PendingState.PAUSED
        !authorized -> PendingState.NEEDS_HUB
        !serverAllowed -> PendingState.SERVER_DENIED
        !connected -> PendingState.WAITING_NETWORK
        else -> PendingState.WAITING_UPLOAD
    }

fun pendingStateLabel(state: PendingState): String =
    when (state) {
        PendingState.NEEDS_SIM -> "需确认接收号码：系统没有告知是哪张卡收到的"
        PendingState.INVALID -> "无法上传：内容或格式被拒绝，可重试或放弃"
        PendingState.SERVER_DENIED -> "中枢已禁止本机上传"
        PendingState.NEEDS_HUB -> "未连接中枢或授权已失效"
        PendingState.PAUSED -> "“同步本机短信”已关闭，记录已保留"
        PendingState.WAITING_NETWORK -> "等待网络"
        PendingState.WAITING_UPLOAD -> "等待上传"
    }
