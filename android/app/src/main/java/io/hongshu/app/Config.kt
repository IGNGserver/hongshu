package io.hongshu.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Config(context: Context) {
    private val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)

    internal val identityChanging: Boolean
        get() = prefs.getBoolean("identity_changing", false)

    // A durable fail-closed fence bridges SQLite reset and the single identity preferences commit.
    internal fun beginIdentityChange() {
        check(prefs.edit().putBoolean("identity_changing", true)
            .putLong("capture_generation", captureGeneration + 1).commit()) {
            "无法保存身份切换状态，请重新连接"
        }
    }

    internal fun completeIdentity(identity: LoginIdentity, encryptedToken: String, keepUpload: Boolean) {
        loadingNetwork.reset()
        val saved = prefs.edit()
            .putString("url", identity.origin)
            .putString("token", encryptedToken)
            .putString("device", identity.deviceId)
            .putString("device_name", identity.name)
            .putBoolean("upload", keepUpload)
            .putLong("upload_version", uploadVersion + 1)
            .putBoolean("upload_patch_pending", keepUpload)
            .putBoolean("server_upload_allowed", false)
            .putStringSet("registered_sims", emptySet())
            .putBoolean("notify", identity.notify)
            .putBoolean("sync_healthy", false)
            .putBoolean("inbox_scan_pending", false)
            .putBoolean("recovery_pending", false)
            .putString("status", "已连接")
            .putBoolean("identity_changing", false)
            .commit()
        if (!saved) {
            // commit(false) can still update the in-memory preferences; close that gate too.
            prefs.edit().putBoolean("identity_changing", true).commit()
            error("身份保存失败，请重新连接")
        }
    }

    init {
        synchronized(identityLock) {
            if (!prefs.contains("history_enabled")) {
                prefs
                    .edit()
                    .putBoolean("history_enabled", prefs.getBoolean("upload", false))
                    .commit()
            }
        }
    }

    var historyEnabled: Boolean
        get() = prefs.getBoolean("history_enabled", false)
        set(value) {
            synchronized(identityLock) {
                if (value != historyEnabled)
                    prefs
                        .edit()
                        .putBoolean("history_enabled", value)
                        .putBoolean("inbox_scan_pending", false)
                        .putString("history_state", if (value) "NOT_SCANNED" else "DISABLED")
                        .putLong("history_generation", historyGeneration + 1)
                        .commit()
            }
        }

    val captureGeneration: Long
        get() = prefs.getLong("capture_generation", 0)

    val historyGeneration: Long
        get() = prefs.getLong("history_generation", 0)

    var lowSpeed: Boolean
        get() = prefs.getBoolean("low_speed", false)
        set(value) { prefs.edit().putBoolean("low_speed", value).apply() }

    var recoveryEnabled: Boolean
        get() = prefs.getBoolean("recovery_enabled", false)
        set(value) {
            synchronized(identityLock) {
                if (value != recoveryEnabled)
                    prefs
                        .edit()
                        .putBoolean("recovery_enabled", value)
                        .putLong("recovery_since", if (value) System.currentTimeMillis() else 0)
                        .putLong("recovery_generation", recoveryGeneration + 1)
                        .putBoolean("recovery_pending", false)
                        .putString("recovery_state", if (value) "NOT_SCANNED" else "DISABLED")
                        .commit()
            }
        }

    val recoverySince: Long
        get() = prefs.getLong("recovery_since", 0)

    // Messages this phone sends. On by default; older sent messages follow the history switch,
    // and without it only messages sent after the boundary below are captured.
    var outgoingEnabled: Boolean
        get() = prefs.getBoolean("outgoing_enabled", true)
        set(value) {
            synchronized(identityLock) {
                if (value != outgoingEnabled)
                    prefs
                        .edit()
                        .putBoolean("outgoing_enabled", value)
                        .putLong("outgoing_since", if (value) System.currentTimeMillis() else 0)
                        .putLong("outgoing_generation", outgoingGeneration + 1)
                        .putBoolean("outgoing_pending", false)
                        .commit()
            }
        }

    // Set the first time the (default-on) switch is observed, so an upgrade does not upload
    // the whole sent box unless the user also asked for history.
    val outgoingSince: Long
        get() =
            synchronized(identityLock) {
                prefs.getLong("outgoing_since", 0).takeIf { it > 0 }
                    ?: System.currentTimeMillis().also {
                        prefs.edit().putLong("outgoing_since", it).commit()
                    }
            }

    val outgoingGeneration: Long
        get() = prefs.getLong("outgoing_generation", 0)

    var outgoingPending: Boolean
        get() = prefs.getBoolean("outgoing_pending", false)
        set(value) {
            prefs.edit().putBoolean("outgoing_pending", value).commit()
        }

    var outgoingStatus: String
        get() = prefs.getString("outgoing_status", "尚未读取已发送短信")!!
        set(value) {
            prefs.edit().putString("outgoing_status", value).apply()
        }

    val recoveryGeneration: Long
        get() = prefs.getLong("recovery_generation", 0)

    var recoveryPending: Boolean
        get() = prefs.getBoolean("recovery_pending", false)
        set(value) {
            prefs.edit().putBoolean("recovery_pending", value).commit()
        }

    var recoveryState: HistoryState
        get() =
            runCatching { HistoryState.valueOf(prefs.getString("recovery_state", "NOT_SCANNED")!!) }
                .getOrDefault(HistoryState.NOT_SCANNED)
        set(value) {
            prefs.edit().putString("recovery_state", value.name).apply()
        }

    var recoveryStatus: String
        get() = prefs.getString("recovery_status", "尚未补漏")!!
        set(value) {
            prefs.edit().putString("recovery_status", value).apply()
        }

    val receivedBroadcasts: Long
        get() = prefs.getLong("received_broadcasts", 0)

    val lastReceivedBroadcast: Long
        get() = prefs.getLong("last_received_broadcast", 0)

    fun noteReceivedBroadcast(now: Long) {
        synchronized(identityLock) {
            prefs
                .edit()
                .putLong("received_broadcasts", receivedBroadcasts + 1)
                .putLong("last_received_broadcast", now)
                .commit()
        }
    }

    // Opt-in fallback for ROMs that withhold SMS broadcasts and provider rows. The consent
    // boundary keeps notifications already in the shade from importing older messages.
    var notificationCapture: Boolean
        get() = prefs.getBoolean("notification_capture", false)
        set(value) {
            synchronized(identityLock) {
                if (value != notificationCapture)
                    prefs
                        .edit()
                        .putBoolean("notification_capture", value)
                        .putLong("notification_generation", notificationGeneration + 1)
                        .putLong(
                            "notification_since",
                            if (value) System.currentTimeMillis() else 0,
                        )
                        .putBoolean("recovery_linked", false)
                        .commit()
            }
        }

    // Set once the notification fallback has switched on new-SMS recovery for this consent, so
    // the user's later explicit "off" is not overridden. Cleared whenever the fallback is switched.
    var recoveryLinked: Boolean
        get() = prefs.getBoolean("recovery_linked", false)
        set(value) {
            prefs.edit().putBoolean("recovery_linked", value).commit()
        }

    val notificationSince: Long
        get() = prefs.getLong("notification_since", 0)

    // Empty when the notification fallback is healthy; otherwise a redacted reason shown in
    // diagnostics. Never contains SMS content.
    var notificationFailure: String
        get() = prefs.getString("notification_failure", "")!!
        set(value) {
            prefs.edit().putString("notification_failure", value).apply()
        }

    val notificationGeneration: Long
        get() = prefs.getLong("notification_generation", 0)

    val receivedNotifications: Long
        get() = prefs.getLong("received_notifications", 0)

    val lastReceivedNotification: Long
        get() = prefs.getLong("last_received_notification", 0)

    fun noteReceivedNotification(now: Long) {
        synchronized(identityLock) {
            prefs
                .edit()
                .putLong("received_notifications", receivedNotifications + 1)
                .putLong("last_received_notification", now)
                .commit()
        }
    }

    fun invalidateCaptureSession() {
        synchronized(identityLock) {
            prefs.edit().putLong("capture_generation", captureGeneration + 1).commit()
        }
    }

    var historyState: HistoryState
        get() =
            runCatching { HistoryState.valueOf(prefs.getString("history_state", "NOT_SCANNED")!!) }
                .getOrDefault(HistoryState.NOT_SCANNED)
        set(value) {
            prefs.edit().putString("history_state", value.name).apply()
        }

    var captureFailure: CaptureFailure
        get() =
            runCatching { CaptureFailure.valueOf(prefs.getString("capture_failure", "NONE")!!) }
                .getOrDefault(CaptureFailure.NONE)
        set(value) {
            prefs.edit().putString("capture_failure", value.name).apply()
        }

    var lastHistoryScan: Long
        get() = prefs.getLong("last_history_scan", 0)
        set(value) {
            prefs.edit().putLong("last_history_scan", value).apply()
        }

    var deviceName: String
        get() = prefs.getString("device_name", android.os.Build.MODEL)!!
        set(value) {
            prefs.edit().putString("device_name", value).commit()
        }

    var automaticSim: Boolean
        get() = prefs.getBoolean("automatic_sim", false)
        set(value) {
            prefs.edit().putBoolean("automatic_sim", value).commit()
        }

    var simRecognitionHealthy: Boolean
        get() = prefs.getBoolean("sim_recognition_healthy", false)
        set(value) {
            prefs.edit().putBoolean("sim_recognition_healthy", value).commit()
        }

    var keepAlive: Boolean
        get() = prefs.getBoolean("keep_alive", false)
        set(value) {
            prefs.edit().putBoolean("keep_alive", value).commit()
        }

    var liveOtpIsland: Boolean
        get() = prefs.getBoolean("live_otp_island", true)
        set(value) {
            prefs.edit().putBoolean("live_otp_island", value).commit()
        }

    var hideFromRecents: Boolean
        get() = prefs.getBoolean("hide_from_recents", false)
        set(value) {
            prefs.edit().putBoolean("hide_from_recents", value).commit()
        }

    var restoreRealtimeOnBoot: Boolean
        get() = prefs.getBoolean("restore_realtime_on_boot", false)
        set(value) {
            prefs.edit().putBoolean("restore_realtime_on_boot", value).commit()
        }

    var syncHealthy: Boolean
        get() = prefs.getBoolean("sync_healthy", false)
        set(value) {
            prefs.edit().putBoolean("sync_healthy", value).apply()
        }

    var serverUploadAllowed: Boolean
        get() = prefs.getBoolean("server_upload_allowed", true)
        set(value) {
            prefs.edit().putBoolean("server_upload_allowed", value).apply()
        }

    // Set while the hub has not confirmed this device's local upload switch (offline toggle,
    // or a re-login that creates a new hub device). Sync re-sends it until confirmed, so a
    // missed PATCH is not mistaken for an administrator's decision.
    var uploadPatchPending: Boolean
        get() = prefs.getBoolean("upload_patch_pending", false)
        set(value) {
            prefs.edit().putBoolean("upload_patch_pending", value).commit()
        }

    internal val uploadVersion: Long
        get() = prefs.getLong("upload_version", 0)

    internal fun confirmUpload(version: Long, allowed: Boolean): Boolean = synchronized(identityLock) {
        if (identityChanging || uploadVersion != version) return@synchronized false
        check(prefs.edit().putBoolean("upload_patch_pending", false)
            .putBoolean("server_upload_allowed", allowed).commit()) { "无法保存上传确认" }
        true
    }

    var lastSyncAttempt: Long
        get() = prefs.getLong("last_sync_attempt", 0)
        set(value) {
            prefs.edit().putLong("last_sync_attempt", value).apply()
        }

    // Empty after a successful sync; otherwise the reason the last attempt failed.
    var lastSyncError: String
        get() = prefs.getString("last_sync_error", "")!!
        set(value) {
            prefs.edit().putString("last_sync_error", value).apply()
        }

    var contactsHealthy: Boolean
        get() = prefs.getBoolean("contacts_healthy", true)
        set(value) {
            prefs.edit().putBoolean("contacts_healthy", value).apply()
        }

    // SIM registrations the hub already accepted, scoped by device ID.
    var registeredSims: Set<String>
        get() = prefs.getStringSet("registered_sims", emptySet())!!.toSet()
        set(value) {
            prefs.edit().putStringSet("registered_sims", value.toSet()).apply()
        }

    var inboxScanPending: Boolean
        get() = prefs.getBoolean("inbox_scan_pending", false)
        set(value) {
            prefs.edit().putBoolean("inbox_scan_pending", value).commit()
        }

    var singleSimConfirmed: Boolean
        get() = singleSimSubscription >= 0
        set(value) {
            if (!value) singleSimSubscription = -1
        }

    var singleSimSubscription: Int
        get() = prefs.getInt("single_sim_subscription", -1)
        set(value) {
            prefs.edit().putInt("single_sim_subscription", value).commit()
        }

    var captureStatus: String
        get() = prefs.getString("capture_status", "未扫描")!!
        set(value) {
            prefs.edit().putString("capture_status", value).apply()
        }

    var url: String
        get() = prefs.getString("url", "")!!
        set(v) {
            prefs.edit().putString("url", v.trimEnd('/')).commit()
        }

    var deviceId: String
        get() = prefs.getString("device", "")!!
        set(v) {
            prefs.edit().putString("device", v).commit()
        }

    var upload: Boolean
        get() = !identityChanging && prefs.getBoolean("upload", false)
        set(v) {
            synchronized(identityLock) {
                val edit = prefs.edit().putBoolean("upload", v)
                if (v != upload) edit
                    .putLong("capture_generation", captureGeneration + 1)
                    .putLong("upload_version", uploadVersion + 1)
                    .putBoolean("upload_patch_pending", true)
                if (!v) {
                    edit.putBoolean("inbox_scan_pending", false)
                    edit.putBoolean("recovery_pending", false)
                } else if (!upload && recoveryEnabled) {
                    edit
                        .putLong("recovery_since", System.currentTimeMillis())
                        .putLong("recovery_generation", recoveryGeneration + 1)
                        .putString("recovery_state", "NOT_SCANNED")
                }
                if (v && !upload && notificationCapture)
                    edit.putLong("notification_since", System.currentTimeMillis())
                check(edit.commit()) { "无法保存采集开关，请重试" }
            }
        }

    var notify: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) {
            prefs.edit().putBoolean("notify", v).commit()
        }

    var status: String
        get() = prefs.getString("status", "未连接")!!
        set(v) {
            prefs.edit().putString("status", v).apply()
        }

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(v) {
            prefs.edit().putLong("last_sync", v).apply()
        }

    var token: String
        get() {
            if (identityChanging) return ""
            val encrypted = prefs.getString("token", null) ?: return ""
            return try {
                val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        set(value) {
            if (value.isEmpty()) {
                prefs.edit().remove("token").commit()
                return
            }
            prefs.edit().putString("token", encryptToken(value)).commit()
        }

    internal fun encryptToken(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hongshu-device", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "hongshu-device",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }
}
