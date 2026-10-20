package com.example.carriex.transport

import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.auth.ServerAuthentication
import com.example.carriex.model.Message
import com.example.carriex.messaging.MessageLimits
import com.example.carriex.messaging.MessageSizeException
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
    @Volatile var onGroupsChanged: (() -> Unit)? = null
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
                        } else if (payload.optString("type") == "groups") {
                            onGroupsChanged?.invoke()
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
        MessageLimits.requireWire(message.content)
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

    private fun prekeys(path: List<String>, body: JSONObject? = null): JSONObject {
        val url = base.newBuilder().addPathSegment("prekeys")
        path.forEach(url::addPathSegment)
        val request = Request.Builder().url(url.build())
        body?.let { request.post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return authenticated(request.build()).use { response ->
            val text = response.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) {
                throw IOException("Server must support X3DH (HTTP ${response.code}); update the relay")
            }
            if (response.code != 200) throw IOException(json.optString("error", "Server must support X3DH (HTTP ${response.code})"))
            json
        }
    }
    fun prekeyStatus(): JSONObject = prekeys(listOf("status"))
    fun publishPrekeys(bundle: JSONObject): JSONObject = prekeys(emptyList(), bundle)
    fun claimPrekeys(peer: String): JSONObject = prekeys(listOf(peer, "claim"), JSONObject())

    class GroupApiException(val code: Int, message: String) : IOException(message)
    fun groups(path: List<String> = emptyList(), body: JSONObject? = null, after: Int? = null, epoch: Int? = null): JSONObject {
        val url = base.newBuilder().addPathSegment("groups")
        path.forEach(url::addPathSegment)
        after?.let { url.addQueryParameter("after", it.toString()) }
        epoch?.let { url.addQueryParameter("epoch", it.toString()) }
        val request = Request.Builder().url(url.build())
        body?.let { request.post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return authenticated(request.build()).use { response ->
            if(response.body?.source()?.request(2_000_001) == true) throw IOException("Oversized group response")
            val json = try { JSONObject(response.body?.string().orEmpty()) } catch (_: Exception) {
                throw GroupApiException(response.code, "Invalid group response for ${path.lastOrNull() ?: "list"} (HTTP ${response.code})")
            }
            if(!response.isSuccessful) throw GroupApiException(response.code, json.optString("error", "Group request failed"))
            json
        }
    }

    data class SendResult(val accepted: Boolean, val retryable: Boolean = true, val detail: String? = null)

    fun sendMessage(message: Message): Boolean = sendMessageResult(message).accepted

    fun sendMessageResult(message: Message): SendResult {
        require(message.from == authentication.userId) { "Sender identity mismatch" }
        return try {
            MessageLimits.requireWire(message.content)
            val request = Request.Builder().url(base.newBuilder().addPathSegment("send").build())
                .post(encodeMessage(message).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            authenticated(request).use { response ->
                if (response.code == 201) SendResult(true)
                else {
                    val text = response.body?.string().orEmpty()
                    val detail = try { JSONObject(text).optString("error", "HTTP ${response.code}").take(256) }
                        catch (_: Exception) { "HTTP ${response.code}" }
                    // Older relays returned 429 for an oversized packet too.
                    val permanentSize = detail.contains("exceeds 256 KiB", ignoreCase = true)
                    SendResult(false, !permanentSize && (response.code == 401 || response.code == 429 || response.code >= 500), detail)
                }
            }
        } catch (e: MessageSizeException) { SendResult(false, false, e.message) }
        catch (e: Exception) { SendResult(false, true, e.message) }
    }

    fun getAllMessages(userId: String): List<Message> {
        require(userId == authentication.userId) { "Device identity mismatch" }
        return try {
            val result = mutableListOf<Message>()
            var cursor = 0L
            repeat(4) {
                val request = Request.Builder().url(base.newBuilder().addPathSegment("messages").addPathSegment(userId)
                    .addQueryParameter("after",cursor.toString()).build()).build()
                val next = authenticated(request).use { response ->
                    if (response.code != 200) return result
                    val messages = JSONObject(response.body!!.string()).getJSONArray("messages")
                    var pageCursor = cursor
                    for (index in 0 until messages.length()) {
                        val payload = messages.getJSONObject(index)
                        pageCursor = maxOf(pageCursor,payload.optLong("cursor",0))
                        try { decodeMessage(payload,userId)?.let(result::add) } catch (_: Exception) {
                            // Preserve a routable malformed packet for local quarantine.
                            if (payload.optString("id").matches(Regex("[0-9a-f]{32}")) && payload.optString("from").matches(Regex("[0-9a-f]{64}")))
                                result.add(Message(payload.getString("from"),userId,ByteArray(0),Message.MessageType.DATA,payload.getString("id")))
                        }
                    }
                    if (messages.length()<500) 0 else pageCursor
                }
                if (next <= cursor) return result
                cursor=next
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    fun setInboxPolicy(contacts:Set<String>,blocked:Set<String>) {
        val data=JSONObject().put("contacts",org.json.JSONArray(contacts.toList())).put("blocked",org.json.JSONArray(blocked.toList()))
        val request=Request.Builder().url(base.newBuilder().addPathSegment("messages").addPathSegment(authentication.userId).addPathSegment("policy").build())
            .post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        authenticated(request).use { if(it.code!=200) throw IOException("Unable to synchronize inbox protection (HTTP ${it.code})") }
    }

    fun acknowledge(userId: String, id: String, quarantine: Boolean = false): Boolean {
        require(userId == authentication.userId)
        return try {
            val body = JSONObject().put("ids", org.json.JSONArray().put(id)).put("quarantine",quarantine).toString()
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
        require(bytes.isNotEmpty()) { "Empty encrypted frame" }
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
