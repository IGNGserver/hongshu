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
                        .putLong(
                            "notification_since",
                            if (value) System.currentTimeMillis() else 0,
                        )
                        .commit()
            }
        }

    val notificationSince: Long
        get() = prefs.getLong("notification_since", 0)

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
        get() = prefs.getBoolean("upload", false)
        set(v) {
            synchronized(identityLock) {
                val edit = prefs.edit().putBoolean("upload", v)
                if (v != upload) edit.putLong("capture_generation", captureGeneration + 1)
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
                edit.commit()
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
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            prefs
                .edit()
                .putString(
                    "token",
                    Base64.encodeToString(
                        cipher.iv + cipher.doFinal(value.toByteArray()),
                        Base64.NO_WRAP,
                    ),
                )
                .commit()
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
