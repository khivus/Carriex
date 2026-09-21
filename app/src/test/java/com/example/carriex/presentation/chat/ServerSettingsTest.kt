package com.example.carriex.presentation.chat

import org.junit.Assert.*
import org.junit.Test

class ServerSettingsTest {
    @Test fun acceptsDomainsAndIpAddresses() {
        assertEquals("http://example.com:5000", ChatViewModel.serverUrl(" example.com ", "5000", false))
        assertEquals("https://192.168.1.2", ChatViewModel.serverUrl("192.168.1.2", "443", true))
        assertEquals("http://[::1]:5000", ChatViewModel.serverUrl("[::1]", "5000", false))
    }

    @Test fun rejectsMalformedEndpoints() {
        for ((host, port) in listOf("" to "5000", "http://example.com" to "5000", "example.com/path" to "5000", "example.com" to "0", "example.com" to "65536", "example.com" to "abc", "user@example.com" to "5000")) {
            assertThrows(IllegalArgumentException::class.java) { ChatViewModel.serverUrl(host, port, false) }
        }
    }

    @Test fun validatesRecipientIds() {
        assertTrue(ChatViewModel.validUserId("a".repeat(64)))
        assertFalse(ChatViewModel.validUserId("user-123_abc.test"))
        assertFalse(ChatViewModel.validUserId(""))
        assertFalse(ChatViewModel.validUserId("someone/else"))
        assertFalse(ChatViewModel.validUserId("a".repeat(65)))
    }
}
