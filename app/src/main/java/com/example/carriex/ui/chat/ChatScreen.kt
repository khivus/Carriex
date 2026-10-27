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
import com.example.carriex.localization.UiErrors
import com.example.carriex.localization.AppLanguage
import com.example.carriex.localization.LocalizedFormat
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
                require(model.viewedServer?.fingerprint == server) { getString(R.string.ui_return_to_the_original_server_to_invite_this_person) }
                val group = model.groups.firstOrNull { it.state.id == groupId }?.state ?: error(getString(R.string.ui_group_is_unavailable))
                val peer = ContactQr.parse(result.contents)
                require(group.admin == model.userId && !group.closed) { getString(R.string.ui_only_the_group_admin_can_invite_people) }
                require(peer !in group.members && peer !in group.invited) { getString(R.string.ui_this_person_is_already_a_member_or_has_an_invitation) }
                val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_invite_to, group.name))
                    .setMessage(getString(R.string.ui_n_n_n_nthey_must_use_this_server_and_accept_the_invitation, model.contactLabel(peer), peer))
                    .setPositiveButton(getString(R.string.ui_invite), null).setNegativeButton(getString(R.string.ui_cancel), null).create()
                fun ready() = model.viewedServer?.fingerprint == server && model.viewingConnectedServer &&
                    model.groups.firstOrNull { it.state.id == groupId }?.ready == true
                dialog.setOnShowListener {
                    val invite = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    // Opening the camera backgrounds MainActivity and pauses its connection.
                    // Keep the confirmation while that connection and group keys resume.
                    refreshInviteConfirmation = {
                        invite.isEnabled = ready()
                        invite.text = if(ready()) getString(R.string.ui_invite) else getString(R.string.ui_joining_group)
                    }
                    refreshInviteConfirmation?.invoke()
                    invite.setOnClickListener {
                        if(ready()) { dialog.dismiss(); model.changeGroup(groupId, "invite", peer) }
                    }
                }
                dialog.setOnDismissListener { refreshInviteConfirmation = null }
                pairingDialog = dialog; dialog.show()
            } catch(e: Exception) {
                pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_unable_to_invite))
                    .setMessage(e.message?.let { UiErrors.text(requireContext(), it) } ?: getString(R.string.ui_invalid_contact_qr_code)).setPositiveButton(getString(R.string.ui_done), null).show()
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
    private var settingsSection: String? = null
    private var requestsVisible = false
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
    private var messageScroll: ConversationScrollView? = null
    private var messageList: LinearLayout? = null
    private var connectionLabel: TextView? = null
    private var errorLabel: TextView? = null
    private var sendButton: MaterialButton? = null
    private var conversationTitle: TextView? = null
    private data class MessageViews(val row: View, val body: TextView, val status: TextView?, val time: TextView,
        val footer: View, val sender: TextView? = null, val bubble: View? = null)
    private val messageViews = linkedMapOf<String, MessageViews>()
    private var ownBubbleColor = 0
    private var incomingBubbleColor = 0
    private var groupBubbleColors = emptyMap<String, Int>()
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
        settingsSection = savedInstanceState?.getString("settings_section")
        requestsVisible = savedInstanceState?.getBoolean("requests_page") ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("verify_contact_id", expectedScannedPeer)
        outState.putString("invite_scan_group", inviteScanGroup)
        outState.putString("invite_scan_server", inviteScanServer)
        outState.putBoolean("notifications_page", settingsVisible)
        outState.putString("settings_section", settingsSection)
        outState.putBoolean("requests_page", requestsVisible)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        toolbarMenuMode = -1
        root = column().apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        toolbar = MaterialToolbar(requireContext()).apply {
            navigationIcon = DrawerArrowDrawable(context)
            navigationContentDescription = getString(R.string.ui_open_navigation_menu)
            setNavigationOnClickListener {
                if(settingsVisible && settingsSection != null) { settingsSection = null; settingsBuilt = false; render() }
                else if(requestsVisible) { requestsVisible = false; render() }
                else if(!settingsVisible && !model.serversVisible && model.selectedConversation != null) model.showChats()
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
            drawerStatus = label(getString(R.string.ui_private_messaging), 13f, true).also(header::addView)
            addHeaderView(header)

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
        settingsVisible && settingsSection != null -> { settingsSection = null; settingsBuilt = false; render(); true }
        settingsVisible -> { settingsVisible = false; render(); true }
        requestsVisible -> { requestsVisible = false; render(); true }
        !model.serversVisible && model.selectedConversation != null -> { model.showChats(); true }
        !model.serversVisible -> { drawer.openDrawer(GravityCompat.START); true }
        else -> false
    }

    private fun render() {
        root.post { if(view != null) reportVisibleMessages() }
        refreshInviteConfirmation?.invoke()
        updateNavigation()
        if(model.connected) requestMessageAlertPermission()
        if (settingsVisible) {
            if (!settingsBuilt) { root.removeAllViews(); settingsPage(); settingsBuilt = true }
            refreshNotificationStatus()
            return
        }
        if(settingsBuilt) {
            settingsBuilt = false; messageList = null; renderedScope = null; pageScroll = null
        }
        if(requestsVisible) { root.removeAllViews(); requestsPage(); return }
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
        scroll.setPadding(0, 0, 0, dp(8))
        pageScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column().apply {
            setPadding(0, dp(8), 0, 0)
            addView(button(getString(R.string.ui_scan_server_qr)) { scanServer() })
            addView(button(getString(R.string.ui_import_pairing_text)) { importPairingText() })
        })
        content.addView(label(getString(R.string.ui_connect_to_a_server_or_view_its_saved_messages_offline_more_settings_a), 14f, true))
        model.error?.let { content.addView(label(UiErrors.text(requireContext(), it), 14f).apply { setTextColor(color(androidx.appcompat.R.attr.colorError)) }) }
        if (model.savedServers.isEmpty()) {
            content.addView(label(getString(R.string.ui_your_servers_will_appear_here), 24f))
            content.addView(label(getString(R.string.ui_tap_at_the_bottom_right_to_add_a_server_scan_the_server_owner_s_pairin), 16f, true))
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
                    contentDescription = getString(R.string.ui_options_for, server.name)
                    setOnClickListener { serverOptions(this, server) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            row.addView(label(server.url, 14f, true))
            val status = model.statusFor(server)
            row.addView(label(UiErrors.text(requireContext(), status.label), 14f, status != com.example.carriex.transport.ServerTransport.ConnectionStatus.CONNECTED).apply {
                if (status == com.example.carriex.transport.ServerTransport.ConnectionStatus.CONNECTED)
                    setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            val unread = model.unreadFor(server)
            if (unread > 0) row.addView(label(getString(R.string.ui_unread_messages, unread), 14f, true))
            if(model.autoConnectUrl == server.url) row.addView(label(getString(R.string.ui_connects_on_app_startup), 12f, true))
            row.addView(actionRow().apply {
                val current = model.activeServer?.url == server.url
                addAction(if(current && model.connecting) getString(R.string.ui_cancel) else if(current && model.connected) getString(R.string.ui_disconnect) else getString(R.string.ui_connect)) {
                    if(current && (model.connected || model.connecting)) model.disconnect() else model.connect(server)
                }.contentDescription = if(current && model.connecting) getString(R.string.ui_cancel_connection_to, server.name)
                    else if(current && model.connected) getString(R.string.ui_disconnect_from, server.name) else getString(R.string.ui_connect_to, server.name)
                addAction(getString(R.string.ui_view_messages)) { model.viewSaved(server) }.contentDescription = getString(R.string.ui_view_messages_on, server.name)
            })
            content.addView(row)
        }
    }

    private fun serverOptions(anchor: View, server: PairedServer) {
        PopupMenu(requireContext(), anchor).apply {
            setForceShowIcon(true)
            menu.add(0, 3, 2, getString(R.string.ui_connect_on_app_startup)).apply {
                icon = menuIcon(R.drawable.ic_nav_connection)
                isCheckable = true; isChecked = model.autoConnectUrl == server.url
            }
            menu.add(0, 4, 3, getString(R.string.ui_server_details)).icon = menuIcon(R.drawable.ic_nav_info)
            menu.add(0, 5, 4, getString(R.string.ui_forget_server)).icon = menuIcon(R.drawable.ic_delete)
            setOnMenuItemClickListener { item ->
                when(item.itemId) {
                    3 -> model.setAutoConnect(server, model.autoConnectUrl != server.url)
                    4 -> MaterialAlertDialogBuilder(requireContext()).setTitle(server.name)
                        .setMessage(getString(R.string.ui_n_ntrusted_server_key_n, server.url, server.fingerprint.chunked(8).joinToString(" ")))
                        .setPositiveButton(getString(R.string.ui_done), null).show()
                    5 -> MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_forget, server.name))
                        .setMessage(getString(R.string.ui_this_removes_its_saved_address_and_trusted_key_saved_messages_stay_on_))
                        .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_forget_03d5d8)) { _, _ ->
                            androidx.core.app.NotificationManagerCompat.from(requireContext()).cancel(server.fingerprint, 1)
                            model.forgetServer(server); scheduleNotifications()
                        }.show()
                }
                true
            }
            show()
        }
    }

    private fun scanServer() {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(PortraitQrCaptureActivity::class.java)
            .setPrompt(getString(R.string.ui_scan_the_pairing_qr_shown_by_your_carriex_server))
            .setBeepEnabled(false).setOrientationLocked(true))
    }

    private fun addServer() {
        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_add_server))
            .setItems(arrayOf(getString(R.string.ui_scan_server_qr), getString(R.string.ui_import_pairing_text))) { _, choice ->
                if(choice == 1) importPairingText() else scanner.launch(ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setCaptureActivity(PortraitQrCaptureActivity::class.java)
                    .setPrompt(getString(R.string.ui_scan_the_pairing_qr_shown_by_your_carriex_server)).setBeepEnabled(false).setOrientationLocked(true))
            }.setNegativeButton(getString(R.string.ui_cancel), null).show()
    }

    private fun chooseChats() {
        val server = model.viewedServer ?: model.activeServer
        if(server != null) { model.viewSaved(server); return }
        if(model.savedServers.isEmpty()) { model.showServers(); return }
        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_choose_a_server))
            .setItems(model.savedServers.map { it.name }.toTypedArray()) { _, index -> model.viewSaved(model.savedServers[index]) }
            .setNegativeButton(getString(R.string.ui_cancel), null).show()
    }

    private fun updateNavigation() {
        val conversation = !settingsVisible && !requestsVisible && !model.serversVisible && model.selectedConversation != null
        val back = conversation || requestsVisible || (settingsVisible && settingsSection != null)
        toolbar.navigationIcon = if(back) androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), R.drawable.ic_back)
            else DrawerArrowDrawable(requireContext())
        toolbar.setNavigationIconTint(ink)
        toolbar.navigationContentDescription = when {
            conversation || requestsVisible -> getString(R.string.ui_back_to_chats)
            back -> getString(R.string.ui_back_to_settings)
            else -> getString(R.string.ui_open_navigation_menu)
        }
        pageActions.showMode(when {
            settingsVisible || requestsVisible || conversation -> PageActions.NONE
            model.serversVisible -> PageActions.NONE
            else -> PageActions.CHATS
        })
        toolbar.title = when {
            settingsVisible -> settingsSection?.let { sectionTitle(it) } ?: getString(R.string.ui_settings)
            requestsVisible -> getString(R.string.ui_requests)
            model.serversVisible -> getString(R.string.ui_carriex_servers)
            model.selectedGroup != null -> model.currentGroup?.state?.name ?: getString(R.string.ui_group)
            model.selectedPeer != null -> model.contactLabel(model.selectedPeer!!)
            else -> getString(R.string.ui_chats_5e76fd, model.viewedServer?.name ?: "Carriex")
        }
        toolbar.contentDescription = toolbar.title
        val active = model.activeServer
        drawerStatus.text = if(active == null) getString(R.string.ui_private_messaging_nchoose_a_server_to_connect) else "${active.name}\n${UiErrors.text(requireContext(), model.connectionStatus.label)}"
        rebuildDrawer()
        val mode = when { settingsVisible || requestsVisible || model.serversVisible -> 0; model.selectedGroup != null -> 3; conversation -> 2; else -> 1 }
        if(toolbarMenuMode != mode) {
            toolbar.menu.clear()
            if(conversation && model.selectedGroup == null) {
                toolbar.menu.add(0, 107, 0, getString(R.string.ui_verify_chat)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            if(mode != 0) {
                toolbar.menu.add(0, R.id.page_options, 1, getString(R.string.ui_more_options)).apply {
                    icon = menuIcon(R.drawable.ic_more_vertical)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            toolbarMenuMode = mode
        }
        if(conversation && model.selectedGroup == null) {
            val verified = model.contactVerified(model.selectedPeer!!)
            toolbar.menu.findItem(107).apply {
                title = if(verified) getString(R.string.ui_chat_verified) else getString(R.string.ui_chat_not_verified)
                icon = menuIcon(if(verified) R.drawable.ic_shield_verified else R.drawable.ic_nav_shield).apply {
                    androidx.core.graphics.drawable.DrawableCompat.setTint(this,
                        if(verified) color(androidx.appcompat.R.attr.colorPrimary) else muted)
                }
            }
        }
    }

    private fun rebuildDrawer() {
        val menu = navigation.menu
        menu.clear()
        fun entry(group: Int, id: Int, title: String, icon: Int, selected: Boolean, action: () -> Unit) {
            menu.add(group, id, menu.size(), title).apply {
                setIcon(icon); isCheckable = true; isChecked = selected
                setOnMenuItemClickListener {
                    pageActions.close(false); drawer.closeDrawer(GravityCompat.START)
                    action(); true
                }
            }
        }
        entry(1, 3, getString(R.string.ui_my_id, model.userId.take(8)), R.drawable.ic_nav_person, false) { showMyIdQr() }
        entry(1, 4, getString(R.string.ui_settings), R.drawable.ic_nav_settings, settingsVisible) {
            settingsVisible = true; settingsSection = null; requestsVisible = false; settingsBuilt = false; render()
        }
        entry(2, 2, getString(R.string.ui_servers), R.drawable.ic_nav_servers, !settingsVisible && model.serversVisible) {
            settingsVisible = false; requestsVisible = false; model.showServers()
        }
        var id = 1000
        model.savedServers.forEach { server ->
            val current = !settingsVisible && !requestsVisible && !model.serversVisible && model.viewedServer?.url == server.url
            entry(2, id++, server.name, R.drawable.ic_nav_servers, current && model.selectedConversation == null) {
                settingsVisible = false; requestsVisible = false; model.navigateTo(server)
            }
            model.navigationChats(server).forEach { chat ->
                entry(2, id++, "    ${chat.label}", if(chat.id.startsWith("group:")) R.drawable.ic_group else R.drawable.ic_stat_message,
                    current && model.selectedConversation == chat.id) {
                    settingsVisible = false; requestsVisible = false; model.navigateTo(server, chat.id)
                }
            }
        }
    }

    private fun pageOptions(anchor: View) {
        val current = model.activeServer?.url == model.viewedServer?.url
        val connection = if(current && model.connecting) getString(R.string.ui_cancel_connection)
            else if(current && model.connected) getString(R.string.ui_disconnect) else getString(R.string.ui_connect)
        val entries: List<Triple<Int, String, Int>> = when(toolbarMenuMode) {
            1 -> listOf(
                Triple(203, connection, R.drawable.ic_nav_connection))
            2 -> listOf(
                Triple(101, getString(R.string.ui_contact_id), R.drawable.ic_nav_person),
                Triple(102, getString(R.string.ui_verify_friend_s_id), R.drawable.ic_scan_qr),
                Triple(103, getString(R.string.ui_rename_chat), R.drawable.ic_nav_edit),
                Triple(104, getString(R.string.ui_remove_chat), R.drawable.ic_delete),
                Triple(105, getString(R.string.ui_block_contact), R.drawable.ic_block),
                Triple(106, getString(R.string.ui_restart_secure_session), R.drawable.ic_restart))
            3 -> buildList {
                add(Triple(301, getString(R.string.ui_group_members), R.drawable.ic_group))
                val group = model.currentGroup?.state
                if(group != null && !group.closed && model.userId in group.members) {
                    if(group.admin == model.userId) {
                        add(Triple(302, getString(R.string.ui_invite_person), R.drawable.ic_nav_add))
                        add(Triple(305, getString(R.string.ui_rename_group), R.drawable.ic_nav_edit))
                        add(Triple(304, getString(R.string.ui_delete_group), R.drawable.ic_delete))
                    } else add(Triple(303, getString(R.string.ui_leave_group), R.drawable.ic_delete))
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
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_verify_chat))
            .setMessage(if(model.contactVerified(peer))
                getString(R.string.ui_this_chat_is_verified_scan_the_my_id_qr_directly_from_your_friend_s_ph)
                else getString(R.string.ui_this_chat_is_not_verified_scan_the_my_id_qr_directly_from_your_friend_))
            .setPositiveButton(getString(R.string.ui_scan_qr)) { _, _ -> scanContact(peer) }
            .setNegativeButton(getString(R.string.ui_cancel), null).show()
    }

    private fun handlePageOption(id: Int): Boolean {
        when(id) {
            R.id.page_options -> pageOptions(toolbar.findViewById<View>(R.id.page_options) ?: toolbar)
            301 -> groupMembers()
            302 -> inviteGroupMember()
            305 -> renameGroup()
            303, 304 -> {
                val group = model.currentGroup?.state ?: return false
                pairingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if(id == 304) getString(R.string.ui_delete_group_1d6345) else getString(R.string.ui_leave_group_cfcdb9))
                    .setMessage(if(id == 304) getString(R.string.ui_this_closes_the_group_for_everyone_admin_rights_cannot_be_transferred_)
                        else getString(R.string.ui_you_will_stop_receiving_messages_the_remaining_members_will_refresh_th))
                    .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(if(id == 304) getString(R.string.ui_delete) else getString(R.string.ui_leave)) { _, _ ->
                        model.changeGroup(group.id, if(id == 304) "close" else "leave", if(id == 304) "" else model.userId)
                    }.show()
            }
            201 -> showRequests()
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
                        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_remove_chat_ec0359))
                            .setMessage(getString(R.string.ui_remove_this_chat_its_saved_messages_and_draft_from_this_device_the_con))
                            .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_remove)) { _, _ ->
                                if(model.viewedServer?.fingerprint == serverScope) model.removeChat(peer)
                            }.show()
                    }
                    105 -> model.blockContact(peer)
                    106 -> MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_restart_secure_session_5d5f6a))
                        .setMessage(getString(R.string.ui_use_this_if_messages_can_no_longer_be_decrypted_after_a_long_gap_your_))
                        .setPositiveButton(getString(R.string.ui_restart)) { _, _ -> model.restartSecureSession(peer) }
                        .setNegativeButton(getString(R.string.ui_cancel), null).show()
                    107 -> promptChatVerification(peer)
                    else -> return false
                }
            }
        }
        return true
    }

    private fun scheduleNotifications() {
        runCatching { NotificationPolling.schedule(requireContext()) }.onFailure {
            model.showError(it.message ?: getString(R.string.ui_unable_to_schedule_notification_checks))
        }
    }

    fun showNotificationSettings() {
        settingsVisible = true; settingsSection = "Notifications"; settingsBuilt = false
        requestsVisible = false
        if(view != null) render()
    }

    fun showMessageRequests(server: PairedServer) {
        settingsVisible = false
        model.viewSaved(server)
        requestsVisible = true
        if(view != null) render()
    }

    private fun requestMessageAlertPermission(explicit: Boolean = false) {
        if(Build.VERSION.SDK_INT < 33 || (!MessageRequestNotifications.enabled(requireContext()) &&
                !MessageRequestNotifications.invitationsEnabled(requireContext()))) return
        val prefs = NotificationPolling.preferences(requireContext())
        if(androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED && (explicit || !prefs.getBoolean("request_alert_permission_asked", false))) {
            prefs.edit().putBoolean("request_alert_permission_asked", true).apply()
            requestAlertPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun settingsContent(): LinearLayout = spacedList().also { content ->
        settingsStatus = null
        root.addView(ScrollView(requireContext()).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun toggle(parent: LinearLayout, title: String, detail: String, key: String, default: Boolean = false,
                       changed: (Boolean) -> Unit = {}) {
        val prefs = NotificationPolling.preferences(requireContext())
        parent.addView(MaterialCheckBox(requireContext()).apply {
            text = title; isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply(); changed(checked) }
        })
        parent.addView(label(detail, 14f, true))
    }

    private fun sectionTitle(id: String): String = when(id) {
        "Chats" -> getString(R.string.ui_chats)
        "Privacy" -> getString(R.string.ui_privacy)
        "Notifications" -> getString(R.string.ui_notifications)
        "Appearance" -> getString(R.string.ui_appearance)
        else -> getString(R.string.language)
    }

    private fun settingsPage() {
        when(settingsSection) {
            "Chats" -> {
                val content = settingsContent()
                toggle(content, getString(R.string.ui_auto_accept_message_requests), getString(R.string.ui_start_chats_from_new_contacts_automatically_blocked_devices_stay_block),
                    "auto_accept_messages") { model.applyChatPreferences() }
                toggle(content, getString(R.string.ui_auto_accept_group_invitations), getString(R.string.ui_join_groups_automatically_when_invited_invitations_from_blocked_device),
                    "auto_accept_groups") { model.applyChatPreferences() }
                content.addView(label(getString(R.string.ui_both_options_apply_to_all_saved_servers_turn_them_off_to_review_new_re), 14f, true))
            }
            "Privacy" -> {
                val content = settingsContent()
                toggle(content, getString(R.string.ui_send_message_read_status), getString(R.string.ui_let_contacts_know_when_you_view_their_messages_in_a_direct_chat_off_by),
                    "send_read_status") { model.applyChatPreferences() }
                content.addView(label(getString(R.string.ui_security), 22f).apply { setTypeface(typeface, Typeface.BOLD) })
                content.addView(label(getString(R.string.ui_security_options_and_blocked_devices_apply_to, model.viewedServer?.name ?: getString(R.string.ui_the_selected_server)), 14f, true))
                content.addView(button(getString(R.string.ui_chat_security)) { chatSecurity() }.apply { isEnabled = model.viewedServer != null })
            }
            "Notifications" -> notificationsSettings()
            "Appearance" -> {
                val content = settingsContent()
                content.addView(label(getString(R.string.ui_theme), 22f).apply { setTypeface(typeface, Typeface.BOLD) })
                content.addView(button(Appearance.modes(requireContext())[Appearance.mode(requireContext())]) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_theme))
                        .setSingleChoiceItems(Appearance.modes(requireContext()), Appearance.mode(requireContext())) { dialog, index ->
                            NotificationPolling.preferences(requireContext()).edit().putInt("appearance_mode", index).apply()
                            dialog.dismiss(); requireActivity().recreate()
                        }.setNegativeButton(getString(R.string.ui_cancel), null).show()
                })
                content.addView(label(getString(R.string.ui_accent_color), 22f).apply { setTypeface(typeface, Typeface.BOLD) })
                content.addView(button(Appearance.accents(requireContext())[Appearance.accent(requireContext())]) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_accent_color))
                        .setSingleChoiceItems(Appearance.accents(requireContext()), Appearance.accent(requireContext())) { dialog, index ->
                            NotificationPolling.preferences(requireContext()).edit().putInt("appearance_accent", index).apply()
                            dialog.dismiss(); requireActivity().recreate()
                        }.setNegativeButton(getString(R.string.ui_cancel), null).show()
                })
                content.addView(label(getString(R.string.ui_message_bubbles), 22f).apply { setTypeface(typeface, Typeface.BOLD) })
                content.addView(button(getString(R.string.ui_your_messages, Appearance.bubbleLabel(requireContext(), true))) { chooseBubbleColor(true) }.apply {
                    contentDescription = getString(R.string.ui_your_message_bubble_color)
                })
                content.addView(button(getString(R.string.ui_incoming_messages, Appearance.bubbleLabel(requireContext(), false))) { chooseBubbleColor(false) }.apply {
                    contentDescription = getString(R.string.ui_incoming_message_bubble_color)
                })
                content.addView(bubblePreview(getString(R.string.ui_your_message), Appearance.bubble(requireContext(), true,
                    color(androidx.appcompat.R.attr.colorPrimary)), true))
                content.addView(bubblePreview(getString(R.string.ui_incoming_message), Appearance.bubble(requireContext(), false,
                    color(com.google.android.material.R.attr.colorSurfaceContainer)), false))
                toggle(content, getString(R.string.ui_different_colors_for_group_senders), getString(R.string.ui_automatically_give_each_sender_a_different_bubble_color_in_groups_colo),
                    Appearance.GROUP_COLORS)
            }
            else -> {
                val content = settingsContent()
                content.addView(button(getString(R.string.language)) {
                    val choices = arrayOf(getString(R.string.language_system), getString(R.string.language_en), getString(R.string.language_ru))
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.language)
                        .setSingleChoiceItems(choices, AppLanguage.tags.indexOf(AppLanguage.selected(requireContext())).coerceAtLeast(0)) { dialog, index ->
                            dialog.dismiss()
                            AppLanguage.set(requireContext(), AppLanguage.tags[index])
                            NotificationPolling.channels(requireContext())
                            if (NotificationPolling.enabled(requireContext()) && NotificationPolling.interval(requireContext()) < 900_000L)
                                NotificationPolling.schedule(requireContext())
                        }.setNegativeButton(R.string.ui_cancel, null).show()
                })
                content.addView(label(getString(R.string.language_detail), 14f, true))
                listOf("Chats" to getString(R.string.ui_message_requests_and_group_invitations), "Privacy" to getString(R.string.ui_chat_security_blocked_devices_and_read_status),
                    "Notifications" to getString(R.string.ui_request_alerts_and_background_checks), "Appearance" to getString(R.string.ui_theme_and_accent_color)).forEach { (title, detail) ->
                    content.addView(button(sectionTitle(title)) { settingsSection = title; settingsBuilt = false; render() })
                    content.addView(label(detail, 14f, true))
                }
            }
        }
    }

    private fun bubblePreview(text: String, fill: Int, own: Boolean) = label(text, 14f).apply {
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(fill)
        setTextColor(BubbleColors.foreground(fill))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if(own) Gravity.END else Gravity.START
            topMargin = dp(6); bottomMargin = dp(6)
        }
    }

    private fun chooseBubbleColor(own: Boolean) {
        val title = if(own) getString(R.string.ui_your_message_bubble_color) else getString(R.string.ui_incoming_message_bubble_color)
        val presets = BubbleColors.presets.entries.toList()
        fun save(value: String?) {
            val edit = NotificationPolling.preferences(requireContext()).edit()
            val key = if(own) Appearance.OWN_BUBBLE else Appearance.INCOMING_BUBBLE
            if(value == null) edit.remove(key) else edit.putString(key, value)
            edit.apply(); settingsBuilt = false; render()
        }
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setItems((listOf(if(own) getString(R.string.ui_use_accent_color_default) else getString(R.string.ui_use_theme_surface_default)) +
                presets.map { Appearance.colorName(requireContext(), it.key) } + getString(R.string.ui_custom_color)).toTypedArray()) { _, index ->
                when(index) {
                    0 -> save(null)
                    in 1..presets.size -> save(presets[index - 1].value)
                    else -> {
                        val initial = Appearance.bubble(requireContext(), own, color(if(own) androidx.appcompat.R.attr.colorPrimary
                            else com.google.android.material.R.attr.colorSurfaceContainer))
                        val body = column().apply { setPadding(dp(16), dp(6), dp(16), dp(4)) }
                        val preview = bubblePreview(getString(R.string.ui_message_preview), initial, own)
                        val input = field(body, getString(R.string.ui_hex_color_rrggbb), BubbleColors.hex(initial)) { value ->
                            BubbleColors.parse(value)?.let { fill -> preview.background = rounded(fill); preview.setTextColor(BubbleColors.foreground(fill)) }
                        }.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(7)) }
                        body.addView(preview)
                        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(body)
                            .setPositiveButton(getString(R.string.ui_save), null).setNegativeButton(getString(R.string.ui_cancel), null).create()
                        dialog.setOnShowListener {
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val fill = BubbleColors.parse(input.text.toString())
                                if(fill == null) input.error = getString(R.string.ui_enter_six_hex_digits_for_example_1565c0)
                                else { dialog.dismiss(); save(BubbleColors.hex(fill)) }
                            }
                        }
                        pairingDialog = dialog; dialog.show()
                    }
                }
            }.setNegativeButton(getString(R.string.ui_cancel), null).show()
    }

    private fun notificationsSettings() {
        val context = requireContext()
        val prefs = NotificationPolling.preferences(context)
        val content = settingsContent()
        content.addView(label(getString(R.string.ui_request_alerts), 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(MaterialCheckBox(context).apply {
            text = getString(R.string.ui_alert_when_someone_tries_to_contact_me)
            isChecked = MessageRequestNotifications.enabled(context)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("message_request_alerts", enabled).apply()
                if(enabled) {
                    requestMessageAlertPermission(explicit = true)
                    model.notifyPendingMessageRequests()
                } else MessageRequestNotifications.clearMessages(context)
            }
        })
        content.addView(label(getString(R.string.ui_new_contact_requests_notify_you_even_while_carriex_is_open_tap_the_not), 15f, true))
        toggle(content, getString(R.string.ui_group_invitation_alerts), getString(R.string.ui_notify_when_you_receive_a_group_invitation_including_while_carriex_is_),
            "group_invitation_alerts", true) { enabled ->
            if(enabled) { requestMessageAlertPermission(explicit = true); model.notifyPendingMessageRequests() }
            else MessageRequestNotifications.clearGroupAlerts(requireContext())
        }
        content.addView(label(getString(R.string.ui_background_checks), 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(label(getString(R.string.ui_get_a_notification_when_a_saved_server_has_pending_messages_checks_fet), 15f, true))
        content.addView(MaterialCheckBox(context).apply {
            text = getString(R.string.ui_check_for_pending_messages)
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
        content.addView(label(getString(R.string.ui_check_interval), 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        val millis = NotificationPolling.interval(context)
        val units = longArrayOf(1000, 60_000, 3_600_000)
        val unitIndex = prefs.getInt("notification_interval_unit", if(millis % 60_000 == 0L) 1 else 0).coerceIn(0, 2)
        val storedInput = prefs.getString("notification_interval_input", null)
        val current = storedInput?.takeIf { runCatching { PollInterval.parse(it, units[unitIndex]) == millis }.getOrDefault(false) }
            ?: java.math.BigDecimal.valueOf(millis).divide(java.math.BigDecimal.valueOf(units[unitIndex]), java.math.MathContext.DECIMAL64).stripTrailingZeros().toPlainString()
        val intervalRow = actionRow()
        val intervalBox = column()
        val input = field(intervalBox, getString(R.string.ui_custom_interval), current) { }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        intervalRow.addView(intervalBox, LinearLayout.LayoutParams(0, -2, 1f))
        val unit = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, arrayOf(getString(R.string.ui_seconds), getString(R.string.ui_minutes), getString(R.string.ui_hours)))
            setSelection(unitIndex)
            contentDescription = getString(R.string.ui_interval_unit)
        }
        intervalRow.addView(unit, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
        content.addView(intervalRow)
        content.addView(label(getString(R.string.ui_default_15_minutes_enter_any_custom_interval_of_at_least_one_second_in), 14f, true))
        content.addView(button(getString(R.string.ui_apply_interval)) {
            try {
                val value = PollInterval.parse(input.text.toString(), units[unit.selectedItemPosition])
                prefs.edit().putLong("notification_interval", value).putInt("notification_interval_unit", unit.selectedItemPosition)
                    .putString("notification_interval_input", input.text.toString().trim()).apply()
                input.error = null
                scheduleNotifications(); refreshNotificationStatus()
                Toast.makeText(context, getString(R.string.ui_checks_every, LocalizedFormat.interval(requireContext(), value)), Toast.LENGTH_SHORT).show()
            } catch(e: IllegalArgumentException) { input.error = UiErrors.text(requireContext(), e.message) }
        })
        content.addView(label(getString(R.string.ui_servers_to_check), 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        if(model.savedServers.isEmpty()) content.addView(label(getString(R.string.ui_add_a_server_from_the_servers_view_first), 14f, true))
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
        content.addView(label(getString(R.string.ui_android_settings), 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(button(getString(R.string.ui_battery_optimization)) { offerBatteryExemption(manual = true) })
        content.addView(button(getString(R.string.ui_notification_permission_channels)) {
            startActivity(Intent(if(Build.VERSION.SDK_INT >= 26) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                if(Build.VERSION.SDK_INT >= 26) putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else data = Uri.parse("package:${context.packageName}")
            })
        })
        settingsStatus = label("", 14f, true).also(content::addView)
        content.addView(button(getString(R.string.ui_check_now)) { }.apply {
            setOnClickListener {
                if(!NotificationPolling.enabled(context) || !NotificationPolling.permitted(context)) {
                    Toast.makeText(context, getString(R.string.ui_enable_checks_and_allow_notifications_first), Toast.LENGTH_SHORT).show()
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
            append(if(NotificationPolling.enabled(context)) getString(R.string.ui_checks_enabled) else getString(R.string.ui_checks_disabled))
            append(" · ${LocalizedFormat.interval(requireContext(), NotificationPolling.interval(context))}\n")
            append(if(NotificationPolling.permitted(context)) getString(R.string.ui_notifications_allowed) else getString(R.string.ui_notification_permission_needed))
            append(if(optimized) getString(R.string.ui_battery_optimized) else getString(R.string.ui_battery_exemption_enabled))
            append(getString(R.string.ui_ntiming_is_best_effort_force_stopping_carriex_stops_checks_until_you_o))
            state.getString("scheduler_error", null)?.let { append(getString(R.string.ui_nscheduling, UiErrors.text(context, it))) }
            NotificationPolling.servers(context).forEach { server ->
                val checked = state.getLong("checked_${server.fingerprint}", 0)
                append("\n\n${server.name}: ")
                if(checked == 0L) append(getString(R.string.ui_not_checked_yet)) else {
                    append(getString(R.string.ui_0_pending, state.getInt("count_${server.fingerprint}", 0)))
                    append(android.text.format.DateUtils.getRelativeTimeSpanString(checked))
                }
                state.getString("error_${server.fingerprint}", null)?.let { append("\n${UiErrors.text(context, it)}") }
            }
        }
    }

    private fun offerBatteryExemption(manual: Boolean = false) {
        val context = requireContext()
        val prefs = NotificationPolling.preferences(context)
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        if(!manual && (exempt || prefs.getBoolean("battery_prompt_shown", false))) return
        prefs.edit().putBoolean("battery_prompt_shown", true).apply()
        MaterialAlertDialogBuilder(context).setTitle(getString(R.string.ui_background_notification_checks))
            .setMessage(if(exempt) getString(R.string.ui_carriex_already_has_a_battery_optimization_exemption_android_and_devic)
                else getString(R.string.ui_allow_carriex_to_run_without_battery_optimization_for_more_reliable_ba))
            .setNegativeButton(getString(R.string.ui_keep_current_setting), null)
            .setPositiveButton(if(exempt) getString(R.string.ui_battery_settings) else getString(R.string.ui_choose_in_android)) { _, _ ->
                val intent = if(exempt) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }.show()
    }

    override fun onResume() {
        super.onResume()
        if(::root.isInitialized) {
            refreshNotificationStatus()
            root.post { if(view != null) reportVisibleMessages() }
        }
    }

    private fun importPairingText() {
        val body = column().apply { setPadding(dp(12), dp(4), dp(12), 0) }
        val input = field(body, getString(R.string.ui_pairing_text), "") { }.apply {
            setSingleLine(false); minLines = 4; maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(8192))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_import_pairing_text))
            .setView(body).setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_review), null).create()
        pairingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val server = PairedServer.parse(input.text.toString())
                    dialog.dismiss()
                    confirmPairing(server)
                } catch (e: Exception) { input.error = e.message?.let { UiErrors.text(requireContext(), it) } ?: getString(R.string.ui_invalid_pairing_text) }
            }
        }
        dialog.show()
    }

    private fun reviewPairing(text: String) {
        try { confirmPairing(PairedServer.parse(text)) }
        catch (e: Exception) { model.showError(e.message ?: getString(R.string.ui_this_is_not_a_carriex_server_pairing_qr)) }
    }

    private fun confirmPairing(server: PairedServer) {
        if (!isAdded) return
        val content = column().apply { setPadding(dp(12), dp(4), dp(12), 0) }
        content.addView(label(server.name, 22f))
        content.addView(label(server.url, 16f).apply { setTextIsSelectable(true) })
        content.addView(label(getString(R.string.ui_server_key), 14f, true))
        content.addView(label(server.fingerprint.chunked(8).joinToString(" "), 14f).apply { setTextIsSelectable(true) })
        content.addView(label(getString(R.string.ui_save_only_if_this_qr_or_text_came_from_the_server_owner_through_a_trus), 14f, true))
        val problem = label("", 14f).apply { setTextColor(color(androidx.appcompat.R.attr.colorError)) }
        content.addView(problem)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_save_server))
            .setView(ScrollView(requireContext()).apply { addView(content) })
            .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_save_server_b05e2f), null).create()
        pairingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (model.saveServer(server)) {
                    scheduleNotifications()
                    if(settingsVisible) { settingsBuilt = false; render() }
                    dialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.ui_server_saved), Toast.LENGTH_SHORT).show()
                } else problem.text = UiErrors.text(requireContext(), model.error)
            }
        }
        dialog.show()
    }
    private fun copyId() {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.ui_carriex_user_id), model.userId))
        Toast.makeText(requireContext(), getString(R.string.ui_user_id_copied), Toast.LENGTH_SHORT).show()
    }

    private fun showMyIdQr() {
        val text = try { ContactQr.encode(model.userId) } catch (e: Exception) {
            model.showError(e.message ?: getString(R.string.ui_device_identity_is_unavailable)); return
        }
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        val bitmap = BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 640, 640)
        body.addView(ImageView(requireContext()).apply {
            setImageBitmap(bitmap); contentDescription = getString(R.string.ui_your_carriex_user_id_qr)
            adjustViewBounds = true; setBackgroundColor(android.graphics.Color.WHITE)
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(label(model.userId.chunked(8).joinToString(" "), 14f).apply { setTextIsSelectable(true) })
        body.addView(label(getString(R.string.ui_ask_your_friend_to_scan_this_using_the_pencil_button_scan_qr_or_the_ch), 14f, true))
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_my_id_54c534))
            .setView(ScrollView(requireContext()).apply { addView(body) })
            .setPositiveButton(getString(R.string.ui_done), null).setNeutralButton(getString(R.string.ui_copy_id)) { _, _ -> copyId() }
            .setNegativeButton(getString(R.string.ui_share_id)) { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, model.userId)
                }, getString(R.string.ui_share_carriex_user_id)))
            }.show()
    }

    private fun showContactId(peer: String) {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(model.contactLabel(peer), 18f))
        body.addView(label(peer, 16f).apply { setTextIsSelectable(true) })
        body.addView(label(UiErrors.text(requireContext(), model.contactSecurityLabel(peer)), 14f, true))
        body.addView(label(UiErrors.text(requireContext(), model.contactKeyProtection(peer)),14f,true))
        model.contactEncryptionFingerprint(peer)?.let {
            body.addView(label(getString(R.string.ui_encryption_key_fingerprint_n, it), 14f, true).apply { setTextIsSelectable(true) })
        }
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_contact_id))
            .setView(body).setPositiveButton(getString(R.string.ui_done), null)
            .setNeutralButton(getString(R.string.ui_copy_id)) { _, _ ->
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.ui_carriex_contact_id), peer))
                Toast.makeText(requireContext(), getString(R.string.ui_contact_id_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.ui_verify_id)) { _, _ -> scanContact(peer) }.show()
    }

    private fun startChat(peer: String) {
        if (model.contactName(peer) == null) nameChat(peer, opening = true) else model.openChat(peer)
    }

    private fun nameChat(peer: String, opening: Boolean) {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(getString(R.string.ui_choose_an_optional_name_for, model.contactLabel(peer)), 14f, true))
        val input = field(body, getString(R.string.ui_chat_name_optional), model.contactName(peer).orEmpty()) { }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(if (opening) getString(R.string.ui_name_chat) else getString(R.string.ui_rename_chat))
            .setView(body).setPositiveButton(if (opening) getString(R.string.ui_open_chat) else getString(R.string.ui_save), null)
            .setNegativeButton(if (opening) getString(R.string.ui_skip) else getString(R.string.ui_cancel), null).create()
        fun finish(name: String) {
            if (model.setContactName(peer, name)) {
                dialog.dismiss()
                if (opening) model.openChat(peer)
            } else input.error = getString(R.string.ui_unable_to_save_chat_name)
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
            .setPrompt(getString(R.string.ui_scan_my_id_qr_directly_from_your_friend_s_phone))
            .setBeepEnabled(false).setOrientationLocked(true))
    }

    private fun reviewContact(text: String, expectedId: String?) {
        if (!isAdded) return
        try {
            val id = ContactQr.parse(text)
            val result = ContactQr.compare(id, model.userId, expectedId)
            val dialog = MaterialAlertDialogBuilder(requireContext())
            when (result) {
                ContactQr.Result.MATCH -> dialog.setTitle(getString(R.string.ui_id_matches_this_chat))
                    .setMessage(getString(R.string.ui_the_qr_matches_the_device_id_in_this_conversation_x3dh_encryption_keys, id))
                    .setPositiveButton(getString(R.string.ui_mark_verified)) { _, _ -> model.markContactVerified(id) }
                ContactQr.Result.MISMATCH -> dialog.setTitle(getString(R.string.ui_id_does_not_match))
                    .setMessage(getString(R.string.ui_this_qr_belongs_to_a_different_device_id_this_conversation_has_not_bee, expectedId, id))
                    .setPositiveButton(getString(R.string.ui_close), null)
                ContactQr.Result.OWN_ID -> dialog.setTitle(getString(R.string.ui_this_is_your_own_id))
                    .setMessage(getString(R.string.ui_scan_the_qr_on_your_friend_s_phone)).setPositiveButton(getString(R.string.ui_close), null)
                ContactQr.Result.NEW_CONTACT -> dialog.setTitle(getString(R.string.ui_chat_with_this_device))
                    .setMessage(getString(R.string.ui_confirm_this_qr_came_from_your_friend_s_phone_both_phones_must_connect, id))
                    .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_open_chat)) { currentDialog, _ ->
                        currentDialog.dismiss()
                        root.post { model.markContactVerified(id); startChat(id) }
                    }
            }
            pairingDialog = dialog.show()
        } catch (e: Exception) {
            pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_cannot_read_user_id))
                .setMessage(UiErrors.text(requireContext(), e.message)).setPositiveButton(getString(R.string.ui_close), null).show()
        }
    }

    private fun errorBanner() {
        root.addView(label(connectionText(), 14f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        model.error?.let { detail ->
            root.addView(label(UiErrors.text(requireContext(), detail), 14f).apply {
                setTextColor(color(androidx.appcompat.R.attr.colorError))
                setOnClickListener { model.dismissError() }
            })
        }
    }

    private fun connectionText() = if (model.viewedServer?.url == model.activeServer?.url)
        UiErrors.text(requireContext(), model.connectionStatus.label) else getString(R.string.ui_offline_saved_messages)

    private fun chats() {
        root.addView(actionRow().apply {
            addView(label(connectionText(), 14f, true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val server = model.viewedServer
            val connecting = model.connecting && model.activeServer?.url == server?.url
            if(server != null && !model.viewingConnectedServer) addView(
                MaterialButton(requireContext()).apply {
                    text = if(connecting) getString(R.string.ui_connecting) else getString(R.string.ui_connect)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
                    contentDescription = getString(R.string.ui_connect_to_viewed_server)
                    isAllCaps = false; isEnabled = !connecting
                    setOnClickListener { model.connect(server) }
                }, LinearLayout.LayoutParams(-2, dp(48)))
        })
        model.error?.let { detail -> root.addView(label(UiErrors.text(requireContext(), detail), 14f).apply {
            setTextColor(color(androidx.appcompat.R.attr.colorError))
            setOnClickListener { model.dismissError() }
        }) }
        val list = spacedList()
        pageScroll = ScrollView(requireContext()).apply { clipToPadding = false; addView(list) }
        pageScroll?.setPadding(0, 0, 0, dp(88))
        root.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val requests = model.messageRequests().sumOf { it.count }
        val invitations = model.groups.count { model.userId in it.state.invited && !it.state.closed }
        if(requests > 0 || invitations > 0)
            list.addView(button(getString(R.string.ui_requests_messages_groups, requests, invitations)) { showRequests() })
        if (model.conversations.isEmpty() && model.groups.isEmpty()) {
            list.addView(label(getString(R.string.ui_start_a_conversation), 24f))
            list.addView(label(getString(R.string.ui_tap_the_pencil_button_to_scan_a_friend_s_qr_or_import_their_id_both_de), 16f, true))
        }
        val rows = linkedMapOf<String, View>()
        model.groups.filter { model.userId !in it.state.invited }.forEach { conversation ->
            val group = conversation.state
            val row = column().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                setOnClickListener { model.openGroup(group.id) }
                isFocusable = true
            }
            row.addView(actionRow().apply {
                addView(label(group.name, 18f).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(menuIcon(R.drawable.ic_group), null, null, null)
                    compoundDrawablePadding = dp(8)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addMessageTime(this, com.example.carriex.messaging.ConversationSummary.latest(conversation.messages)?.sentAt)
            })
            if(model.userId in group.invited) {
                row.addView(label(getString(R.string.ui_invited_you_to_this_group, model.contactLabel(group.admin)), 14f, true))
                row.addView(actionRow().apply {
                    addAction(if(conversation.joining) getString(R.string.ui_joining) else getString(R.string.ui_join_group), model.viewingConnectedServer && !conversation.joining) { model.changeGroup(group.id, "join", model.userId) }
                    addAction(getString(R.string.ui_decline), model.viewingConnectedServer && !conversation.joining) { model.changeGroup(group.id, "decline", model.userId) }
                })
            } else {
                row.addView(label(when {
                    group.closed -> getString(R.string.ui_group_closed_saved_history)
                    model.userId !in group.members -> getString(R.string.ui_no_longer_a_member_saved_history)
                    else -> getString(R.string.ui_members, group.members.size) + if(group.admin == model.userId) getString(R.string.ui_you_are_the_admin) else ""
                }, 13f, true))
                com.example.carriex.messaging.ConversationSummary.latest(conversation.messages)?.let { latest -> row.addView(label(
                    (if(latest.from == model.userId) getString(R.string.ui_you) else "${model.contactLabel(latest.from)}: ") + String(latest.content, Charsets.UTF_8), 14f, true).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }) }
            }
            rows["group:${group.id}"] = row
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
                addView(label(model.contactLabel(peer) + if (count > 0) getString(R.string.ui_new, count) else "", 18f).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addMessageTime(this, latest?.sentAt)
            }, LinearLayout.LayoutParams(-1, -2))
            row.addView(label(latest?.let { (if (it.from == model.userId) getString(R.string.ui_you) else "") + String(it.content, Charsets.UTF_8) } ?: getString(R.string.ui_say_hello), 14f, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            rows[peer] = row
        }
        model.viewedServer?.let { server -> model.navigationChats(server).forEach { summary -> rows[summary.id]?.let(list::addView) } }
    }

    private fun addMessageTime(row: LinearLayout, sentAt: Long?) {
        if(sentAt == null) return
        val date = java.util.Date(sentAt)
        val time = android.text.format.DateFormat.getTimeFormat(requireContext()).format(date)
        val day = android.text.format.DateFormat.getDateFormat(requireContext()).format(date)
        row.addView(label(if(android.text.format.DateUtils.isToday(sentAt)) time else "$day, $time", 12f, true).apply {
            setSingleLine(true); contentDescription = getString(R.string.ui_last_message_sent, day, time)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    private fun chatSecurity() {
        val body=column().apply { setPadding(dp(12),dp(8),dp(12),dp(4)) }
        body.addView(label(getString(R.string.ui_leave_fallback_disabled_for_stronger_initial_protection_double_ratchet),14f,true))
        val fallback=MaterialCheckBox(requireContext()).apply {
            text=getString(R.string.ui_allow_sessions_without_a_one_time_key);isChecked=model.allowFallback()
        }
        body.addView(fallback)
        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_chat_security))
            .setView(body).setPositiveButton(getString(R.string.ui_done)) { _,_->model.setAllowFallback(fallback.isChecked) }
            .setNeutralButton(getString(R.string.ui_blocked_devices)) { _,_->
                val ids=model.blockedContacts()
                MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_tap_a_device_to_unblock))
                    .setItems(ids.map { model.contactLabel(it) }.toTypedArray()) { _,index ->model.unblockContact(ids[index]) }
                    .setNegativeButton(getString(R.string.ui_close),null).show()
            }.setNegativeButton(getString(R.string.ui_cancel),null).show()
    }

    private fun createGroup() {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(getString(R.string.ui_you_will_be_the_permanent_admin_invite_people_from_the_group_s_menu_af), 14f, true))
        val input = field(body, getString(R.string.ui_group_name), "") { }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_new_group)).setView(body)
            .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_create), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if(name.isBlank() || name.any { it.code < 32 }) input.error = getString(R.string.ui_enter_a_group_name)
                else { dialog.dismiss(); model.createGroup(name) }
            }
        }
        pairingDialog = dialog; dialog.show()
    }

    private fun renameGroup() {
        val group = model.currentGroup?.state ?: return
        val server = model.viewedServer?.fingerprint ?: return
        if(group.admin != model.userId || group.closed) return
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(getString(R.string.ui_the_new_name_will_be_visible_to_everyone_in_this_group), 14f, true))
        val input = field(body, getString(R.string.ui_group_name), group.name) { }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(160))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_rename_group)).setView(body)
            .setPositiveButton(getString(R.string.ui_save), null).setNegativeButton(getString(R.string.ui_cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                when {
                    name.isBlank() || name.codePointCount(0, name.length) > 80 || name.any { it.code < 32 } ->
                        input.error = getString(R.string.ui_enter_a_group_name_of_1_to_80_characters)
                    model.viewedServer?.fingerprint != server || model.selectedGroup != group.id -> dialog.dismiss()
                    !model.viewingConnectedServer -> input.error = getString(R.string.ui_connect_to_this_server_before_renaming_the_group)
                    else -> {
                        dialog.dismiss()
                        if(name != group.name) model.changeGroup(group.id, "rename", name)
                    }
                }
            }
        }
        pairingDialog = dialog; dialog.show()
    }

    private fun inviteGroupMember() {
        val group = model.currentGroup?.state ?: return
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(getString(R.string.ui_paste_the_person_s_full_device_id_they_must_use_this_server_and_accept), 14f, true))
        val input = field(body, getString(R.string.ui_device_id), "") { }.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(64)) }
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_invite_person)).setView(body)
            .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_invite), null)
        val contacts = model.conversations.keys.filter { it !in group.members && it !in group.invited }.sorted()
        if(contacts.isNotEmpty()) builder.setNeutralButton(getString(R.string.ui_choose_contact)) { _, _ ->
            pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_invite_contact))
                .setItems(contacts.map { model.contactLabel(it) }.toTypedArray()) { _, index -> model.changeGroup(group.id, "invite", contacts[index]) }
                .setNegativeButton(getString(R.string.ui_cancel), null).show()
        }
        val dialog = builder.create()
        body.addView(button(getString(R.string.ui_scan_qr_code)) {
            inviteScanGroup = group.id
            inviteScanServer = model.viewedServer?.fingerprint
            dialog.dismiss()
            inviteScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                .setPrompt(getString(R.string.ui_scan_my_id_qr_from_the_person_you_want_to_invite))
                .setBeepEnabled(false).setOrientationLocked(true))
        })
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().trim()
                if(!ChatViewModel.validUserId(id) || id in group.members || id in group.invited) input.error = getString(R.string.ui_enter_a_new_member_s_complete_64_character_device_id)
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
            id == group.admin -> getString(R.string.ui_admin)
            id in group.invited -> getString(R.string.ui_invited)
            else -> ""
        } }.toTypedArray()
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_members_a37bc2, group.name))
            .setItems(entries) { _, index ->
                val peer = people[index]
                val actions = mutableListOf(getString(R.string.ui_rename_member))
                if(admin && peer != group.admin) actions.add(getString(R.string.ui_remove))
                pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(model.contactLabel(peer))
                    .setItems(actions.toTypedArray()) { _, action ->
                        if(action == 0) renameGroupMember(peer)
                        else pairingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.ui_remove_62f82a, model.contactLabel(peer)))
                    .setMessage(if(peer in group.invited) getString(R.string.ui_revoke_this_invitation) else getString(R.string.ui_remove_this_person_and_refresh_every_remaining_member_s_sender_key))
                    .setPositiveButton(getString(R.string.ui_remove)) { _, _ -> model.changeGroup(group.id, "remove", peer) }
                    .setNegativeButton(getString(R.string.ui_cancel), null).show()
                    }.setNegativeButton(getString(R.string.ui_cancel), null).show()
            }.setPositiveButton(getString(R.string.ui_done), null).show()
    }

    private fun renameGroupMember(peer: String) {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(getString(R.string.ui_this_name_is_saved_on_this_device_for_this_server), 14f, true))
        val input = field(body, getString(R.string.ui_member_name_optional), model.contactName(peer).orEmpty()) { }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_rename_member)).setView(body)
            .setPositiveButton(getString(R.string.ui_save), null).setNegativeButton(getString(R.string.ui_cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(model.setContactName(peer, input.text.toString())) { dialog.dismiss(); groupMembers() }
                else input.error = getString(R.string.ui_unable_to_save_member_name)
            }
        }
        pairingDialog = dialog; dialog.show()
    }

    private fun showRequests() {
        requestsVisible = true; settingsVisible = false
        render()
    }

    private fun requestsPage() {
        messageList = null; pageScroll = null; renderedScope = null
        val serverScope = model.viewedServer?.fingerprint ?: return
        MessageRequestNotifications.clear(requireContext(), serverScope)
        MessageRequestNotifications.dismissInvitations(requireContext(), serverScope)
        val content = settingsContent()
        model.error?.let { content.addView(label(UiErrors.text(requireContext(), it), 14f, true)) }
        content.addView(label(getString(R.string.ui_message_requests), 22f).apply { setTypeface(typeface, Typeface.BOLD) })
        val requests = model.messageRequests()
        if(requests.isEmpty()) content.addView(label(getString(R.string.ui_no_message_requests), 14f, true))
        requests.forEach { request ->
            content.addView(button("${model.contactLabel(request.peer)} (${request.count})") {
                pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_review_message_request))
                    .setMessage(getString(R.string.ui_n_n_n_ncompare_this_id_with_your_contact_before_accepting, request.peer, UiErrors.text(requireContext(), request.reason)))
                    .setPositiveButton(getString(R.string.ui_accept_retry)) { _, _ ->
                        if(model.viewedServer?.fingerprint == serverScope && model.viewingConnectedServer) {
                            requestsVisible = false; model.acceptRequest(request.peer)
                        } else model.showError(getString(R.string.ui_connect_to_this_server_before_accepting_a_request))
                    }
                    .setNegativeButton(getString(R.string.ui_block)) { _, _ -> if(model.viewedServer?.fingerprint == serverScope) model.blockContact(request.peer) }
                    .setNeutralButton(getString(R.string.ui_discard)) { _, _ -> if(model.viewedServer?.fingerprint == serverScope) model.discardRequest(request.peer) }.show()
            })
        }
        content.addView(label(getString(R.string.ui_group_invitations), 22f).apply { setTypeface(typeface, Typeface.BOLD) })
        val invitations = model.groups.filter { model.userId in it.state.invited && !it.state.closed }
        if(invitations.isEmpty()) content.addView(label(getString(R.string.ui_no_group_invitations), 14f, true))
        invitations.forEach { invitation ->
            val group = invitation.state
            content.addView(label(group.name, 18f).apply { setTypeface(typeface, Typeface.BOLD) })
            content.addView(label(getString(R.string.ui_invited_by, model.contactLabel(group.admin)), 14f, true))
            content.addView(actionRow().apply {
                addAction(if(invitation.joining || model.groupProgress(group.id) != null) getString(R.string.ui_joining_757a26) else getString(R.string.ui_join_group),
                    model.viewingConnectedServer && !invitation.joining && model.groupProgress(group.id) == null) {
                    requestsVisible = false; model.changeGroup(group.id, "join", model.userId)
                }
                addAction(getString(R.string.ui_decline), model.viewingConnectedServer && !invitation.joining && model.groupProgress(group.id) == null) {
                    model.changeGroup(group.id, "decline", model.userId)
                }
            })
        }
        if(!model.viewingConnectedServer) content.addView(button(getString(R.string.ui_connect_to_review_requests)) {
            model.viewedServer?.let { model.connect(it) }
        })
    }

    private fun importContactText() {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        val input = field(body, getString(R.string.ui_recipient_user_id), "") { }.apply {
            setSingleLine(false); minLines = 2; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(1024))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.ui_import_user_id)).setView(body)
            .setNegativeButton(getString(R.string.ui_cancel), null).setPositiveButton(getString(R.string.ui_open_chat), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val text = input.text.toString().trim()
                    val id = if (text.startsWith("{")) ContactQr.parse(text) else text
                    if (id == model.userId) input.error = getString(R.string.ui_this_is_your_own_id_import_your_friend_s_id)
                    else if (ChatViewModel.validUserId(id)) {
                        dialog.dismiss()
                        root.post { startChat(id) }
                    }
                    else input.error = getString(R.string.ui_paste_the_other_device_s_complete_64_character_id)
                } catch (e: Exception) { input.error = e.message?.let { UiErrors.text(requireContext(), it) } ?: getString(R.string.ui_invalid_user_id) }
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
        val scroll = ConversationScrollView(requireContext()).apply {
            clipToPadding = false; addView(list)
            // Focus stays in the composer when messages change.
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        messageList = list; messageScroll = scroll
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> reportVisibleMessages() }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composeRow = actionRow().apply { gravity = Gravity.BOTTOM; setPadding(0, dp(4), 0, 0) }
        val input = object : TextInputEditText(requireContext()) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
                super.onCreateInputConnection(outAttrs).also {
                    outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
                }
        }.apply {
            hint = getString(R.string.ui_message); minLines = 1; maxLines = 5
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
        sendButton = button(getString(R.string.ui_send)) { model.send(input.text.toString()) }
        composeRow.addView(sendButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        root.addView(composeRow)
        updateConversation(initial = true)
    }

    private fun reportVisibleMessages() {
        if(settingsVisible || requestsVisible || model.serversVisible || model.selectedGroup != null || !isResumed ||
            !requireActivity().hasWindowFocus()) return
        if(drawer.isDrawerVisible(GravityCompat.START)) return
        val visible = messageViews.filterValues { row ->
            row.row.getGlobalVisibleRect(android.graphics.Rect())
        }.keys
        model.messagesViewed(visible)
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
            background = rounded(if(mine) ownBubbleColor else groupBubbleColors[message.from] ?: incomingBubbleColor)
        }
        val sender = if(model.selectedGroup != null && !mine) label(model.contactLabel(message.from), 12f, true).apply {
            setPadding(0, 0, 0, dp(2)); setTypeface(typeface, Typeface.BOLD)
        }.also { bubble.addView(it, LinearLayout.LayoutParams(-2, -2)) } else null
        val body = label(String(message.content, Charsets.UTF_8)).apply {
            setPadding(0, 0, 0, 0)
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
            val width = kotlin.math.ceil(listOf(getString(R.string.ui_sending), getString(R.string.ui_sent), getString(R.string.ui_received), getString(R.string.ui_read), getString(R.string.ui_expired), getString(R.string.ui_failed)).maxOf(it.paint::measureText).toDouble()).toInt()
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
        fun person(id: String, subject: Boolean = false) = if(id == model.userId) (if(subject) getString(R.string.ui_you_905cb3) else getString(R.string.ui_you_8af56d)) else model.contactLabel(id)
        val actor = person(event.getString("actor"), subject = true)
        val target = person(event.getString("target"))
        return when(event.getString("action")) {
            "create" -> getString(R.string.ui_created_the_group, actor)
            "invite" -> getString(R.string.ui_invited_53c58d, actor, target)
            "join" -> getString(R.string.ui_joined_the_group, actor)
            "leave" -> getString(R.string.ui_left_the_group, actor)
            "remove" -> {
                val previous = model.currentGroup?.events?.firstOrNull { it.state.revision == event.getInt("revision") - 1 }?.state
                if(previous != null && event.getInt("epoch") == previous.epoch) getString(R.string.ui_cancelled_the_invitation_for, actor, target)
                else getString(R.string.ui_removed_from_the_group, actor, target)
            }
            "decline" -> getString(R.string.ui_declined_the_invitation, actor)
            "close" -> getString(R.string.ui_closed_the_group, actor)
            "rename" -> getString(R.string.ui_renamed_the_group_to, actor, event.getString("name"))
            else -> getString(R.string.ui_group_membership_updated)
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
            model.groupProgress(group.state.id) != null -> UiErrors.text(requireContext(), model.groupProgress(group.state.id))
            group.joining -> getString(R.string.ui_joining_group_waiting_for_admin_security_confirmation)
            model.groupSending -> getString(R.string.ui_preparing_message)
            group.state.closed -> getString(R.string.ui_this_group_is_closed_saved_history_is_available)
            model.userId in group.state.invited -> getString(R.string.ui_accept_the_invitation_from_the_chat_list_to_join)
            model.userId !in group.state.members -> getString(R.string.ui_you_are_no_longer_a_member_saved_history_is_available)
            !model.viewingConnectedServer -> connectionText()
            group.problem != null -> getString(R.string.ui_updating_group_security, UiErrors.text(requireContext(), group.problem))
            !group.ready -> getString(R.string.ui_joining_group_updating_security)
            else -> getString(R.string.ui_members_449a38, group.state.members.size, connectionText())
        }
    }

    private fun updateConversation(initial: Boolean = false) {
        val peer = model.selectedConversation ?: return
        val list = messageList ?: return
        val scroll = messageScroll ?: return
        conversationTitle?.let { if (it.text.toString() != model.contactLabel(peer)) it.text = model.contactLabel(peer) }
        connectionLabel?.let { if (it.text.toString() != conversationStatus()) it.text = conversationStatus() }
        errorLabel?.apply {
            val detail = UiErrors.text(requireContext(), model.error)
            if (text.toString() != detail) text = detail
            visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
        val group = model.currentGroup
        ownBubbleColor = Appearance.bubble(requireContext(), true, color(androidx.appcompat.R.attr.colorPrimary))
        incomingBubbleColor = Appearance.bubble(requireContext(), false, color(com.google.android.material.R.attr.colorSurfaceContainer))
        groupBubbleColors = if(group != null && Appearance.groupColorsEnabled(requireContext())) {
            Appearance.groupColors(requireContext(), model.viewedServer?.fingerprint.orEmpty(), group.state.id,
                (group.state.members + group.messages.filter { it.type != Message.MessageType.GROUP_EVENT }.map { it.from })
                    .filter { it != model.userId }, ownBubbleColor)
        } else emptyMap()
        sendButton?.isEnabled = model.viewingConnectedServer && (model.selectedGroup == null ||
            (group != null && group.ready && !group.joining && !model.groupSending && model.groupProgress(group.state.id) == null &&
                !group.state.closed && model.userId in group.state.members))
        sendButton?.text = if(model.groupSending) getString(R.string.ui_sending) else getString(R.string.ui_send)
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
        messages.forEachIndexed { index, message ->
            val views = messageViews.getOrPut(message.id) {
                messageRow(message).also { list.addView(it.row) }
            }
            if(list.indexOfChild(views.row) != index) { list.removeView(views.row); list.addView(views.row, index) }
            if(message.type == Message.MessageType.GROUP_EVENT) {
                views.body.text = groupEventText(message)
                return@forEachIndexed
            }
            val fill = if(message.from == model.userId) ownBubbleColor else groupBubbleColors[message.from] ?: incomingBubbleColor
            val foreground = BubbleColors.foreground(fill)
            val background = views.bubble?.background as? GradientDrawable
            if(background?.color?.defaultColor != fill) background?.setColor(fill)
            listOfNotNull(views.body, views.sender, views.status, views.time).forEach { text ->
                if(text.currentTextColor != foreground) text.setTextColor(foreground)
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
                Message.DeliveryStatus.SENDING -> getString(R.string.ui_sending)
                Message.DeliveryStatus.SENT -> getString(R.string.ui_sent)
                Message.DeliveryStatus.RECEIVED -> getString(R.string.ui_received)
                Message.DeliveryStatus.READ -> getString(R.string.ui_read)
                Message.DeliveryStatus.EXPIRED -> getString(R.string.ui_expired)
                Message.DeliveryStatus.FAILED -> getString(R.string.ui_failed)
                null -> ""
            }
            views.status?.let { if (it.text.toString() != status) it.text = status }
            val time = message.sentAt?.let { android.text.format.DateFormat.getTimeFormat(requireContext()).format(java.util.Date(it)) }.orEmpty()
            if (views.time.text.toString() != time) {
                views.time.text = time
                views.time.contentDescription = message.sentAt?.let {
                    getString(R.string.ui_sent_c9016a, android.text.format.DateFormat.getDateFormat(requireContext()).format(java.util.Date(it)), time)
                }
            }
            views.footer.visibility = if (message.from == model.userId || message.sentAt != null) View.VISIBLE else View.GONE
            views.time.visibility = if (message.sentAt == null) View.GONE else View.VISIBLE
        }
        if (messages.isEmpty()) list.addView(label(if(group != null) getString(R.string.empty_group, group.state.name) else getString(R.string.ui_say_hello_to, model.contactLabel(peer)), 16f, true))
        if(initial) scroll.showLatest()
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
