package com.example.carriex.transport

import com.example.carriex.model.Message
import android.util.Log
import java.util.Objects.toString

/**
 * In-memory transport layer.
 * 
 * It simulates delivery between two peers in the same process.
 * It must NOT modify, decrypt, or inspect the message content.
 */
class InMemoryTransport {
    companion object {
        private const val TAG = "InMemoryTransport"
    }

    private val listeners = mutableMapOf<String, (Message) -> Unit>()

    /**
     * Registers a receiver for a specific user ID.
     */
    fun register(userId: String, onMessageReceived: (Message) -> Unit) {
        listeners[userId] = onMessageReceived
    }

    /**
     * "Sends" the message by finding the registered listener for the 'to' address.
     */
    fun send(message: Message) {
        Log.d(TAG, "Message: ${message.content.toHex()} was sent from ${message.from} to ${message.to}")
        listeners[message.to]?.invoke(message)
    }

    fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
