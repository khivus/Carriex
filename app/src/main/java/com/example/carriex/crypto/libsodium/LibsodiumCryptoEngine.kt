package com.example.carriex.crypto.libsodium

import com.example.carriex.crypto.CryptoEngine
import com.example.carriex.crypto.KeyManager
import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.crypto.model.SessionKeys
import com.example.carriex.session.Session
import com.example.carriex.session.SessionState

/**
 * Implementation of CryptoEngine using Libsodium.
 */
class LibsodiumCryptoEngine(private val keyManager: KeyManager) : CryptoEngine {

    companion object {
        private const val NONCE_BYTES = 24
    }

    override fun encrypt(data: ByteArray, session: Session): ByteArray {
        if (session.state != SessionState.ESTABLISHED) {
            throw IllegalStateException("Session not established")
        }
        val keys = session.sessionKeys ?: throw IllegalStateException("Session keys missing")
        val nonce = NativeCrypto.randombytes(NONCE_BYTES)

        val ciphertext = NativeCrypto.aeadXchacha20poly1305Encrypt(data, null, nonce, keys.tx)
        return nonce + ciphertext
    }

    override fun decrypt(data: ByteArray, session: Session): ByteArray {
        if (session.state != SessionState.ESTABLISHED) {
            throw IllegalStateException("Session not established")
        }
        val keys = session.sessionKeys ?: throw IllegalStateException("Session keys missing")

        if (data.size < NONCE_BYTES) throw IllegalArgumentException("Invalid data size")

        val nonce = data.copyOfRange(0, NONCE_BYTES)
        val ciphertext = data.copyOfRange(NONCE_BYTES, data.size)

        return NativeCrypto.aeadXchacha20poly1305Decrypt(ciphertext, null, nonce, keys.rx)
            ?: throw RuntimeException("Decryption failed")
    }

    override fun generateKeyPair(): KeyPair {
        return NativeCrypto.kxKeypair()
    }

    /**
     * Retrieves an identity key pair via KeyManager.
     */
    override fun getIdentityKeyPair(userId: String): KeyPair {
        return keyManager.generateOrGetIdentityKeyPair(userId)
    }

    override fun saveSessionKeys(userId: String, remoteUserId: String, keys: SessionKeys) {
        keyManager.storeSessionKeys(userId, remoteUserId, keys)
    }

    override fun loadSessionKeys(userId: String, remoteUserId: String): SessionKeys? {
        return keyManager.getSessionKeys(userId, remoteUserId)
    }

    override fun establishSession(myKeyPair: KeyPair, remotePublicKey: ByteArray): Session {
        val isClient = comparePublicKeys(myKeyPair.publicKey, remotePublicKey) < 0

        val sessionKeys = if (isClient) {
            NativeCrypto.kxClientSessionKeys(myKeyPair.publicKey, myKeyPair.secretKey, remotePublicKey)
        } else {
            NativeCrypto.kxServerSessionKeys(myKeyPair.publicKey, myKeyPair.secretKey, remotePublicKey)
        } ?: throw RuntimeException("Failed to establish session keys")

        return Session(
            state = SessionState.ESTABLISHED,
            myKeyPair = myKeyPair,
            remotePublicKey = remotePublicKey,
            sessionKeys = sessionKeys
        )
    }

    private fun comparePublicKeys(pk1: ByteArray, pk2: ByteArray): Int {
        for (i in pk1.indices) {
            val b1 = pk1[i].toInt() and 0xFF
            val b2 = pk2[i].toInt() and 0xFF
            if (b1 != b2) return b1 - b2
        }
        return 0
    }
}