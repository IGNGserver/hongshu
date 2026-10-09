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
    var singleSimConfirmed: Boolean
        get() = prefs.getBoolean("single_sim_confirmed", false)
        set(value) {
            prefs.edit().putBoolean("single_sim_confirmed", value).commit()
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
