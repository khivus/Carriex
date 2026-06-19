package com.example.carriex.crypto.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.carriex.crypto.KeyManager
import com.example.carriex.crypto.libsodium.NativeCrypto
import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.crypto.model.SessionKeys
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore implementation of KeyManager.
 * 
 * Uses a hybrid model:
 * 1. An AES wrapping key is stored in Android Keystore.
 * 2. Libsodium identity keys and session keys are encrypted with the wrapping key and stored in SharedPreferences.
 */
class AndroidKeyStoreKeyManager(context: Context) : KeyManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_KEY_ALIAS_PREFIX = "carriex_wrap_"
        private const val IDENTITY_KEY_ALIAS_PREFIX = "carriex_identity_"
        private const val SESSION_KEY_ALIAS_PREFIX = "carriex_session_"
        private const val PREFS_NAME = "carriex_secure_storage"
        private const val AES_GCM_TAG_LENGTH = 128
        private const val NONCE_LENGTH = 12
    }

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun generateOrGetIdentityKeyPair(userId: String): KeyPair {
        val encryptedKeyBase64 = prefs.getString(IDENTITY_KEY_ALIAS_PREFIX + userId, null)
        if (encryptedKeyBase64 != null) {
            val encryptedData = android.util.Base64.decode(encryptedKeyBase64, android.util.Base64.DEFAULT)
            val decryptedData = decryptData(userId, encryptedData)
            return KeySerializer.deserialize(decryptedData)
        }

        // Generate new Libsodium keypair
        val keyPair = NativeCrypto.kxKeypair()
        
        // Encrypt and store it
        val encrypted = encryptData(userId, KeySerializer.serialize(keyPair))
        prefs.edit().putString(
            IDENTITY_KEY_ALIAS_PREFIX + userId, 
            android.util.Base64.encodeToString(encrypted, android.util.Base64.DEFAULT)
        ).apply()
        
        return keyPair
    }

    override fun getPublicKey(userId: String): ByteArray {
        return generateOrGetIdentityKeyPair(userId).publicKey
    }

    override fun getPrivateKey(userId: String): ByteArray {
        return generateOrGetIdentityKeyPair(userId).secretKey
    }

    override fun deleteKeys(userId: String) {
        keyStore.deleteEntry(WRAP_KEY_ALIAS_PREFIX + userId)
        prefs.edit().remove(IDENTITY_KEY_ALIAS_PREFIX + userId).apply()
        
        // Delete all session keys for this user
        val prefix = "${SESSION_KEY_ALIAS_PREFIX}${userId}_"
        val allEntries = prefs.all
        val editor = prefs.edit()
        for (key in allEntries.keys) {
            if (key.startsWith(prefix)) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    override fun storeSessionKeys(userId: String, remoteUserId: String, keys: SessionKeys) {
        val alias = "${SESSION_KEY_ALIAS_PREFIX}${userId}_${remoteUserId}"
        val encrypted = encryptData(userId, KeySerializer.serializeSessionKeys(keys))
        prefs.edit().putString(
            alias, 
            android.util.Base64.encodeToString(encrypted, android.util.Base64.DEFAULT)
        ).apply()
    }

    override fun getSessionKeys(userId: String, remoteUserId: String): SessionKeys? {
        val alias = "${SESSION_KEY_ALIAS_PREFIX}${userId}_${remoteUserId}"
        val encryptedBase64 = prefs.getString(alias, null) ?: return null
        val encryptedData = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
        val decryptedData = decryptData(userId, encryptedData)
        return KeySerializer.deserializeSessionKeys(decryptedData)
    }

    override fun rotateIdentityKeys(userId: String) {
        // Implementation for key rotation
        // 1. Generate new identity key pair
        // 2. Encrypt and store it, possibly keeping the old one for some time
        // For now, this is a stub.
    }

    /**
     * Retrieves or generates an AES wrapping key from Android Keystore.
     */
    private fun getOrGenerateWrappingKey(userId: String): SecretKey {
        val alias = WRAP_KEY_ALIAS_PREFIX + userId
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            return entry?.secretKey ?: throw RuntimeException("Failed to load wrapping key from Keystore")
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun encryptData(userId: String, data: ByteArray): ByteArray {
        val wrappingKey = getOrGenerateWrappingKey(userId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        
        // Combine IV and ciphertext for storage: [IV (12 bytes)][Ciphertext]
        return iv + ciphertext
    }

    private fun decryptData(userId: String, data: ByteArray): ByteArray {
        if (data.size < NONCE_LENGTH) throw IllegalArgumentException("Invalid encrypted data size")
        
        val wrappingKey = getOrGenerateWrappingKey(userId)
        val iv = data.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = data.copyOfRange(NONCE_LENGTH, data.size)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
        
        return cipher.doFinal(ciphertext)
    }
}
