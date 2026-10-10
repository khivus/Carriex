package com.example.carriex.presentation.chat

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.example.carriex.auth.AndroidDeviceIdentity
import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.crypto.keystore.AndroidKeyStoreKeyManager
import com.example.carriex.crypto.libsodium.LibsodiumCryptoEngine
import com.example.carriex.messaging.MessagingService
import com.example.carriex.messaging.ChatStore
import com.example.carriex.model.Message
import com.example.carriex.session.DefaultSessionManager
import com.example.carriex.servers.PairedServer
import com.example.carriex.servers.ServerListStore
import com.example.carriex.transport.ServerTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.Executors

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val chatStore = ChatStore(application)
    var onStateChanged: (() -> Unit)? = null
    private val serverStore = ServerListStore(
        { preferences.getString("paired_servers_v1", null) },
        { preferences.edit().putString("paired_servers_v1", it).commit() },
    )
    var savedServers = emptyList<PairedServer>()
        private set
    var autoConnectUrl: String? = null
        private set
    var activeServer: PairedServer? = null
        private set
    var viewedServer: PairedServer? = null
        private set
    var serversVisible = true
        private set
    private val identity = runCatching { AndroidDeviceIdentity.load() }
    val userId: String = identity.getOrNull()?.userId.orEmpty()
    var connected = false
        private set
    val offlineView get() = viewedServer != null && !viewingConnectedServer
    val viewingConnectedServer get() = connected && viewedServer?.url == activeServer?.url
    var connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
        private set
    var connecting = false
        private set
    var error: String? = identity.exceptionOrNull()?.let { "Unable to access device authentication key: ${it.message}" }
        private set
    var selectedPeer: String? = null
        private set
    private class ServerChats {
        val conversations = linkedMapOf<String, MutableList<Message>>()
        val unread = mutableMapOf<String, Int>()
        val drafts = mutableMapOf<String, String>()
        var loaded = false
        var loading = false
    }
    private val chatsByServer = mutableMapOf<String, ServerChats>()
    private fun chatsFor(serverScope: String) = chatsByServer.getOrPut(serverScope) { ServerChats() }
    val conversations get() = chatsFor(scope).conversations
    val unread get() = chatsFor(scope).unread
    val drafts get() = chatsFor(scope).drafts
    private var transport: ServerTransport? = null
    private var pendingTransport: ServerTransport? = null
    private var service: MessagingService? = null
    @Volatile private var generation = 0
    private var viewGeneration = 0
    private var scope = ""

    private fun refreshServers() {
        try { savedServers = serverStore.list(); autoConnectUrl = serverStore.autoConnectUrl() }
        catch (e: Exception) { error = "Unable to read saved servers: ${e.message}" }
    }

    fun setAutoConnect(server: PairedServer, enabled: Boolean) {
        try {
            serverStore.setAutoConnect(if (enabled) server.url else autoConnectUrl?.takeUnless { it == server.url })
            refreshServers()
            error = null
        } catch (e: Exception) { error = e.message }
        changed()
    }

    fun statusFor(server: PairedServer): ServerTransport.ConnectionStatus =
        if (activeServer?.url == server.url) connectionStatus else ServerTransport.ConnectionStatus.DISCONNECTED

    fun unreadFor(server: PairedServer): Int = chatsByServer[server.fingerprint]?.unread?.values?.sum() ?: 0

    fun saveServer(server: PairedServer): Boolean {
        return try {
            serverStore.save(server)
            refreshServers()
            error = null
            changed()
            true
        } catch (e: Exception) { error = e.message; changed(); false }
    }

    fun forgetServer(server: PairedServer) {
        if (activeServer?.url == server.url) disconnect()
        if (viewedServer?.url == server.url) {
            viewGeneration++; viewedServer = null; selectedPeer = null; serversVisible = true
        }
        try { serverStore.remove(server.url); refreshServers(); error = null }
        catch (e: Exception) { error = e.message }
        changed()
    }

    fun showError(detail: String) { error = detail; changed() }
    fun viewSaved(server: PairedServer) {
        val attempt = ++viewGeneration
        viewedServer = server; scope = server.fingerprint; selectedPeer = null; serversVisible = false
        error = null; changed()
        val chats = chatsFor(server.fingerprint)
        if (chats.loaded || chats.loading) return
        chats.loading = true
        worker.execute {
            val history = runCatching { chatStore.history(server.fingerprint) }
            handler.post {
                chats.loading = false
                if (history.isFailure) {
                    if (attempt == viewGeneration) error = "Unable to unlock saved messages: ${history.exceptionOrNull()?.message}"
                } else loadHistory(server.fingerprint, history.getOrThrow())
                changed()
            }
        }
    }

    private fun loadHistory(serverScope: String, history: List<Message>) {
        val chats = chatsFor(serverScope)
        chats.conversations.clear()
        history.forEach { saved ->
            val peer = if (saved.from == userId) saved.to else saved.from
            chats.conversations.getOrPut(peer) { mutableListOf() }.add(saved)
        }
        preferences.getStringSet("contacts_$serverScope", emptySet())!!.filter(::validUserId).sorted().forEach {
            chats.conversations.getOrPut(it) { mutableListOf() }
        }
        chats.loaded = true
    }
    private val poll = object : Runnable {
        override fun run() {
            val current = service ?: return
            current.fetchNewMessages()
            handler.postDelayed(this, 5000)
        }
    }

    init {
        refreshServers()
        savedServers.firstOrNull { it.url == autoConnectUrl }?.let(::connect)
    }

    fun connect(server: PairedServer) {
        if (activeServer?.url == server.url) {
            if (connecting || connected) { viewSaved(server); return }
        }
        val device = identity.getOrElse { error = "Unable to access device authentication key: ${it.message}"; changed(); return }
        if (savedServers.none { it.url == server.url && it.fingerprint == server.fingerprint }) {
            error = "Save this server's pairing before connecting"; changed(); return
        }
        val endpoint = server.url
        val candidate = try {
            ServerTransport(endpoint, DeviceAuthentication(endpoint, device, server.client()), client = server.client())
        } catch (e: Exception) { error = e.message; changed(); return }
        // Cancel sockets, retries and any in-flight setup before starting another server.
        disconnect()
        activeServer = server
        pendingTransport = candidate
        connecting = true
        connectionStatus = ServerTransport.ConnectionStatus.CONNECTING
        error = null
        // Show cached history immediately; connection setup runs after the history read.
        viewSaved(server)
        val attempt = ++generation
        worker.execute {
            val healthy = candidate.testServerActive()
            handler.post {
                if (attempt != generation) { candidate.close(); return@post }
                pendingTransport = null
                connecting = false
                if (!healthy) {
                    connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
                    error = candidate.connectionError ?: "Cannot reach this Carriex server. Check the address, port and connection."
                    candidate.close()
                    changed(); return@post
                }
                if (!chatsFor(server.fingerprint).loaded) {
                    connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
                    error = "Unable to unlock saved messages"
                    candidate.close(); changed(); return@post
                }
                try {
                    val chatScope = server.fingerprint
                    val chats = chatsFor(chatScope)
                    val crypto = LibsodiumCryptoEngine(AndroidKeyStoreKeyManager(getApplication(), chatScope))
                    transport = candidate
                    candidate.onConnectionStatusChanged = { status -> handler.post {
                        if (attempt == generation && connectionStatus != status) { connectionStatus = status; changed() }
                    } }
                    val activeService = MessagingService(userId, crypto, candidate, DefaultSessionManager(), chatStore, chatScope)
                    service = activeService
                    activeService.onError = { detail -> handler.post { if (attempt == generation) {
                        error = contactErrorLabel(detail, chatScope); changed()
                    } } }
                    activeService.onMessageDecrypted = { message ->
                        val inserted = chatStore.addIncoming(chatScope, message)
                        if (inserted) handler.post {
                            if (attempt == generation) {
                                chats.conversations.getOrPut(message.from) { mutableListOf() }.add(message)
                                if (serversVisible || scope != chatScope || selectedPeer != message.from)
                                    chats.unread[message.from] = (chats.unread[message.from] ?: 0) + 1
                                saveContacts(chatScope); changed()
                            }
                        }
                        true
                    }
                    activeService.onStatus = { id, status -> handler.post {
                        if (attempt == generation) {
                            var updated = false
                            chats.conversations.values.forEach { list ->
                                val index = list.indexOfFirst { it.id == id && it.from == userId }
                                if (index >= 0 && (list[index].status == null || list[index].status!!.ordinal < status.ordinal)) {
                                    list[index] = list[index].copy(status = status)
                                    updated = true
                                }
                            }
                            if (updated) changed()
                        }
                    } }
                    connected = true
                    worker.execute {
                        if (attempt != generation) return@execute
                        try { activeService.start() }
                        catch (e: Exception) { handler.post { if (attempt == generation) {
                            activeService.close(); candidate.close()
                            handler.removeCallbacks(poll)
                            service = null; transport = null; connected = false
                            connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
                            error = "Unable to start chat: ${e.message}"; changed()
                        } } }
                    }
                    handler.post(poll)
                } catch (e: Exception) {
                    candidate.close()
                    service?.close()
                    service = null
                    connected = false
                    transport = null
                    connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
                    error = "Unable to start chat: ${e.message}"
                }
                changed()
            }
        }
    }

    fun openChat(peer: String): Boolean {
        val id = peer.trim()
        if (!validUserId(id) || id == userId) return false
        conversations.getOrPut(id) { mutableListOf() }
        selectedPeer = id
        unread.remove(id)
        saveContacts(); changed()
        return true
    }

    private fun contactNameKey(peer: String, serverScope: String = scope) = "contact_name_${serverScope}_$peer"
    fun contactName(peer: String, serverScope: String = scope): String? = preferences.getString(contactNameKey(peer, serverScope), null)
    fun contactLabel(peer: String, serverScope: String = scope): String {
        val shortId = peer.take(8)
        val name = contactName(peer, serverScope).orEmpty()
        return if (name.isBlank()) shortId else "$name ($shortId)"
    }

    private fun contactErrorLabel(detail: String, serverScope: String): String {
        val prefix = listOf("Could not send to ", "Unable to read a message from ")
            .firstOrNull(detail::startsWith) ?: return detail
        val peer = detail.drop(prefix.length).take(64)
        return if (validUserId(peer)) detail.replaceFirst(peer, contactLabel(peer, serverScope)) else detail
    }

    fun setContactName(peer: String, name: String): Boolean {
        if (viewedServer == null || !validUserId(peer) || peer == userId) return false
        val value = name.trim()
        if (value.length > 64) return false
        if (!preferences.edit().putString(contactNameKey(peer), value).commit()) {
            error = "Unable to save chat name"; changed(); return false
        }
        changed()
        return true
    }

    fun showChats() { selectedPeer = null; serversVisible = false; changed() }
    fun showServers() {
        viewGeneration++; selectedPeer = null; serversVisible = true; changed()
    }
    fun dismissError() { error = null; changed() }
    fun send(text: String) {
        if (!viewingConnectedServer) return
        val peer = selectedPeer ?: return
        if (text.isBlank()) return
        val activeService = service ?: return
        val activeScope = scope
        val chats = chatsFor(activeScope)
        val attempt = generation
        val message = Message(userId, peer, text.toByteArray(), Message.MessageType.DATA,
            status = Message.DeliveryStatus.SENDING, sentAt = System.currentTimeMillis())
        drafts.remove(peer); changed()
        worker.execute {
            try {
                chatStore.addOutgoing(activeScope, message)
            } catch (e: Exception) {
                handler.post { if (attempt == generation) {
                    error = "Message could not be saved: ${e.message}"; chats.drafts[peer] = text; changed()
                } }
                return@execute
            }
            handler.post {
                if (attempt == generation) {
                    chats.conversations.getOrPut(peer) { mutableListOf() }.add(message)
                    changed()
                }
            }
            try { if (attempt == generation) activeService.send(message) }
            catch (e: Exception) { handler.post { if (attempt == generation) {
                error = "Message saved locally; send will retry: ${e.message}"; changed()
            } } }
        }
    }

    fun disconnect() {
        generation++
        viewGeneration++
        activeServer?.let { chatsByServer[it.fingerprint]?.loaded = false }
        handler.removeCallbacks(poll)
        service?.close()
        service = null
        transport?.close()
        transport = null
        pendingTransport?.close()
        pendingTransport = null
        connected = false
        activeServer = null
        viewedServer = null
        serversVisible = true
        connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
        connecting = false
        selectedPeer = null
        error = null
        changed()
    }

    private fun saveContacts(serverScope: String = scope) {
        preferences.edit().putStringSet("contacts_$serverScope", chatsFor(serverScope).conversations.keys.toSet()).apply()
    }
    private fun changed() { onStateChanged?.invoke() }
    override fun onCleared() { onStateChanged = null; disconnect(); worker.shutdown() }

    companion object {
        fun validUserId(value: String) = value.matches(Regex("[0-9a-f]{64}"))
        fun serverUrl(host: String, port: String, tls: Boolean): String {
            val address = host.trim().removePrefix("[").removeSuffix("]")
            require(address.isNotEmpty() && !address.contains(Regex("[\\s/@?#]")) && !address.contains("://")) { "Enter a domain or IP address without a URL path." }
            val number = port.trim().toIntOrNull()
            require(number != null && number in 1..65535) { "Port must be between 1 and 65535." }
            val authority = if (address.contains(':')) "[$address]" else address
            return "${if (tls) "https" else "http"}://$authority:$number".toHttpUrl().toString().trimEnd('/')
        }
    }
}
