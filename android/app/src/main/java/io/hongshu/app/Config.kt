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
            prefs.edit().putBoolean("upload", v).commit()
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
