package com.example.carriex.crypto

import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.session.Session

/**
 * Interface for cryptographic operations.
 * 
 * It must NOT know about UI, ViewModel, or Transport.
 * Its sole responsibility is processing ByteArrays.
 */
interface CryptoEngine {
    fun encrypt(data: ByteArray, session: Session): ByteArray
    fun decrypt(data: ByteArray, session: Session): ByteArray

    fun generateKeyPair(): KeyPair

    fun getIdentityKeyPair(userId: String): KeyPair

    fun saveSessionKeys(userId: String, remoteUserId: String, keys: com.example.carriex.crypto.model.SessionKeys)

    fun loadSessionKeys(userId: String, remoteUserId: String): com.example.carriex.crypto.model.SessionKeys?

    fun establishSession(myKeyPair: KeyPair, remotePublicKey: ByteArray): Session
}
