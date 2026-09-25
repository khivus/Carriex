package com.example.carriex.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.model.Message
import com.example.carriex.crypto.model.KeyPair
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatStoreAndroidTest {
    @Test fun encryptedHistoryAndOutboxSurviveReopenWithoutDuplicates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = UUID.randomUUID().toString()
        val outgoing = Message("me", "friend", "private outgoing".toByteArray(), Message.MessageType.DATA,
            status = Message.DeliveryStatus.SENDING)
        val incoming = Message("friend", "me", "private incoming".toByteArray(), Message.MessageType.DATA)
        val exchange = Message("me", "friend", byteArrayOf(1, 2, 3), Message.MessageType.KEY_EXCHANGE)
        ChatStore(context).use { store ->
            store.addOutgoing(scope, outgoing)
            assertTrue(store.addIncoming(scope, incoming))
            assertFalse(store.addIncoming(scope, incoming))
            store.setWire(scope, outgoing.copy(content = "encrypted wire".toByteArray()))
            assertEquals("encrypted wire", String(store.pending(scope).single().second!!.content))
            store.mark(scope, outgoing.id, Message.DeliveryStatus.SENT)
            store.savePendingSession(scope, "friend", exchange.id, KeyPair(exchange.content, byteArrayOf(4, 5, 6)))
            store.saveExchangeOutbox(scope, exchange)
        }
        ChatStore(context).use { reopened ->
            val history = reopened.history(scope)
            assertEquals(2, history.size)
            assertEquals("private outgoing", String(history[0].content))
            assertEquals(Message.DeliveryStatus.SENT, history[0].status)
            assertEquals("private incoming", String(history[1].content))
            assertTrue(reopened.pending(scope).isEmpty())
            assertTrue(reopened.history("other-server").isEmpty())
            assertArrayEquals(byteArrayOf(4, 5, 6), reopened.pendingSession(scope, "friend")!!.second.secretKey)
            assertArrayEquals(exchange.content, reopened.exchangeOutbox(scope, "me").single().content)
            reopened.clearPendingSession(scope, "friend")
            reopened.clearExchangeOutbox(scope, exchange.id)
        }
    }
}
