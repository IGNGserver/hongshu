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
        PendingState.NEEDS_SIM -> "接收号码待确认"
        PendingState.INVALID -> "记录异常 · 保留等待处理"
        PendingState.SERVER_DENIED -> "中枢已禁止本机上传"
        PendingState.NEEDS_HUB -> "等待连接或重新授权中枢"
        PendingState.PAUSED -> "同步已关闭 · 记录已保留"
        PendingState.WAITING_NETWORK -> "等待网络"
        PendingState.WAITING_UPLOAD -> "等待上传确认"
    }
