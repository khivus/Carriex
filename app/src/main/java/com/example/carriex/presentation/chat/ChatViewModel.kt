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
    var onStateChanged: (() -> Unit)? = null
    private val serverStore = ServerListStore(
        { preferences.getString("paired_servers_v1", null) },
        { preferences.edit().putString("paired_servers_v1", it).commit() },
    )
    var savedServers = emptyList<PairedServer>()
        private set
    var activeServer: PairedServer? = null
        private set
    private val identity = runCatching { AndroidDeviceIdentity.load() }
    val userId: String = identity.getOrNull()?.userId.orEmpty()
    var connected = false
        private set
    var connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
        private set
    var connecting = false
        private set
    var error: String? = identity.exceptionOrNull()?.let { "Unable to access device authentication key: ${it.message}" }
        private set
    var selectedPeer: String? = null
        private set
    val conversations = linkedMapOf<String, MutableList<Message>>()
    val unread = mutableMapOf<String, Int>()
    val drafts = mutableMapOf<String, String>()
    private var transport: ServerTransport? = null
    private var service: MessagingService? = null
    private var generation = 0
    private var scope = ""
    init { refreshServers() }

    private fun refreshServers() {
        try { savedServers = serverStore.list() }
        catch (e: Exception) { error = "Unable to read saved servers: ${e.message}" }
    }

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
        try { serverStore.remove(server.url); refreshServers(); error = null }
        catch (e: Exception) { error = e.message }
        changed()
    }

    fun showError(detail: String) { error = detail; changed() }
    private val poll = object : Runnable {
        override fun run() {
            service?.fetchNewMessages()
            handler.postDelayed(this, 5000)
        }
    }

    fun connect(server: PairedServer) {
        if (connecting || connected) return
        val device = identity.getOrElse { error = "Unable to access device authentication key: ${it.message}"; changed(); return }
        if (savedServers.none { it.url == server.url && it.fingerprint == server.fingerprint }) {
            error = "Save this server's pairing before connecting"; changed(); return
        }
        val endpoint = server.url
        val candidate = try {
            ServerTransport(endpoint, DeviceAuthentication(endpoint, device, server.client()), client = server.client())
        } catch (e: Exception) { error = e.message; changed(); return }
        activeServer = server
        connecting = true
        error = null
        changed()
        val attempt = ++generation
        worker.execute {
            val healthy = candidate.testServerActive()
            handler.post {
                if (attempt != generation) { candidate.close(); return@post }
                connecting = false
                if (!healthy) {
                    error = candidate.connectionError ?: "Cannot reach this Carriex server. Check the address, port and connection."
                    candidate.close()
                    changed(); return@post
                }
                try {
                    scope = server.fingerprint
                    val crypto = LibsodiumCryptoEngine(AndroidKeyStoreKeyManager(getApplication(), scope))
                    transport = candidate
                    candidate.onConnectionStatusChanged = { status -> handler.post {
                        if (attempt == generation) { connectionStatus = status; changed() }
                    } }
                    val activeService = MessagingService(userId, crypto, candidate, DefaultSessionManager())
                    service = activeService
                    activeService.onError = { detail -> handler.post { if (attempt == generation) { error = detail; changed() } } }
                    activeService.onMessageDecrypted = { message -> handler.post {
                        if (attempt == generation) {
                            conversations.getOrPut(message.from) { mutableListOf() }.add(message)
                            if (selectedPeer != message.from) unread[message.from] = (unread[message.from] ?: 0) + 1
                            saveContacts(); changed()
                        }
                    } }
                    preferences.getStringSet("contacts_$scope", emptySet())!!.filter(::validUserId).sorted().forEach {
                        conversations.getOrPut(it) { mutableListOf() }
                    }
                    connected = true
                    activeService.start()
                    handler.post(poll)
                } catch (e: Exception) {
                    candidate.close()
                    service?.close()
                    service = null
                    connected = false
                    transport = null
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

    fun showChats() { selectedPeer = null; changed() }
    fun dismissError() { error = null; changed() }
    fun send(text: String) {
        val peer = selectedPeer ?: return
        if (text.isBlank()) return
        try {
            service?.send(peer, text) ?: return
            conversations.getValue(peer).add(Message(userId, peer, text.toByteArray(), Message.MessageType.DATA))
            drafts.remove(peer)
            changed()
        } catch (e: Exception) { error = "Message could not be sent: ${e.message}"; changed() }
    }

    fun disconnect() {
        generation++
        handler.removeCallbacks(poll)
        service?.close()
        service = null
        transport?.close()
        transport = null
        connected = false
        activeServer = null
        connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
        connecting = false
        selectedPeer = null
        conversations.clear(); unread.clear(); drafts.clear()
        error = null
        changed()
    }

    private fun saveContacts() { preferences.edit().putStringSet("contacts_$scope", conversations.keys.toSet()).apply() }
    private fun changed() { onStateChanged?.invoke() }
    override fun onCleared() { onStateChanged = null; disconnect(); worker.shutdownNow() }

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
