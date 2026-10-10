package com.example.carriex.messaging

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.carriex.model.Message
import com.example.carriex.crypto.model.KeyPair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Phone-owned history and outgoing queue. Message bodies are encrypted before SQLite sees them. */
class ChatStore(context: Context) : SQLiteOpenHelper(context, "chat-v1.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id TEXT PRIMARY KEY, scope TEXT NOT NULL, peer TEXT NOT NULL,
            sender TEXT NOT NULL, recipient TEXT NOT NULL, body BLOB NOT NULL,
            wire BLOB, status TEXT, created_at INTEGER NOT NULL, sent_at INTEGER
        )""")
        db.execSQL("CREATE INDEX conversation ON messages(scope, peer, created_at)")
        db.execSQL("CREATE TABLE processed_exchange (scope TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(scope,id))")
        createExchangeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion !in 1..2 || newVersion != 3)
            throw IllegalStateException("Chat database migration from $oldVersion is required")
        if (oldVersion == 1) createExchangeTables(db)
        db.execSQL("ALTER TABLE messages ADD COLUMN sent_at INTEGER")
        // Old outgoing rows were created when sending; incoming rows only have arrival time.
        db.execSQL("UPDATE messages SET sent_at=created_at WHERE status IS NOT NULL")
    }

    private fun createExchangeTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE pending_session (
            scope TEXT NOT NULL, peer TEXT NOT NULL, id TEXT NOT NULL,
            public_key BLOB NOT NULL, secret_key BLOB NOT NULL, PRIMARY KEY(scope,peer))""")
        db.execSQL("""CREATE TABLE exchange_outbox (
            scope TEXT NOT NULL, peer TEXT NOT NULL, id TEXT NOT NULL,
            public_key BLOB NOT NULL, PRIMARY KEY(scope,id))""")
    }

    private fun key(): SecretKey {
        val alias = "carriex-chat-storage-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun seal(scope: String, id: String, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("$scope:$id".toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(bytes)
    }

    private fun open(scope: String, id: String, bytes: ByteArray): ByteArray {
        require(bytes.size >= 28) { "Invalid stored message" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("$scope:$id".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    fun addOutgoing(scope: String, message: Message) {
        val values = ContentValues().apply {
            put("id", message.id); put("scope", scope); put("peer", message.to)
            put("sender", message.from); put("recipient", message.to)
            put("body", seal(scope, message.id, message.content))
            put("status", Message.DeliveryStatus.SENDING.name)
            put("created_at", System.currentTimeMillis())
            put("sent_at", message.sentAt ?: System.currentTimeMillis())
        }
        require(writableDatabase.insertOrThrow("messages", null, values) != -1L)
    }

    /** Returns false for a redelivery already committed to local storage. */
    fun addIncoming(scope: String, message: Message): Boolean {
        val values = ContentValues().apply {
            put("id", message.id); put("scope", scope); put("peer", message.from)
            put("sender", message.from); put("recipient", message.to)
            put("body", seal(scope, message.id, message.content))
            put("created_at", System.currentTimeMillis())
            message.sentAt?.let { put("sent_at", it) }
        }
        return writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun hasIncoming(scope: String, id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM messages WHERE scope=? AND id=?", arrayOf(scope, id)).use { it.moveToFirst() }

    fun hasExchange(scope: String, id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM processed_exchange WHERE scope=? AND id=?", arrayOf(scope, id)).use { it.moveToFirst() }

    fun markExchange(scope: String, id: String) {
        val values = ContentValues().apply { put("scope", scope); put("id", id) }
        writableDatabase.insertWithOnConflict("processed_exchange", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun savePendingSession(scope: String, peer: String, id: String, pair: KeyPair) {
        val values = ContentValues().apply {
            put("scope", scope); put("peer", peer); put("id", id)
            put("public_key", seal(scope, "$id-public", pair.publicKey))
            put("secret_key", seal(scope, "$id-secret", pair.secretKey))
        }
        writableDatabase.insertWithOnConflict("pending_session", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pendingSession(scope: String, peer: String): Pair<String, KeyPair>? {
        readableDatabase.query("pending_session", arrayOf("id", "public_key", "secret_key"),
            "scope=? AND peer=?", arrayOf(scope, peer), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getString(0)
            return id to KeyPair(open(scope, "$id-public", cursor.getBlob(1)),
                open(scope, "$id-secret", cursor.getBlob(2)))
        }
    }

    fun clearPendingSession(scope: String, peer: String) {
        writableDatabase.delete("pending_session", "scope=? AND peer=?", arrayOf(scope, peer))
    }

    fun saveExchangeOutbox(scope: String, message: Message) {
        val values = ContentValues().apply {
            put("scope", scope); put("peer", message.to); put("id", message.id)
            put("public_key", seal(scope, message.id, message.content))
        }
        writableDatabase.insertWithOnConflict("exchange_outbox", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun exchangeOutbox(scope: String, userId: String): List<Message> {
        val result = mutableListOf<Message>()
        readableDatabase.query("exchange_outbox", arrayOf("id", "peer", "public_key"),
            "scope=?", arrayOf(scope), null, null, "rowid").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                result.add(Message(userId, cursor.getString(1), open(scope, id, cursor.getBlob(2)),
                    Message.MessageType.KEY_EXCHANGE, id))
            }
        }
        return result
    }

    fun clearExchangeOutbox(scope: String, id: String) {
        writableDatabase.delete("exchange_outbox", "scope=? AND id=?", arrayOf(scope, id))
    }

    fun setWire(scope: String, message: Message) {
        val values = ContentValues().apply { put("wire", seal(scope, message.id, message.content)) }
        writableDatabase.update("messages", values, "id=? AND scope=?", arrayOf(message.id, scope))
    }

    fun mark(scope: String, id: String, status: Message.DeliveryStatus) {
        val values = ContentValues().apply { put("status", status.name) }
        writableDatabase.update("messages", values,
            "id=? AND scope=? AND status IS NOT NULL AND status IN (${if (status == Message.DeliveryStatus.SENT) "'SENDING'" else "'SENDING','SENT'"})",
            arrayOf(id, scope))
    }

    fun history(scope: String): List<Message> {
        val messages = mutableListOf<Message>()
        readableDatabase.query("messages", arrayOf("id", "sender", "recipient", "body", "status", "sent_at"),
            "scope=?", arrayOf(scope), null, null, "created_at, rowid").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val status = cursor.getString(4)?.let(Message.DeliveryStatus::valueOf)
                messages.add(Message(cursor.getString(1), cursor.getString(2), open(scope, id, cursor.getBlob(3)),
                    Message.MessageType.DATA, id, status, if (cursor.isNull(5)) null else cursor.getLong(5)))
            }
        }
        return messages
    }

    fun pending(scope: String): List<Pair<Message, Message?>> {
        val pending = mutableListOf<Pair<Message, Message?>>()
        readableDatabase.query("messages", arrayOf("id", "sender", "recipient", "body", "wire", "sent_at"),
            "scope=? AND status='SENDING'", arrayOf(scope), null, null, "created_at").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val sender = cursor.getString(1); val recipient = cursor.getString(2)
                val plain = Message(sender, recipient, open(scope, id, cursor.getBlob(3)), Message.MessageType.DATA, id,
                    sentAt = if (cursor.isNull(5)) null else cursor.getLong(5))
                val wire = if (cursor.isNull(4)) null else Message(sender, recipient,
                    open(scope, id, cursor.getBlob(4)), Message.MessageType.DATA, id)
                pending.add(plain to wire)
            }
        }
        return pending
    }
}
