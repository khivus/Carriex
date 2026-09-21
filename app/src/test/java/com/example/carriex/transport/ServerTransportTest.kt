package com.example.carriex.transport

import com.example.carriex.auth.ServerAuthentication
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real loopback WebSocket handshakes; no Android runtime or mocked callbacks. */
class ServerTransportTest {
    private fun auth() = object : ServerAuthentication {
        override val userId = "alice"
        override fun token() = "test-token"
        override fun invalidate(token: String) { }
    }
    private class Peer : AutoCloseable {
        val server = ServerSocket(0).apply { soTimeout = 4000 }
        val sockets = mutableListOf<Socket>()
        val url get() = "http://127.0.0.1:${server.localPort}"
        fun accept(): Socket {
            val socket = server.accept().apply { soTimeout = 4000 }
            sockets.add(socket)
            val reader = socket.getInputStream().bufferedReader()
            var key = ""
            while (true) {
                val line = reader.readLine() ?: error("Missing handshake")
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", true)) key = line.substringAfter(':').trim()
            }
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
            socket.getOutputStream().flush()
            return socket
        }
        override fun close() { sockets.forEach { it.close() }; server.close() }
    }
    private fun expect(events: LinkedBlockingQueue<ServerTransport.ConnectionStatus>, value: ServerTransport.ConnectionStatus) {
        assertEquals(value, events.poll(5, TimeUnit.SECONDS))
    }
    @Test fun reconnectsAfterAbruptDropAndStopsOnDisconnect() {
        Peer().use { peer ->
            val transport = ServerTransport(peer.url, auth(), 30)
            val events = LinkedBlockingQueue<ServerTransport.ConnectionStatus>()
            transport.onConnectionStatusChanged = { events.offer(it) }
            try {
                transport.connectWebSocket("alice") { }
                expect(events, ServerTransport.ConnectionStatus.CONNECTING)
                peer.accept().close()
                expect(events, ServerTransport.ConnectionStatus.CONNECTED)
                expect(events, ServerTransport.ConnectionStatus.RECONNECTING)
                peer.accept()
                expect(events, ServerTransport.ConnectionStatus.CONNECTED)
                transport.disconnectWebSockets()
                expect(events, ServerTransport.ConnectionStatus.DISCONNECTED)
                assertNull(events.poll(200, TimeUnit.MILLISECONDS))
            } finally { transport.close() }
        }
    }
    @Test fun intentionalDisconnectCancelsScheduledRetry() {
        Peer().use { peer ->
            val transport = ServerTransport(peer.url, auth(), 500)
            val events = LinkedBlockingQueue<ServerTransport.ConnectionStatus>()
            transport.onConnectionStatusChanged = { events.offer(it) }
            try {
                transport.connectWebSocket("alice") { }
                expect(events, ServerTransport.ConnectionStatus.CONNECTING)
                peer.accept()
                expect(events, ServerTransport.ConnectionStatus.CONNECTED)
                peer.sockets.last().close()
                expect(events, ServerTransport.ConnectionStatus.RECONNECTING)
                transport.disconnectWebSockets()
                expect(events, ServerTransport.ConnectionStatus.DISCONNECTED)
                peer.server.soTimeout = 800
                try { peer.server.accept().use { fail("Unexpected retry after disconnect") } }
                catch (_: java.net.SocketTimeoutException) { }
            } finally { transport.close() }
        }
    }
    @Test fun retriesInitialHandshakeFailure() {
        Peer().use { peer ->
            val transport = ServerTransport(peer.url, auth(), 30)
            val events = LinkedBlockingQueue<ServerTransport.ConnectionStatus>()
            transport.onConnectionStatusChanged = { events.offer(it) }
            try {
                transport.connectWebSocket("alice") { }
                expect(events, ServerTransport.ConnectionStatus.CONNECTING)
                peer.server.accept().close()
                expect(events, ServerTransport.ConnectionStatus.RECONNECTING)
                peer.accept()
                expect(events, ServerTransport.ConnectionStatus.CONNECTED)
            } finally { transport.close() }
        }
    }
    @Test fun reconnectsAfterServerCloseFrame() {
        Peer().use { peer ->
            val transport = ServerTransport(peer.url, auth(), 30)
            val events = LinkedBlockingQueue<ServerTransport.ConnectionStatus>()
            transport.onConnectionStatusChanged = { events.offer(it) }
            try {
                transport.connectWebSocket("alice") { }
                expect(events, ServerTransport.ConnectionStatus.CONNECTING)
                val socket = peer.accept()
                expect(events, ServerTransport.ConnectionStatus.CONNECTED)
                socket.getOutputStream().write(byteArrayOf(0x88.toByte(), 2, 3, 0xe8.toByte()))
                socket.getOutputStream().flush()
                expect(events, ServerTransport.ConnectionStatus.RECONNECTING)
                peer.accept()
                expect(events, ServerTransport.ConnectionStatus.CONNECTED)
            } finally { transport.close() }
        }
    }
}
