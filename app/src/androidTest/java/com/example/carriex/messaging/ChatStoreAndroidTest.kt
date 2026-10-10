package com.example.carriex.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import com.example.carriex.model.Message
import com.example.carriex.crypto.model.KeyPair
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatStoreAndroidTest {
    @Test fun upgradingHistoryDoesNotReplaceUnknownSenderTimeWithArrivalTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("""CREATE TABLE messages (
                id TEXT PRIMARY KEY, scope TEXT NOT NULL, peer TEXT NOT NULL,
                sender TEXT NOT NULL, recipient TEXT NOT NULL, body BLOB NOT NULL,
                wire BLOB, status TEXT, created_at INTEGER NOT NULL)""")
            database.execSQL("INSERT INTO messages VALUES ('outgoing','scope','friend','me','friend',X'0102',NULL,'SENT',1700000000123)")
            database.execSQL("INSERT INTO messages VALUES ('incoming','scope','friend','friend','me',X'0304',NULL,NULL,1700000100456)")
            ChatStore(context).use { it.onUpgrade(database, 2, 3) }
            database.rawQuery("SELECT id,sent_at,body FROM messages ORDER BY id", null).use { rows ->
                assertTrue(rows.moveToNext())
                assertEquals("incoming", rows.getString(0))
                assertTrue("Arrival time is not sender time", rows.isNull(1))
                assertArrayEquals(byteArrayOf(3, 4), rows.getBlob(2))
                assertTrue(rows.moveToNext())
                assertEquals("outgoing", rows.getString(0))
                assertEquals(1_700_000_000_123L, rows.getLong(1))
                assertArrayEquals(byteArrayOf(1, 2), rows.getBlob(2))
            }
        }
    }

    @Test fun encryptedHistoryAndOutboxSurviveReopenWithoutDuplicates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = UUID.randomUUID().toString()
        val outgoing = Message("me", "friend", "private outgoing".toByteArray(), Message.MessageType.DATA,
            status = Message.DeliveryStatus.SENDING, sentAt = 1_700_000_000_123L)
        val incoming = Message("friend", "me", "private incoming".toByteArray(), Message.MessageType.DATA,
            sentAt = 1_700_000_100_456L)
        val exchange = Message("me", "friend", byteArrayOf(1, 2, 3), Message.MessageType.KEY_EXCHANGE)
        ChatStore(context).use { store ->
            store.addOutgoing(scope, outgoing)
            assertTrue(store.addIncoming(scope, incoming))
            assertFalse(store.addIncoming(scope, incoming))
            store.setWire(scope, outgoing.copy(content = "encrypted wire".toByteArray()))
            assertEquals("encrypted wire", String(store.pending(scope).single().second!!.content))
            assertEquals(outgoing.sentAt, store.pending(scope).single().first.sentAt)
            store.mark(scope, outgoing.id, Message.DeliveryStatus.SENT)
            store.savePendingSession(scope, "friend", exchange.id, KeyPair(exchange.content, byteArrayOf(4, 5, 6)))
            store.saveExchangeOutbox(scope, exchange)
        }
        ChatStore(context).use { reopened ->
            val history = reopened.history(scope)
            assertEquals(2, history.size)
            assertEquals("private outgoing", String(history[0].content))
            assertEquals(Message.DeliveryStatus.SENT, history[0].status)
            assertEquals(outgoing.sentAt, history[0].sentAt)
            assertEquals("private incoming", String(history[1].content))
            assertEquals(incoming.sentAt, history[1].sentAt)
            reopened.mark(scope, outgoing.id, Message.DeliveryStatus.RECEIVED)
            assertEquals(outgoing.sentAt, reopened.history(scope).first().sentAt)
            assertTrue(reopened.pending(scope).isEmpty())
            assertTrue(reopened.history("other-server").isEmpty())
            assertArrayEquals(byteArrayOf(4, 5, 6), reopened.pendingSession(scope, "friend")!!.second.secretKey)
            assertArrayEquals(exchange.content, reopened.exchangeOutbox(scope, "me").single().content)
            reopened.clearPendingSession(scope, "friend")
            reopened.clearExchangeOutbox(scope, exchange.id)
        }
    }
}
