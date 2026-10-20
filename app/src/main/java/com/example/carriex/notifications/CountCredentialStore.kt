package com.example.carriex.notifications

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A stolen polling credential cannot send/read/ack messages; still encrypt it at rest. */
class CountCredentialStore(context: Context, private val scope: String) {
    private val preferences = context.getSharedPreferences("notification_credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val alias = "carriex-count-credentials-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun read(): CountCredential? = try {
        preferences.getString(scope, null)?.let { text ->
            val wire = text.decodeBase64()!!.toByteArray(); require(wire.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wire.copyOfRange(0,12)))
            cipher.updateAAD(scope.toByteArray())
            val plain = cipher.doFinal(wire.copyOfRange(12,wire.size))
            try { JSONObject(String(plain)).let { CountCredential(it.getString("token"),it.getLong("expires")) } }
            finally { plain.fill(0) }
        }
    } catch (_: Exception) { null }
    fun write(value: CountCredential?) {
        if(value == null) { preferences.edit().remove(scope).commit(); return }
        val plain = JSONObject().put("token",value.token).put("expires",value.expiresAt).toString().toByteArray()
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(scope.toByteArray())
            check(preferences.edit().putString(scope,(cipher.iv+cipher.doFinal(plain)).toByteString().base64()).commit())
        } finally { plain.fill(0) }
    }
}
