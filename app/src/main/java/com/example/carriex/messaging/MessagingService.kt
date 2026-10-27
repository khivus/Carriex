package com.example.carriex.messaging

import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.x3dh.X3dhProtocol
import com.example.carriex.model.Message
import com.example.carriex.transport.ServerTransport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All protocol work is serialized off the UI thread. Acks follow durable commits. */
class MessagingService(
    private val userId: String,
    device: DeviceIdentity,
    private val transport: ServerTransport,
    private val chatStore: ChatStore,
    private val scope: String,
    private val contacts: () -> Set<String> = { emptySet() },
    private val blocked: () -> Set<String> = { emptySet() },
    private val allowFallback: () -> Boolean = { false },
    private val protectUnknown: Boolean = true,
    private val autoAccept: () -> Boolean = { false },
    private val sendReadStatus: () -> Boolean = { false },
    private val applyContactPolicy: (String, ContactAction) -> Unit = { _, _ -> },
) {
    var onMessageDecrypted: ((Message) -> Boolean)? = null
    var onStatus: ((String, Message.DeliveryStatus) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onRequestsChanged: (() -> Unit)? = null
    var onNewMessageRequest: (() -> Unit)? = null
    var onContactActionCompleted: ((String, ContactAction, Message) -> Unit)? = null
    private val protocol = X3dhProtocol(device, chatStore.x3dh(scope), allowWithoutOneTimeKey = allowFallback)
    private val outgoing = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var ready = false
    private var refreshAt = 0L

    @Synchronized fun close() {
        closed = true; outgoing.shutdownNow()
        onMessageDecrypted = null; onStatus = null; onError = null; onRequestsChanged = null; onNewMessageRequest = null; onContactActionCompleted = null
    }
    @Synchronized private fun enqueue(task: () -> Unit) {
        if (!closed) outgoing.execute { if (!closed) task() }
    }
    /** Called on the connection worker before the socket is opened. */
    fun start() {
        refreshPrekeys()
        if(protectUnknown) transport.setInboxPolicy(contacts(),blocked())
        if (closed) return
        ready = true
        transport.onReceipt = { id, state -> enqueue {
            if (state == "received" || state == "read") updateStatus(id,
                if(state == "read") Message.DeliveryStatus.READ else Message.DeliveryStatus.RECEIVED)
        } }
        transport.connectWebSocket(userId) { message -> enqueue { receive(message) } }
        enqueue { retryPending() }
    }
    private fun refreshPrekeys() {
        val status = transport.prekeyStatus()
        val bundle = chatStore.atomic {
            if(!status.isNull("receipt")) protocol.published(status.getJSONObject("receipt"))
            protocol.publication(status.getInt("available"), status.getLong("message_ttl_seconds"),status.optLong("revision",0))
        }
        val receipt=transport.publishPrekeys(bundle)
        chatStore.atomic { protocol.published(receipt) }
        protocol.prekeyWarning()?.let { onError?.invoke(it) }
        refreshAt = System.currentTimeMillis() + 3_600_000
    }
    fun fetchNewMessages() {
        if (!ready || closed || !fetching.compareAndSet(false, true)) return
        enqueue {
            try {
                if (System.currentTimeMillis() >= refreshAt) refreshPrekeys()
                transport.getAllMessages(userId).forEach(::receive)
                transport.statuses(userId).forEach { (id, state) ->
                    updateStatus(id, when(state) {
                        "read" -> Message.DeliveryStatus.READ
                        "received" -> Message.DeliveryStatus.RECEIVED
                        else -> Message.DeliveryStatus.SENT
                    })
                }
                flushReadStatus()
                retryPending()
            } catch (e: Exception) { onError?.invoke("Unable to receive messages: ${e.message}") }
            finally { fetching.set(false) }
        }
    }
    fun send(plain: Message) { enqueue { if (ready) sendPlain(plain) } }
    fun restartSession(peer: String) { enqueue {
        queueContactAction(peer, ContactAction(restart = true))
    } }
    fun contactAction(peer: String, action: ContactAction) { enqueue { queueContactAction(peer, action) } }

    private fun queueContactAction(peer: String, action: ContactAction) {
        try {
            check(ready) { "Wait for the server connection to finish" }
            chatStore.queueContactAction(scope, userId, peer, action)
            processContactActions()
        } catch(e: Exception) { onError?.invoke("Contact action is waiting: ${e.message}") }
    }

    /** Exact packets and action markers survive response loss or process death. */
    private fun processContactActions() {
        chatStore.contactActions(scope).forEach { plain ->
            if(closed) return
            try {
                check(plain.from == userId)
                val action = ContactAction.parse(plain.content)
                val wire = chatStore.savedWire(scope, plain) ?: chatStore.atomic {
                    protocol.encrypt(plain) { transport.claimPrekeys(plain.to) }.also { chatStore.setWire(scope, it) }
                }
                val result = transport.sendMessageResult(wire)
                check(result.accepted || !result.retryable) { result.detail ?: "Connection unavailable" }
                val delivery = if(result.accepted) Message.DeliveryStatus.SENT else Message.DeliveryStatus.FAILED
                chatStore.atomic {
                    // Preference commit happens before erasure so a completed block survives process death.
                    applyContactPolicy(plain.to, action)
                    if(action.deleteHistory) chatStore.removeChat(scope, plain.to)
                    else chatStore.mark(scope, plain.id, delivery)
                    if(action.removeKeys) protocol.forgetPeer(plain.to)
                    else if(action.restart) protocol.restartSending(plain.to)
                    chatStore.x3dh(scope).remove("contact-action/${plain.to}")
                }
                onContactActionCompleted?.invoke(plain.to, action, plain.copy(status = delivery))
                if(!result.accepted) onError?.invoke("Contact action completed locally, but the server rejected its notice: ${result.detail}")
                if(protectUnknown && (action.block || action.deleteHistory)) {
                    try { transport.setInboxPolicy(contacts(), blocked()) }
                    catch(e: Exception) { onError?.invoke("Unable to update inbox policy: ${e.message}") }
                }
            } catch(e: Exception) { if(!closed) onError?.invoke("Contact action is waiting: ${e.message}") }
        }
    }
    fun refreshPolicy() { enqueue {
        try { if(protectUnknown) transport.setInboxPolicy(contacts(),blocked()) }
        catch(e:Exception) { onError?.invoke(e.message ?: "Unable to update inbox policy") }
    } }
    fun acceptRequests(peer:String) { enqueue {
        chatStore.requestMessages(scope,peer).forEach { message ->
            receive(message,true)
        }
        onRequestsChanged?.invoke()
    } }
    fun messagesViewed(ids: Collection<String>) { enqueue {
        if(!sendReadStatus()) return@enqueue
        ids.forEach { chatStore.queueReadStatus(scope, it, userId) }
        flushReadStatus()
    } }
    private fun flushReadStatus() {
        if(!sendReadStatus() || closed) return
        val ids = chatStore.pendingReadStatus(scope)
        // A previous delivery acknowledgement may have failed in transit. Confirm it
        // before reporting viewing so a successful read POST cannot retire the retry.
        if(ids.isNotEmpty() && transport.acknowledge(userId, ids) && sendReadStatus() &&
            transport.reportRead(userId, ids)) chatStore.readStatusSent(scope, ids)
    }
    private fun retryPending() {
        processContactActions()
        val actions = chatStore.contactActions(scope).map { it.id }.toSet()
        chatStore.pending(scope).forEach { (plain, wire) ->
            if (!closed && plain.id !in actions) {
                if(wire!=null && !chatStore.atomic { protocol.wireUsable(wire) }) {
                    updateStatus(plain.id,Message.DeliveryStatus.EXPIRED)
                    onError?.invoke("A saved message's encryption session expired. Copy its text and send it again.")
                } else if (wire != null) sendWire(wire) else sendPlain(plain)
            }
        }
    }
    private fun sendPlain(plain: Message) {
        try {
            chatStore.savedWire(scope, plain)?.let { sendWire(it); return }
            val wire = chatStore.atomic {
                protocol.encrypt(plain) { transport.claimPrekeys(plain.to) }.also { chatStore.setWire(scope, it) }
            }
            if (!closed) sendWire(wire)
        } catch (e: MessageSizeException) {
            updateStatus(plain.id, Message.DeliveryStatus.FAILED)
            onError?.invoke(e.message ?: "Message is too long")
        } catch (e: Exception) { onError?.invoke("Could not send to ${plain.to}: ${e.message}") }
    }
    private fun sendWire(message: Message) {
        val result = transport.sendMessageResult(message)
        if (result.accepted) updateStatus(message.id, Message.DeliveryStatus.SENT)
        else if (!closed) {
            if (!result.retryable) updateStatus(message.id, Message.DeliveryStatus.FAILED)
            val action = if (result.retryable) "Will retry while connected." else "Send failed; review the error before sending again."
            onError?.invoke("Could not send to ${message.to}: ${result.detail ?: "Connection unavailable"}. $action")
        }
    }
    private fun receive(message: Message, retryRequest:Boolean=false) {
        if(chatStore.wasRemoved(scope,message.id)) { transport.acknowledge(userId,message.id,true); return }
        if(message.from in blocked()) { transport.acknowledge(userId,message.id,true); return }
        if(!retryRequest && chatStore.hasQuarantined(scope,message.id)) { transport.acknowledge(userId,message.id,true); return }
        if(protectUnknown && message.from !in contacts() && !autoAccept()) {
            val (saved, firstRequest) = chatStore.atomic {
                val first = chatStore.requests(scope).none { it.peer == message.from }
                val saved = chatStore.quarantine(scope,message,"Message request from an unknown contact")
                saved to (saved && first)
            }
            if (!saved) onError?.invoke("Message request storage is full. Extra requests were discarded.")
            if (firstRequest) onNewMessageRequest?.invoke()
            transport.acknowledge(userId,message.id,true); onRequestsChanged?.invoke(); return
        }
        try {
            if (message.type != Message.MessageType.DATA) error("Legacy key exchange rejected; contact must update Carriex")
            val decoded = chatStore.atomic {
                if (chatStore.hasIncoming(scope, message.id)) null
                else protocol.decrypt(message).also {
                    if(it.type == Message.MessageType.CHAT_EVENT) {
                        val action = ContactAction.parse(it.content)
                        if(action.removeKeys || action.restart) protocol.restartSending(message.from)
                    }
                    check(chatStore.addIncoming(scope, it)); chatStore.removeRequest(scope,message.id)
                }
            }
            // UI notification comes AFTER commit. Redelivery only repeats the acknowledgement.
            if (decoded != null) onMessageDecrypted?.invoke(decoded)
            if (!closed) transport.acknowledge(userId, message.id)
        } catch (e: Exception) {
            chatStore.atomic { chatStore.quarantine(scope,message,e.message ?: "Invalid encrypted message") }
            transport.acknowledge(userId,message.id,true)
            onRequestsChanged?.invoke()
            onError?.invoke("Unable to read a message from ${message.from}: ${e.message}. Review Message requests.")
        }
    }
    private fun updateStatus(id: String, status: Message.DeliveryStatus) {
        chatStore.mark(scope, id, status); onStatus?.invoke(id, status)
    }
}
