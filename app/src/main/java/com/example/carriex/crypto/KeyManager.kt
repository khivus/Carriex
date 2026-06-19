package com.example.carriex.crypto

import com.example.carriex.crypto.model.KeyPair

/**
 * Interface for secure key management.
 * 
 * Abstraction layer to isolate Android Keystore details from the rest of the app.
 */
interface KeyManager {
    /**
     * Retrieves existing identity key pair or generates a new one if not found.
     */
    fun generateOrGetIdentityKeyPair(userId: String): KeyPair

    /**
     * Retrieves the public key for a given user identity.
     */
    fun getPublicKey(userId: String): ByteArray

    /**
     * Retrieves the private key for a given user identity.
     */
    fun getPrivateKey(userId: String): ByteArray

    /**
     * Deletes all keys associated with a user.
     */
    fun deleteKeys(userId: String)

    /**
     * Securely stores session keys for a given peer.
     */
    fun storeSessionKeys(userId: String, remoteUserId: String, keys: com.example.carriex.crypto.model.SessionKeys)

    /**
     * Retrieves session keys for a given peer.
     */
    fun getSessionKeys(userId: String, remoteUserId: String): com.example.carriex.crypto.model.SessionKeys?

    /**
     * Rotates the identity keys for a user.
     */
    fun rotateIdentityKeys(userId: String)
}
