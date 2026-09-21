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
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.carriex.presentation.chat.ChatViewModel
import com.google.android.material.button.MaterialButton
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
    private fun label(value: String, size: Float = 16f, secondary: Boolean = false) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(if (secondary) muted else ink)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(value: String, action: () -> Unit) = MaterialButton(requireContext()).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }
    private fun field(parent: LinearLayout, title: String, value: String, changed: (String) -> Unit): TextInputEditText {
        val box = TextInputLayout(requireContext()).apply {
            hint = title
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
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
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model.onStateChanged = { if (this.view != null) render() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (model.selectedPeer != null) model.showChats()
                else { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        render()
    }

    private fun render() {
        val restoreFocus = composer?.hasFocus() == true && renderedPeer == model.selectedPeer
        composer = null
        root.removeAllViews()
        if (!model.connected) setup() else if (model.selectedPeer == null) chats() else conversation()
        renderedPeer = model.selectedPeer
        if (restoreFocus) composer?.let { it.requestFocus(); it.setSelection(it.text.length) }
    }

    private fun setup() {
        val content = column()
        val scroll = ScrollView(requireContext()).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        content.addView(label("Carriex", 36f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(label("Saved servers", 24f))
        content.addView(label("Scan a server's pairing QR or paste its pairing text once. Its identity is checked whenever you connect.", 14f, true))
        content.addView(button("Scan server QR") {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the pairing QR shown by your Carriex server")
                .setBeepEnabled(false).setOrientationLocked(false))
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
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            }
            row.addView(label(server.name, 20f).apply { setTypeface(typeface, Typeface.BOLD) })
            row.addView(label(server.url, 14f, true).apply { setTextIsSelectable(true) })
            row.addView(label("Server key: ${server.fingerprint.chunked(8).joinToString(" ")}", 11f, true))
            val actions = LinearLayout(requireContext())
            actions.addView(button("Connect") { model.connect(server) }.apply { isEnabled = !model.connecting }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(button("Forget") {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Forget ${server.name}?")
                    .setMessage("This removes the saved address and trusted server key. You will need its QR or pairing text to connect again.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> model.forgetServer(server) }.show()
            }.apply { isEnabled = !model.connecting }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actions)
            content.addView(row)
        }
        content.addView(label("Your user ID", 14f, true))
        content.addView(label(model.userId, 14f).apply { setTextIsSelectable(true) })
        content.addView(button("Copy my ID") { copyId() })
        content.addView(button("My ID QR") { showMyIdQr() }.apply { isEnabled = model.userId.isNotEmpty() })
    }

    private fun importPairingText() {
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
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
        val content = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
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
        val body = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val bitmap = BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 640, 640)
        body.addView(ImageView(requireContext()).apply {
            setImageBitmap(bitmap); contentDescription = "Your Carriex user ID QR"
            adjustViewBounds = true; setBackgroundColor(android.graphics.Color.WHITE)
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(label(model.userId.chunked(8).joinToString(" "), 14f).apply { setTextIsSelectable(true) })
        body.addView(label("Ask your friend to scan this from your phone using Scan friend's ID or Verify friend's ID in your chat. This identifies this installation of Carriex.", 14f, true))
        pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("My ID QR")
            .setView(ScrollView(requireContext()).apply { addView(body) })
            .setPositiveButton("Done", null).setNeutralButton("Copy ID") { _, _ -> copyId() }
            .setNegativeButton("Share ID") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, model.userId)
                }, "Share Carriex user ID"))
            }.show()
    }

    private fun scanContact(expectedId: String? = null) {
        expectedScannedPeer = expectedId
        contactScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan My ID QR directly from your friend's phone")
            .setBeepEnabled(false).setOrientationLocked(false))
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
                    .setNegativeButton("Cancel", null).setPositiveButton("Open chat") { _, _ ->
                        if (model.connected) model.openChat(id) else model.showError("Connect to a server before adding a chat")
                    }
            }
            pairingDialog = dialog.show()
        } catch (e: Exception) {
            pairingDialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Cannot read user ID")
                .setMessage(e.message).setPositiveButton("Close", null).show()
        }
    }

    private fun errorBanner() {
        root.addView(label(model.connectionStatus.label, 14f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        model.error?.let { detail ->
            root.addView(label(detail, 14f).apply {
                setTextColor(color(androidx.appcompat.R.attr.colorError))
                setOnClickListener { model.dismissError() }
            })
        }
    }

    private fun chats() {
        root.addView(label("Chats", 32f).apply { setTypeface(typeface, Typeface.BOLD) })
        root.addView(label("${model.activeServer?.name} · ${model.activeServer?.url}", 12f, true))
        val actions = LinearLayout(requireContext())
        actions.addView(button("My ID QR") { showMyIdQr() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Servers") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Change server?")
                .setMessage("This closes the connection and clears messages from this session. Your user ID and saved contacts are kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> model.disconnect() }.show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        errorBanner()
        val list = column()
        root.addView(ScrollView(requireContext()).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (model.conversations.isEmpty()) {
            list.addView(label("Start a conversation", 24f))
            list.addView(label("Tap New chat and enter the other person's user ID. Both devices should connect to the same server.", 16f, true))
        }
        model.conversations.entries.toList().reversed().forEach { (peer, messages) ->
            val row = column().apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                isClickable = true; isFocusable = true
                setOnClickListener { model.openChat(peer) }
            }
            val count = model.unread[peer] ?: 0
            row.addView(label(peer + if (count > 0) "  ·  $count new" else "", 18f).apply { setTypeface(typeface, Typeface.BOLD) })
            row.addView(label(messages.lastOrNull()?.let { (if (it.from == model.userId) "You: " else "") + String(it.content, Charsets.UTF_8) } ?: "Say hello", 14f, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            list.addView(row)
        }
        root.addView(button("+  New chat") { newChat() })
        root.addView(button("Scan friend's ID") { scanContact() })
    }

    private fun newChat() {
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val input = field(body, "Recipient user ID", "") { }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("New chat").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Open chat", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (model.openChat(input.text.toString())) dialog.dismiss()
                else input.error = "Paste the other device's complete 64-character ID."
            }
        }
        dialog.show()
    }

    private fun conversation() {
        val peer = model.selectedPeer ?: return
        root.addView(button("‹  All chats") { model.showChats() })
        root.addView(label(peer, 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        val identityActions = LinearLayout(requireContext())
        identityActions.addView(button("My ID QR") { showMyIdQr() }, LinearLayout.LayoutParams(0, -2, 1f))
        identityActions.addView(button("Verify friend's ID") { scanContact(peer) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(identityActions)
        root.addView(label("Messages stay in this app session", 12f, true))
        errorBanner()
        val list = column()
        val scroll = ScrollView(requireContext()).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val messages = model.conversations[peer].orEmpty()
        if (messages.isEmpty()) list.addView(label("Say hello to $peer", 16f, true))
        messages.forEach { message ->
            val mine = message.from == model.userId
            val row = LinearLayout(requireContext()).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                setPadding(if (mine) dp(40) else 0, dp(4), if (mine) 0 else dp(40), dp(4))
            }
            val bubble = label(String(message.content, Charsets.UTF_8)).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = rounded(color(if (mine) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainer))
                setTextColor(color(if (mine) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurface))
                setTextIsSelectable(true)
            }
            row.addView(bubble, LinearLayout.LayoutParams(-2, -2)); list.addView(row)
        }
        val composeRow = LinearLayout(requireContext()).apply { gravity = Gravity.BOTTOM }
        val input = TextInputEditText(requireContext()).apply {
            hint = "Message"; minLines = 1; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(model.drafts[peer].orEmpty())
            doAfterTextChanged { model.drafts[peer] = it.toString() }
        }
        composer = input
        composeRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composeRow.addView(button("Send") { model.send(input.text.toString()) })
        root.addView(composeRow)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroyView() {
        pairingDialog?.dismiss()
        pairingDialog = null
        model.onStateChanged = null
        composer = null
        super.onDestroyView()
    }
}
