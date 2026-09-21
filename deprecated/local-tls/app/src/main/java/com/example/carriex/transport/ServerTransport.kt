package com.example.carriex.transport

import android.util.Log
import com.example.carriex.model.Message
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server-based transport layer.
 * Communicates with a remote server via WebSocket, with HTTP fallback.
 */
class ServerTransport(private val baseUrl: String, private val localCa: LocalCertificateAuthority? = null) {
    companion object {
        private const val TAG = "ServerTransport"
    }

    private val client = OkHttpClient.Builder().apply {
        followRedirects(false)
        followSslRedirects(false)
        localCa?.let { sslSocketFactory(it.socketFactory, it.trustManager) }
    }.build()

    private fun openConnection(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            if (this is javax.net.ssl.HttpsURLConnection) {
                localCa?.let { sslSocketFactory = it.socketFactory }
            }
        }
    }
    private class Connection {
        var socket: WebSocket? = null
        var connected = false
    }
    private val connections = mutableMapOf<String, Connection>()

    @Synchronized
    fun connectWebSocket(userId: String, onMessageReceived: (Message) -> Unit) {
        connections.remove(userId)?.socket?.cancel()
        val connection = Connection()
        connections[userId] = connection
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("ws").addPathSegment(userId).build()
        connection.socket = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(this@ServerTransport) {
                        if (connections[userId] !== connection) {
                            webSocket.cancel()
                            return
                        }
                        connection.connected = true
                    }
                    Log.d(TAG, "WebSocket connected: $userId")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(this@ServerTransport) {
                        if (connections[userId] !== connection) return
                    }
                    try {
                        val payload = JSONObject(text)
                        if (payload.optString("type") == "message") {
                            decodeMessage(payload.getJSONObject("message"), userId)
                                ?.let(onMessageReceived)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to process WebSocket message for $userId", e)
                    }
                }

                private fun removeConnection() {
                    synchronized(this@ServerTransport) {
                        if (connections[userId] === connection) connections.remove(userId)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    removeConnection()
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    removeConnection()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    removeConnection()
                    Log.w(TAG, "WebSocket failed: $userId", t)
                }
            })
    }

    /** Returns whether an open socket accepted the message, not a delivery receipt. */
    @Synchronized
    fun sendWebSocketMessage(message: Message): Boolean {
        val connection = connections[message.from] ?: return false
        if (!connection.connected) return false
        return connection.socket?.send(encodeMessage(message).put("type", "send").toString()) == true
    }

    @Synchronized
    fun disconnectWebSockets() {
        connections.values.forEach { it.socket?.cancel() }
        connections.clear()
    }

    private fun encodeMessage(message: Message): JSONObject = JSONObject().apply {
        put("sender", message.from)
        put("recipient", message.to)
        put("ciphertext", (byteArrayOf(message.type.ordinal.toByte()) + message.content).toHex())
    }

    private fun decodeMessage(payload: JSONObject, userId: String): Message? {
        val rawBytes = payload.getString("ciphertext").decodeHex()
        if (rawBytes.isEmpty()) return null
        val type = Message.MessageType.values().getOrElse(rawBytes[0].toInt()) {
            Message.MessageType.DATA
        }
        return Message(payload.getString("from"), userId, rawBytes.copyOfRange(1, rawBytes.size), type)
    }

    /**
     * Checks if the server is reachable and healthy.
     */
    @Volatile var connectionError: String? = null
        private set

    fun testServerActive(): Boolean {
        connectionError = null
        var connection: HttpURLConnection? = null
        return try {
            val conn = openConnection(URL("$baseUrl/health"))
            connection = conn
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            val healthy = conn.responseCode == 200 && conn.inputStream.bufferedReader().use {
                JSONObject(it.readText()).optString("status") == "alive"
            }
            if (!healthy) connectionError = "This address did not return a Carriex health response (HTTP ${conn.responseCode}). Check the server port."
            healthy
        } catch (e: javax.net.ssl.SSLException) {
            connectionError = "TLS connection failed. The server must support HTTPS on this port and present a trusted certificate matching the domain or IP. Plain HTTP port 5000 cannot accept HTTPS."
            false
        } catch (e: Exception) {
            connectionError = "Cannot reach $baseUrl. Check the address, port, network and server firewall."
            false
        } finally {
            connection?.disconnect()
        }
    }
    /**
     * Sends the message to the server via POST /send.
     */
    fun sendMessage(message: Message): Boolean {
        return try {
            val url = URL("$baseUrl/send")
            val conn = openConnection(url)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val payload = encodeMessage(message)

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            val success = conn.responseCode == 201
            conn.disconnect()
            success
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Retrieves all messages for a specific user via GET /messages/{userId}.
     */
    fun getAllMessages(userId: String): List<Message> {
        val messages = mutableListOf<Message>()
        try {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("messages").addPathSegment(userId).build().toUrl()
            val conn = openConnection(url)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                val jsonArray = jsonObject.getJSONArray("messages")

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    decodeMessage(item, userId)?.let(messages::add)
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            // Error handling could be added here
        }
        return messages
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Invalid ciphertext length" }
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            val firstDigit = Character.digit(this[i], 16)
            val secondDigit = Character.digit(this[i + 1], 16)
            require(firstDigit >= 0 && secondDigit >= 0) { "Invalid ciphertext hex" }
            result[i / 2] = ((firstDigit shl 4) + secondDigit).toByte()
        }
        return result
    }
}
