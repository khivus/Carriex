package com.example.carriex.messaging

/** Relay counts the hex encoding of the complete JSON/base64 envelope. */
object MessageLimits {
    const val MAX_PLAINTEXT_BYTES = 90 * 1024
    const val MAX_RELAY_BYTES = 256 * 1024
    fun requirePlaintext(bytes: ByteArray) {
        if (bytes.size > MAX_PLAINTEXT_BYTES)
            throw MessageSizeException("Message is too long. Maximum text size is 90 KiB in UTF-8.")
    }
    fun requireWire(bytes: ByteArray) {
        if (2L * (bytes.size + 1L) > MAX_RELAY_BYTES)
            throw MessageSizeException("Encrypted message exceeds the relay's 256 KiB limit. Send a shorter message.")
    }
}

class MessageSizeException(message: String) : IllegalArgumentException(message)
