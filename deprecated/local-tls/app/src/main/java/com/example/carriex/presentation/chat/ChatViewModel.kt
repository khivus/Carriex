package com.example.carriex.presentation.chat

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.example.carriex.crypto.keystore.AndroidKeyStoreKeyManager
import com.example.carriex.crypto.libsodium.LibsodiumCryptoEngine
import com.example.carriex.messaging.MessagingService
import com.example.carriex.model.Message
import com.example.carriex.session.DefaultSessionManager
import com.example.carriex.transport.LocalCertificateAuthority
import com.example.carriex.transport.ServerTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    var onStateChanged: (() -> Unit)? = null
    var host: String = preferences.getString("host", "")!!
    var port: String = preferences.getString("port", "443")!!
    var useTls: Boolean = preferences.getBoolean("tls", true)
    val userId: String = preferences.getString("user", null) ?: ("user-" + UUID.randomUUID().toString().take(12)).also {
        preferences.edit().putString("user", it).apply()
    }
    var connected = false
        private set
    var connecting = false
        private set
    var error: String? = null
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
    private val poll = object : Runnable {
        override fun run() {
            service?.fetchNewMessages()
            handler.postDelayed(this, 5000)
        }
    }

    fun trustedCa(): LocalCertificateAuthority? {
        val endpoint = serverUrl(host, port, true)
        return preferences.getString("ca_$endpoint", null)?.let {
            LocalCertificateAuthority(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
        }
    }

    fun trustCa(bytes: ByteArray) {
        val ca = LocalCertificateAuthority(bytes)
        val endpoint = serverUrl(host, port, true)
        preferences.edit().putString("ca_$endpoint", android.util.Base64.encodeToString(ca.certificate.encoded, android.util.Base64.NO_WRAP)).apply()
        changed()
    }

    fun removeCa() {
        preferences.edit().remove("ca_${serverUrl(host, port, true)}").apply()
        changed()
    }

    fun connect() {
        if (connecting) return
        val endpoint = try { serverUrl(host, port, useTls) } catch (e: IllegalArgumentException) {
            error = e.message; changed(); return
        }
        val candidate = try { ServerTransport(endpoint, if (useTls) trustedCa() else null) } catch (e: Exception) {
            error = "Cannot use the saved CA certificate: ${e.message}. Remove it and import a valid root CA."
            changed(); return
        }
        connecting = true
        error = null
        changed()
        val attempt = ++generation
        worker.execute {
            val healthy = candidate.testServerActive()
            handler.post {
                if (attempt != generation) { candidate.disconnectWebSockets(); return@post }
                connecting = false
                if (!healthy) {
                    error = candidate.connectionError ?: "Cannot reach this Carriex server. Check the address, port and connection."
                    changed(); return@post
                }
                try {
                    scope = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray()).joinToString("") { "%02x".format(it) }
                    val crypto = LibsodiumCryptoEngine(AndroidKeyStoreKeyManager(getApplication(), scope))
                    transport = candidate
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
                    preferences.getStringSet("contacts_$scope", emptySet())!!.sorted().forEach {
                        conversations.getOrPut(it) { mutableListOf() }
                    }
                    preferences.edit().putString("host", host.trim()).putString("port", port.trim()).putBoolean("tls", useTls).apply()
                    connected = true
                    activeService.start()
                    handler.post(poll)
                } catch (e: Exception) {
                    candidate.disconnectWebSockets()
                    service = null
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
        transport?.disconnectWebSockets()
        transport = null
        connected = false
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
        fun validUserId(value: String) = value.matches(Regex("[A-Za-z0-9_.-]{1,64}"))
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
