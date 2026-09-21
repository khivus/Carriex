package com.example.carriex.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.google.android.material.materialswitch.MaterialSwitch

/** One device identity, a conversation list, and a focused chat composer. */
class ChatScreen : Fragment() {
    private val importCa = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var count = stream.read(buffer)
                    while (count != -1) {
                        require(out.size() + count <= 65536) { "Certificate file is too large." }
                        out.write(buffer, 0, count)
                        count = stream.read(buffer)
                    }
                    out.toByteArray()
                } ?: error("Cannot open certificate.")
                val ca = com.example.carriex.transport.LocalCertificateAuthority(bytes)
                MaterialAlertDialogBuilder(requireContext()).setTitle("Trust this server CA?")
                    .setMessage("For ${model.host}:${model.port}\n\nSHA-256 fingerprint:\n${ca.fingerprint}\n\nCompare this fingerprint with the one shown on your server using a trusted channel before continuing.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Trust CA") { _, _ ->
                        try { model.trustCa(bytes) } catch (e: Exception) { certificateError(e) }
                    }.show()
            } catch (e: Exception) { certificateError(e) }
        }
    }
    private fun certificateError(e: Exception) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Certificate not imported").setMessage(e.message).setPositiveButton("OK", null).show()
    }
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
        content.addView(label("Your conversations, on your server", 18f, true))
        content.addView(label("Connect to a server", 24f))
        field(content, "Server domain or IP", model.host) { model.host = it }.isEnabled = !model.connecting
        field(content, "Port", model.port) { model.port = it }.apply { inputType = InputType.TYPE_CLASS_NUMBER; isEnabled = !model.connecting }
        content.addView(MaterialSwitch(requireContext()).apply {
            text = "Use HTTPS"; isChecked = model.useTls; isEnabled = !model.connecting
            setOnCheckedChangeListener { _, checked -> model.useTls = checked }
        })
        content.addView(button("Local server certificate") {
            try {
                ChatViewModel.serverUrl(model.host, model.port, true)
                val ca = runCatching { model.trustedCa() }.getOrNull()
                MaterialAlertDialogBuilder(requireContext()).setTitle("Local server CA")
                    .setMessage(ca?.let { "Trusted for ${model.host}:${model.port}\nSHA-256:\n${it.fingerprint}" }
                        ?: "Import the root CA certificate exported from your local server. Trust applies only to this server address and port.")
                    .setPositiveButton("Import CA") { _, _ -> importCa.launch(arrayOf("*/*")) }
                    .setNeutralButton("Remove CA") { _, _ -> model.removeCa() }
                    .setNegativeButton("Cancel", null).show()
            } catch (e: Exception) { certificateError(e) }
        }.apply { isEnabled = !model.connecting })
        content.addView(label("Your user ID", 14f, true))
        content.addView(label(model.userId).apply { setTextIsSelectable(true) })
        content.addView(label("Share this ID so someone can start a chat with you. Each device has its own ID.", 14f, true))
        content.addView(button("Copy my ID") { copyId() })
        model.error?.let { content.addView(label(it, 14f).apply { setTextColor(color(androidx.appcompat.R.attr.colorError)) }) }
        content.addView(button(if (model.connecting) "Connecting…" else "Connect") { model.connect() }.apply { isEnabled = !model.connecting })
    }

    private fun copyId() {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Carriex user ID", model.userId))
        Toast.makeText(requireContext(), "User ID copied", Toast.LENGTH_SHORT).show()
    }

    private fun errorBanner() {
        model.error?.let { detail ->
            root.addView(label(detail, 14f).apply {
                setTextColor(color(androidx.appcompat.R.attr.colorError))
                setOnClickListener { model.dismissError() }
            })
        }
    }

    private fun chats() {
        root.addView(label("Chats", 32f).apply { setTypeface(typeface, Typeface.BOLD) })
        root.addView(label("${model.host}:${model.port} · ${model.userId}", 12f, true))
        val actions = LinearLayout(requireContext())
        actions.addView(button("Copy my ID") { copyId() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Server") {
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
    }

    private fun newChat() {
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val input = field(body, "Recipient user ID", "") { }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("New chat").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Open chat", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (model.openChat(input.text.toString())) dialog.dismiss()
                else input.error = "Enter another user's ID (letters, numbers, _, . or -; up to 64 characters)."
            }
        }
        dialog.show()
    }

    private fun conversation() {
        val peer = model.selectedPeer ?: return
        root.addView(button("‹  All chats") { model.showChats() })
        root.addView(label(peer, 24f).apply { setTypeface(typeface, Typeface.BOLD) })
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
        model.onStateChanged = null
        composer = null
        super.onDestroyView()
    }
}
