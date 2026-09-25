package com.example.carriex.transport

import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.auth.ServerAuthentication
import com.example.carriex.model.Message
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Authenticated WebSocket transport with authenticated HTTP fallback. */
class ServerTransport(
    private val baseUrl: String,
    private val authentication: ServerAuthentication,
    private val retryDelayMillis: Long = 1000,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val base = baseUrl.toHttpUrl()
    init { DeviceAuthentication.requireSecureEndpoint(base) }

    enum class ConnectionStatus(val label: String) {
        DISCONNECTED("Disconnected"), CONNECTING("Connecting and authenticating..."),
        CONNECTED("Connected - device authenticated"),
        RECONNECTING("Connection lost - reconnecting and authenticating...")
    }
    @Volatile var connectionStatus = ConnectionStatus.DISCONNECTED
        private set
    @Volatile var connectionError: String? = null
        private set
    @Volatile var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null
    @Volatile var onReceipt: ((String, String) -> Unit)? = null
    private var closed = false
    private val retries = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carriex-reconnect").apply { isDaemon = true }
    }
    private class Connection(val receive: (Message) -> Unit) {
        var socket: WebSocket? = null
        var connected = false
        var failures = 0
        var retry: ScheduledFuture<*>? = null
    }
    private val connections = mutableMapOf<String, Connection>()

    private fun status(value: ConnectionStatus) {
        connectionStatus = value
        onConnectionStatusChanged?.invoke(value)
    }

    @Synchronized
    fun connectWebSocket(userId: String, onMessageReceived: (Message) -> Unit) {
        check(!closed) { "Transport is closed" }
        require(userId == authentication.userId) { "Device identity mismatch" }
        connections.remove(userId)?.let { it.retry?.cancel(false); it.socket?.cancel() }
        val connection = Connection(onMessageReceived)
        connections[userId] = connection
        status(ConnectionStatus.CONNECTING)
        // Signing and challenge exchange never block the UI thread or transport lock.
        connection.retry = retries.schedule({ openSocket(userId, connection) }, 0, TimeUnit.MILLISECONDS)
    }

    private fun openSocket(userId: String, connection: Connection) {
        synchronized(this) {
            if (connections[userId] !== connection || closed) return
            connection.retry = null
        }
        val token = try { authentication.token() } catch (e: Exception) {
            synchronized(this) {
                if (connections[userId] === connection && !closed) {
                    connectionError = e.message ?: "Device authentication failed"
                    scheduleReconnect(userId, connection)
                }
            }
            return
        }
        synchronized(this) {
            if (connections[userId] !== connection || closed) return
            val url = base.newBuilder().addPathSegment("ws").addPathSegment(userId).build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            connection.socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(this@ServerTransport) {
                        if (connections[userId] !== connection || connection.socket !== webSocket) {
                            webSocket.cancel(); return
                        }
                        connection.connected = true
                        connection.failures = 0
                        connectionError = null
                        status(ConnectionStatus.CONNECTED)
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(this@ServerTransport) {
                        if (connections[userId] !== connection || connection.socket !== webSocket) return
                    }
                    try {
                        val payload = JSONObject(text)
                        if (payload.optString("type") == "message") {
                            decodeMessage(payload.getJSONObject("message"), userId)?.let(connection.receive)
                        } else if (payload.optString("type") == "receipt") {
                            onReceipt?.invoke(payload.getString("id"), payload.getString("state"))
                        }
                    } catch (_: Exception) { /* Ignore malformed frames. */ }
                }
                private fun reconnect(webSocket: WebSocket) {
                    synchronized(this@ServerTransport) {
                        if (connections[userId] !== connection || connection.socket !== webSocket) return
                        connection.connected = false
                        connection.socket = null
                        webSocket.cancel()
                        scheduleReconnect(userId, connection)
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 1008) authentication.invalidate(token)
                    webSocket.close(code, reason)
                    reconnect(webSocket)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect(webSocket)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) authentication.invalidate(token)
                    reconnect(webSocket)
                }
            })
        }
    }

    // Caller holds the transport lock. Only one retry per active connection.
    private fun scheduleReconnect(userId: String, connection: Connection) {
        status(ConnectionStatus.RECONNECTING)
        val delay = (retryDelayMillis * (1L shl connection.failures)).coerceAtMost(30000)
        connection.failures = (connection.failures + 1).coerceAtMost(5)
        connection.retry = retries.schedule({ openSocket(userId, connection) }, delay, TimeUnit.MILLISECONDS)
    }

    /** Local socket acceptance, not a delivery receipt. */
    @Synchronized
    fun sendWebSocketMessage(message: Message): Boolean {
        require(message.from == authentication.userId) { "Sender identity mismatch" }
        val connection = connections[message.from] ?: return false
        if (!connection.connected) return false
        return connection.socket?.send(encodeMessage(message).put("type", "send").toString()) == true
    }

    @Synchronized
    fun disconnectWebSockets() {
        val previous = connections.values.toList()
        connections.clear()
        previous.forEach { it.retry?.cancel(false); it.socket?.cancel() }
        status(ConnectionStatus.DISCONNECTED)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        disconnectWebSockets()
        onConnectionStatusChanged = null
        onReceipt = null
        retries.shutdownNow()
        authentication.close()
        client.closeOffThread()
    }

    /** Verify protocol availability, then prove ownership before entering chat. */
    fun testServerActive(): Boolean {
        connectionError = null
        return try {
            client.newCall(Request.Builder().url(base.newBuilder().addPathSegment("health").build()).build())
                .execute().use { response ->
                    if (response.code != 200 || JSONObject(response.body!!.string()).optString("status") != "alive") {
                        throw IOException("This address did not return a Carriex health response")
                    }
                }
            authentication.token()
            true
        } catch (_: javax.net.ssl.SSLException) {
            connectionError = "Server identity could not be verified. Check the address, device clock and saved pairing. If the server key changed, verify it with its owner before pairing again."
            false
        } catch (e: Exception) {
            connectionError = "Cannot authenticate with $baseUrl: ${e.message}"
            false
        }
    }

    private fun authenticated(request: Request): Response {
        repeat(2) { attempt ->
            val token = authentication.token()
            val response = client.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute()
            if (response.code != 401 || attempt == 1) return response
            response.close()
            authentication.invalidate(token)
        }
        error("Unreachable")
    }

    fun sendMessage(message: Message): Boolean {
        require(message.from == authentication.userId) { "Sender identity mismatch" }
        return try {
            val request = Request.Builder().url(base.newBuilder().addPathSegment("send").build())
                .post(encodeMessage(message).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            authenticated(request).use { it.code == 201 }
        } catch (_: Exception) { false }
    }

    fun getAllMessages(userId: String): List<Message> {
        require(userId == authentication.userId) { "Device identity mismatch" }
        return try {
            val request = Request.Builder().url(base.newBuilder().addPathSegment("messages").addPathSegment(userId).build()).build()
            authenticated(request).use { response ->
                if (response.code != 200) return emptyList()
                val messages = JSONObject(response.body!!.string()).getJSONArray("messages")
                (0 until messages.length()).mapNotNull { decodeMessage(messages.getJSONObject(it), userId) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun acknowledge(userId: String, id: String): Boolean {
        require(userId == authentication.userId)
        return try {
            val body = JSONObject().put("ids", org.json.JSONArray().put(id)).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(base.newBuilder().addPathSegment("messages")
                .addPathSegment(userId).addPathSegment("ack").build()).post(body).build()
            authenticated(request).use { it.code == 200 }
        } catch (_: Exception) { false }
    }

    fun statuses(userId: String): List<Pair<String, String>> {
        require(userId == authentication.userId)
        return try {
            val request = Request.Builder().url(base.newBuilder().addPathSegment("statuses")
                .addPathSegment(userId).build()).build()
            authenticated(request).use { response ->
                if (response.code != 200) return emptyList()
                val rows = JSONObject(response.body!!.string()).getJSONArray("statuses")
                (0 until rows.length()).map { rows.getJSONObject(it).let { row -> row.getString("id") to row.getString("state") } }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun encodeMessage(message: Message) = JSONObject().apply {
        put("id", message.id)
        put("sender", message.from)
        put("recipient", message.to)
        put("ciphertext", (byteArrayOf(message.type.ordinal.toByte()) + message.content).toHex())
    }
    private fun decodeMessage(payload: JSONObject, userId: String): Message? {
        val bytes = payload.getString("ciphertext").decodeHex()
        if (bytes.isEmpty()) return null
        val type = Message.MessageType.values().getOrElse(bytes[0].toInt()) { Message.MessageType.DATA }
        return Message(payload.getString("from"), userId, bytes.copyOfRange(1, bytes.size), type,
            payload.optString("id").ifEmpty { java.util.UUID.randomUUID().toString().replace("-", "") })
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Invalid ciphertext length" }
        return ByteArray(length / 2) { index ->
            val first = Character.digit(this[index * 2], 16)
            val second = Character.digit(this[index * 2 + 1], 16)
            require(first >= 0 && second >= 0) { "Invalid ciphertext hex" }
            ((first shl 4) + second).toByte()
        }
    }
}
