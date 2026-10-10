package com.example.carriex.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.*
import androidx.activity.OnBackPressedCallback
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
import com.example.carriex.servers.PairedServer
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
    private var pairingDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var model: ChatViewModel
    private lateinit var root: LinearLayout
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
    private data class MessageViews(val row: View, val body: TextView, val status: TextView?, val time: TextView, val footer: View)
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
    private fun LinearLayout.addAction(value: String, enabled: Boolean = true, action: () -> Unit) {
        if (childCount > 0) addView(Space(context), LinearLayout.LayoutParams(dp(4), 1))
        addView(button(value, action).apply { isEnabled = enabled }, LinearLayout.LayoutParams(0, -2, 1f))
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
        model = ViewModelProvider(this)[ChatViewModel::class.java]
        expectedScannedPeer = savedInstanceState?.getString("verify_contact_id")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("verify_contact_id", expectedScannedPeer)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = column().apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.onStateChanged = { if (this.view != null) render() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!model.serversVisible && model.selectedPeer != null) model.showChats()
                else if (!model.serversVisible) model.showServers()
                else { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        render()
    }

    private fun render() {
        val scope = model.viewedServer?.fingerprint
        if (!model.serversVisible && model.selectedPeer != null && !renderedServers &&
            renderedPeer == model.selectedPeer && renderedScope == scope && messageList != null) {
            updateConversation()
            return
        }
        val serverScroll = if (renderedServers) (root.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        val chatScroll = if (!renderedServers && renderedPeer == null && renderedScope == scope)
            (root.getChildAt(root.childCount - 2) as? ScrollView)?.scrollY else null
        composer = null
        messageScroll = null; messageList = null; connectionLabel = null; errorLabel = null; sendButton = null; conversationTitle = null
        messageViews.clear()
        root.removeAllViews()
        if (model.serversVisible) {
            setup()
            (root.getChildAt(0) as? ScrollView)?.let { scroll -> scroll.post { scroll.scrollTo(0, serverScroll) } }
        } else if (model.selectedPeer == null) {
            chats()
            if (chatScroll != null) (root.getChildAt(root.childCount - 2) as? ScrollView)?.let { scroll ->
                scroll.post { scroll.scrollTo(0, chatScroll) }
            }
        } else conversation()
        renderedPeer = model.selectedPeer
        renderedServers = model.serversVisible
        renderedScope = scope
    }

    private fun setup() {
        val content = column().apply { setPadding(0, 0, 0, dp(8)) }
        val scroll = ScrollView(requireContext()).apply { isFillViewport = true; clipToPadding = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        content.addView(label("Carriex", 36f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(label("Saved servers", 24f))
        content.addView(label("Scan a server's pairing QR or paste its pairing text once. Its identity is checked whenever you connect.", 14f, true))
        if (model.savedServers.isNotEmpty()) content.addView(label("Choose one server to connect automatically on app startup. Uncheck it to disable autoconnect.", 14f, true))
        content.addView(button("Scan server QR") {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                .setPrompt("Scan the pairing QR shown by your Carriex server")
                .setBeepEnabled(false).setOrientationLocked(true))
        }.apply { isEnabled = !model.connecting })
        content.addView(button("Import pairing text") { importPairingText() }.apply { isEnabled = !model.connecting })
        model.error?.let { content.addView(label(it, 14f).apply { setTextColor(color(androidx.appcompat.R.attr.colorError)) }) }
        if (model.connecting) {
            content.addView(label("Connecting to ${model.activeServer?.name}...", 16f, true))
            content.addView(button("Cancel connection") { model.disconnect() })
        }
        if (model.savedServers.isEmpty()) content.addView(label("No saved servers yet", 18f, true))
        model.savedServers.forEach { server ->
            val row = column().apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            row.addView(label(server.name, 20f).apply { setTypeface(typeface, Typeface.BOLD) })
            row.addView(label(server.url, 14f, true).apply { setTextIsSelectable(true) })
            val status = model.statusFor(server)
            row.addView(label(status.label, 14f, status != com.example.carriex.transport.ServerTransport.ConnectionStatus.CONNECTED).apply {
                if (status == com.example.carriex.transport.ServerTransport.ConnectionStatus.CONNECTED)
                    setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            val unread = model.unreadFor(server)
            if (unread > 0) row.addView(label("$unread unread messages", 14f, true))
            row.addView(MaterialCheckBox(requireContext()).apply {
                text = "Connect on app startup"
                isChecked = model.autoConnectUrl == server.url
                setOnCheckedChangeListener { _, checked -> model.setAutoConnect(server, checked) }
            })
            row.addView(label("Server key: ${server.fingerprint.chunked(8).joinToString(" ")}", 11f, true))
            val actions = actionRow()
            val current = model.activeServer?.url == server.url
            if (current && model.connected) {
                actions.addAction("Disconnect") { model.disconnect() }
            } else if (current && model.connecting) {
                actions.addAction("Cancel") { model.disconnect() }
            } else {
                actions.addAction("Connect") { model.connect(server) }
            }
            actions.addAction("Chats") { model.viewSaved(server) }
            row.addView(actions)
            row.addView(button("Forget") {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Forget ${server.name}?")
                    .setMessage("This removes the saved address and trusted server key. You will need its QR or pairing text to connect again.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> model.forgetServer(server) }.show()
            }.apply { isEnabled = !model.connecting })
            content.addView(row)
        }
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
        body.addView(label("Ask your friend to scan this from your phone using New chat > Scan QR or ID & verify > Verify friend's ID. This identifies this installation of Carriex.", 14f, true))
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("My ID")
            .setView(ScrollView(requireContext()).apply { addView(body) })
            .setPositiveButton("Done", null).setNeutralButton("Copy ID") { _, _ -> copyId() }
            .setNegativeButton("Share ID") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, model.userId)
                }, "Share Carriex user ID"))
            }.show()
    }

    private fun identityMenu(peer: String) {
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("ID & verification")
            .setItems(arrayOf("My ID", "Contact ID", "Verify friend's ID", "Rename chat")) { _, choice ->
                when (choice) {
                    0 -> showMyIdQr()
                    1 -> showContactId(peer)
                    2 -> scanContact(peer)
                    3 -> nameChat(peer, opening = false)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showContactId(peer: String) {
        val body = column().apply { setPadding(dp(12), dp(6), dp(12), dp(4)) }
        body.addView(label(model.contactLabel(peer), 18f))
        body.addView(label(peer, 16f).apply { setTextIsSelectable(true) })
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
                    .setMessage("The QR matches the device ID in this conversation. If you scanned it directly from your friend's phone, you have compared the same identity.\n\n$id")
                    .setPositiveButton("Done", null)
                ContactQr.Result.MISMATCH -> dialog.setTitle("ID does not match")
                    .setMessage("This QR belongs to a different device ID. This conversation has not been verified. Check with your friend before continuing.\n\nChat ID:\n$expectedId\n\nScanned ID:\n$id")
                    .setPositiveButton("Close", null)
                ContactQr.Result.OWN_ID -> dialog.setTitle("This is your own ID")
                    .setMessage("Scan the QR on your friend's phone.").setPositiveButton("Close", null)
                ContactQr.Result.NEW_CONTACT -> dialog.setTitle("Chat with this device?")
                    .setMessage("Confirm this QR came from your friend's phone. Both phones must connect to the same server.\n\n$id")
                    .setNegativeButton("Cancel", null).setPositiveButton("Open chat") { currentDialog, _ ->
                        currentDialog.dismiss()
                        if (model.viewingConnectedServer) root.post { startChat(id) }
                        else model.showError("Connect to this server before adding a chat")
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
        root.addView(label("Chats", 32f).apply { setTypeface(typeface, Typeface.BOLD) })
        root.addView(label("${model.viewedServer?.name} · ${model.viewedServer?.url}", 12f, true))
        val actions = actionRow()
        actions.addAction("My ID") { showMyIdQr() }
        actions.addAction("Servers") { model.showServers() }
        root.addView(actions)
        if (!model.viewingConnectedServer && !(model.connecting && model.viewedServer?.url == model.activeServer?.url))
            root.addView(button("Connect") { model.viewedServer?.let(model::connect) })
        errorBanner()
        val list = spacedList()
        root.addView(ScrollView(requireContext()).apply { clipToPadding = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (model.conversations.isEmpty()) {
            list.addView(label("Start a conversation", 24f))
            list.addView(label("Tap New chat to scan a friend's QR or import their user ID. Both devices should connect to the same server.", 16f, true))
        }
        model.conversations.entries.toList().reversed().forEach { (peer, messages) ->
            val row = column().apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                isClickable = true; isFocusable = true
                setOnClickListener { startChat(peer) }
            }
            val count = model.unread[peer] ?: 0
            row.addView(label(model.contactLabel(peer) + if (count > 0) "  ·  $count new" else "", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
            row.addView(label(messages.lastOrNull()?.let { (if (it.from == model.userId) "You: " else "") + String(it.content, Charsets.UTF_8) } ?: "Say hello", 14f, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            list.addView(row)
        }
        root.addView(button("New chat") { newChat() })
    }

    private fun newChat() {
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("New chat")
            .setItems(arrayOf("Scan QR", "Import text")) { _, choice ->
                if (choice == 0) scanContact() else importContactText()
            }.setNegativeButton("Cancel", null).show()
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
        val peer = model.selectedPeer ?: return
        root.addView(actionRow().apply {
            addAction("All chats") { model.showChats() }
            addAction("ID & verify") { identityMenu(peer) }
        })
        conversationTitle = label(model.contactLabel(peer), 24f).apply { setTypeface(typeface, Typeface.BOLD) }.also(root::addView)
        root.addView(label("Messages are saved on this phone", 12f, true))
        connectionLabel = label(connectionText(), 14f, true).apply {
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
            setText(model.drafts[peer].orEmpty())
            doAfterTextChanged { model.drafts[peer] = it.toString() }
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
        return MessageViews(row, body, status, time, footer)
    }

    private fun updateConversation(initial: Boolean = false) {
        val peer = model.selectedPeer ?: return
        val list = messageList ?: return
        val scroll = messageScroll ?: return
        val wasAtBottom = scroll.scrollY >= list.height - scroll.height - dp(24)
        conversationTitle?.let { if (it.text.toString() != model.contactLabel(peer)) it.text = model.contactLabel(peer) }
        connectionLabel?.let { if (it.text.toString() != connectionText()) it.text = connectionText() }
        errorLabel?.apply {
            val detail = model.error.orEmpty()
            if (text.toString() != detail) text = detail
            visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
        sendButton?.isEnabled = model.viewingConnectedServer
        composer?.let { input ->
            val draft = model.drafts[peer].orEmpty()
            if (input.text.toString() != draft) {
                input.setText(draft)
                input.setSelection(draft.length)
            }
        }
        val messages = model.conversations[peer].orEmpty()
        val ids = messages.mapTo(hashSetOf()) { it.id }
        messageViews.keys.filter { it !in ids }.forEach { id -> list.removeView(messageViews.remove(id)!!.row) }
        if (messageViews.isEmpty()) list.removeAllViews()
        var added = false
        var sent = false
        messages.forEach { message ->
            val views = messageViews.getOrPut(message.id) {
                added = true
                if (message.from == model.userId) sent = true
                messageRow(message).also { list.addView(it.row) }
            }
            val body = String(message.content, Charsets.UTF_8)
            if (views.body.text.toString() != body) views.body.text = body
            val status = when (message.status) {
                Message.DeliveryStatus.SENDING -> "Sending…"
                Message.DeliveryStatus.SENT -> "Sent"
                Message.DeliveryStatus.RECEIVED -> "Received"
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
        if (messages.isEmpty()) list.addView(label("Say hello to ${model.contactLabel(peer)}", 16f, true))
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
        super.onDestroyView()
    }
}
