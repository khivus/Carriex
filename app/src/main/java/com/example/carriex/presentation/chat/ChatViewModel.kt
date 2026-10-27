package com.example.carriex.presentation.chat

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.example.carriex.auth.AndroidDeviceIdentity
import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.messaging.MessagingService
import com.example.carriex.messaging.ChatStore
import com.example.carriex.messaging.GroupMessagingService
import com.example.carriex.messaging.GroupConversation
import com.example.carriex.model.Message
import com.example.carriex.notifications.MessageRequestNotifications
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
    var startupSuggestion: PairedServer? = null
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
    var selectedGroup: String? = null
        private set
    val compatibilityByServer = mutableMapOf<String, com.example.carriex.transport.ServerCompatibility>()
    private val groupsByServer = mutableMapOf<String, List<GroupConversation>>()
    private val optimisticGroups = mutableMapOf<String, MutableList<Message>>()
    private val savingGroupMessages = mutableMapOf<String, String>()
    private val contactOperations = mutableSetOf<String>()
    private val groupOperations = mutableMapOf<String, String>()
    private val autoJoinAttempts = mutableSetOf<String>()
    val groups get() = groupsByServer[scope].orEmpty().map { group ->
        val pending = optimisticGroups["$scope/${group.state.id}"].orEmpty()
        group.copy(messages = group.messages + pending.filter { candidate -> group.messages.none { it.id == candidate.id } })
    }
    val currentGroup get() = groups.firstOrNull { it.state.id == selectedGroup }
    val selectedConversation get() = selectedGroup?.let { "group:$it" } ?: selectedPeer
    val groupDrafts = mutableMapOf<String, String>()
    val groupSending get() = "$scope/$selectedGroup" in savingGroupMessages
    fun groupProgress(id: String): String? = groupOperations["$scope/$id"]
    private class ServerChats {
        val conversations = linkedMapOf<String, MutableList<Message>>()
        val unread = mutableMapOf<String, Int>()
        val drafts = mutableMapOf<String, String>()
        val removing = mutableSetOf<String>()
        val removedIds = mutableSetOf<String>()
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
    private var groupService: GroupMessagingService? = null
    @Volatile private var generation = 0
    private var foreground = false
    private var viewGeneration = 0
    private var scope = ""

    private fun refreshServers() {
        try { savedServers = serverStore.list(); autoConnectUrl = serverStore.autoConnectUrl() }
        catch (e: Exception) { error = "Unable to read saved servers: ${e.message}" }
    }

    fun dismissStartupSuggestion() { startupSuggestion = null; changed() }

    fun setAutoConnect(server: PairedServer, enabled: Boolean) {
        try {
            preferences.edit().putBoolean("startup_suggestion_seen", true).commit()
            startupSuggestion = null
            serverStore.setAutoConnect(if (enabled) server.url else autoConnectUrl?.takeUnless { it == server.url })
            refreshServers()
            error = null
        } catch (e: Exception) { error = e.message }
        changed()
    }

    fun statusFor(server: PairedServer): ServerTransport.ConnectionStatus =
        if (activeServer?.url == server.url) connectionStatus else ServerTransport.ConnectionStatus.DISCONNECTED

    fun navigationChats(server: PairedServer): List<com.example.carriex.messaging.ConversationSummary> {
        val summary = com.example.carriex.messaging.ConversationSummary
        return (chatsFor(server.fingerprint).conversations.map { (peer, messages) ->
            com.example.carriex.messaging.ConversationSummary(peer, contactLabel(peer, server.fingerprint), summary.latest(messages))
        } + groupsByServer[server.fingerprint].orEmpty().filter { userId !in it.state.invited }.map {
            com.example.carriex.messaging.ConversationSummary("group:${it.state.id}", it.state.name,
                summary.latest(it.messages + optimisticGroups["${server.fingerprint}/${it.state.id}"].orEmpty()))
        }).sortedWith(summary.order)
    }

    fun navigateTo(server: PairedServer, conversation: String? = null) {
        viewSaved(server)
        if(conversation?.startsWith("group:") == true) selectedGroup = conversation.removePrefix("group:")
        else if(conversation != null) openChat(conversation)
        if(activeServer?.url != server.url || (!connected && !connecting)) connect(server, navigate = false)
        changed()
    }

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
        MessageRequestNotifications.clear(getApplication(), server.fingerprint)
        MessageRequestNotifications.clearInvitations(getApplication(), server.fingerprint)
        if (activeServer?.url == server.url) disconnect()
        if (viewedServer?.url == server.url) {
            viewGeneration++; viewedServer = null; selectedPeer = null; selectedGroup = null; serversVisible = true
        }
        try { serverStore.remove(server.url); refreshServers(); error = null }
        catch (e: Exception) { error = e.message }
        changed()
    }

    fun showError(detail: String) { error = detail; changed() }
    fun viewSaved(server: PairedServer) {
        ++viewGeneration
        viewedServer = server; scope = server.fingerprint; selectedPeer = null; selectedGroup = null; serversVisible = false
        error = null; changed()
        loadSaved(server)
    }

    private fun loadSaved(server: PairedServer) {
        val attempt = viewGeneration
        val chats = chatsFor(server.fingerprint)
        if (chats.loaded || chats.loading) return
        chats.loading = true
        worker.execute {
            val history = runCatching { chatStore.history(server.fingerprint) }
            val groupHistory = runCatching { GroupMessagingService.cached(chatStore, server.fingerprint, userId) }
            handler.post {
                chats.loading = false
                if (history.isFailure) {
                    if (attempt == viewGeneration) error = "Unable to unlock saved messages: ${history.exceptionOrNull()?.message}"
                } else loadHistory(server.fingerprint, history.getOrThrow())
                groupHistory.getOrNull()?.let { groupsByServer[server.fingerprint] = it }
                changed()
            }
        }
    }

    private fun loadHistory(serverScope: String, history: List<Message>) {
        val chats = chatsFor(serverScope)
        chats.conversations.clear()
        history.forEach { saved ->
            val peer = if (saved.from == userId) saved.to else saved.from
            if(peer !in chats.removing && saved.id !in chats.removedIds)
                chats.conversations.getOrPut(peer) { mutableListOf() }.add(saved)
        }
        preferences.getStringSet("contacts_$serverScope", emptySet())!!.filter(::validUserId).sorted().forEach {
            if(it !in chats.removing) chats.conversations.getOrPut(it) { mutableListOf() }
        }
        chats.loaded = true
        saveContacts(serverScope)
    }
    private val poll = object : Runnable {
        override fun run() {
            if(!foreground) return
            val current = service ?: return
            current.fetchNewMessages()
            groupService?.sync()
            handler.postDelayed(this, 5000)
        }
    }

    init {
        refreshServers()
        savedServers.forEach(::loadSaved)
        if(com.example.carriex.ui.chat.SetupProgress.completed(preferences)) savedServers.firstOrNull { it.url == autoConnectUrl }?.let(::connect)
    }

    fun connect(server: PairedServer, navigate: Boolean = true) {
        if (activeServer?.url == server.url) {
            if (connecting || connected) { if (navigate) viewSaved(server); return }
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
        closeConnection()
        activeServer = server
        pendingTransport = candidate
        connecting = true
        connectionStatus = ServerTransport.ConnectionStatus.CONNECTING
        error = null
        // Show cached history immediately; connection setup runs after the history read.
        if (navigate) viewSaved(server) else loadSaved(server)
        val attempt = ++generation
        worker.execute {
            val healthy = candidate.testServerActive()
            handler.post {
                if (attempt != generation) { candidate.close(); return@post }
                pendingTransport = null
                candidate.serverCompatibility?.let { compatibilityByServer[server.fingerprint] = it }
                connecting = false
                if (!healthy) {
                    connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
                    error = if(candidate.serverCompatibility?.level == com.example.carriex.transport.ServerCompatibility.Level.INCOMPATIBLE) null
                        else candidate.connectionError ?: "Cannot reach this Carriex server. Check the address, port and connection."
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
                    transport = candidate
                    candidate.onConnectionStatusChanged = { status -> handler.post {
                        if (attempt == generation && connectionStatus != status) {
                            connectionStatus = status
                            if(status == ServerTransport.ConnectionStatus.CONNECTED &&
                                !preferences.getBoolean("startup_suggestion_seen", false)) {
                                preferences.edit().putBoolean("startup_suggestion_seen", true).commit()
                                if(autoConnectUrl == null) startupSuggestion = server
                            }
                            changed()
                        }
                    } }
                    val activeService = MessagingService(userId, device, candidate, chatStore, chatScope,
                        contacts = { preferences.getStringSet("contacts_$chatScope",emptySet()).orEmpty().toSet() },
                        blocked = { preferences.getStringSet("blocked_$chatScope",emptySet()).orEmpty().toSet() },
                        allowFallback = { preferences.getBoolean("fallback_$chatScope",false) },
                        autoAccept = { preferences.getBoolean("auto_accept_messages", false) &&
                            preferences.getStringSet("contacts_$chatScope", emptySet()).orEmpty().size < 128 },
                        sendReadStatus = { preferences.getBoolean("send_read_status", false) },
                        applyContactPolicy = { peer, action ->
                            val edit = preferences.edit()
                            if(action.deleteHistory) edit.putStringSet("contacts_$chatScope",
                                preferences.getStringSet("contacts_$chatScope", emptySet()).orEmpty().filter { it != peer }.toSet())
                            if(action.block) edit.putStringSet("blocked_$chatScope",
                                preferences.getStringSet("blocked_$chatScope", emptySet()).orEmpty() + peer)
                            if(action.removeKeys) edit.remove("verified_id_${chatScope}_$peer")
                            check(edit.commit()) { "Unable to save contact policy" }
                        })
                    service = activeService
                    activeService.onContactActionCompleted = { peer, action, message -> handler.post {
                        if(attempt == generation) {
                            contactOperations.remove("$chatScope/$peer")
                            chats.removing.remove(peer)
                            if(action.deleteHistory) {
                                chats.removedIds.addAll(chats.conversations[peer].orEmpty().map { it.id })
                                chats.conversations.remove(peer); chats.unread.remove(peer); chats.drafts.remove(peer)
                                if(scope == chatScope && selectedPeer == peer) { selectedPeer = null; serversVisible = false }
                            } else {
                                val conversation = chats.conversations.getOrPut(peer) { mutableListOf() }
                                if(conversation.none { it.id == message.id }) conversation.add(message)
                            }
                            saveContacts(chatScope); changed()
                        }
                    } }
                    val activeGroups = GroupMessagingService(device, candidate, chatStore, chatScope,
                        allowFallback = { preferences.getBoolean("fallback_$chatScope", false) })
                    groupService = activeGroups
                    activeGroups.onChanged = { groups -> handler.post { if(attempt == generation) {
                        groups.forEach { group -> optimisticGroups["$chatScope/${group.state.id}"]?.removeAll { pending ->
                            group.messages.any { it.id == pending.id }
                        } }
                        val previous = groupsByServer[chatScope].orEmpty().associateBy { it.state.id }
                        val unchanged = previous.size == groups.size && groups.all { group -> previous[group.state.id]?.let { old ->
                            old.state.revision == group.state.revision && old.ready == group.ready && old.problem == group.problem &&
                                old.joining == group.joining && old.events.map { it.state.revision to it.afterMessage } == group.events.map { it.state.revision to it.afterMessage } &&
                                old.messages.map { Triple(it.id, it.status, it.sentAt) } == group.messages.map { Triple(it.id, it.status, it.sentAt) }
                        } == true }
                        if(groups.any { group -> previous[group.state.id]?.let { old ->
                            val known = old.messages.map { it.id }.toSet()
                            group.messages.any { it.from != userId && it.id !in known }
                        } == true }) com.example.carriex.notifications.NotificationPolling.liveMessage(getApplication(), server)
                        groupsByServer[chatScope] = groups
                        groups.forEach { MessageRequestNotifications.groupInvitation(getApplication(), server, it.state, userId) }
                        autoAcceptGroups(chatScope, groups)
                        if(!unchanged) changed()
                    } } }
                    activeGroups.onError = { detail -> handler.post { if(attempt == generation) { error = detail; changed() } } }
                    candidate.onGroupsChanged = { activeGroups.sync() }
                    activeService.onNewMessageRequest = { handler.post { notifyPendingMessageRequests(server) } }
                    activeService.onRequestsChanged = { handler.post { if(attempt==generation) changed() } }
                    activeService.onError = { detail -> handler.post { if (attempt == generation) {
                        contactOperations.removeAll { key -> key.startsWith("$chatScope/") &&
                            chatStore.x3dh(chatScope).get("contact-action/${key.substringAfter('/')}") == null }
                        error = contactErrorLabel(detail, chatScope); changed()
                    } } }
                    activeService.onMessageDecrypted = { message ->
                        handler.post {
                            if (attempt == generation && message.from !in chats.removing && message.id !in chats.removedIds) {
                                chats.conversations.getOrPut(message.from) { mutableListOf() }.add(message)
                                if (serversVisible || scope != chatScope || selectedPeer != message.from)
                                    chats.unread[message.from] = (chats.unread[message.from] ?: 0) + 1
                                com.example.carriex.notifications.NotificationPolling.liveMessage(getApplication(), server)
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
                        try { activeService.start(); activeGroups.start() }
                        catch (e: Exception) { handler.post { if (attempt == generation) {
                            activeService.close(); candidate.close()
                            activeGroups.close(); groupService = null
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
                    groupService?.close(); groupService = null
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
        if(id !in conversations && conversations.size>=128) { error="At most 128 contacts are supported";changed();return false }
        conversations.getOrPut(id) { mutableListOf() }
        selectedPeer = id
        selectedGroup = null
        unread.remove(id)
        saveContacts(); changed()
        service?.refreshPolicy()
        return true
    }

    fun messageRequests(): List<ChatStore.Request> = chatStore.requests(scope)
    fun openGroup(id: String) {
        if(groups.none { it.state.id == id }) return
        selectedGroup = id; selectedPeer = null; serversVisible = false; error = null; changed()
    }
    fun createGroup(name: String) {
        if(!viewingConnectedServer) { showError("Connect to this server before creating a group."); return }
        val chatScope = scope
        groupService?.create(name) { id -> handler.post { if(scope == chatScope) openGroup(id) } }
    }
    fun changeGroup(id: String, action: String, target: String = "", deleteHistory: Boolean = false) {
        if(!viewingConnectedServer) { showError("Connect to this server to change group membership."); return }
        val active = groupService ?: return
        val key = "$scope/$id"
        if(key in groupOperations || (action == "join" && groups.any { it.state.id == id && it.joining })) return
        val attempt = generation
        groupOperations[key] = when(action) {
            "join" -> "Joining group…"
            "leave" -> "Leaving group…"
            "rename" -> "Renaming group…"
            else -> "Updating group…"
        }
        if(action == "join") openGroup(id) else changed()
        active.change(id, action, target, deleteHistory) { handler.post {
            if(attempt == generation) { groupOperations.remove(key); changed() }
        } }
    }
    fun clearGroupHistory(id: String) {
        val serverScope = scope
        val group = groups.firstOrNull { it.state.id == id } ?: return
        if(!group.state.closed && userId in group.state.members) { showError("Leave this group before deleting its saved history."); return }
        if(activeServer?.fingerprint == serverScope && groupService != null) groupService?.clearHistory(id)
        else worker.execute {
            val result = runCatching {
                GroupMessagingService.clearCachedHistory(chatStore, serverScope, id)
                GroupMessagingService.cached(chatStore, serverScope, userId)
            }
            handler.post { result.onSuccess { groupsByServer[serverScope] = it }.onFailure { error = it.message }; changed() }
        }
    }
    fun sendGroup(text: String) {
        if(!viewingConnectedServer || text.isBlank()) return
        val id = selectedGroup ?: return
        val group = currentGroup ?: return
        if(!group.ready || group.joining || group.state.closed || userId !in group.state.members) return
        val active = groupService ?: return
        val key = "$scope/$id"
        if(key in savingGroupMessages || key in groupOperations) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        try { com.example.carriex.messaging.MessageLimits.requirePlaintext(bytes) }
        catch(e: com.example.carriex.messaging.MessageSizeException) { showError(e.message ?: "Message is too large"); return }
        val message = Message(userId, id, bytes, Message.MessageType.DATA,
            status = Message.DeliveryStatus.SENDING, sentAt = System.currentTimeMillis())
        val chatScope = scope
        val attempt = generation
        savingGroupMessages[key] = message.id
        optimisticGroups.getOrPut(key) { mutableListOf() }.add(message)
        groupDrafts.remove(key)
        error = null
        changed() // Clear the composer and show Sending before any disk or network work.
        worker.execute {
            try {
                active.persistOutgoing(id, message, group.state.epoch)
            } catch(e: Exception) {
                handler.post {
                    if(savingGroupMessages[key] == message.id) savingGroupMessages.remove(key)
                    optimisticGroups[key]?.removeAll { it.id == message.id }
                    if(groupDrafts[key].isNullOrEmpty()) groupDrafts[key] = text
                    if(scope == chatScope) { error = "Message could not be saved: ${e.message}"; changed() }
                }
                return@execute
            }
            handler.post {
                if(savingGroupMessages[key] == message.id) savingGroupMessages.remove(key)
                groupsByServer[chatScope] = groupsByServer[chatScope].orEmpty().map { savedGroup ->
                    if(savedGroup.state.id != id || savedGroup.messages.any { it.id == message.id }) savedGroup
                    else savedGroup.copy(messages = savedGroup.messages + message)
                }
                if(scope == chatScope) changed()
            }
            if(attempt == generation) active.flush(id)
        }
    }
    fun notifyPendingMessageRequests(server: PairedServer? = activeServer ?: viewedServer) {
        server ?: return
        if(savedServers.none { it.url == server.url && it.fingerprint == server.fingerprint }) return
        val contacts = preferences.getStringSet("contacts_${server.fingerprint}", emptySet()).orEmpty()
        val blocked = preferences.getStringSet("blocked_${server.fingerprint}", emptySet()).orEmpty()
        val count = chatStore.requests(server.fingerprint).count { it.peer !in contacts && it.peer !in blocked }
        MessageRequestNotifications.show(getApplication(), server, count)
        groupsByServer[server.fingerprint].orEmpty().forEach {
            MessageRequestNotifications.groupInvitation(getApplication(), server, it.state, userId)
        }
    }
    private fun autoAcceptGroups(serverScope: String, groups: List<GroupConversation>) {
        if(!preferences.getBoolean("auto_accept_groups", false) || activeServer?.fingerprint != serverScope) return
        val active = groupService ?: return
        val blocked = preferences.getStringSet("blocked_$serverScope", emptySet()).orEmpty()
        groups.filter { userId in it.state.invited && !it.state.closed && !it.joining && it.state.admin !in blocked }.forEach { group ->
            val key = "$serverScope/${group.state.id}/${group.state.revision}"
            if(autoJoinAttempts.add(key)) active.change(group.state.id, "join", userId)
        }
    }

    fun applyChatPreferences() {
        if(!preferences.getBoolean("send_read_status", false)) worker.execute { chatStore.clearPendingReadStatus() }
        val serverScope = activeServer?.fingerprint ?: return
        if(preferences.getBoolean("auto_accept_messages", false)) {
            val blocked = preferences.getStringSet("blocked_$serverScope", emptySet()).orEmpty()
            chatStore.requests(serverScope).filter { it.peer !in blocked && it.reason == "Message request from an unknown contact" }
                .forEach { service?.acceptRequests(it.peer) }
        }
        autoJoinAttempts.clear()
        autoAcceptGroups(serverScope, groupsByServer[serverScope].orEmpty())
    }

    fun messagesViewed(ids: Collection<String>) {
        if(!viewingConnectedServer || selectedPeer == null || !preferences.getBoolean("send_read_status", false)) return
        val incoming = conversations[selectedPeer].orEmpty().filter { it.from != userId && it.id in ids }.map { it.id }
        if(incoming.isNotEmpty()) service?.messagesViewed(incoming)
    }

    fun removeChat(peer: String, block: Boolean = false, removeKeys: Boolean = false) {
        contactAction(peer, com.example.carriex.messaging.ContactAction(deleteHistory = true, block = block, removeKeys = removeKeys))
    }
    private fun contactAction(peer: String, action: com.example.carriex.messaging.ContactAction) {
        if(!viewingConnectedServer) { showError("Connect to this server to notify the contact before changing this chat."); return }
        if(!validUserId(peer)) return
        if(action.block && blockedContacts().size >= 128 && peer !in blockedContacts()) { showError("Blocked device limit reached"); return }
        contactOperations.add("$scope/$peer")
        service?.contactAction(peer, action)
        changed()
    }
    fun contactActionPending(peer: String): Boolean = "$scope/$peer" in contactOperations || chatStore.x3dh(scope).get("contact-action/$peer") != null
    fun allowFallback(serverScope: String = scope):Boolean = preferences.getBoolean("fallback_$serverScope",false)
    fun setAllowFallback(value:Boolean, serverScope: String = scope) {
        preferences.edit().putBoolean("fallback_$serverScope",value).commit(); changed()
        if(value && serverScope == activeServer?.fingerprint) chatStore.requests(serverScope).filter { it.peer in chatsFor(serverScope).conversations }.forEach { service?.acceptRequests(it.peer) }
    }
    fun acceptRequest(peer:String) {
        if(!openChat(peer)) return
        MessageRequestNotifications.clear(getApplication(), scope)
        service?.acceptRequests(peer)
    }
    fun blockContact(peer:String) {
        if(!validUserId(peer)) return
        val serverScope = scope
        val ids = blockedContacts().toMutableSet()
        if(ids.size >= 128 && peer !in ids) { showError("Blocked device limit reached"); return }
        ids.add(peer)
        if(!preferences.edit().putStringSet("blocked_$serverScope", ids).commit()) { showError("Unable to save contact policy"); return }
        chatStore.discardRequests(serverScope, peer)
        MessageRequestNotifications.clear(getApplication(), serverScope)
        val action = com.example.carriex.messaging.ContactAction(block = true)
        if(activeServer?.fingerprint == serverScope && service != null) {
            service?.refreshPolicy(); service?.contactAction(peer, action)
        } else worker.execute {
            val result = runCatching { chatStore.queueContactAction(serverScope, userId, peer, action) }
            handler.post { result.exceptionOrNull()?.let { error = it.message }; changed() }
        }
        changed()
    }
    fun blockedContacts(serverScope: String = scope):List<String> = preferences.getStringSet("blocked_$serverScope",emptySet()).orEmpty().sorted()
    fun unblockContact(peer:String, serverScope: String = scope) {
        preferences.edit().putStringSet("blocked_$serverScope",blockedContacts(serverScope).filter { it!=peer }.toSet()).commit()
        if(serverScope == activeServer?.fingerprint) service?.refreshPolicy()
        changed()
    }
    fun contactKeyProtection(peer:String):String = when(chatStore.x3dh(scope).get("peer-protection/$peer")) {
        "one-time" -> "Double Ratchet; initial exchange used a one-time key."
        "fallback" -> "Double Ratchet; initial exchange used fallback without a one-time key."
        else -> "No session protection information yet."
    }
    fun discardRequest(peer:String) {
        chatStore.discardRequests(scope,peer)
        MessageRequestNotifications.clear(getApplication(), scope)
        changed()
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

    fun contactVerified(peer: String): Boolean = preferences.getBoolean("verified_id_${scope}_$peer", false)
    fun markContactVerified(peer: String) {
        if (!validUserId(peer) || peer == userId || viewedServer == null) return
        if (!preferences.edit().putBoolean("verified_id_${scope}_$peer", true).commit()) {
            error = "Unable to save contact verification"
        }
        changed()
    }
    fun contactSecurityLabel(peer: String): String = if (contactVerified(peer))
        "Contact ID verified" else "Contact ID not verified"
    fun contactEncryptionFingerprint(peer: String): String? = chatStore.x3dh(scope).get("peer/$peer")?.let {
        com.example.carriex.crypto.x3dh.X3dhCrypto.hex(com.example.carriex.crypto.x3dh.X3dhCrypto.hash(
            com.example.carriex.crypto.x3dh.X3dhCrypto.decode(it, 32))).chunked(8).joinToString(" ")
    }

    fun setContactName(peer: String, name: String): Boolean {
        if (viewedServer == null || !validUserId(peer)) return false
        val value = name.trim()
        if (value.length > 64) return false
        if (!preferences.edit().putString(contactNameKey(peer), value).commit()) {
            error = "Unable to save chat name"; changed(); return false
        }
        changed()
        return true
    }

    fun showChats() { selectedPeer = null; selectedGroup = null; serversVisible = false; changed() }
    fun restartSecureSession(peer: String) {
        if (!viewingConnectedServer) {
            error = "Connect to this server before restarting a secure session."; changed(); return
        }
        if (validUserId(peer)) contactAction(peer, com.example.carriex.messaging.ContactAction(restart = true))
    }
    fun showServers() {
        viewGeneration++; selectedPeer = null; selectedGroup = null; serversVisible = true; changed()
    }
    fun dismissError() { error = null; changed() }
    fun send(text: String) {
        if(selectedGroup != null) { sendGroup(text); return }
        if (!viewingConnectedServer) return
        val peer = selectedPeer ?: return
        if(contactActionPending(peer)) { showError("Wait for this contact action to finish before sending messages."); return }
        if(peer in blockedContacts()) { showError("Unblock this contact before sending messages."); return }
        if (text.isBlank()) return
        val body = text.toByteArray(Charsets.UTF_8)
        try { com.example.carriex.messaging.MessageLimits.requirePlaintext(body) }
        catch (e: com.example.carriex.messaging.MessageSizeException) { error = e.message; changed(); return }
        val activeService = service ?: return
        val activeScope = scope
        val chats = chatsFor(activeScope)
        val attempt = generation
        val message = Message(userId, peer, body, Message.MessageType.DATA,
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

    /** Keep the authenticated socket and in-flight sends while Android keeps the process alive. */
    fun onBackground() {
        foreground = false
        handler.removeCallbacks(poll)
    }

    fun onForeground() {
        foreground = true
        handler.removeCallbacks(poll)
        if(service != null) handler.post(poll)
    }

    private fun closeConnection() {
        generation++
        savingGroupMessages.clear()
        groupOperations.clear()
        contactOperations.clear()
        autoJoinAttempts.clear()
        optimisticGroups.clear()
        activeServer?.let { chatsByServer[it.fingerprint]?.loaded = false }
        handler.removeCallbacks(poll)
        service?.close()
        service = null
        groupService?.close(); groupService = null
        groupsByServer[activeServer?.fingerprint]?.let { groups ->
            activeServer?.fingerprint?.let { groupsByServer[it] = groups.map { group -> group.copy(ready = false) } }
        }
        transport?.close()
        transport = null
        pendingTransport?.close()
        pendingTransport = null
        connected = false
        activeServer = null
        connectionStatus = ServerTransport.ConnectionStatus.DISCONNECTED
        connecting = false
    }

    fun disconnect() {
        closeConnection()
        viewGeneration++
        viewedServer = null
        serversVisible = true
        selectedPeer = null
        selectedGroup = null
        error = null
        changed()
    }

    private fun saveContacts(serverScope: String = scope) {
        val chats = chatsFor(serverScope)
        preferences.edit().putStringSet("contacts_$serverScope", chats.conversations.keys.filterNot { it in chats.removing }.toSet()).apply()
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
