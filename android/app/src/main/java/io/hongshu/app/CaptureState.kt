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
    NOTIFICATION_COLLISION,
    NOTIFICATION_HELD,
    NOTIFICATION_ONLY,
    NEEDS_SIM,
    INVALID,
    SERVER_DENIED,
    NEEDS_HUB,
    PAUSED,
    WAITING_NETWORK,
    WAITING_UPLOAD,
}

data class PendingCounts(
    val ready: Int = 0,
    val needsSim: Int = 0,
    val invalid: Int = 0,
    val collisions: Int = 0,
    // Notification rows briefly waiting for their inbox/broadcast copy; not a problem yet.
    val held: Int = 0,
) {
    val total: Int get() = ready + needsSim + invalid + collisions + held

    // "Pending" means captured on this phone and not yet acknowledged by the hub.
    fun label(): String =
        if (total == 0) "没有未上传的短信"
        else
            "未上传 $total 条：" +
                listOfNotNull(
                        "$ready 条等待上传".takeIf { ready > 0 },
                        "$held 条正在与收件箱核对".takeIf { held > 0 },
                        "$needsSim 条需确认接收号码".takeIf { needsSim > 0 },
                        "$invalid 条无法上传".takeIf { invalid > 0 },
                        "$collisions 条通知身份碰撞需处理".takeIf { collisions > 0 },
                    )
                    .joinToString(" · ")

    // One line for the home screen; names the part that needs the user, if any.
    fun banner(): String =
        "有 $total 条本机短信尚未上传到中枢" +
            when {
                collisions > 0 -> "，其中 $collisions 条通知身份碰撞需处理"
                held == total -> "，正在与收件箱核对"
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
    val notificationUncertain: Boolean = false,
    // Seen only in an SMS app notification: no broadcast or inbox row has paired with it.
    val notificationOnly: Boolean = false,
    // Still inside the pairing window (see NOTIFICATION_NUMBER_HOLD).
    val notificationHeld: Boolean = false,
)

fun pendingState(
    receiver: String,
    blocked: String,
    upload: Boolean,
    serverAllowed: Boolean,
    connected: Boolean,
    authorized: Boolean = true,
    notificationOnly: Boolean = false,
    notificationHeld: Boolean = false,
): PendingState =
    when {
        blocked == "notification_collision" -> PendingState.NOTIFICATION_COLLISION
        blocked == "invalid_message" -> PendingState.INVALID
        notificationHeld -> PendingState.NOTIFICATION_HELD
        notificationOnly && (receiver.isEmpty() || blocked == "sim_not_confirmed") -> PendingState.NOTIFICATION_ONLY
        receiver.isEmpty() || blocked == "sim_not_confirmed" -> PendingState.NEEDS_SIM
        !upload -> PendingState.PAUSED
        !authorized -> PendingState.NEEDS_HUB
        !serverAllowed -> PendingState.SERVER_DENIED
        !connected -> PendingState.WAITING_NETWORK
        else -> PendingState.WAITING_UPLOAD
    }

fun pendingStateLabel(state: PendingState): String =
    when (state) {
        PendingState.NOTIFICATION_COLLISION -> "通知身份碰撞：中枢无法区分这些观察，已保留；请核实接收号码或放弃"
        PendingState.NOTIFICATION_HELD -> "来自短信通知：正在等待收件箱或系统广播的同一条短信，用来核对号码与内容"
        PendingState.NOTIFICATION_ONLY -> "只在短信通知中出现：收件箱和系统广播都没有这条短信，也不知道是哪张卡收到的；请确认接收号码或放弃"
        PendingState.NEEDS_SIM -> "需确认接收号码：系统没有告知是哪张卡收到的"
        PendingState.INVALID -> "无法上传：内容或格式被拒绝，可重试或放弃"
        PendingState.SERVER_DENIED -> "中枢已禁止本机上传"
        PendingState.NEEDS_HUB -> "未连接中枢或授权已失效"
        PendingState.PAUSED -> "“同步本机短信”已关闭，记录已保留"
        PendingState.WAITING_NETWORK -> "等待网络"
        PendingState.WAITING_UPLOAD -> "等待上传"
    }
