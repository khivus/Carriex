package com.example.carriex.auth

import com.example.carriex.model.Message
import com.example.carriex.transport.ServerTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Cross-language tests using the actual Kotlin transport and real Flask server. */
class DeviceServerIntegrationTest {
    private fun identity() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair())

    private class Server(val python: String, val root: String) : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val url = "http://127.0.0.1:$port"
        private val log = File.createTempFile("carriex-auth-test-", ".log")
        private val dataDir = java.nio.file.Files.createTempDirectory("carriex-auth-data-").toFile().apply {
            resolve("messages.sqlite3").deleteOnExit(); deleteOnExit()
        }
        private var process: Process? = null
        fun start() {
            process = ProcessBuilder(python, "-c", "from src.app import create_app; create_app({'ALLOW_INSECURE_HTTP': True, 'AUTH_SESSION_TTL': 3}).run(host='127.0.0.1',port=$port,threaded=True,use_reloader=False)")
                .directory(File(root)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .apply { environment()["CARRIEX_DATA_DIR"] = dataDir.absolutePath }.start()
            val client = OkHttpClient()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (System.nanoTime() < deadline && process!!.isAlive) {
                    try {
                        client.newCall(Request.Builder().url("$url/health").build()).execute().use { if (it.code == 200) return }
                    } catch (_: Exception) { }
                    Thread.sleep(30)
                }
                error("Flask failed to start: ${log.readText()}")
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
        fun stop() { process?.destroyForcibly()?.waitFor(5, TimeUnit.SECONDS) }
        override fun close() { stop(); log.delete() }
    }

    private fun server(): Server {
        val python = System.getenv("CARRIEX_TEST_PYTHON")
        val root = System.getenv("CARRIEX_TEST_SERVER_ROOT")
        assumeTrue("Set CARRIEX_TEST_PYTHON and CARRIEX_TEST_SERVER_ROOT for Flask integration", python != null && root != null)
        return Server(python!!, root!!).also { it.start() }
    }

    private fun awaitStatus(transport: ServerTransport, status: ServerTransport.ConnectionStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (transport.connectionStatus != status && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(transport.connectionError, status, transport.connectionStatus)
    }

    @Test fun actualClientAuthenticatesSendsAndRenewsExpiredWebSocket() {
        server().use { server ->
            val alice = identity()
            val bob = identity()
            val sender = ServerTransport(server.url, DeviceAuthentication(server.url, alice), 40)
            val recipient = ServerTransport(server.url, DeviceAuthentication(server.url, bob), 40)
            val incoming = LinkedBlockingQueue<Message>()
            val receipts = LinkedBlockingQueue<Pair<String, String>>()
            val states = LinkedBlockingQueue<ServerTransport.ConnectionStatus>()
            recipient.onConnectionStatusChanged = { states.offer(it) }
            sender.onReceipt = { id, state -> receipts.offer(id to state) }
            try {
                assertTrue(sender.testServerActive())
                assertTrue(recipient.testServerActive())
                sender.connectWebSocket(alice.userId) { }
                recipient.connectWebSocket(bob.userId) { incoming.offer(it) }
                awaitStatus(sender, ServerTransport.ConnectionStatus.CONNECTED)
                awaitStatus(recipient, ServerTransport.ConnectionStatus.CONNECTED)
                val message = Message(alice.userId, bob.userId, "authenticated".toByteArray(), Message.MessageType.DATA)
                assertTrue(sender.sendWebSocketMessage(message))
                val received = incoming.poll(5, TimeUnit.SECONDS)!!
                assertEquals(alice.userId, received.from)
                assertArrayEquals(message.content, received.content)
                assertEquals(message.id, received.id)
                assertTrue(recipient.acknowledge(bob.userId, received.id))
                assertEquals(message.id to "received", receipts.poll(5, TimeUnit.SECONDS))
                assertEquals("received", sender.statuses(alice.userId).single { it.first == message.id }.second)
                states.clear()
                assertEquals(ServerTransport.ConnectionStatus.RECONNECTING, states.poll(6, TimeUnit.SECONDS))
                assertEquals(ServerTransport.ConnectionStatus.CONNECTED, states.poll(6, TimeUnit.SECONDS))
                val second = Message(alice.userId, bob.userId, "after renewal".toByteArray(), Message.MessageType.DATA)
                assertTrue(sender.sendMessage(second))
                assertArrayEquals(second.content, incoming.poll(5, TimeUnit.SECONDS)!!.content)
            } finally { sender.close(); recipient.close() }
        }
    }

    @Test fun actualClientRecoversHttpSessionAndWebSocketAfterServerRestart() {
        server().use { server ->
            val identity = identity()
            val transport = ServerTransport(server.url, DeviceAuthentication(server.url, identity), 100)
            val incoming = LinkedBlockingQueue<Message>()
            try {
                assertTrue(transport.testServerActive())
                transport.connectWebSocket(identity.userId) { incoming.offer(it) }
                awaitStatus(transport, ServerTransport.ConnectionStatus.CONNECTED)
                server.stop()
                awaitStatus(transport, ServerTransport.ConnectionStatus.RECONNECTING)
                server.start()
                awaitStatus(transport, ServerTransport.ConnectionStatus.CONNECTED)
                transport.disconnectWebSockets()
                // Force another restart while HTTP still holds a cached bearer token.
                server.stop(); server.start()
                val message = Message(identity.userId, identity.userId, "after restart".toByteArray(), Message.MessageType.DATA)
                assertTrue(transport.sendMessage(message))
                val messages = transport.getAllMessages(identity.userId)
                assertEquals(1, messages.size)
                assertArrayEquals(message.content, messages.single().content)
            } finally { transport.close() }
        }
    }
}
