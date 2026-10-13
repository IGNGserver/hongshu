package io.hongshu.app

internal const val STOP_REALTIME = "stop"

internal enum class RealtimeStart {
    STOP,
    DISABLED,
    UNAUTHORIZED,
    CONNECT,
}

// A null action is normal when Android recreates a sticky service. Persisted user intent wins.
internal fun realtimeStart(
    action: String?,
    enabled: Boolean,
    authenticated: Boolean,
    configured: Boolean,
): RealtimeStart =
    when {
        action == STOP_REALTIME -> RealtimeStart.STOP
        !enabled -> RealtimeStart.DISABLED
        !authenticated || !configured -> RealtimeStart.UNAUTHORIZED
        else -> RealtimeStart.CONNECT
    }

internal fun restoreRealtimeOnBoot(
    optedIn: Boolean,
    enabled: Boolean,
    authenticated: Boolean,
    configured: Boolean,
): Boolean =
    optedIn && realtimeStart(null, enabled, authenticated, configured) == RealtimeStart.CONNECT

internal fun realtimeRetryDelay(attempt: Int): Long = 1000L shl attempt.coerceIn(0, 6)
