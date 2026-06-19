package com.example.carriex.session

import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.crypto.model.SessionKeys

/**
 * Model representing a communication session with another user.
 */
data class Session(
    val state: SessionState,
    val myKeyPair: KeyPair? = null,
    val remotePublicKey: ByteArray? = null,
    val sessionKeys: SessionKeys? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Session
        if (state != other.state) return false
        if (myKeyPair != other.myKeyPair) return false
        if (sessionKeys != other.sessionKeys) return false
        if (remotePublicKey != null) {
            if (other.remotePublicKey == null) return false
            if (!remotePublicKey.contentEquals(other.remotePublicKey)) return false
        } else if (other.remotePublicKey != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + (myKeyPair?.hashCode() ?: 0)
        result = 31 * result + (remotePublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (sessionKeys?.hashCode() ?: 0)
        return result
    }
}
