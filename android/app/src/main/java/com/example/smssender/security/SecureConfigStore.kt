package com.example.smssender.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.smssender.model.AppConfig
import com.example.smssender.model.SmtpSecurity
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(config: AppConfig) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val plaintext = config.toJson().toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(plaintext)
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): AppConfig {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return AppConfig()
        val iv = preferences.getString(KEY_IV, null) ?: return AppConfig()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            val json = String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
            JSONObject(json).toConfig()
        }.getOrDefault(AppConfig())
    }

    fun isConfigured(): Boolean = preferences.contains(KEY_CIPHERTEXT)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun AppConfig.toJson() = JSONObject()
        .put("apiUrl", apiUrl.trim())
        .put("apiKey", apiKey)
        .put("smtpHost", smtpHost.trim())
        .put("smtpPort", smtpPort)
        .put("security", security.name)
        .put("username", username)
        .put("password", password)
        .put("from", from.trim())
        .put("to", to.trim())

    private fun JSONObject.toConfig() = AppConfig(
        apiUrl = optString("apiUrl"),
        apiKey = optString("apiKey"),
        smtpHost = optString("smtpHost"),
        smtpPort = optString("smtpPort", "465"),
        security = runCatching { SmtpSecurity.valueOf(optString("security")) }
            .getOrDefault(SmtpSecurity.SSL_TLS),
        username = optString("username"),
        password = optString("password"),
        from = optString("from"),
        to = optString("to"),
    )

    private companion object {
        const val PREFS = "secure_config"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_ALIAS = "sms_sender_config_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
