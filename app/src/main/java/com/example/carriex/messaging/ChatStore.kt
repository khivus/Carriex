package com.example.carriex.messaging

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.carriex.model.Message
import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.crypto.x3dh.X3dhStateStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Phone-owned history and outgoing queue. Message bodies are encrypted before SQLite sees them. */
class ChatStore(context: Context, databaseName: String = "chat-v1.db") : SQLiteOpenHelper(context, databaseName, null, 8) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id TEXT NOT NULL, scope TEXT NOT NULL, peer TEXT NOT NULL,
            sender TEXT NOT NULL, recipient TEXT NOT NULL, body BLOB NOT NULL,
            wire BLOB, status TEXT, created_at INTEGER NOT NULL, sent_at INTEGER,
            PRIMARY KEY(scope,id)
        )""")
        db.execSQL("CREATE INDEX conversation ON messages(scope, peer, created_at)")
        db.execSQL("CREATE TABLE processed_exchange (scope TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(scope,id))")
        createExchangeTables(db)
        createX3dhTables(db)
        createQuarantine(db)
        createRemovedMessages(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion !in 1..7 || newVersion !in 3..8 || oldVersion >= newVersion)
            throw IllegalStateException("Chat database migration from $oldVersion is required")
        if (oldVersion == 1) createExchangeTables(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sent_at INTEGER")
            // Old outgoing rows were created when sending; incoming rows only have arrival time.
            db.execSQL("UPDATE messages SET sent_at=created_at WHERE status IS NOT NULL")
        }
        if (oldVersion < 4 && newVersion >= 4) {
            createX3dhTables(db)
            // Re-encrypt unsent legacy data using X3DH; retain all saved history.
            val uncertain = db.rawQuery("SELECT id,scope,body FROM messages WHERE status='SENDING' AND wire IS NOT NULL", null).use { rows ->
                buildList { while (rows.moveToNext()) add(Triple(rows.getString(0), rows.getString(1), rows.getBlob(2))) }
            }
            uncertain.forEach { (oldId, scope, body) ->
                // A relay may already hold the old ciphertext after a lost HTTP
                // response. A fresh ID avoids an immutable-ID content conflict.
                val newId = java.util.UUID.randomUUID().toString().replace("-", "")
                val plain = open(scope, oldId, body)
                try {
                    val values = ContentValues().apply { put("id", newId); put("body", seal(scope, newId, plain)) }
                    check(db.update("messages", values, "id=? AND scope=?", arrayOf(oldId, scope)) == 1)
                } finally { plain.fill(0) }
            }
            db.execSQL("UPDATE messages SET wire=NULL WHERE status='SENDING'")
            db.execSQL("DELETE FROM pending_session")
            db.execSQL("DELETE FROM exchange_outbox")
        }
        if (oldVersion < 5 && newVersion >= 5) {
            // IDs are authenticated within a server scope; another relay must not
            // be able to collide with a saved message from a different scope.
            db.execSQL("""CREATE TABLE messages_v5 (
                id TEXT NOT NULL, scope TEXT NOT NULL, peer TEXT NOT NULL,
                sender TEXT NOT NULL, recipient TEXT NOT NULL, body BLOB NOT NULL,
                wire BLOB, status TEXT, created_at INTEGER NOT NULL, sent_at INTEGER,
                PRIMARY KEY(scope,id))""")
            db.execSQL("INSERT INTO messages_v5 SELECT id,scope,peer,sender,recipient,body,wire,status,created_at,sent_at FROM messages")
            db.execSQL("DROP TABLE messages")
            db.execSQL("ALTER TABLE messages_v5 RENAME TO messages")
            db.execSQL("CREATE INDEX conversation ON messages(scope,peer,created_at)")
        }
        if (oldVersion < 6 && newVersion >= 6) createQuarantine(db)
        if (oldVersion < 8 && newVersion >= 8) createRemovedMessages(db)
        if (oldVersion < 7 && newVersion >= 7) {
            // A relay may already hold fixed-key wire data after a lost response.
            // Reseal under a new ID, preserving text and time, to avoid ID conflicts.
            val pending = db.rawQuery("SELECT id,scope,body FROM messages WHERE status='SENDING' AND wire IS NOT NULL", null).use { rows ->
                buildList { while (rows.moveToNext()) add(Triple(rows.getString(0), rows.getString(1), rows.getBlob(2))) }
            }
            pending.forEach { (oldId, scope, body) ->
                val newId = java.util.UUID.randomUUID().toString().replace("-", "")
                val plain = open(scope, oldId, body)
                try {
                    val values = ContentValues().apply { put("id", newId); put("body", seal(scope, newId, plain)); putNull("wire") }
                    check(db.update("messages", values, "id=? AND scope=?", arrayOf(oldId, scope)) == 1)
                } finally { plain.fill(0) }
            }
            db.execSQL("DELETE FROM x3dh_state WHERE substr(name,1,8)='session/' OR substr(name,1,16)='peer-protection/' OR name='local/legacy-until'")
        }
    }

    private fun createQuarantine(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE quarantined (scope TEXT NOT NULL,id TEXT NOT NULL,peer TEXT NOT NULL,body BLOB NOT NULL,reason TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(scope,id))")
    }
    private fun createRemovedMessages(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE removed_message_ids (scope TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(scope,id))")
    }

    fun wasRemoved(scope: String, id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM removed_message_ids WHERE scope=? AND id=?", arrayOf(scope,id)).use { it.moveToFirst() }

    /** Erase local content while retaining only IDs needed to suppress redeliveries. */
    fun removeChat(scope: String, peer: String): List<String> = atomic {
        val db = writableDatabase
        val ids = db.rawQuery("SELECT id FROM messages WHERE scope=? AND peer=? UNION SELECT id FROM quarantined WHERE scope=? AND peer=?",
            arrayOf(scope,peer,scope,peer)).use { rows -> buildList { while(rows.moveToNext()) add(rows.getString(0)) } }
        db.execSQL("INSERT OR IGNORE INTO removed_message_ids SELECT scope,id FROM messages WHERE scope=? AND peer=?", arrayOf(scope,peer))
        db.execSQL("INSERT OR IGNORE INTO removed_message_ids SELECT scope,id FROM quarantined WHERE scope=? AND peer=?", arrayOf(scope,peer))
        for(table in listOf("messages","quarantined","pending_session","exchange_outbox"))
            db.delete(table,"scope=? AND peer=?",arrayOf(scope,peer))
        ids
    }
    data class Request(val peer: String, val reason: String, val count: Int)
    private fun pruneRequests() {
        writableDatabase.delete("quarantined", "created<?", arrayOf((System.currentTimeMillis()-30L*86400000).toString()))
    }
    fun requests(scope: String): List<Request> {
        pruneRequests()
        return readableDatabase.rawQuery(
        "SELECT peer,reason,count(*) FROM quarantined WHERE scope=? GROUP BY peer ORDER BY max(created) DESC",
        arrayOf(scope)).use { rows -> buildList { while(rows.moveToNext()) add(Request(rows.getString(0), rows.getString(1), rows.getInt(2))) } }
    }
    fun quarantine(scope: String, message: Message, reason: String): Boolean {
        if(wasRemoved(scope,message.id)) return true
        pruneRequests()
        if (hasQuarantined(scope,message.id)) return true
        val stats = readableDatabase.rawQuery("SELECT count(*),coalesce(sum(length(body)),0),coalesce(sum(CASE WHEN peer=? THEN 1 ELSE 0 END),0) FROM quarantined WHERE scope=?",
            arrayOf(message.from,scope)).use { it.moveToFirst(); Triple(it.getInt(0),it.getLong(1),it.getInt(2)) }
        if (stats.first >= 64 || stats.third >= 2 || stats.second + message.content.size*2 > 4*1024*1024) return false
        val bytes = org.json.JSONObject().put("from",message.from).put("to",message.to).put("id",message.id)
            .put("type",message.type.ordinal).put("content",com.example.carriex.crypto.x3dh.X3dhCrypto.encode(message.content)).toString().toByteArray()
        val values=ContentValues().apply { put("scope",scope);put("id",message.id);put("peer",message.from)
            put("body",seal(scope,"request:${message.id}",bytes));put("reason",reason.take(256));put("created",System.currentTimeMillis()) }
        return writableDatabase.insertOrThrow("quarantined",null,values) != -1L
    }
    fun hasQuarantined(scope:String,id:String):Boolean = readableDatabase.rawQuery("SELECT 1 FROM quarantined WHERE scope=? AND id=?",arrayOf(scope,id)).use { it.moveToFirst() }
    fun requestMessages(scope:String,peer:String):List<Message> = readableDatabase.rawQuery(
        "SELECT id,body FROM quarantined WHERE scope=? AND peer=? ORDER BY created",arrayOf(scope,peer)).use { rows -> buildList {
        while(rows.moveToNext()) {
            val data=org.json.JSONObject(String(open(scope,"request:${rows.getString(0)}",rows.getBlob(1))))
            add(Message(data.getString("from"),data.getString("to"),com.example.carriex.crypto.x3dh.X3dhCrypto.decode(data.getString("content")),
                Message.MessageType.values()[data.getInt("type")],data.getString("id")))
        }
    } }
    fun removeRequest(scope:String,id:String) { writableDatabase.delete("quarantined","scope=? AND id=?",arrayOf(scope,id)) }
    fun discardRequests(scope:String,peer:String) { writableDatabase.delete("quarantined","scope=? AND peer=?",arrayOf(scope,peer)) }

    private fun createX3dhTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE x3dh_state (scope TEXT NOT NULL, name TEXT NOT NULL, body BLOB NOT NULL, PRIMARY KEY(scope,name))")
    }

    /** One durable commit for session, prekey consumption, history and ciphertext. */
    @Synchronized
    fun <T> atomic(action: () -> T): T {
        val database = writableDatabase
        database.beginTransaction()
        return try { action().also { database.setTransactionSuccessful() } }
        finally { database.endTransaction() }
    }

    fun x3dh(scope: String): X3dhStateStore = object : X3dhStateStore {
        override fun get(key: String): String? = readableDatabase.rawQuery(
            "SELECT body FROM x3dh_state WHERE scope=? AND name=?", arrayOf(scope, key)).use {
            if (it.moveToFirst()) String(open(scope, "x3dh:$key", it.getBlob(0)), Charsets.UTF_8) else null
        }
        override fun put(key: String, value: String) {
            val values = ContentValues().apply {
                put("scope", scope); put("name", key); put("body", seal(scope, "x3dh:$key", value.toByteArray(Charsets.UTF_8)))
            }
            writableDatabase.insertWithOnConflict("x3dh_state", null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
                check(it != -1L) { "Unable to save encryption state" }
            }
        }
        override fun remove(key: String) { writableDatabase.delete("x3dh_state", "scope=? AND name=?", arrayOf(scope, key)) }
        override fun keys(prefix: String): List<String> = readableDatabase.rawQuery(
            "SELECT name FROM x3dh_state WHERE scope=? AND substr(name,1,?)=? ORDER BY name",
            arrayOf(scope, prefix.length.toString(), prefix)).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
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
        "SELECT 1 FROM messages WHERE scope=? AND id=? UNION ALL SELECT 1 FROM removed_message_ids WHERE scope=? AND id=? LIMIT 1",
        arrayOf(scope, id, scope, id)).use { it.moveToFirst() }

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
        check(writableDatabase.update("messages", values, "id=? AND scope=?", arrayOf(message.id, scope)) == 1) {
            "Outgoing message was not saved"
        }
    }

    fun savedWire(scope: String, message: Message): Message? = readableDatabase.rawQuery(
        "SELECT wire FROM messages WHERE scope=? AND id=? AND sender=? AND recipient=? AND wire IS NOT NULL",
        arrayOf(scope, message.id, message.from, message.to)).use {
        if (it.moveToFirst()) message.copy(content = open(scope, message.id, it.getBlob(0)), sentAt = null) else null
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
