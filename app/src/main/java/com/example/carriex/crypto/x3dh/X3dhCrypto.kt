package com.example.carriex.crypto.x3dh

import com.example.carriex.crypto.libsodium.NativeCrypto
import com.example.carriex.crypto.model.KeyPair
import java.math.BigInteger
import java.security.MessageDigest
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Byte-level protocol operations, independent of Android storage and transport. */
interface X3dhPrimitives {
    fun keyPair(): KeyPair
    fun random(size: Int): ByteArray
    fun dh(secret: ByteArray, publicKey: ByteArray): ByteArray
    fun sign(secret: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    fun encrypt(plain: ByteArray, aad: ByteArray, key: ByteArray): ByteArray
    fun decrypt(wire: ByteArray, aad: ByteArray, key: ByteArray): ByteArray
}

class SodiumX3dhPrimitives : X3dhPrimitives {
    override fun keyPair() = NativeCrypto.kxKeypair()
    override fun random(size: Int) = NativeCrypto.randombytes(size)
    override fun dh(secret: ByteArray, publicKey: ByteArray): ByteArray {
        require(secret.size == 32 && publicKey.size == 32)
        return NativeCrypto.x25519(secret, publicKey) ?: error("Invalid X25519 public key")
    }
    override fun sign(secret: ByteArray, message: ByteArray): ByteArray {
        val noise = random(64)
        return try { NativeCrypto.xeddsaSign(secret, message, noise) ?: error("XEdDSA signing failed") }
        finally { noise.fill(0) }
    }
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try { NativeCrypto.ed25519Verify(X3dhCrypto.edwardsPublic(publicKey), message, signature) }
        catch (_: IllegalArgumentException) { false }
    override fun encrypt(plain: ByteArray, aad: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32)
        val nonce = random(24)
        return nonce + NativeCrypto.aeadXchacha20poly1305Encrypt(plain, aad, nonce, key)
    }
    override fun decrypt(wire: ByteArray, aad: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32 && wire.size >= 40) { "Invalid encrypted envelope" }
        return NativeCrypto.aeadXchacha20poly1305Decrypt(wire.copyOfRange(24, wire.size), aad,
            wire.copyOfRange(0, 24), key) ?: error("Message authentication failed")
    }
}

object X3dhCrypto {
    fun encode(bytes: ByteArray): String = bytes.toByteString().base64()
    fun decode(text: String, size: Int? = null): ByteArray {
        require(text.length <= 2_000_000) { "Oversized encoding" }
        val bytes = text.decodeBase64()?.toByteArray() ?: error("Invalid base64")
        require(encode(bytes) == text && (size == null || bytes.size == size)) { "Invalid encoding" }
        return bytes
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun encodeKey(key: ByteArray): ByteArray { require(key.size == 32); return byteArrayOf(5) + key }
    fun binding(userId: String, identity: ByteArray) =
        "Carriex encryption identity v2\n$userId\n${encode(identity)}".toByteArray(Charsets.US_ASCII)

    // Only public arithmetic uses BigInteger; secret scalar arithmetic stays in libsodium.
    fun edwardsPublic(montgomery: ByteArray): ByteArray {
        require(montgomery.size == 32)
        val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val u = BigInteger(1, montgomery.reversedArray())
        require(u < p && u != p.subtract(BigInteger.ONE)) { "Noncanonical X25519 key" }
        val y = u.subtract(BigInteger.ONE).multiply(u.add(BigInteger.ONE).modInverse(p)).mod(p)
        val big = y.toByteArray().reversedArray()
        return ByteArray(32) { if (it < big.size) big[it] else 0 }
    }

    fun hkdf(input: ByteArray, salt: ByteArray = ByteArray(32), info: String, count: Int = 32): ByteArray {
        require(count in 1..64)
        fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        val prk = hmac(salt, input)
        var previous = ByteArray(0)
        var result = ByteArray(0)
        try {
            for (index in 1..((count + 31) / 32)) {
                val next = hmac(prk, previous + info.toByteArray(Charsets.US_ASCII) + index.toByte())
                previous.fill(0); previous = next; result += next
            }
            return result.copyOf(count)
        } finally { prk.fill(0); previous.fill(0); result.fill(0) }
    }
    fun initiate(ops: X3dhPrimitives, identity: KeyPair, ephemeral: KeyPair,
                 remoteIdentity: ByteArray, signed: ByteArray, oneTime: ByteArray?): ByteArray = derive(listOfNotNull(
        ops.dh(identity.secretKey, signed), ops.dh(ephemeral.secretKey, remoteIdentity),
        ops.dh(ephemeral.secretKey, signed), oneTime?.let { ops.dh(ephemeral.secretKey, it) }))
    fun respond(ops: X3dhPrimitives, identity: KeyPair, signed: KeyPair, oneTime: KeyPair?,
                remoteIdentity: ByteArray, ephemeral: ByteArray): ByteArray = derive(listOfNotNull(
        ops.dh(signed.secretKey, remoteIdentity), ops.dh(identity.secretKey, ephemeral),
        ops.dh(signed.secretKey, ephemeral), oneTime?.let { ops.dh(it.secretKey, ephemeral) }))
    private fun derive(parts: List<ByteArray>): ByteArray {
        val material = ByteArray(32) { 0xff.toByte() } + parts.fold(ByteArray(0)) { a, b -> a + b }
        return try { hkdf(material, info = "Carriex X3DH v2") }
        finally { material.fill(0); parts.forEach { it.fill(0) } }
    }
}
