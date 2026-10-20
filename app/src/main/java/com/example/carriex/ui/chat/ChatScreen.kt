package com.example.carriex.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.PopupMenu
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.carriex.presentation.chat.ChatViewModel
import com.example.carriex.model.Message
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.example.carriex.notifications.NotificationPolling
import com.example.carriex.notifications.MessageRequestNotifications
import com.example.carriex.notifications.CountPoller
import com.example.carriex.notifications.PollInterval
import com.example.carriex.servers.PairedServer
import com.example.carriex.R
import com.example.carriex.auth.ContactQr
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** One device identity, a conversation list, and a focused chat composer. */
class ChatScreen : Fragment() {
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { reviewPairing(it) }
    }
    private var expectedScannedPeer: String? = null
    private val contactScanner = registerForActivityResult(ScanContract()) { result ->
        val expected = expectedScannedPeer
        expectedScannedPeer = null
        result.contents?.let { reviewContact(it, expected) }
    }
    private var inviteScanGroup: String? = null
    private var inviteScanServer: String? = null
    private var refreshInviteConfirmation: (() -> Unit)? = null
    private val inviteScanner = registerForActivityResult(ScanContract()) { result ->
        val groupId = inviteScanGroup
        val server = inviteScanServer
        inviteScanGroup = null; inviteScanServer = null
        if(result.contents != null && groupId != null && isAdded) {
            try {
                require(model.viewedServer?.fingerprint == server) { "Return to the original server to invite this person." }
                val group = model.groups.firstOrNull { it.state.id == groupId }?.state ?: error("Group is unavailable")
                val peer = ContactQr.parse(result.contents)
                require(group.admin == model.userId && !group.closed) { "Only the group admin can invite people." }
                require(peer !in group.members && peer !in group.invited) { "This person is already a member or has an invitation." }
                val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Invite to ${group.name}?")
                    .setMessage("${model.contactLabel(peer)}\n\n$peer\n\nThey must use this server and accept the invitation.")
                    .setPositiveButton("Invite", null).setNegativeButton("Cancel", null).create()
                fun ready() = model.viewedServer?.fingerprint == server && model.viewingConnectedServer &&
                    model.groups.firstOrNull { it.state.id == groupId }?.ready == true
                dialog.setOnShowListener {
                    val invite = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    // Opening the camera backgrounds MainActivity and pauses its connection.
                    // Keep the confirmation while that connection and group keys resume.
                    refreshInviteConfirmation = {
                        invite.isEnabled = ready()
                        invite.text = if(ready()) "Invite" else "Joining group…"
                    }
                    refreshInviteConfirmation?.invoke()
                    invite.setOnClickListener {
                        if(ready()) { dialog.dismiss(); model.changeGroup(groupId, "invite", peer) }
                    }
                }
                dialog.setOnDismissListener { refreshInviteConfirmation = null }
                pairingDialog = dialog; dialog.show()
            } catch(e: Exception) {
                pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Unable to invite")
                    .setMessage(e.message ?: "Invalid contact QR code").setPositiveButton("Done", null).show()
            }
        }
    }
    private var pairingDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var model: ChatViewModel
    private lateinit var root: LinearLayout
    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigation: NavigationView
    private lateinit var drawerStatus: TextView
    private lateinit var pageActions: PageActions
    private var toolbarMenuMode = -1
    private var settingsVisible = false
    private var settingsBuilt = false
    private var settingsStatus: TextView? = null
    private var pageScroll: ScrollView? = null
    private val settingsWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        NotificationPolling.preferences(requireContext()).edit().putBoolean("notifications_enabled", granted).apply()
        scheduleNotifications()
        settingsBuilt = false; render()
        if (granted) offerBatteryExemption()
    }
    private val requestAlertPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) model.notifyPendingMessageRequests()
        refreshNotificationStatus()
    }
    private var composer: EditText? = null
    private var renderedPeer: String? = null
    private var renderedServers = false
    private var renderedScope: String? = null
    private var messageScroll: ScrollView? = null
    private var messageList: LinearLayout? = null
    private var connectionLabel: TextView? = null
    private var errorLabel: TextView? = null
    private var sendButton: MaterialButton? = null
    private var conversationTitle: TextView? = null
    private data class MessageViews(val row: View, val body: TextView, val status: TextView?, val time: TextView,
        val footer: View, val sender: TextView? = null, val bubble: View? = null)
    private val messageViews = linkedMapOf<String, MessageViews>()
    private val ink get() = color(com.google.android.material.R.attr.colorOnSurface)
    private val muted get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private fun color(attribute: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
    }
    private fun column() = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
    private fun actionRow() = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(4) }
    }
    private fun LinearLayout.addAction(value: String, enabled: Boolean = true, action: () -> Unit): MaterialButton {
        if (childCount > 0) addView(Space(context), LinearLayout.LayoutParams(dp(4), 1))
        val control = button(value, action).apply { isEnabled = enabled }
        addView(control, LinearLayout.LayoutParams(0, -2, 1f))
        return control
    }
    private fun spacedList() = column().apply { setPadding(0, dp(6), 0, dp(6)) }
    private fun label(value: String, size: Float = 16f, secondary: Boolean = false) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(if (secondary) muted else ink)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(value: String, action: () -> Unit) = MaterialButton(requireContext()).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(2) }
    }
    private fun menuIcon(resource: Int) = androidx.appcompat.content.res.AppCompatResources
        .getDrawable(requireContext(), resource)!!.mutate().apply {
            androidx.core.graphics.drawable.DrawableCompat.setTint(this, ink)
        }
    private fun field(parent: LinearLayout, title: String, value: String, changed: (String) -> Unit): TextInputEditText {
        val box = TextInputLayout(requireContext()).apply {
            hint = title
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        }
        val edit = TextInputEditText(box.context).apply {
            setSingleLine(true); setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            doAfterTextChanged { changed(it.toString()) }
        }
        box.addView(edit); parent.addView(box)
        return edit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(requireActivity())[ChatViewModel::class.java]
        expectedScannedPeer = savedInstanceState?.getString("verify_contact_id")
        inviteScanGroup = savedInstanceState?.getString("invite_scan_group")
        inviteScanServer = savedInstanceState?.getString("invite_scan_server")
        settingsVisible = savedInstanceState?.getBoolean("notifications_page") ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("verify_contact_id", expectedScannedPeer)
        outState.putString("invite_scan_group", inviteScanGroup)
        outState.putString("invite_scan_server", inviteScanServer)
        outState.putBoolean("notifications_page", settingsVisible)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        toolbarMenuMode = -1
        root = column().apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        toolbar = MaterialToolbar(requireContext()).apply {
            navigationIcon = DrawerArrowDrawable(context)
            navigationContentDescription = "Open navigation menu"
            setNavigationOnClickListener {
                if(!settingsVisible && !model.serversVisible && model.selectedConversation != null) model.showChats()
                else { pageActions.close(false); drawer.openDrawer(GravityCompat.START) }
            }
            setOnMenuItemClickListener { item -> handlePageOption(item.itemId) }
        }
        pageActions = PageActions(requireContext(), root).apply {
            addServer = { this@ChatScreen.addServer() }
            scanContact = { this@ChatScreen.scanContact() }
            importContact = { importContactText() }
            createGroup = { this@ChatScreen.createGroup() }
        }
        val shell = column().apply {
            addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
            addView(pageActions, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        navigation = NavigationView(requireContext()).apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow))
            val header = column().apply { setPadding(dp(24), dp(24), dp(16), dp(16)) }
            header.addView(label("Carriex", 28f).apply { setTypeface(typeface, Typeface.BOLD) })
            drawerStatus = label("Private messaging", 13f, true).also(header::addView)
            addHeaderView(header)
            menu.add(1, 1, 0, "Chats").setIcon(R.drawable.ic_stat_message).isCheckable = true
            menu.add(1, 2, 1, "Servers").setIcon(R.drawable.ic_nav_servers).isCheckable = true
            menu.add(1, 3, 2, "My ID").setIcon(R.drawable.ic_nav_person)
            menu.add(1, 4, 3, "Notifications").setIcon(R.drawable.ic_nav_bell).isCheckable = true
            menu.setGroupCheckable(1, true, true)
            menu.findItem(3).isCheckable = false
            menu.add(2, 5, 4, "Add server").setIcon(R.drawable.ic_nav_add)
            setNavigationItemSelectedListener { item ->
                pageActions.close(false)
                drawer.closeDrawer(GravityCompat.START)
                when(item.itemId) {
                    1 -> { settingsVisible = false; chooseChats() }
                    2 -> { settingsVisible = false; model.showServers() }
                    3 -> showMyIdQr()
                    4 -> { settingsVisible = true; settingsBuilt = false; render() }
                    5 -> addServer()
                }
                true
            }
        }
        drawer = BackSwipeDrawerLayout(requireContext()).apply {
            // Swipe-back navigation is disabled until animated transitions are added.
            // onBackSwipe = { if(!goBack()) openDrawer(GravityCompat.START) }
            onBackSwipe = { pageActions.close(false); openDrawer(GravityCompat.START) }
            addView(shell, DrawerLayout.LayoutParams(-1, -1))
            addView(navigation, DrawerLayout.LayoutParams(minOf(dp(320), resources.displayMetrics.widthPixels-dp(56)), -1, GravityCompat.START))
        }
        return drawer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.onStateChanged = { if (this.view != null) render() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            private var fromLeftEdge = false
            override fun handleOnBackStarted(backEvent: BackEventCompat) { fromLeftEdge = backEvent.swipeEdge == BackEventCompat.EDGE_LEFT }
            override fun handleOnBackCancelled() { fromLeftEdge = false }
            override fun handleOnBackPressed() {
                val gesture = fromLeftEdge
                fromLeftEdge = false
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START)
                else if(pageActions.expanded) pageActions.close()
                // else if(model.serversVisible && !settingsVisible && gesture) drawer.openDrawer(GravityCompat.START)
                else if(gesture) drawer.openDrawer(GravityCompat.START)
                else if (!goBack()) { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        render()
    }

    private fun goBack(): Boolean = when {
        settingsVisible -> { settingsVisible = false; render(); true }
        !model.serversVisible && model.selectedConversation != null -> { model.showChats(); true }
        !model.serversVisible -> { drawer.openDrawer(GravityCompat.START); true }
        else -> false
    }

    private fun render() {
        refreshInviteConfirmation?.invoke()
        updateNavigation()
        if(model.connected) requestMessageAlertPermission()
        if (settingsVisible) {
            if (!settingsBuilt) { root.removeAllViews(); notificationsSettings(); settingsBuilt = true }
            refreshNotificationStatus()
            return
        }
        if(settingsBuilt) {
            settingsBuilt = false; messageList = null; renderedScope = null; pageScroll = null
        }
        val scope = model.viewedServer?.fingerprint
        if (!model.serversVisible && model.selectedConversation != null && !renderedServers &&
            renderedPeer == model.selectedConversation && renderedScope == scope && messageList != null) {
            updateConversation()
            return
        }
        val serverScroll = if (renderedServers) pageScroll?.scrollY ?: 0 else 0
        val chatScroll = if (!renderedServers && renderedPeer == null && renderedScope == scope)
            pageScroll?.scrollY else null
        composer = null
        messageScroll = null; messageList = null; connectionLabel = null; errorLabel = null; sendButton = null; conversationTitle = null
        messageViews.clear()
        pageScroll = null
        root.removeAllViews()
        if (model.serversVisible) {
            setup()
            pageScroll?.let { scroll -> scroll.post { scroll.scrollTo(0, serverScroll) } }
        } else if (model.selectedConversation == null) {
            chats()
            if (chatScroll != null) pageScroll?.let { scroll ->
                scroll.post { scroll.scrollTo(0, chatScroll) }
            }
        } else conversation()
        renderedPeer = model.selectedConversation
        renderedServers = model.serversVisible
        renderedScope = scope
    }

    private fun setup() {
        val content = column().apply { setPadding(0, 0, 0, dp(8)) }
        val scroll = ScrollView(requireContext()).apply { isFillViewport = true; clipToPadding = false; addView(content) }
        scroll.setPadding(0, 0, 0, dp(88))
        pageScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        content.addView(label("Connect to a server, or view its saved messages offline. More settings are in its menu.", 14f, true))
        model.error?.let { content.addView(label(it, 14f).apply { setTextColor(color(androidx.appcompat.R.attr.colorError)) }) }
        if (model.savedServers.isEmpty()) {
            content.addView(label("Your servers will appear here", 24f))
            content.addView(label("Tap + at the bottom right to add a server. Scan the server owner's pairing QR or import their pairing text.", 16f, true))
        }
        model.savedServers.forEach { server ->
            val row = column().apply {
                setPadding(dp(16), dp(8), dp(8), dp(12))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                isClickable = true; isFocusable = true
                setOnClickListener { model.viewSaved(server) }
            }
            row.addView(actionRow().apply {
                addView(label(server.name, 20f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageButton(requireContext()).apply {
                    setImageResource(R.drawable.ic_more_vertical)
                    imageTintList = android.content.res.ColorStateList.valueOf(muted)
                    background = null
                    contentDescription = "Options for ${server.name}"
                    setOnClickListener { serverOptions(this, server) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            row.addView(label(server.url, 14f, true))
            val status = model.statusFor(server)
            row.addView(label(status.label, 14f, status != com.example.carriex.transport.ServerTransport.ConnectionStatus.CONNECTED).apply {
                if (status == com.example.carriex.transport.ServerTransport.ConnectionStatus.CONNECTED)
                    setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            val unread = model.unreadFor(server)
            if (unread > 0) row.addView(label("$unread unread messages", 14f, true))
            if(model.autoConnectUrl == server.url) row.addView(label("Connects on app startup", 12f, true))
            row.addView(actionRow().apply {
                val current = model.activeServer?.url == server.url
                addAction(if(current && model.connecting) "Cancel" else if(current && model.connected) "Disconnect" else "Connect") {
                    if(current && (model.connected || model.connecting)) model.disconnect() else model.connect(server)
                }.contentDescription = if(current && model.connecting) "Cancel connection to ${server.name}"
                    else if(current && model.connected) "Disconnect from ${server.name}" else "Connect to ${server.name}"
                addAction("View messages") { model.viewSaved(server) }.contentDescription = "View messages on ${server.name}"
            })
            content.addView(row)
        }
    }

    private fun serverOptions(anchor: View, server: PairedServer) {
        PopupMenu(requireContext(), anchor).apply {
            setForceShowIcon(true)
            menu.add(0, 3, 2, "Connect on app startup").apply {
                icon = menuIcon(R.drawable.ic_nav_connection)
                isCheckable = true; isChecked = model.autoConnectUrl == server.url
            }
            menu.add(0, 4, 3, "Server details").icon = menuIcon(R.drawable.ic_nav_info)
            menu.add(0, 5, 4, "Forget server").icon = menuIcon(R.drawable.ic_delete)
            setOnMenuItemClickListener { item ->
                when(item.itemId) {
                    3 -> model.setAutoConnect(server, model.autoConnectUrl != server.url)
                    4 -> MaterialAlertDialogBuilder(requireContext()).setTitle(server.name)
                        .setMessage("${server.url}\n\nTrusted server key\n${server.fingerprint.chunked(8).joinToString(" ")}")
                        .setPositiveButton("Done", null).show()
                    5 -> MaterialAlertDialogBuilder(requireContext()).setTitle("Forget ${server.name}?")
                        .setMessage("This removes its saved address and trusted key. Saved messages stay on this device. Pair again to reconnect.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ ->
                            androidx.core.app.NotificationManagerCompat.from(requireContext()).cancel(server.fingerprint, 1)
                            model.forgetServer(server); scheduleNotifications()
                        }.show()
                }
                true
            }
            show()
        }
    }

    private fun addServer() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Add server")
            .setItems(arrayOf("Scan server QR", "Import pairing text")) { _, choice ->
                if(choice == 1) importPairingText() else scanner.launch(ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setCaptureActivity(PortraitQrCaptureActivity::class.java)
                    .setPrompt("Scan the pairing QR shown by your Carriex server").setBeepEnabled(false).setOrientationLocked(true))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseChats() {
        val server = model.viewedServer ?: model.activeServer
        if(server != null) { model.viewSaved(server); return }
        if(model.savedServers.isEmpty()) { model.showServers(); return }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Choose a server")
            .setItems(model.savedServers.map { it.name }.toTypedArray()) { _, index -> model.viewSaved(model.savedServers[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateNavigation() {
        val conversation = !settingsVisible && !model.serversVisible && model.selectedConversation != null
        toolbar.navigationIcon = if(conversation) androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), R.drawable.ic_back)
            else DrawerArrowDrawable(requireContext())
        toolbar.setNavigationIconTint(ink)
        toolbar.navigationContentDescription = if(conversation) "Back to chats" else "Open navigation menu"
        pageActions.showMode(when {
            settingsVisible || conversation -> PageActions.NONE
            model.serversVisible -> PageActions.SERVERS
            else -> PageActions.CHATS
        })
        toolbar.title = when {
            settingsVisible -> "Carriex - notifications"
            model.serversVisible -> "Carriex - servers"
            model.selectedGroup != null -> model.currentGroup?.state?.name ?: "Group"
            model.selectedPeer != null -> model.contactLabel(model.selectedPeer!!)
            else -> "${model.viewedServer?.name ?: "Carriex"} - chats"
        }
        toolbar.contentDescription = toolbar.title
        val active = model.activeServer
        drawerStatus.text = if(active == null) "Private messaging\nChoose a server to connect" else "${active.name}\n${model.connectionStatus.label}"
        navigation.setCheckedItem(when { settingsVisible -> 4; model.serversVisible -> 2; else -> 1 })
        val mode = when { settingsVisible || model.serversVisible -> 0; model.selectedGroup != null -> 3; conversation -> 2; else -> 1 }
        if(toolbarMenuMode != mode) {
            toolbar.menu.clear()
            if(conversation && model.selectedGroup == null) {
                toolbar.menu.add(0, 107, 0, "Verify chat").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            if(mode != 0) {
                toolbar.menu.add(0, 204, 1, "More options").apply {
                    icon = menuIcon(R.drawable.ic_more_vertical)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            toolbarMenuMode = mode
        }
        if(conversation && model.selectedGroup == null) {
            val verified = model.contactVerified(model.selectedPeer!!)
            toolbar.menu.findItem(107).apply {
                title = if(verified) "Chat verified" else "Chat not verified"
                icon = menuIcon(if(verified) R.drawable.ic_shield_verified else R.drawable.ic_nav_shield).apply {
                    androidx.core.graphics.drawable.DrawableCompat.setTint(this,
                        if(verified) color(androidx.appcompat.R.attr.colorPrimary) else muted)
                }
            }
        }
    }

    private fun pageOptions(anchor: View) {
        val current = model.activeServer?.url == model.viewedServer?.url
        val connection = if(current && model.connecting) "Cancel connection"
            else if(current && model.connected) "Disconnect" else "Connect"
        val entries: List<Triple<Int, String, Int>> = when(toolbarMenuMode) {
            1 -> listOf(
                Triple(201, "Message requests (${model.messageRequests().sumOf { it.count }})", R.drawable.ic_nav_requests),
                Triple(202, "Chat security", R.drawable.ic_nav_shield),
                Triple(203, connection, R.drawable.ic_nav_connection))
            2 -> listOf(
                Triple(101, "Contact ID", R.drawable.ic_nav_person),
                Triple(102, "Verify friend's ID", R.drawable.ic_scan_qr),
                Triple(103, "Rename chat", R.drawable.ic_nav_edit),
                Triple(104, "Remove chat", R.drawable.ic_delete),
                Triple(105, "Block contact", R.drawable.ic_block),
                Triple(106, "Restart secure session", R.drawable.ic_restart),
                Triple(202, "Chat security", R.drawable.ic_nav_shield))
            3 -> buildList {
                add(Triple(301, "Group members", R.drawable.ic_group))
                val group = model.currentGroup?.state
                if(group != null && !group.closed && model.userId in group.members) {
                    if(group.admin == model.userId) {
                        add(Triple(302, "Invite person", R.drawable.ic_nav_add))
                        add(Triple(304, "Delete group", R.drawable.ic_delete))
                    } else add(Triple(303, "Leave group", R.drawable.ic_delete))
                }
            }
            else -> return
        }
        PopupMenu(requireContext(), anchor).apply {
            setForceShowIcon(true)
            entries.forEachIndexed { order, (id, title, resource) ->
                menu.add(0, id, order, title).icon = menuIcon(resource)
            }
            setOnMenuItemClickListener { handlePageOption(it.itemId) }
            show()
        }
    }

    private fun promptChatVerification(peer: String) {
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Verify chat")
            .setMessage(if(model.contactVerified(peer))
                "This chat is verified. Scan the My ID QR directly from your friend's phone to verify it again."
                else "This chat is not verified. Scan the My ID QR directly from your friend's phone and confirm that it matches this chat.")
            .setPositiveButton("Scan QR") { _, _ -> scanContact(peer) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun handlePageOption(id: Int): Boolean {
        when(id) {
            204 -> pageOptions(toolbar.findViewById<View>(204) ?: toolbar)
            301 -> groupMembers()
            302 -> inviteGroupMember()
            303, 304 -> {
                val group = model.currentGroup?.state ?: return false
                pairingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if(id == 304) "Delete group?" else "Leave group?")
                    .setMessage(if(id == 304) "This closes the group for everyone. Admin rights cannot be transferred. Saved history stays on each device."
                        else "You will stop receiving messages. The remaining members will refresh their sender keys. Saved history stays on this device.")
                    .setNegativeButton("Cancel", null).setPositiveButton(if(id == 304) "Delete" else "Leave") { _, _ ->
                        model.changeGroup(group.id, if(id == 304) "close" else "leave", if(id == 304) "" else model.userId)
                    }.show()
            }
            201 -> showRequests()
            202 -> chatSecurity()
            203 -> if(model.activeServer?.url == model.viewedServer?.url && (model.connected || model.connecting)) model.disconnect()
                else model.viewedServer?.let { model.connect(it) }
            else -> {
                val peer = model.selectedPeer ?: return false
                when(id) {
                    101 -> showContactId(peer)
                    102 -> scanContact(peer)
                    103 -> nameChat(peer, opening = false)
                    104 -> {
                        val serverScope = model.viewedServer?.fingerprint ?: return false
                        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Remove chat?")
                            .setMessage("Remove this chat, its saved messages and draft from this device? The contact will not be blocked. Future messages can appear as message requests.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                                if(model.viewedServer?.fingerprint == serverScope) model.removeChat(peer)
                            }.show()
                    }
                    105 -> model.blockContact(peer)
                    106 -> MaterialAlertDialogBuilder(requireContext()).setTitle("Restart secure session?")
                        .setMessage("Use this if messages can no longer be decrypted after a long gap. Your next message starts a new secure session. Saved history and existing encrypted packets are kept.")
                        .setPositiveButton("Restart") { _, _ -> model.restartSecureSession(peer) }
                        .setNegativeButton("Cancel", null).show()
                    107 -> promptChatVerification(peer)
                    else -> return false
                }
            }
        }
        return true
    }

    private fun scheduleNotifications() {
        runCatching { NotificationPolling.schedule(requireContext()) }.onFailure {
            model.showError(it.message ?: "Unable to schedule notification checks")
        }
    }

    fun showNotificationSettings() {
        settingsVisible = true; settingsBuilt = false
        if(view != null) render()
    }

    fun showMessageRequests(server: PairedServer) {
        settingsVisible = false
        model.viewSaved(server)
        if(view != null) showRequests()
    }

    private fun requestMessageAlertPermission(explicit: Boolean = false) {
        if(Build.VERSION.SDK_INT < 33 || !MessageRequestNotifications.enabled(requireContext())) return
        val prefs = NotificationPolling.preferences(requireContext())
        if(androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED && (explicit || !prefs.getBoolean("request_alert_permission_asked", false))) {
            prefs.edit().putBoolean("request_alert_permission_asked", true).apply()
            requestAlertPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationsSettings() {
        val context = requireContext()
        val prefs = NotificationPolling.preferences(context)
        val content = spacedList()
        root.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1))
        content.addView(label("Message requests", 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(MaterialCheckBox(context).apply {
            text = "Alert when someone tries to contact me"
            isChecked = MessageRequestNotifications.enabled(context)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("message_request_alerts", enabled).apply()
                if(enabled) {
                    requestMessageAlertPermission(explicit = true)
                    model.notifyPendingMessageRequests()
                } else MessageRequestNotifications.clearAll(context)
            }
        })
        content.addView(label("New contact requests notify you even while Carriex is open. Tap the notification to review, accept, or block the request.", 15f, true))
        content.addView(label("Background checks", 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(label("Get a notification when a saved server has pending messages. Checks fetch counts only; open Carriex to receive and decrypt messages.", 15f, true))
        content.addView(MaterialCheckBox(context).apply {
            text = "Check for pending messages"
            isChecked = NotificationPolling.enabled(context)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("notifications_enabled", enabled).apply()
                if(enabled && Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    scheduleNotifications(); refreshNotificationStatus()
                    if(enabled) offerBatteryExemption()
                }
            }
        })
        content.addView(label("Check interval", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        val millis = NotificationPolling.interval(context)
        val units = longArrayOf(1000, 60_000, 3_600_000)
        val unitIndex = prefs.getInt("notification_interval_unit", if(millis % 60_000 == 0L) 1 else 0).coerceIn(0, 2)
        val storedInput = prefs.getString("notification_interval_input", null)
        val current = storedInput?.takeIf { runCatching { PollInterval.parse(it, units[unitIndex]) == millis }.getOrDefault(false) }
            ?: java.math.BigDecimal.valueOf(millis).divide(java.math.BigDecimal.valueOf(units[unitIndex]), java.math.MathContext.DECIMAL64).stripTrailingZeros().toPlainString()
        val intervalRow = actionRow()
        val intervalBox = column()
        val input = field(intervalBox, "Custom interval", current) { }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        intervalRow.addView(intervalBox, LinearLayout.LayoutParams(0, -2, 1f))
        val unit = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, arrayOf("Seconds", "Minutes", "Hours"))
            setSelection(unitIndex)
            contentDescription = "Interval unit"
        }
        intervalRow.addView(unit, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
        content.addView(intervalRow)
        content.addView(label("Default: 15 minutes. Enter any custom interval of at least one second, including decimals. Android may delay checks to save battery. Below 15 minutes, an ongoing notification keeps polling active and uses more battery.", 14f, true))
        content.addView(button("Apply interval") {
            try {
                val value = PollInterval.parse(input.text.toString(), units[unit.selectedItemPosition])
                prefs.edit().putLong("notification_interval", value).putInt("notification_interval_unit", unit.selectedItemPosition)
                    .putString("notification_interval_input", input.text.toString().trim()).apply()
                input.error = null
                scheduleNotifications(); refreshNotificationStatus()
                Toast.makeText(context, "Checks every ${PollInterval.label(value)}", Toast.LENGTH_SHORT).show()
            } catch(e: IllegalArgumentException) { input.error = e.message }
        })
        content.addView(label("Servers to check", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        if(model.savedServers.isEmpty()) content.addView(label("Add a server from the navigation menu first.", 14f, true))
        model.savedServers.forEach { server ->
            content.addView(MaterialCheckBox(context).apply {
                text = server.name
                isChecked = prefs.getBoolean("notify_${server.fingerprint}", true)
                setOnCheckedChangeListener { _, enabled ->
                    prefs.edit().putBoolean("notify_${server.fingerprint}", enabled).apply()
                    if(!enabled) androidx.core.app.NotificationManagerCompat.from(context).cancel(server.fingerprint, 1)
                    scheduleNotifications(); refreshNotificationStatus()
                }
            })
        }
        content.addView(label("Android settings", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(button("Battery optimization") { offerBatteryExemption(manual = true) })
        content.addView(button("Notification permission & channels") {
            startActivity(Intent(if(Build.VERSION.SDK_INT >= 26) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                if(Build.VERSION.SDK_INT >= 26) putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else data = Uri.parse("package:${context.packageName}")
            })
        })
        settingsStatus = label("", 14f, true).also(content::addView)
        content.addView(button("Check now") { }.apply {
            setOnClickListener {
                if(!NotificationPolling.enabled(context) || !NotificationPolling.permitted(context)) {
                    Toast.makeText(context, "Enable checks and allow notifications first", Toast.LENGTH_SHORT).show()
                } else {
                    isEnabled = false
                    val checkButton = this
                    settingsWorker.execute {
                        runCatching { CountPoller.poll(context.applicationContext, force = true) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if(this@ChatScreen.view != null) { checkButton.isEnabled = true; refreshNotificationStatus() }
                        }
                    }
                }
            }
        })
    }

    private fun refreshNotificationStatus() {
        val context = context ?: return
        val state = NotificationPolling.state(context)
        val optimized = !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        settingsStatus?.text = buildString {
            append(if(NotificationPolling.enabled(context)) "Checks enabled" else "Checks disabled")
            append(" · ${PollInterval.label(NotificationPolling.interval(context))}\n")
            append(if(NotificationPolling.permitted(context)) "Notifications allowed" else "Notification permission needed")
            append(if(optimized) " · battery optimized" else " · battery exemption enabled")
            append("\nTiming is best effort. Force-stopping Carriex stops checks until you open it again.")
            state.getString("scheduler_error", null)?.let { append("\nScheduling: $it") }
            NotificationPolling.servers(context).forEach { server ->
                val checked = state.getLong("checked_${server.fingerprint}", 0)
                append("\n\n${server.name}: ")
                if(checked == 0L) append("not checked yet") else {
                    append("${state.getInt("count_${server.fingerprint}", 0)} pending · ")
                    append(android.text.format.DateUtils.getRelativeTimeSpanString(checked))
                }
                state.getString("error_${server.fingerprint}", null)?.let { append("\n$it") }
            }
        }
    }

    private fun offerBatteryExemption(manual: Boolean = false) {
        val context = requireContext()
        val prefs = NotificationPolling.preferences(context)
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        if(!manual && (exempt || prefs.getBoolean("battery_prompt_shown", false))) return
        prefs.edit().putBoolean("battery_prompt_shown", true).apply()
        MaterialAlertDialogBuilder(context).setTitle("Background notification checks")
            .setMessage(if(exempt) "Carriex already has a battery optimization exemption. Android and device settings can still delay checks."
                else "Allow Carriex to run without battery optimization for more reliable background checks? This may use more battery. You can keep optimization enabled; Android may delay notifications. Timing is still best effort.")
            .setNegativeButton("Keep current setting", null)
            .setPositiveButton(if(exempt) "Battery settings" else "Choose in Android") { _, _ ->
                val intent = if(exempt) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }.show()
    }

    override fun onResume() {
        super.onResume()
        if(::root.isInitialized) refreshNotificationStatus()
    }

    private fun importPairingText() {
        val body = column().apply { setPadding(dp(12), dp(4), dp(12), 0) }
        val input = field(body, "Pairing text", "") { }.apply {
            setSingleLine(false); minLines = 4; maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Import pairing text")
            .setView(body).setNegativeButton("Cancel", null).setPositiveButton("Review", null).create()
        pairingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val server = PairedServer.parse(input.text.toString())
                    dialog.dismiss()
                    confirmPairing(server)
                } catch (e: Exception) { input.error = e.message ?: "Invalid pairing text" }
            }
        }
        dialog.show()
    }

    private fun reviewPairing(text: String) {
        try { confirmPairing(PairedServer.parse(text)) }
        catch (e: Exception) { model.showError(e.message ?: "This is not a Carriex server pairing QR") }
    }

    private fun confirmPairing(server: PairedServer) {
        if (!isAdded) return
        val content = column().apply { setPadding(dp(12), dp(4), dp(12), 0) }
        content.addView(label(server.name, 22f))
        content.addView(label(server.url, 16f).apply { setTextIsSelectable(true) })
        content.addView(label("Server key", 14f, true))
        content.addView(label(server.fingerprint.chunked(8).joinToString(" "), 14f).apply { setTextIsSelectable(true) })
        content.addView(label("Save only if this QR or text came from the server owner through a trusted channel.", 14f, true))
        val problem = label("", 14f).apply { setTextColor(color(androidx.appcompat.R.attr.colorError)) }
        content.addView(problem)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Save server?")
            .setView(ScrollView(requireContext()).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save server", null).create()
        pairingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (model.saveServer(server)) {
                    scheduleNotifications()
                    if(settingsVisible) { settingsBuilt = false; render() }
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "Server saved", Toast.LENGTH_SHORT).show()
                } else problem.text = model.error
            }
        }
        dialog.show()
    }
    private fun copyId() {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Carriex user ID", model.userId))
        Toast.makeText(requireContext(), "User ID copied", Toast.LENGTH_SHORT).show()
    }

    private fun showMyIdQr() {
        val text = try { ContactQr.encode(model.userId) } catch (e: Exception) {
            model.showError(e.message ?: "Device identity is unavailable"); return
        }
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        val bitmap = BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 640, 640)
        body.addView(ImageView(requireContext()).apply {
            setImageBitmap(bitmap); contentDescription = "Your Carriex user ID QR"
            adjustViewBounds = true; setBackgroundColor(android.graphics.Color.WHITE)
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(label(model.userId.chunked(8).joinToString(" "), 14f).apply { setTextIsSelectable(true) })
        body.addView(label("Ask your friend to scan this using the pencil button > Scan QR, or the chat's three-dot menu > Verify friend's ID. This identifies this installation of Carriex.", 14f, true))
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("My ID")
            .setView(ScrollView(requireContext()).apply { addView(body) })
            .setPositiveButton("Done", null).setNeutralButton("Copy ID") { _, _ -> copyId() }
            .setNegativeButton("Share ID") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, model.userId)
                }, "Share Carriex user ID"))
            }.show()
    }

    private fun showContactId(peer: String) {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(model.contactLabel(peer), 18f))
        body.addView(label(peer, 16f).apply { setTextIsSelectable(true) })
        body.addView(label(model.contactSecurityLabel(peer), 14f, true))
        body.addView(label(model.contactKeyProtection(peer),14f,true))
        model.contactEncryptionFingerprint(peer)?.let {
            body.addView(label("Encryption key fingerprint:\n$it", 14f, true).apply { setTextIsSelectable(true) })
        }
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Contact ID")
            .setView(body).setPositiveButton("Done", null)
            .setNeutralButton("Copy ID") { _, _ ->
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Carriex contact ID", peer))
                Toast.makeText(requireContext(), "Contact ID copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Verify ID") { _, _ -> scanContact(peer) }.show()
    }

    private fun startChat(peer: String) {
        if (model.contactName(peer) == null) nameChat(peer, opening = true) else model.openChat(peer)
    }

    private fun nameChat(peer: String, opening: Boolean) {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label("Choose an optional name for ${model.contactLabel(peer)}.", 14f, true))
        val input = field(body, "Chat name (optional)", model.contactName(peer).orEmpty()) { }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(if (opening) "Name chat" else "Rename chat")
            .setView(body).setPositiveButton(if (opening) "Open chat" else "Save", null)
            .setNegativeButton(if (opening) "Skip" else "Cancel", null).create()
        fun finish(name: String) {
            if (model.setContactName(peer, name)) {
                dialog.dismiss()
                if (opening) model.openChat(peer)
            } else input.error = "Unable to save chat name"
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { finish(input.text.toString()) }
            if (opening) dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish("") }
        }
        pairingDialog = dialog
        dialog.show()
    }

    private fun scanContact(expectedId: String? = null) {
        expectedScannedPeer = expectedId
        contactScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(PortraitQrCaptureActivity::class.java)
            .setPrompt("Scan My ID QR directly from your friend's phone")
            .setBeepEnabled(false).setOrientationLocked(true))
    }

    private fun reviewContact(text: String, expectedId: String?) {
        if (!isAdded) return
        try {
            val id = ContactQr.parse(text)
            val result = ContactQr.compare(id, model.userId, expectedId)
            val dialog = MaterialAlertDialogBuilder(requireContext())
            when (result) {
                ContactQr.Result.MATCH -> dialog.setTitle("ID matches this chat")
                    .setMessage("The QR matches the device ID in this conversation. X3DH encryption keys must carry a signature from this device identity. Mark verified only if you scanned directly from your friend's phone.\n\n$id")
                    .setPositiveButton("Mark verified") { _, _ -> model.markContactVerified(id) }
                ContactQr.Result.MISMATCH -> dialog.setTitle("ID does not match")
                    .setMessage("This QR belongs to a different device ID. This conversation has not been verified. Check with your friend before continuing.\n\nChat ID:\n$expectedId\n\nScanned ID:\n$id")
                    .setPositiveButton("Close", null)
                ContactQr.Result.OWN_ID -> dialog.setTitle("This is your own ID")
                    .setMessage("Scan the QR on your friend's phone.").setPositiveButton("Close", null)
                ContactQr.Result.NEW_CONTACT -> dialog.setTitle("Chat with this device?")
                    .setMessage("Confirm this QR came from your friend's phone. Both phones must connect to the same server.\n\n$id")
                    .setNegativeButton("Cancel", null).setPositiveButton("Open chat") { currentDialog, _ ->
                        currentDialog.dismiss()
                        root.post { model.markContactVerified(id); startChat(id) }
                    }
            }
            pairingDialog = dialog.show()
        } catch (e: Exception) {
            pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Cannot read user ID")
                .setMessage(e.message).setPositiveButton("Close", null).show()
        }
    }

    private fun errorBanner() {
        root.addView(label(connectionText(), 14f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        model.error?.let { detail ->
            root.addView(label(detail, 14f).apply {
                setTextColor(color(androidx.appcompat.R.attr.colorError))
                setOnClickListener { model.dismissError() }
            })
        }
    }

    private fun connectionText() = if (model.viewedServer?.url == model.activeServer?.url)
        model.connectionStatus.label else "Offline - saved messages"

    private fun chats() {
        root.addView(actionRow().apply {
            addView(label(connectionText(), 14f, true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val server = model.viewedServer
            val connecting = model.connecting && model.activeServer?.url == server?.url
            if(server != null && !model.viewingConnectedServer) addView(
                MaterialButton(requireContext()).apply {
                    text = if(connecting) "Connecting…" else "Connect"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
                    contentDescription = "Connect to viewed server"
                    isAllCaps = false; isEnabled = !connecting
                    setOnClickListener { model.connect(server) }
                }, LinearLayout.LayoutParams(-2, dp(48)))
        })
        model.error?.let { detail -> root.addView(label(detail, 14f).apply {
            setTextColor(color(androidx.appcompat.R.attr.colorError))
            setOnClickListener { model.dismissError() }
        }) }
        val list = spacedList()
        pageScroll = ScrollView(requireContext()).apply { clipToPadding = false; addView(list) }
        pageScroll?.setPadding(0, 0, 0, dp(88))
        root.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (model.conversations.isEmpty() && model.groups.isEmpty()) {
            list.addView(label("Start a conversation", 24f))
            list.addView(label("Tap the pencil button to scan a friend's QR or import their ID. Both devices should use the same server.", 16f, true))
        }
        model.groups.sortedWith(compareByDescending<com.example.carriex.messaging.GroupConversation> { model.userId in it.state.invited }
            .thenByDescending { it.messages.lastOrNull()?.sentAt ?: 0L }).forEach { conversation ->
            val group = conversation.state
            val row = column().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                setOnClickListener { model.openGroup(group.id) }
                isFocusable = true
            }
            row.addView(label(group.name, 18f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setCompoundDrawablesRelativeWithIntrinsicBounds(menuIcon(R.drawable.ic_group), null, null, null)
                compoundDrawablePadding = dp(8)
            })
            if(model.userId in group.invited) {
                row.addView(label("${model.contactLabel(group.admin)} invited you to this group", 14f, true))
                row.addView(actionRow().apply {
                    addAction(if(conversation.joining) "Joining…" else "Join group", model.viewingConnectedServer && !conversation.joining) { model.changeGroup(group.id, "join", model.userId) }
                    addAction("Decline", model.viewingConnectedServer && !conversation.joining) { model.changeGroup(group.id, "decline", model.userId) }
                })
            } else {
                row.addView(label(when {
                    group.closed -> "Group closed · saved history"
                    model.userId !in group.members -> "No longer a member · saved history"
                    else -> "${group.members.size} members" + if(group.admin == model.userId) " · You are the admin" else ""
                }, 13f, true))
                conversation.messages.lastOrNull()?.let { latest -> row.addView(label(
                    (if(latest.from == model.userId) "You: " else "${model.contactLabel(latest.from)}: ") + String(latest.content, Charsets.UTF_8), 14f, true).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }) }
            }
            list.addView(row)
        }
        val previews = model.conversations.map { (peer, messages) ->
            val latest = messages.maxByOrNull { it.sentAt ?: Long.MIN_VALUE }
                ?.takeIf { it.sentAt != null } ?: messages.lastOrNull()
            peer to latest
        }.sortedWith(compareByDescending<Pair<String, Message?>> { it.second?.sentAt ?: Long.MIN_VALUE }.thenBy { it.first })
        previews.forEach { (peer, latest) ->
            val row = column().apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                isClickable = true; isFocusable = true
                setOnClickListener { startChat(peer) }
            }
            val count = model.unread[peer] ?: 0
            row.addView(LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(model.contactLabel(peer) + if (count > 0) "  ·  $count new" else "", 18f).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                latest?.sentAt?.let { sentAt ->
                    val date = java.util.Date(sentAt)
                    val time = android.text.format.DateFormat.getTimeFormat(requireContext()).format(date)
                    val day = android.text.format.DateFormat.getDateFormat(requireContext()).format(date)
                    val text = if (android.text.format.DateUtils.isToday(sentAt)) time else "$day, $time"
                    addView(label(text, 12f, true).apply {
                        setSingleLine(true)
                        contentDescription = "Last message sent $day, $time"
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2))
            row.addView(label(latest?.let { (if (it.from == model.userId) "You: " else "") + String(it.content, Charsets.UTF_8) } ?: "Say hello", 14f, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            list.addView(row)
        }
    }

    private fun chatSecurity() {
        val body=column().apply { setPadding(dp(12),dp(8),dp(12),dp(4)) }
        body.addView(label("Leave fallback disabled for stronger initial protection. Double Ratchet derives a new key for each message and refreshes chains when a new peer ratchet key arrives.",14f,true))
        val fallback=MaterialCheckBox(requireContext()).apply {
            text="Allow sessions without a one-time key";isChecked=model.allowFallback()
        }
        body.addView(fallback)
        MaterialAlertDialogBuilder(requireContext()).setTitle("Chat security")
            .setView(body).setPositiveButton("Done") { _,_->model.setAllowFallback(fallback.isChecked) }
            .setNeutralButton("Blocked devices") { _,_->
                val ids=model.blockedContacts()
                MaterialAlertDialogBuilder(requireContext()).setTitle("Tap a device to unblock")
                    .setItems(ids.map { model.contactLabel(it) }.toTypedArray()) { _,index ->model.unblockContact(ids[index]) }
                    .setNegativeButton("Close",null).show()
            }.setNegativeButton("Cancel",null).show()
    }

    private fun createGroup() {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label("You will be the permanent admin. Invite people from the group's menu after creating it.", 14f, true))
        val input = field(body, "Group name", "") { }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("New group").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if(name.isBlank() || name.any { it.code < 32 }) input.error = "Enter a group name"
                else { dialog.dismiss(); model.createGroup(name) }
            }
        }
        pairingDialog = dialog; dialog.show()
    }

    private fun inviteGroupMember() {
        val group = model.currentGroup?.state ?: return
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label("Paste the person's full device ID. They must use this server and accept the invitation before joining.", 14f, true))
        val input = field(body, "Device ID", "") { }.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(64)) }
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle("Invite person").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Invite", null)
        val contacts = model.conversations.keys.filter { it !in group.members && it !in group.invited }.sorted()
        if(contacts.isNotEmpty()) builder.setNeutralButton("Choose contact") { _, _ ->
            pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Invite contact")
                .setItems(contacts.map { model.contactLabel(it) }.toTypedArray()) { _, index -> model.changeGroup(group.id, "invite", contacts[index]) }
                .setNegativeButton("Cancel", null).show()
        }
        val dialog = builder.create()
        body.addView(button("Scan QR code") {
            inviteScanGroup = group.id
            inviteScanServer = model.viewedServer?.fingerprint
            dialog.dismiss()
            inviteScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                .setPrompt("Scan My ID QR from the person you want to invite")
                .setBeepEnabled(false).setOrientationLocked(true))
        })
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().trim()
                if(!ChatViewModel.validUserId(id) || id in group.members || id in group.invited) input.error = "Enter a new member's complete 64-character device ID"
                else { dialog.dismiss(); model.changeGroup(group.id, "invite", id) }
            }
        }
        pairingDialog = dialog; dialog.show()
    }

    private fun groupMembers() {
        val group = model.currentGroup?.state ?: return
        val people = group.members + group.invited
        val admin = group.admin == model.userId && !group.closed
        val entries = people.map { id -> model.contactLabel(id) + when {
            id == group.admin -> " · Admin"
            id in group.invited -> " · Invited"
            else -> ""
        } }.toTypedArray()
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("${group.name} · members")
            .setItems(entries) { _, index ->
                val peer = people[index]
                val actions = mutableListOf("Rename member")
                if(admin && peer != group.admin) actions.add("Remove")
                pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(model.contactLabel(peer))
                    .setItems(actions.toTypedArray()) { _, action ->
                        if(action == 0) renameGroupMember(peer)
                        else pairingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove ${model.contactLabel(peer)}?")
                    .setMessage(if(peer in group.invited) "Revoke this invitation?" else "Remove this person and refresh every remaining member's sender key?")
                    .setPositiveButton("Remove") { _, _ -> model.changeGroup(group.id, "remove", peer) }
                    .setNegativeButton("Cancel", null).show()
                    }.setNegativeButton("Cancel", null).show()
            }.setPositiveButton("Done", null).show()
    }

    private fun renameGroupMember(peer: String) {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label("This name is saved on this device for this server.", 14f, true))
        val input = field(body, "Member name (optional)", model.contactName(peer).orEmpty()) { }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Rename member").setView(body)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(model.setContactName(peer, input.text.toString())) { dialog.dismiss(); groupMembers() }
                else input.error = "Unable to save member name"
            }
        }
        pairingDialog = dialog; dialog.show()
    }

    private fun showRequests() {
        model.viewedServer?.let { MessageRequestNotifications.clear(requireContext(), it.fingerprint) }
        pairingDialog?.dismiss()
        val requests=model.messageRequests()
        if(requests.isEmpty()) {
            pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Message requests").setMessage("No pending requests or rejected encrypted messages.").setPositiveButton("Done",null).show();return
        }
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Message requests")
            .setItems(requests.map { "${model.contactLabel(it.peer)} · ${it.count}" }.toTypedArray()) { _,index ->
                val request=requests[index]
                pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Review request")
                    .setMessage("${request.peer}\n\n${request.reason}\n\nAccept only if you want to communicate with this device. Compare its ID with your contact.")
                    .setPositiveButton("Accept / retry") { _,_->model.acceptRequest(request.peer) }
                    .setNegativeButton("Block") { _,_->model.blockContact(request.peer) }
                    .setNeutralButton("Discard") { _,_->model.discardRequest(request.peer) }.show()
            }.setNegativeButton("Close",null).show()
    }

    private fun importContactText() {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        val input = field(body, "Recipient user ID", "") { }.apply {
            setSingleLine(false); minLines = 2; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Import user ID").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Open chat", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val text = input.text.toString().trim()
                    val id = if (text.startsWith("{")) ContactQr.parse(text) else text
                    if (id == model.userId) input.error = "This is your own ID. Import your friend's ID."
                    else if (ChatViewModel.validUserId(id)) {
                        dialog.dismiss()
                        root.post { startChat(id) }
                    }
                    else input.error = "Paste the other device's complete 64-character ID."
                } catch (e: Exception) { input.error = e.message ?: "Invalid user ID" }
            }
        }
        pairingDialog = dialog
        dialog.show()
    }

    private fun conversation() {
        val peer = model.selectedConversation ?: return
        val group = model.selectedGroup
        val groupDraft = "${model.viewedServer?.fingerprint}/$group"
        connectionLabel = label(conversationStatus(), 14f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(root::addView)
        errorLabel = label("", 14f).apply {
            setTextColor(color(androidx.appcompat.R.attr.colorError))
            setOnClickListener { model.dismissError() }
        }.also(root::addView)
        val list = spacedList()
        val scroll = ScrollView(requireContext()).apply {
            clipToPadding = false; addView(list)
            // Focus stays in the composer when messages change.
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        messageList = list; messageScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composeRow = actionRow().apply { gravity = Gravity.BOTTOM; setPadding(0, dp(4), 0, 0) }
        val input = object : TextInputEditText(requireContext()) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
                super.onCreateInputConnection(outAttrs).also {
                    outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
                }
        }.apply {
            hint = "Message"; minLines = 1; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setText(if(group != null) model.groupDrafts[groupDraft].orEmpty() else model.drafts[peer].orEmpty())
            doAfterTextChanged {
                if(group != null) model.groupDrafts[groupDraft] = it.toString() else model.drafts[peer] = it.toString()
            }
            setOnEditorActionListener { _, action, event ->
                if (event?.keyCode == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) model.send(text.toString())
                    true
                } else if (event == null && action == EditorInfo.IME_ACTION_SEND) {
                    model.send(text.toString()); true
                } else false
            }
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) model.send(text.toString())
                    true
                } else false
            }
        }
        composer = input
        composeRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = button("Send") { model.send(input.text.toString()) }
        composeRow.addView(sendButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        root.addView(composeRow)
        updateConversation(initial = true)
    }

    private fun messageRow(message: Message): MessageViews {
        if(message.type == Message.MessageType.GROUP_EVENT) {
            val text = label(groupEventText(message), 12f, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            return MessageViews(text, text, null, text, text)
        }
        val mine = message.from == model.userId
        val row = LinearLayout(requireContext()).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(if (mine) dp(20) else 0, dp(2), if (mine) 0 else dp(20), dp(2))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val bubble = column().apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(color(if (mine) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainer))
        }
        val sender = if(model.selectedGroup != null && !mine) label(model.contactLabel(message.from), 12f, true).apply {
            setPadding(0, 0, 0, dp(2)); setTypeface(typeface, Typeface.BOLD)
        }.also { bubble.addView(it, LinearLayout.LayoutParams(-2, -2)) } else null
        val body = label(String(message.content, Charsets.UTF_8)).apply {
            setPadding(0, 0, 0, 0)
            setTextColor(color(if (mine) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurface))
            setTextIsSelectable(true)
        }
        bubble.addView(body, LinearLayout.LayoutParams(-2, -2))
        val footer = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        val status = if (mine) label("", 11f, true).apply {
            gravity = Gravity.END
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }.also {
            // Reserve the longest status width so the timestamp never shifts.
            val width = kotlin.math.ceil(listOf("Sending…", "Sent", "Received").maxOf(it.paint::measureText).toDouble()).toInt()
            footer.addView(it, LinearLayout.LayoutParams(width, -2))
            footer.addView(Space(requireContext()), LinearLayout.LayoutParams(dp(6), 1))
        } else null
        val time = label("", 11f, true).apply {
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setSingleLine(true)
        }
        footer.addView(time, LinearLayout.LayoutParams(-2, -2))
        bubble.addView(footer, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (mine) Gravity.END else Gravity.START })
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        return MessageViews(row, body, status, time, footer, sender, bubble)
    }

    private fun groupEventText(message: Message): String {
        val event = org.json.JSONObject(String(message.content, Charsets.UTF_8))
        fun person(id: String, subject: Boolean = false) = if(id == model.userId) (if(subject) "You" else "you") else model.contactLabel(id)
        val actor = person(event.getString("actor"), subject = true)
        val target = person(event.getString("target"))
        return when(event.getString("action")) {
            "create" -> "$actor created the group"
            "invite" -> "$actor invited $target"
            "join" -> "$actor joined the group"
            "leave" -> "$actor left the group"
            "remove" -> {
                val previous = model.currentGroup?.events?.firstOrNull { it.state.revision == event.getInt("revision") - 1 }?.state
                if(previous != null && event.getInt("epoch") == previous.epoch) "$actor cancelled the invitation for $target"
                else "$actor removed $target from the group"
            }
            "decline" -> "$actor declined the invitation"
            "close" -> "$actor closed the group"
            else -> "Group membership updated"
        }
    }

    private fun groupTimeline(group: com.example.carriex.messaging.GroupConversation): List<Message> {
        val anchors = group.messages.mapTo(hashSetOf()) { it.id }
        val events = group.events.groupBy { it.afterMessage?.takeIf(anchors::contains) }
        fun activity(event: com.example.carriex.messaging.GroupEvent) = Message(event.state.json.getString("actor"),
            group.state.id, event.state.json.toString().toByteArray(Charsets.UTF_8), Message.MessageType.GROUP_EVENT,
            id = "group-event:${event.state.revision}")
        return buildList {
            events[null].orEmpty().forEach { add(activity(it)) }
            group.messages.forEach { message ->
                add(message)
                events[message.id].orEmpty().forEach { add(activity(it)) }
            }
        }
    }

    private fun conversationStatus(): String {
        val group = model.currentGroup ?: return connectionText()
        return when {
            model.groupProgress(group.state.id) != null -> model.groupProgress(group.state.id)!!
            group.joining -> "Joining group · waiting for admin security confirmation…"
            model.groupSending -> "Preparing message…"
            group.state.closed -> "This group is closed. Saved history is available."
            model.userId in group.state.invited -> "Accept the invitation from the chat list to join."
            model.userId !in group.state.members -> "You are no longer a member. Saved history is available."
            !model.viewingConnectedServer -> connectionText()
            group.problem != null -> "Updating group security · ${group.problem}"
            !group.ready -> "Joining group · updating security…"
            else -> "${group.state.members.size} members · ${connectionText()}"
        }
    }

    private fun updateConversation(initial: Boolean = false) {
        val peer = model.selectedConversation ?: return
        val list = messageList ?: return
        val scroll = messageScroll ?: return
        val wasAtBottom = scroll.scrollY >= list.height - scroll.height - dp(24)
        conversationTitle?.let { if (it.text.toString() != model.contactLabel(peer)) it.text = model.contactLabel(peer) }
        connectionLabel?.let { if (it.text.toString() != conversationStatus()) it.text = conversationStatus() }
        errorLabel?.apply {
            val detail = model.error.orEmpty()
            if (text.toString() != detail) text = detail
            visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
        val group = model.currentGroup
        sendButton?.isEnabled = model.viewingConnectedServer && (model.selectedGroup == null ||
            (group != null && group.ready && !group.joining && !model.groupSending && model.groupProgress(group.state.id) == null &&
                !group.state.closed && model.userId in group.state.members))
        sendButton?.text = if(model.groupSending) "Sending…" else "Send"
        composer?.let { input ->
            val draft = if(model.selectedGroup != null) model.groupDrafts["${model.viewedServer?.fingerprint}/${model.selectedGroup}"].orEmpty()
                else model.drafts[peer].orEmpty()
            if (input.text.toString() != draft) {
                input.setText(draft)
                input.setSelection(draft.length)
            }
        }
        val messages = if(model.selectedGroup != null) group?.let(::groupTimeline).orEmpty() else model.conversations[peer].orEmpty()
        val ids = messages.mapTo(hashSetOf()) { it.id }
        messageViews.keys.filter { it !in ids }.forEach { id -> list.removeView(messageViews.remove(id)!!.row) }
        if (messageViews.isEmpty()) list.removeAllViews()
        var added = false
        var sent = false
        messages.forEachIndexed { index, message ->
            val views = messageViews.getOrPut(message.id) {
                added = true
                if (message.from == model.userId) sent = true
                messageRow(message).also { list.addView(it.row) }
            }
            if(list.indexOfChild(views.row) != index) { list.removeView(views.row); list.addView(views.row, index) }
            if(message.type == Message.MessageType.GROUP_EVENT) {
                views.body.text = groupEventText(message)
                return@forEachIndexed
            }
            views.sender?.let { sender ->
                val name = model.contactLabel(message.from)
                if(sender.text.toString() != name) sender.text = name
                // Include the sender's intrinsic width when sizing a short message.
                views.bubble?.minimumWidth = minOf(kotlin.math.ceil(sender.paint.measureText(name).toDouble()).toInt() + dp(20),
                    maxOf(dp(40), resources.displayMetrics.widthPixels - dp(60)))
            }
            val body = String(message.content, Charsets.UTF_8)
            if (views.body.text.toString() != body) views.body.text = body
            val status = when (message.status) {
                Message.DeliveryStatus.SENDING -> "Sending…"
                Message.DeliveryStatus.SENT -> "Sent"
                Message.DeliveryStatus.RECEIVED -> "Received"
                Message.DeliveryStatus.EXPIRED -> "Expired"
                Message.DeliveryStatus.FAILED -> "Failed"
                null -> ""
            }
            views.status?.let { if (it.text.toString() != status) it.text = status }
            val time = message.sentAt?.let { android.text.format.DateFormat.getTimeFormat(requireContext()).format(java.util.Date(it)) }.orEmpty()
            if (views.time.text.toString() != time) {
                views.time.text = time
                views.time.contentDescription = message.sentAt?.let {
                    "Sent ${android.text.format.DateFormat.getDateFormat(requireContext()).format(java.util.Date(it))}, $time"
                }
            }
            views.footer.visibility = if (message.from == model.userId || message.sentAt != null) View.VISIBLE else View.GONE
            views.time.visibility = if (message.sentAt == null) View.GONE else View.VISIBLE
        }
        if (messages.isEmpty()) list.addView(label(if(group != null) "Say hello to ${group.state.name}" else "Say hello to ${model.contactLabel(peer)}", 16f, true))
        if (initial || (added && (wasAtBottom || sent))) scroll.post {
            if (messageScroll === scroll) scroll.scrollTo(0, maxOf(0, list.height - scroll.height))
        }
    }

    override fun onDestroyView() {
        pairingDialog?.dismiss()
        pairingDialog = null
        model.onStateChanged = null
        composer = null
        messageScroll = null; messageList = null; connectionLabel = null; errorLabel = null; sendButton = null; conversationTitle = null
        messageViews.clear()
        settingsBuilt = false; settingsStatus = null; pageScroll = null
        super.onDestroyView()
    }

    override fun onDestroy() { settingsWorker.shutdownNow(); super.onDestroy() }
}
