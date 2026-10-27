package com.example.carriex.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A salted, versioned verifier; the Android store additionally wraps this in Keystore AES-GCM. */
object PasswordVerifier {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 128
    enum class Algorithm(val jcaName: String, val iterations: Int) {
        SHA256("PBKDF2WithHmacSHA256", 600_000),
        // PBKDF2WithHmacSHA256 is only guaranteed by Android from API 26.
        SHA1("PBKDF2WithHmacSHA1", 1_300_000)
    }

    fun create(password: CharArray, algorithm: Algorithm = Algorithm.SHA256): String {
        require(password.size in MIN_LENGTH..MAX_LENGTH)
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt, algorithm)
        return try { "1:${algorithm.name}:${salt.hex()}:${hash.hex()}" } finally { hash.fill(0) }
    }

    fun matches(password: CharArray, record: String): Boolean {
        if (password.size !in MIN_LENGTH..MAX_LENGTH) return false
        return runCatching {
            val parts = record.split(':')
            require(parts.size == 4 && parts[0] == "1")
            val algorithm = Algorithm.valueOf(parts[1])
            val salt = parts[2].unhex()
            val expected = parts[3].unhex()
            val actual = derive(password, salt, algorithm)
            try { MessageDigest.isEqual(expected, actual) } finally { expected.fill(0); actual.fill(0) }
        }.getOrDefault(false)
    }

    private fun derive(password: CharArray, salt: ByteArray, algorithm: Algorithm): ByteArray {
        val spec = PBEKeySpec(password, salt, algorithm.iterations, 256)
        return try { SecretKeyFactory.getInstance(algorithm.jcaName).generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray {
        require(length == 64 && all { it in '0'..'9' || it in 'a'..'f' })
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
