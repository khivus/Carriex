package com.example.carriex.messaging

import org.junit.Assert.*
import org.junit.Test

class MessagePayloadTest {
    @Test fun preservesSenderTimeAndBodyWithoutUsingArrivalTime() {
        val sentAt = 1_700_000_000_123L
        val body = "Hello\nПривет 🌍".toByteArray()
        val decoded = MessagePayload.decode(MessagePayload.encode(body, sentAt))
        assertArrayEquals(body, decoded.content)
        assertEquals(sentAt, decoded.sentAt)
        val empty = MessagePayload.decode(MessagePayload.encode(byteArrayOf(), sentAt))
        assertArrayEquals(byteArrayOf(), empty.content)
        assertEquals(sentAt, empty.sentAt)
    }

    @Test fun legacyPlaintextHasNoInventedSendTime() {
        val body = "Old message".toByteArray()
        val decoded = MessagePayload.decode(body)
        assertArrayEquals(body, decoded.content)
        assertNull(decoded.sentAt)
        assertArrayEquals(body, MessagePayload.encode(body, null))
    }

    @Test fun malformedTimestampIsRejected() {
        val encoded = MessagePayload.encode("message".toByteArray(), 1_700_000_000_123L)
        assertThrows(IllegalArgumentException::class.java) { MessagePayload.decode(encoded.copyOf(10)) }
        java.util.Arrays.fill(encoded, 9, 17, 0.toByte())
        assertThrows(IllegalArgumentException::class.java) { MessagePayload.decode(encoded) }
    }
}
