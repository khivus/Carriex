package com.example.carriex.messaging

import java.nio.ByteBuffer

/** Timestamp and body travel together inside authenticated encryption. */
object MessagePayload {
    private val prefix = byteArrayOf(0, 67, 65, 82, 82, 73, 69, 88, 1)
    data class Decoded(val content: ByteArray, val sentAt: Long?)

    fun encode(content: ByteArray, sentAt: Long?): ByteArray {
        if (sentAt == null) return content
        require(sentAt > 0) { "Invalid message send time" }
        return ByteBuffer.allocate(prefix.size + 8 + content.size)
            .put(prefix).putLong(sentAt).put(content).array()
    }

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size < prefix.size || !prefix.indices.all { bytes[it] == prefix[it] })
            return Decoded(bytes, null)
        require(bytes.size >= prefix.size + 8) { "Incomplete message timestamp" }
        val sentAt = ByteBuffer.wrap(bytes, prefix.size, 8).long
        require(sentAt > 0) { "Invalid message send time" }
        return Decoded(bytes.copyOfRange(prefix.size + 8, bytes.size), sentAt)
    }
}
