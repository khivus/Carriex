package com.example.carriex.transport

import com.example.carriex.model.Message
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server-based transport layer.
 * Communicates with a remote server via HTTP.
 */
class ServerTransport {
    companion object {
        // Hardcoded IP/Port for the server (10.0.2.2 is the host machine from Android Emulator)
        private const val BASE_URL = "http://10.0.2.2:5000"
    }

    /**
     * Checks if the server is reachable and healthy.
     */
    fun testServerActive(): Boolean {
        return try {
            val url = URL("$BASE_URL/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            
            val isAlive = if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).getString("status") == "alive"
            } else false
            conn.disconnect()
            isAlive
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sends the message to the server via POST /send.
     */
    fun sendMessage(message: Message): Boolean {
        return try {
            val url = URL("$BASE_URL/send")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            // To preserve the MessageType without server modification, 
            // we prepend it to the ciphertext as the first byte.
            val typePrefix = message.type.ordinal.toByte()
            val fullPayload = ByteArray(message.content.size + 1)
            fullPayload[0] = typePrefix
            System.arraycopy(message.content, 0, fullPayload, 1, message.content.size)

            val payload = JSONObject().apply {
                put("sender", message.from)
                put("recipient", message.to)
                put("ciphertext", fullPayload.toHex())
            }

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
            val url = URL("$BASE_URL/messages/$userId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                val jsonArray = jsonObject.getJSONArray("messages")

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val from = item.getString("from")
                    val rawHex = item.getString("ciphertext")
                    val rawBytes = rawHex.decodeHex()

                    if (rawBytes.isNotEmpty()) {
                        val typeOrdinal = rawBytes[0].toInt()
                        val type = Message.MessageType.values().getOrElse(typeOrdinal) { 
                            Message.MessageType.DATA 
                        }
                        val content = rawBytes.copyOfRange(1, rawBytes.size)
                        messages.add(Message(
                            from = from,
                            to = userId,
                            content = content,
                            type = type
                        ))
                    }
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
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            val firstDigit = Character.digit(this[i], 16)
            val secondDigit = Character.digit(this[i + 1], 16)
            result[i / 2] = ((firstDigit shl 4) + secondDigit).toByte()
        }
        return result
    }
}
