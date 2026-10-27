package com.example.carriex.messaging

import java.nio.ByteBuffer
import com.example.carriex.model.Message

/** Timestamp and body travel together inside authenticated encryption. */
object MessagePayload {
    private val prefix = byteArrayOf(0, 67, 65, 82, 82, 73, 69, 88, 1)
    data class Decoded(val content: ByteArray, val sentAt: Long?, val type: Message.MessageType = Message.MessageType.DATA)

    fun encode(content: ByteArray, sentAt: Long?, type: Message.MessageType = Message.MessageType.DATA): ByteArray {
        if(type == Message.MessageType.CHAT_EVENT) {
            require(sentAt != null && sentAt > 0)
            ContactAction.parse(content)
            val typedPrefix = prefix.copyOf().apply { this[lastIndex] = 2 }
            return ByteBuffer.allocate(typedPrefix.size + 8 + content.size)
                .put(typedPrefix).putLong(sentAt).put(content).array()
        }
        require(type == Message.MessageType.DATA)
        if (sentAt == null) return content.copyOf()
        require(sentAt > 0) { "Invalid message send time" }
        return ByteBuffer.allocate(prefix.size + 8 + content.size)
            .put(prefix).putLong(sentAt).put(content).array()
    }

    fun decode(bytes: ByteArray): Decoded {
        val typed = bytes.size >= prefix.size && bytes[prefix.lastIndex] == 2.toByte()
        if (bytes.size < prefix.size || !(0 until prefix.lastIndex).all { bytes[it] == prefix[it] } ||
            (bytes[prefix.lastIndex] != prefix.last() && !typed))
            return Decoded(bytes.copyOf(), null)
        require(bytes.size >= prefix.size + 8) { "Incomplete message timestamp" }
        val sentAt = ByteBuffer.wrap(bytes, prefix.size, 8).long
        require(sentAt > 0) { "Invalid message send time" }
        val content = bytes.copyOfRange(prefix.size + 8, bytes.size)
        if(typed) ContactAction.parse(content)
        return Decoded(content, sentAt, if(typed) Message.MessageType.CHAT_EVENT else Message.MessageType.DATA)
    }
}
