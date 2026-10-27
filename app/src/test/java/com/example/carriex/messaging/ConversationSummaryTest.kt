package com.example.carriex.messaging

import com.example.carriex.model.Message
import org.junit.Assert.*
import org.junit.Test

class ConversationSummaryTest {
    private fun message(time: Long?) = Message("a", "b", byteArrayOf(), Message.MessageType.DATA, sentAt = time)

    @Test fun groupsAndDirectChatsShareOneOrderAndUseSendTimeInsteadOfArrivalOrder() {
        val groupMessages = listOf(message(300), message(100))
        val summaries = listOf(
            ConversationSummary("direct", "Friend", message(200)),
            ConversationSummary("group:one", "Group", ConversationSummary.latest(groupMessages)),
            ConversationSummary("empty", "Empty", null),
            ConversationSummary("legacy", "Legacy", message(null)),
        ).sortedWith(ConversationSummary.order)
        assertEquals(listOf("group:one", "direct", "empty", "legacy"), summaries.map { it.id })
        assertEquals(300L, summaries.first().latest?.sentAt)
        assertNotNull(ConversationSummary.latest(listOf(message(null))))
    }
}
