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
    @Test fun fixedSessionUpgradePreservesHistoryAndResealsPendingMessagesWithFreshIds() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="ratchet-upgrade-${UUID.randomUUID()}.db"
        val scope=UUID.randomUUID().toString()
        val plain=Message("me","friend","pending before ratchet".toByteArray(),Message.MessageType.DATA,
            status=Message.DeliveryStatus.SENDING,sentAt=1700000000123)
        try {
            ChatStore(context,name).use { store ->
                store.addOutgoing(scope,plain);store.setWire(scope,plain.copy(content="fixed wire".toByteArray()))
                store.addIncoming(scope,Message("friend","me","saved history".toByteArray(),Message.MessageType.DATA))
                store.x3dh(scope).put("session/old","{\"root\":\"old secret\"}")
                store.x3dh(scope).put("local/identity","identity stays")
                store.x3dh(scope).put("peer-protection/friend","one-time")
                store.writableDatabase.version=6
            }
            ChatStore(context,name).use { store ->
                val pending=store.pending(scope).single()
                assertNotEquals(plain.id,pending.first.id);assertNull(pending.second)
                assertArrayEquals(plain.content,pending.first.content);assertEquals(plain.sentAt,pending.first.sentAt)
                assertEquals(2,store.history(scope).size)
                assertTrue(store.x3dh(scope).keys("session/").isEmpty())
                assertEquals("identity stays",store.x3dh(scope).get("local/identity"))
                assertNull(store.x3dh(scope).get("peer-protection/friend"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun quarantineSurvivesRestartIsBoundedAndExpiresWithoutChangingHistory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="requests-${UUID.randomUUID()}.db"
        val scope=UUID.randomUUID().toString()
        val first=Message("stranger","me","opaque ciphertext".toByteArray(),Message.MessageType.DATA)
        try {
            ChatStore(context,name).use { store ->
                assertTrue(store.quarantine(scope,first,"Unknown sender"))
                assertTrue(store.quarantine(scope,first.copy(id=UUID.randomUUID().toString()),"Unknown sender"))
                assertFalse(store.quarantine(scope,first.copy(id=UUID.randomUUID().toString()),"Unknown sender"))
            }
            ChatStore(context,name).use { store ->
                assertEquals(2,store.requests(scope).single().count)
                assertArrayEquals(first.content,store.requestMessages(scope,first.from).first().content)
                assertTrue(store.history(scope).isEmpty())
                store.writableDatabase.execSQL("UPDATE quarantined SET created=0")
                assertTrue(store.requests(scope).isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun upgradingLegacyHistoryPreservesBodiesAndScopesMessageIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("""CREATE TABLE messages (
                id TEXT PRIMARY KEY, scope TEXT NOT NULL, peer TEXT NOT NULL,
                sender TEXT NOT NULL, recipient TEXT NOT NULL, body BLOB NOT NULL,
                wire BLOB, status TEXT, created_at INTEGER NOT NULL, sent_at INTEGER)""")
            db.execSQL("CREATE TABLE pending_session (id TEXT)")
            db.execSQL("CREATE TABLE exchange_outbox (id TEXT)")
            db.execSQL("INSERT INTO pending_session VALUES ('legacy')")
            db.execSQL("INSERT INTO exchange_outbox VALUES ('legacy')")
            db.execSQL("INSERT INTO messages VALUES ('pending','server1','friend','me','friend',X'0102',NULL,'SENDING',10,20)")
            db.execSQL("INSERT INTO messages VALUES ('saved','server1','friend','friend','me',X'0506',NULL,NULL,11,21)")
            ChatStore(context).use { it.onUpgrade(db, 3, 5) }
            db.rawQuery("SELECT body,wire,sent_at FROM messages WHERE id='pending'", null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(1,2), it.getBlob(0))
                assertTrue(it.isNull(1)); assertEquals(20L, it.getLong(2))
            }
            db.rawQuery("SELECT body,sent_at FROM messages WHERE id='saved'", null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(5,6), it.getBlob(0)); assertEquals(21L, it.getLong(1))
            }
            db.execSQL("INSERT INTO messages VALUES ('saved','server2','friend','friend','me',X'0708',NULL,NULL,12,22)")
            db.rawQuery("SELECT count(*) FROM messages WHERE id='saved'", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            db.rawQuery("SELECT count(*) FROM pending_session", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT count(*) FROM exchange_outbox", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun legacyPendingCiphertextGetsNewIdAndIsResealedWithoutLosingPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val scope = UUID.randomUUID().toString()
        val outgoing = Message("me", "friend", "legacy pending body".toByteArray(), Message.MessageType.DATA,
            status = Message.DeliveryStatus.SENDING, sentAt = 1_700_000_000_123)
        try { ChatStore(context, name).use { store ->
            store.addOutgoing(scope, outgoing)
            store.setWire(scope, outgoing.copy(content = byteArrayOf(1,2,3)))
            val body = store.readableDatabase.rawQuery("SELECT body FROM messages WHERE scope=? AND id=?",
                arrayOf(scope, outgoing.id)).use { assertTrue(it.moveToFirst()); it.getBlob(0) }
            SQLiteDatabase.create(null).use { db ->
                db.execSQL("""CREATE TABLE messages (id TEXT PRIMARY KEY,scope TEXT,peer TEXT,sender TEXT,recipient TEXT,
                    body BLOB,wire BLOB,status TEXT,created_at INTEGER,sent_at INTEGER)""")
                db.execSQL("CREATE TABLE pending_session (id TEXT)"); db.execSQL("CREATE TABLE exchange_outbox (id TEXT)")
                db.execSQL("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(outgoing.id, scope, "friend", "me", "friend", body, byteArrayOf(1,2,3), "SENDING", 10L, outgoing.sentAt))
                store.onUpgrade(db, 3, 5)
                db.rawQuery("SELECT id,body,wire,sent_at FROM messages", null).use { rows ->
                    assertTrue(rows.moveToFirst()); val newId = rows.getString(0)
                    assertNotEquals(outgoing.id, newId); assertEquals(32, newId.length)
                    assertTrue(rows.isNull(2)); assertEquals(outgoing.sentAt, rows.getLong(3))
                    val sealed = rows.getBlob(1)
                    val key = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        .getKey("carriex-chat-storage-v1", null)
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, sealed.copyOfRange(0,12)))
                    cipher.updateAAD("$scope:$newId".toByteArray())
                    assertArrayEquals(outgoing.content, cipher.doFinal(sealed.copyOfRange(12,sealed.size)))
                }
            }
        } } finally { context.deleteDatabase(name) }
    }
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
