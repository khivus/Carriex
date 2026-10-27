package com.example.carriex.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local UI protection, independent of relay auth and the existing encrypted chat database. */
class AppLockStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    val hasPassword get() = prefs.contains("password")
    var biometric: Boolean
        get() = prefs.getBoolean("biometric", false)
        set(value) { check(prefs.edit().putBoolean("biometric", value).commit()) }
    var everyReturn: Boolean
        get() = prefs.getBoolean("every_return", true)
        set(value) { check(prefs.edit().putBoolean("every_return", value).commit()) }
    val enabled get() = hasPassword || biometric

    fun setPassword(password: CharArray) {
        val algorithm = if (Build.VERSION.SDK_INT >= 26) PasswordVerifier.Algorithm.SHA256 else PasswordVerifier.Algorithm.SHA1
        val record = PasswordVerifier.create(password, algorithm).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val encrypted = cipher.iv + cipher.doFinal(record)
            check(prefs.edit().putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        } finally { record.fill(0) }
    }

    fun verifyPassword(password: CharArray): Boolean {
        val value = prefs.getString("password", null) ?: return false
        val data = Base64.decode(value, Base64.NO_WRAP)
        require(data.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val record = cipher.doFinal(data.copyOfRange(12, data.size))
        return try { PasswordVerifier.matches(password, record.toString(Charsets.UTF_8)) } finally { record.fill(0) }
    }

    fun removePassword() { check(prefs.edit().remove("password").commit()) }

    /** Persist throttling so closing/relaunching the app cannot reset repeated failed attempts. */
    fun retryAfterMillis(now: Long = System.currentTimeMillis()): Long =
        (prefs.getLong("retry_at", 0) - now).coerceIn(0, 30_000)

    fun passwordAttempt(success: Boolean) {
        val failures = if (success) 0 else (prefs.getInt("failures", 0) + 1).coerceAtMost(10)
        val delay = if (failures >= 5) 30_000L else 0L
        check(prefs.edit().putInt("failures", failures).putLong("retry_at", if (delay == 0L) 0L else System.currentTimeMillis() + delay).commit())
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("carriex_app_lock_password_v1", null) as? SecretKey)?.let { return it }
        // Losing an existing wrapping key must never silently remove the password requirement.
        check(!hasPassword) { "App lock key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("carriex_app_lock_password_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
