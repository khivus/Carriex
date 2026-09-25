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
    private val chatStore: ChatStore,
    private val scope: String,
) {
    var onMessageDecrypted: ((Message) -> Boolean)? = null
    var onStatus: ((String, Message.DeliveryStatus) -> Unit)? = null
    private val pendingMessages = mutableMapOf<String, MutableList<Message>>()

    private val outgoing = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    @Volatile private var closed = false
    var onError: ((String) -> Unit)? = null
    @Synchronized
    fun close() { closed = true; outgoing.shutdownNow(); onMessageDecrypted = null; onStatus = null; onError = null }
    fun start() {
        transport.onReceipt = { id, state ->
            if (state == "received") updateStatus(id, Message.DeliveryStatus.RECEIVED)
        }
        transport.connectWebSocket(userId) { message -> receive(message) }
        chatStore.exchangeOutbox(scope, userId).forEach(::sendViaTransport)
        chatStore.pending(scope).forEach { (plain, wire) ->
            if (wire != null) sendViaTransport(wire) else send(plain)
        }
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
                transport.statuses(userId).forEach { (id, state) ->
                    updateStatus(id, if (state == "received") Message.DeliveryStatus.RECEIVED else Message.DeliveryStatus.SENT)
                }
                chatStore.pending(scope).forEach { (plain, wire) ->
                    if (wire != null) sendViaTransport(wire) else send(plain)
                }
                chatStore.exchangeOutbox(scope, userId).forEach(::sendViaTransport)
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
    fun send(plain: Message) {
        if (closed) return
        val to = plain.to
        val session = getOrRestoreSession(to)
        
        if (session.state != SessionState.ESTABLISHED) {
            val pending = pendingMessages.getOrPut(to) { mutableListOf() }
            if (pending.none { it.id == plain.id }) pending.add(plain)
            if (session.state == SessionState.NONE) {
                initiateKeyExchange(to)
            }
            return
        }

        // Encrypt and send
        encryptAndSend(plain, session)
    }

    private fun getOrRestoreSession(to: String): Session {
        val session = sessionManager.getSession(to)
        if (session.state == SessionState.NONE) {
            chatStore.pendingSession(scope, to)?.let { (id, pair) ->
                val restored = Session(state = SessionState.PENDING, myKeyPair = pair)
                sessionManager.updateSession(to, restored)
                return restored
            }
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

    private fun encryptAndSend(plain: Message, session: Session) {
        val encryptedContent = cryptoEngine.encrypt(plain.content, session)
        val message = Message(
            from = userId, 
            to = plain.to,
            content = encryptedContent, 
            type = Message.MessageType.DATA,
            id = plain.id,
        )
        chatStore.setWire(scope, message)
        sendViaTransport(message)
    }

    private fun sendViaTransport(message: Message) {
        if (closed) return
        if (message.type == Message.MessageType.KEY_EXCHANGE) chatStore.saveExchangeOutbox(scope, message)
        outgoing.execute {
            if (!closed && transport.sendMessage(message)) {
                if (message.type == Message.MessageType.DATA) updateStatus(message.id, Message.DeliveryStatus.SENT)
                else chatStore.clearExchangeOutbox(scope, message.id)
            } else if (!closed) {
                onError?.invoke("Could not send to ${message.to}. Will retry while connected.")
            }
        }
    }
    /**
     * Internal handler for messages arriving from transport.
     */
    @Synchronized
    private fun receive(message: Message) {
        if (closed) return
        try {
            val handled = when (message.type) {
                Message.MessageType.KEY_EXCHANGE -> {
                    if (!chatStore.hasExchange(scope, message.id)) {
                        handleKeyExchange(message)
                        chatStore.markExchange(scope, message.id)
                    }
                    true
                }
                Message.MessageType.DATA -> handleDataMessage(message)
            }
            if (handled) outgoing.execute { if (!closed) transport.acknowledge(userId, message.id) }
        } catch (e: Exception) {
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
        chatStore.savePendingSession(scope, to, exchangeMessage.id, keyPair)
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
        chatStore.clearPendingSession(scope, message.from)

        // Now that the session is fully established on both ends,
        // send any messages that were queued while waiting for the key exchange.
        pendingMessages.remove(message.from)?.forEach { plain ->
            encryptAndSend(plain, establishedSession)
        }
    }

    private fun handleDataMessage(message: Message): Boolean {
        if (chatStore.hasIncoming(scope, message.id)) return true
        val session = getOrRestoreSession(message.from)
        if (session.state == SessionState.ESTABLISHED) {
            val decryptedContent = cryptoEngine.decrypt(message.content, session)
            val decryptedMessage = message.copy(content = decryptedContent)
            return onMessageDecrypted?.invoke(decryptedMessage) == true
        }
        return false
    }

    private fun updateStatus(id: String, status: Message.DeliveryStatus) {
        chatStore.mark(scope, id, status)
        onStatus?.invoke(id, status)
    }
}
