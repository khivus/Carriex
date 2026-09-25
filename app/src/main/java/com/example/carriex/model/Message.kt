package com.example.carriex.model

/**
 * Shared data model for messages.
 * 
 * content: ByteArray is used to ensure the transport layer does not 
 * rely on string-based formats, facilitating future encryption.
 */
data class Message(
    val from: String,
    val to: String,
    val content: ByteArray,
    val type: MessageType,
    val id: String = java.util.UUID.randomUUID().toString().replace("-", ""),
    val status: DeliveryStatus? = null,
) {
    enum class DeliveryStatus { SENDING, SENT, RECEIVED }
    enum class MessageType {
        KEY_EXCHANGE,
        DATA
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Message
        if (from != other.from) return false
        if (to != other.to) return false
        if (!content.contentEquals(other.content)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
