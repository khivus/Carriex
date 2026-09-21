package com.example.carriex.messaging

import com.example.carriex.crypto.CryptoEngine
import com.example.carriex.model.Message
import com.example.carriex.session.Session
import com.example.carriex.session.SessionManager
import com.example.carriex.session.SessionState
import com.example.carriex.transport.ServerTransport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrator of the messaging logic.
 * 
 * It coordinates between SessionManager, CryptoEngine and Transport.
 * It must NOT contain UI logic or direct Transport modification.
 */
class MessagingService(
    private val userId: String,
    private val cryptoEngine: CryptoEngine,
    private val transport: ServerTransport,
    private val sessionManager: SessionManager,
) {
    var onMessageDecrypted: ((Message) -> Unit)? = null
    private val pendingMessages = mutableMapOf<String, MutableList<String>>()

    private val outgoing = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    @Volatile private var closed = false
    var onError: ((String) -> Unit)? = null
    @Synchronized
    fun close() { closed = true; outgoing.shutdownNow(); onMessageDecrypted = null; onError = null }
    fun start() {
        transport.connectWebSocket(userId) { message -> receive(message) }
    }

    /**
     * Fetch messages explicitly when HTTP fallback is needed.
     */
    fun fetchNewMessages() {
        if (closed || !fetching.compareAndSet(false, true)) return
        kotlin.concurrent.thread {
            try {
                val incomingMessages = transport.getAllMessages(userId)
                incomingMessages.forEach { msg ->
                    receive(msg)
                }
            } catch (e: Exception) {
                onError?.invoke("Unable to receive messages: ${e.message}")
            } finally {
                fetching.set(false)
            }
        }
    }

    /**
     * Sends a plain text message. Initiates key exchange if session is missing.
     */
    @Synchronized
    fun send(to: String, text: String) {
        if (closed) return
        val session = getOrRestoreSession(to)
        
        if (session.state != SessionState.ESTABLISHED) {
            pendingMessages.getOrPut(to) { mutableListOf() }.add(text)
            if (session.state == SessionState.NONE) {
                initiateKeyExchange(to)
            }
            return
        }

        // Encrypt and send
        encryptAndSend(to, text, session)
    }

    private fun getOrRestoreSession(to: String): Session {
        val session = sessionManager.getSession(to)
        if (session.state == SessionState.NONE) {
            val restoredKeys = cryptoEngine.loadSessionKeys(userId, to)
            if (restoredKeys != null) {
                val restoredSession = Session(
                    state = SessionState.ESTABLISHED,
                    sessionKeys = restoredKeys
                )
                sessionManager.updateSession(to, restoredSession)
                return restoredSession
            }
        }
        return session
    }

    private fun encryptAndSend(to: String, text: String, session: Session) {
        val encryptedContent = cryptoEngine.encrypt(text.toByteArray(), session)
        val message = Message(
            from = userId, 
            to = to, 
            content = encryptedContent, 
            type = Message.MessageType.DATA
        )
        sendViaTransport(message)
    }

    private fun sendViaTransport(message: Message) {
        if (closed) return
        outgoing.execute {
            if (!closed && !transport.sendWebSocketMessage(message) && !transport.sendMessage(message)) {
                onError?.invoke("Could not send to ${message.to}. Check your connection and reconnect before trying again.")
            }
        }
    }
    /**
     * Internal handler for messages arriving from transport.
     */
    @Synchronized
    private fun receive(message: Message) {
        if (closed) return
        try { when (message.type) {
            Message.MessageType.KEY_EXCHANGE -> handleKeyExchange(message)
            Message.MessageType.DATA -> handleDataMessage(message)
        } } catch (e: Exception) {
            onError?.invoke("Unable to read a message from ${message.from}: ${e.message}")
        }
    }

    private fun initiateKeyExchange(to: String) {
        val keyPair = cryptoEngine.generateKeyPair()
        val newSession = Session(
            state = SessionState.PENDING,
            myKeyPair = keyPair
        )
        sessionManager.updateSession(to, newSession)

        val exchangeMessage = Message(
            from = userId,
            to = to,
            content = keyPair.publicKey,
            type = Message.MessageType.KEY_EXCHANGE
        )
        sendViaTransport(exchangeMessage)
    }

    private fun handleKeyExchange(message: Message) {
        val session = sessionManager.getSession(message.from)
        
        val establishedSession = if (session.state == SessionState.PENDING) {
            // We are the initiator (A), and we just received the responder's (B) key.
            val myKeyPair = session.myKeyPair ?: throw IllegalStateException("My keypair missing in pending session")
            cryptoEngine.establishSession(myKeyPair, message.content)
        } else {
            // We are the responder (B), and we just received the initiator's (A) key.
            val myKeyPair = cryptoEngine.generateKeyPair()
            val result = cryptoEngine.establishSession(myKeyPair, message.content)
            
            // 1. Update our session state FIRST so we are ready to receive data.
            sessionManager.updateSession(message.from, result)

            // 2. Send our public key back to the initiator.
            val responseExchange = Message(
                from = userId,
                to = message.from,
                content = myKeyPair.publicKey,
                type = Message.MessageType.KEY_EXCHANGE
            )
            sendViaTransport(responseExchange)
            
            result
        }

        // Finalize session state update
        sessionManager.updateSession(message.from, establishedSession)
        
        // Encrypt and store session keys at rest
        establishedSession.sessionKeys?.let { keys ->
            cryptoEngine.saveSessionKeys(userId, message.from, keys)
        }

        // Now that the session is fully established on both ends,
        // send any messages that were queued while waiting for the key exchange.
        pendingMessages.remove(message.from)?.forEach { text ->
            encryptAndSend(message.from, text, establishedSession)
        }
    }

    private fun handleDataMessage(message: Message) {
        val session = getOrRestoreSession(message.from)
        if (session.state == SessionState.ESTABLISHED) {
            val decryptedContent = cryptoEngine.decrypt(message.content, session)
            val decryptedMessage = message.copy(content = decryptedContent)
            onMessageDecrypted?.invoke(decryptedMessage)
        }
    }
}
