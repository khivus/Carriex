package com.example.carriex.messaging

import com.example.carriex.model.Message

/** The drawer and chat list use the same order, including groups and legacy history. */
data class ConversationSummary(val id: String, val label: String, val latest: Message?) {
    companion object {
        fun latest(messages: List<Message>): Message? =
            messages.maxByOrNull { it.sentAt ?: Long.MIN_VALUE }?.takeIf { it.sentAt != null }
                ?: messages.lastOrNull()

        val order = compareByDescending<ConversationSummary> { it.latest?.sentAt ?: Long.MIN_VALUE }.thenBy { it.id }
    }
}
