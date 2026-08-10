package com.example.carriex.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.carriex.crypto.libsodium.LibsodiumCryptoEngine
import com.example.carriex.crypto.keystore.AndroidKeyStoreKeyManager
import com.example.carriex.messaging.MessagingService
import com.example.carriex.presentation.chat.ChatViewModel
import com.example.carriex.session.DefaultSessionManager
import com.example.carriex.transport.ServerTransport

/**
 * UI Layer: ChatScreen
 * 
 * Displays the chat interface for two users to demonstrate the flow.
 * It must NOT contain business logic.
 */
class ChatScreen : Fragment() {

    // Shared infrastructure for the prototype
    private val sharedTransport = ServerTransport()
    private lateinit var cryptoEngine: LibsodiumCryptoEngine

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        cryptoEngine = LibsodiumCryptoEngine(AndroidKeyStoreKeyManager(requireContext()))

        // Create a root layout programmatically to satisfy "REQUIRED FILES" (no extra XML)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Trace: Initializing User A instance
        val serviceA = MessagingService("UserA", cryptoEngine, sharedTransport, DefaultSessionManager())
        root.addView(createChatView("UserA", "UserB", serviceA))

        // Visual separator
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8).apply {
                setMargins(0, 48, 0, 48)
            }
            setBackgroundColor(0xFFBBBBBB.toInt())
        })

        // Trace: Initializing User B instance
        val serviceB = MessagingService("UserB", cryptoEngine, sharedTransport, DefaultSessionManager())
        root.addView(createChatView("UserB", "UserA", serviceB))

        return root
    }

    /**
     * Helper to create a chat UI component for a specific user.
     */
    private fun createChatView(
        myId: String,
        targetId: String,
        service: MessagingService
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(context).apply {
            text = "Instance: $myId"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        }
        container.addView(title)

        val logView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300)
            hint = "No messages yet..."
            setBackgroundColor(0xFFEEEEEE.toInt())
            setPadding(16, 16, 16, 16)
        }
        container.addView(logView)

        // ViewModel handles UI logic and state
        // Use lateinit to allow referencing the viewModel inside its own callback lambda
        lateinit var viewModel: ChatViewModel
        viewModel = ChatViewModel(service, targetId) {
            // Update UI on state change - Ensure it runs on UI thread
            activity?.runOnUiThread {
                val logs = viewModel.messages.joinToString("\n") { msg ->
                    "[${msg.from}]: ${String(msg.content)}"
                }
                logView.text = logs
            }
        }

        val input = EditText(context).apply { hint = "Message to $targetId" }
        container.addView(input)

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        container.addView(buttonContainer)

        val sendBtn = Button(context).apply {
            text = "Send"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                viewModel.sendMessage(input.text.toString())
                input.text.clear()
            }
        }
        buttonContainer.addView(sendBtn)

        val refreshBtn = Button(context).apply {
            text = "Refresh"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                viewModel.refresh()
            }
        }
        buttonContainer.addView(refreshBtn)

        return container
    }
}
