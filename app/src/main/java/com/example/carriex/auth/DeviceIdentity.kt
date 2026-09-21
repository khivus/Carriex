package com.example.carriex.auth

import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature

/** A signing handle. Android's private key implementation never exposes key bytes. */
class DeviceIdentity(private val keyPair: KeyPair) {
    val publicKey: ByteArray get() = keyPair.public.encoded
    val userId: String = MessageDigest.getInstance("SHA-256").digest(publicKey)
        .joinToString("") { "%02x".format(it) }

    fun signChallenge(userId: String, challenge: String, audience: String): ByteArray {
        require(userId == this.userId) { "Server returned a different device identity" }
        require(challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid server challenge" }
        require(audience.isNotEmpty() && !audience.contains('\n') && !audience.contains('\r')) { "Invalid server audience" }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update("Carriex device authentication v1\n$audience\n$userId\n$challenge".toByteArray(Charsets.UTF_8))
            sign()
        }
    }
}
