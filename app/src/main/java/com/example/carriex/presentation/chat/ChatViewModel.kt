package com.example.carriex.presentation.chat

import android.os.Handler
import android.os.Looper
import com.example.carriex.messaging.MessagingService
import com.example.carriex.model.Message

/**
 * Presenter for the Chat UI.
 * 
 * It holds UI state and handles user interactions.
 * It must NOT implement business/messaging logic directly.
 */
class ChatViewModel(
    private val messagingService: MessagingService,
    private val targetUserId: String,
    private val onStateChanged: () -> Unit
) {
    // List of decrypted messages to display in UI
    val messages = mutableListOf<Message>()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Observe incoming decrypted messages from the service
        messagingService.onMessageDecrypted = { message ->
            // Trace: ViewModel received decrypted message
            mainHandler.post {
                messages.add(message)
                onStateChanged()
            }
        }
    }

    /**
     * Called by UI when user clicks Send.
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        // Trace: ViewModel calling MessagingService.send()
        messagingService.send(targetUserId, text)
        
        // Add sent message to local UI state for display
        val sentMessage = Message("me", targetUserId, text.toByteArray(), Message.MessageType.DATA)
        messages.add(sentMessage)
        onStateChanged()
    }

    /**
     * Manually trigger a fetch of new messages from the transport.
     */
    fun refresh() {
        messagingService.fetchNewMessages()
    }
}
