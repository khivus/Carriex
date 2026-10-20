package com.example.carriex.crypto.groups

import com.example.carriex.crypto.x3dh.SodiumX3dhPrimitives
import com.example.carriex.crypto.x3dh.X3dhCrypto
import com.example.carriex.crypto.x3dh.X3dhPrimitives
import com.example.carriex.messaging.MessageLimits
import com.example.carriex.messaging.MessagePayload
import com.example.carriex.model.Message
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sender chains with per-message keys, sender signatures and bounded out-of-order delivery.
 * This is the Carriex sender-key wire format, not a libsignal-compatible implementation.
 * Every membership epoch uses independent random chain and signing keys.
 */
class SenderKeys(private val ops: X3dhPrimitives = SodiumX3dhPrimitives(), private val clock: () -> Long = System::currentTimeMillis) {
    companion object { const val MAX_SKIP = 128; const val MAX_COUNTER = 1_000_000 }
    data class Encrypted(val state: JSONObject, val wire: ByteArray)
    data class Decrypted(val state: JSONObject, val message: Message)
    private fun hmac(key: ByteArray, step: Int) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(byteArrayOf(step.toByte()))
    }
    fun create(group: GroupState, sender: String): JSONObject {
        require(sender in group.members && !group.closed)
        val pair = ops.keyPair(); val chain = ops.random(32)
        return try {
            JSONObject().put("version", 1).put("group", group.id).put("epoch", group.epoch).put("membership", group.membershipHash)
                .put("sender", sender).put("key_id", X3dhCrypto.hex(ops.random(16))).put("chain", X3dhCrypto.encode(chain))
                .put("signing", X3dhCrypto.encode(pair.publicKey)).put("secret", X3dhCrypto.encode(pair.secretKey))
                .put("counter", 0).put("skipped", JSONObject())
        } finally { chain.fill(0); pair.secretKey.fill(0) }
    }
    fun distribution(state: JSONObject): JSONObject {
        require(state.getInt("counter") == 0) { "Initial sender key must be distributed before sending" }
        return JSONObject(state.toString()).apply { remove("secret"); remove("counter"); remove("skipped") }
    }
    fun receiveDistribution(group: GroupState, sender: String, distribution: JSONObject): JSONObject {
        require(distribution.keys().asSequence().toSet() == setOf("version", "group", "epoch", "membership", "sender", "key_id", "chain", "signing"))
        checkContext(group, sender, distribution)
        X3dhCrypto.decode(distribution.getString("chain"), 32).fill(0)
        X3dhCrypto.decode(distribution.getString("signing"), 32)
        return JSONObject(distribution.toString()).put("counter", 0).put("skipped", JSONObject())
    }
    private fun checkContext(group: GroupState, sender: String, json: JSONObject) {
        require(!group.closed && sender in group.members && json.get("version") == 1 && json.getString("group") == group.id &&
            json.get("epoch") == group.epoch && json.getString("membership") == group.membershipHash &&
            json.getString("sender") == sender && json.getString("key_id").matches(Regex("[0-9a-f]{32}"))) {
            "Sender key belongs to another group, sender or membership epoch"
        }
    }
    private fun aad(group: GroupState, sender: String, key: String, counter: Int, id: String) =
        "Carriex sender key message v1\n${group.id}\n${group.epoch}\n${group.membershipHash}\n$sender\n$key\n$counter\n$id\n".toByteArray(Charsets.US_ASCII)

    fun encrypt(group: GroupState, original: JSONObject, message: Message): Encrypted {
        require(message.to == group.id && message.id.matches(Regex("[0-9a-f]{32}")))
        checkContext(group, message.from, original)
        MessageLimits.requirePlaintext(message.content)
        val state = JSONObject(original.toString()); val counter = state.getInt("counter")
        require(counter in 0 until MAX_COUNTER) { "Sender chain exhausted; a membership refresh is required" }
        val chain = X3dhCrypto.decode(state.getString("chain"), 32)
        val key = hmac(chain, 1); val next = hmac(chain, 2)
        val secret = X3dhCrypto.decode(state.getString("secret"), 32)
        val payload = MessagePayload.encode(message.content, message.sentAt)
        try {
            val associated = aad(group, message.from, state.getString("key_id"), counter, message.id)
            val ciphertext = ops.encrypt(payload, associated, key)
            val wire = JSONObject().put("version", 1).put("group", group.id).put("epoch", group.epoch)
                .put("membership", group.membershipHash).put("sender", message.from).put("key_id", state.getString("key_id"))
                .put("counter", counter).put("id", message.id).put("ciphertext", X3dhCrypto.encode(ciphertext))
                .put("signature", X3dhCrypto.encode(ops.sign(secret, associated + ciphertext)))
            state.put("chain", X3dhCrypto.encode(next)).put("counter", counter + 1)
            return Encrypted(state, wire.toString().toByteArray(Charsets.UTF_8))
        } finally { chain.fill(0); key.fill(0); next.fill(0); secret.fill(0); payload.fill(0) }
    }

    fun decrypt(group: GroupState, original: JSONObject, sender: String, id: String, wire: ByteArray): Decrypted {
        require(wire.size <= 128 * 1024 && id.matches(Regex("[0-9a-f]{32}")))
        val envelope = JSONObject(String(wire, Charsets.UTF_8))
        require(envelope.keys().asSequence().toSet() == setOf("version", "group", "epoch", "membership", "sender", "key_id", "counter", "id", "ciphertext", "signature"))
        checkContext(group, sender, envelope); checkContext(group, sender, original)
        require(envelope.getString("id") == id && envelope.getString("key_id") == original.getString("key_id"))
        require(envelope.get("counter") is Int)
        val counter = envelope.getInt("counter")
        require(counter in 0 until MAX_COUNTER)
        val ciphertext = X3dhCrypto.decode(envelope.getString("ciphertext"))
        val associated = aad(group, sender, envelope.getString("key_id"), counter, id)
        require(ops.verify(X3dhCrypto.decode(original.getString("signing"), 32), associated + ciphertext,
            X3dhCrypto.decode(envelope.getString("signature"), 64))) { "Invalid group sender signature" }
        val state = JSONObject(original.toString()); val skipped = state.getJSONObject("skipped")
        skipped.keys().asSequence().toList().forEach { if(skipped.getJSONObject(it).getLong("expires") < clock()) skipped.remove(it) }
        var chain = X3dhCrypto.decode(state.getString("chain"), 32)
        val current = state.getInt("counter")
        val key: ByteArray
        try {
            if(counter < current) {
                val saved = skipped.optJSONObject(counter.toString()) ?: error("Duplicate group message or expired skipped key")
                key = X3dhCrypto.decode(saved.getString("key"), 32)
                skipped.remove(counter.toString())
            } else {
                require(counter - current <= MAX_SKIP && skipped.length() + counter - current <= MAX_SKIP) { "Group message gap exceeds skipped-key limit" }
                for(index in current until counter) {
                    val missing = hmac(chain, 1); val next = hmac(chain, 2)
                    skipped.put(index.toString(), JSONObject().put("key", X3dhCrypto.encode(missing)).put("expires", clock() + 30L * 86_400_000))
                    missing.fill(0); chain.fill(0); chain = next
                }
                key = hmac(chain, 1)
                val next = hmac(chain, 2)
                state.put("chain", X3dhCrypto.encode(next)).put("counter", counter + 1)
                next.fill(0)
            }
            try {
                val plain = ops.decrypt(ciphertext, associated, key)
                val decoded = try { MessagePayload.decode(plain) } finally { plain.fill(0) }
                MessageLimits.requirePlaintext(decoded.content)
                return Decrypted(state, Message(sender, group.id, decoded.content, Message.MessageType.DATA, id = id, sentAt = decoded.sentAt))
            } finally { key.fill(0) }
        } finally { chain.fill(0) }
    }
}
