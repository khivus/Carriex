package com.example.carriex.crypto.x3dh

import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.messaging.MessagePayload
import com.example.carriex.messaging.MessageLimits
import com.example.carriex.model.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/** Implementations must encrypt values at rest and provide transactions to the caller. */
interface X3dhStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(prefix: String): List<String>
}

/** X3DH bootstrap plus persistent classical Double Ratchet sessions (wire version 3). */
class X3dhProtocol(
    private val device: DeviceIdentity,
    private val store: X3dhStateStore,
    private val ops: X3dhPrimitives = SodiumX3dhPrimitives(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowWithoutOneTimeKey: () -> Boolean = { false },
) {
    companion object {
        const val MAX_PRIVATE_PREKEYS = 2048
        const val MAX_DAILY_PREKEYS = 200
        const val REFILL_INTERVAL = 6 * 3_600_000L
    }
    private val ownId get() = device.userId
    private val idPattern = Regex("[0-9a-f]{64}")
    private val keyIdPattern = Regex("[0-9a-f]{32}")
    private val day = 86_400_000L
    private val ratchet = DoubleRatchet(ops)
    private fun newId() = X3dhCrypto.hex(ops.random(16))
    private fun pair(json: JSONObject) = KeyPair(X3dhCrypto.decode(json.getString("public"), 32),
        X3dhCrypto.decode(json.getString("secret"), 32))
    private fun prekey(path: String, label: String): KeyPair {
        val value = JSONObject(store.get(path) ?: error("$label already consumed or expired"))
        val activeSigned = path == ("local/spk/" + store.get("local/active-spk"))
        require(activeSigned || !value.has("retain_until") || value.getLong("retain_until") >= clock()) { "$label has expired" }
        return pair(value)
    }
    private fun savePair(key: String, value: KeyPair): JSONObject {
        val json = JSONObject().put("public", X3dhCrypto.encode(value.publicKey))
            .put("secret", X3dhCrypto.encode(value.secretKey)).put("created", clock())
        store.put(key, json.toString()); value.secretKey.fill(0)
        return json
    }
    private fun identity(): KeyPair {
        val saved = store.get("local/identity") ?: savePair("local/identity", ops.keyPair()).toString()
        return pair(JSONObject(saved))
    }
    private fun certificate(identity: ByteArray) = JSONObject().put("user_id", ownId)
        .put("identity_key", X3dhCrypto.encode(identity)).put("auth_key", X3dhCrypto.encode(device.publicKey))
        .put("binding", X3dhCrypto.encode(device.signEncryptionIdentity(identity)))

    /** Exact pending publication survives crashes and lost HTTP responses. */
    fun publication(available: Int, ttlSeconds: Long, serverRevision: Long = 0): JSONObject {
        require(ttlSeconds in 1..31_536_000) { "Unsupported server message retention" }
        val previousTtl = store.get("local/ttl")?.toLongOrNull() ?: 0
        val ttl = maxOf(ttlSeconds * 1000, previousTtl)
        if (previousTtl > 0 && ttl > previousTtl) {
            listOf("local/spk/", "local/opk/", "session/").forEach { prefix -> store.keys(prefix).forEach { key ->
                val value = JSONObject(store.get(key)!!)
                if (value.has("retain_until")) {
                    value.put("retain_until", value.getLong("retain_until") + ttl - previousTtl)
                    value.optJSONObject("ratchet")?.getJSONObject("skipped")?.let { skipped ->
                        skipped.keys().forEach { name ->
                            val entry = skipped.getJSONObject(name)
                            entry.put("expires", entry.getLong("expires") + ttl - previousTtl)
                        }
                    }
                    store.put(key, value.toString())
                }
            } }
        }
        store.put("local/ttl", ttl.toString())
        maintainPrekeys()
        maintain()
        store.get("local/pending-publication")?.let { return JSONObject(it) }
        val revision = maxOf(serverRevision, store.get("local/revision")?.toLongOrNull() ?: 0) + 1
        require(revision <= 0x7fffffff)
        val identity = identity()
        try {
            var active = store.get("local/active-spk")
            if (active == null || clock() - JSONObject(store.get("local/spk/$active")!!).optLong("activated", clock()) >= 7 * day) {
                active = newId()
                val created = savePair("local/spk/$active", ops.keyPair())
                created.put("signature", X3dhCrypto.encode(ops.sign(identity.secretKey,
                    X3dhCrypto.encodeKey(X3dhCrypto.decode(created.getString("public"), 32)))))
                store.put("local/spk/$active", created.toString())
            }
            val signed = JSONObject(store.get("local/spk/$active")!!)
            val upload = certificate(identity.publicKey).put("version", 2).put("revision", revision)
                .put("publication_id", newId()).put("signed_prekey", JSONObject().put("id", active)
                    .put("key", signed.getString("public")).put("signature", signed.getString("signature")))
            val keys = JSONArray()
            val budget = store.get("local/refill-budget")?.let(::JSONObject)
                ?.takeIf { clock() - it.getLong("since") < day }
                ?: JSONObject().put("since", clock()).put("count", 0)
            val room = MAX_PRIVATE_PREKEYS - store.keys("local/opk/").size
            val due = clock() >= (store.get("local/next-refill")?.toLongOrNull() ?: 0)
            val count = if (available < 10 && due)
                minOf(50, room.coerceAtLeast(0), (MAX_DAILY_PREKEYS - budget.getInt("count")).coerceAtLeast(0)) else 0
            if (available < 10 && count == 0) store.put("local/prekey-warning",
                "One-time key replenishment is temporarily paused by the local key budget. Existing secure chats still work.")
            else store.remove("local/prekey-warning")
            repeat(count) {
                val id = revision.toString(16).padStart(8, '0') + X3dhCrypto.hex(ops.random(12))
                val key = savePair("local/opk/$id", ops.keyPair())
                keys.put(JSONObject().put("id", id).put("key", key.getString("public")))
            }
            if (count > 0) {
                store.put("local/refill-budget", budget.put("count", budget.getInt("count") + count).toString())
                store.put("local/next-refill", (clock() + REFILL_INTERVAL).toString())
            }
            upload.put("one_time_prekeys", keys)
            store.put("local/pending-publication", upload.toString())
            return upload
        } finally { identity.secretKey.fill(0) }
    }

    /** Only a matching server receipt can retire the old advertised signed key. */
    fun published(receipt: JSONObject) {
        val pending = store.get("local/pending-publication")?.let(::JSONObject) ?: return
        if(receipt.getLong("revision") < pending.getLong("revision")) return
        val signedId = pending.getJSONObject("signed_prekey").getString("id")
        require(receipt.getLong("revision") == pending.getLong("revision") &&
            receipt.getString("publication_id") == pending.getString("publication_id") &&
            receipt.getString("signed_prekey_id") == signedId) { "Prekey publication receipt mismatch" }
        val ttl = store.get("local/ttl")!!.toLong()
        val oldActive = store.get("local/active-spk")
        if (oldActive != null && oldActive != signedId) {
            val old = JSONObject(store.get("local/spk/$oldActive")!!)
                .put("retired", clock()).put("retain_until", clock() + ttl + 37 * day)
            store.put("local/spk/$oldActive", old.toString())
        }
        val active = JSONObject(store.get("local/spk/$signedId")!!)
        if (!active.has("activated")) active.put("activated", clock())
        store.put("local/spk/$signedId", active.toString())
        store.put("local/active-spk", signedId)
        // Bound legacy/orphaned keys too, using a safe post-confirmation deadline.
        store.keys("local/spk/").filter { it != "local/spk/$signedId" }.forEach { key ->
            val value = JSONObject(store.get(key)!!)
            if (!value.has("retain_until")) {
                value.put("retain_until", clock() + ttl + 37 * day)
                store.put(key, value.toString())
            }
        }
        // Include legacy keys whose publication outcome was unknown at upgrade.
        store.keys("local/opk/").forEach { key ->
            val value = JSONObject(store.get(key)!!)
            if (!value.has("retain_until")) {
                // A sender may claim on day seven, send for thirty days, then queue for TTL.
                value.put("retain_until", clock() + ttl + 44 * day)
                store.put(key, value.toString())
            }
        }
        store.put("local/revision", pending.getLong("revision").toString())
        store.remove("local/pending-publication"); store.remove("local/upload-batch")
        maintainPrekeys()
        maintain()
    }

    fun prekeyWarning(): String? = store.get("local/prekey-warning")

    /** Inventory is bounded; prekey maintenance runs on publication, never on each message. */
    private fun maintainPrekeys() {
        val pending = store.get("local/pending-publication")?.let(::JSONObject)
        val pendingSigned = pending?.getJSONObject("signed_prekey")?.getString("id")
        val pendingIds = pending?.getJSONArray("one_time_prekeys")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
        }.orEmpty()
        listOf("local/spk/", "local/opk/").forEach { prefix -> store.keys(prefix).forEach { key ->
            val value = JSONObject(store.get(key)!!)
            val id = key.substringAfterLast('/')
            if (id != pendingSigned && id != store.get("local/active-spk") && id !in pendingIds &&
                value.has("retain_until") && value.getLong("retain_until") < clock()) store.remove(key)
        } }
    }

    private fun maintain() {
        store.keys("session/").forEach { key ->
            val value = JSONObject(store.get(key)!!)
            // Fixed-key sessions cannot be safely converted into ratchet state.
            if (!value.has("ratchet")) {
                store.remove(key)
                return@forEach
            }
            var changed = false
            // Old sessions had no receiver-owned admission time. Start their
            // allowance conservatively now, without trusting the remote clock.
            if (!value.has("admitted")) { value.put("admitted", clock()); changed = true }
            if (DoubleRatchet.prune(value.getJSONObject("ratchet"), clock()) || changed) {
                store.put(key, value.toString())
            }
            if (value.getLong("retain_until") < clock()) store.remove(key)
        }
    }

    private fun newSessionAllowed(peer: String) {
        val sessions = store.keys("session/").map { JSONObject(store.get(it)!!) }
        require(sessions.size < 4096) { "Session storage limit reached" }
        val own = sessions.filter { it.getString("peer") == peer }
        require(own.size < 16 && own.count { clock() - it.optLong("admitted", clock()) < day } < 4) {
            "Too many new sessions from this contact; wait or block the contact"
        }
    }
    fun wireUsable(wire: Message): Boolean = try {
        maintain()
        val envelope = JSONObject(String(wire.content, Charsets.UTF_8))
        require(envelope.getInt("version") == 3)
        val sid = envelope.getString("sid")
        store.get("session/$sid")?.let {
            val saved = JSONObject(it)
            saved.getString("peer") == wire.to && wire.from == ownId && saved.getLong("retain_until") > clock()
        } == true
    } catch (_: Exception) { false }

    /** Retire sending eligibility only; old incoming/outbox packets retain their keys. */
    fun restartSending(peer: String) {
        require(idPattern.matches(peer) && peer != ownId)
        store.keys("session/").forEach { key ->
            val value = JSONObject(store.get(key)!!)
            if (value.getString("peer") == peer) {
                value.put("send_until", 0)
                store.put(key, value.toString())
            }
        }
    }

    /** Forget this peer's pins, root/chain keys and skipped keys, retaining local identity and other peers. */
    fun forgetPeer(peer: String) {
        require(idPattern.matches(peer) && peer != ownId)
        store.keys("session/").forEach { key ->
            if(JSONObject(store.get(key)!!).getString("peer") == peer) store.remove(key)
        }
        store.remove("peer/$peer")
        store.remove("peer-protection/$peer")
    }

    private fun verifyCertificate(json: JSONObject, expected: String): ByteArray {
        require(idPattern.matches(expected) && json.getString("user_id") == expected) { "Wrong contact identity" }
        val ik = X3dhCrypto.decode(json.getString("identity_key"), 32)
        X3dhCrypto.edwardsPublic(ik)
        val authBytes = X3dhCrypto.decode(json.getString("auth_key"))
        require(authBytes.size <= 256 && X3dhCrypto.hex(X3dhCrypto.hash(authBytes)) == expected) { "Contact key does not match ID" }
        val auth = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(authBytes)) as ECPublicKey
        require(auth.params.curve.field.fieldSize == 256 && auth.encoded.contentEquals(authBytes))
        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(auth); update(X3dhCrypto.binding(expected, ik))
            verify(X3dhCrypto.decode(json.getString("binding")))
        }
        require(valid) { "Invalid contact encryption identity signature" }
        val pinned = store.get("peer/$expected")
        require(pinned == null || pinned == X3dhCrypto.encode(ik)) {
            "Contact encryption identity changed. Verify with your friend; sending is blocked."
        }
        return ik
    }

    private val headerFields = listOf("sender", "recipient", "identity_key", "auth_key", "binding",
        "recipient_identity", "ephemeral", "signed_id", "one_time_id", "created_at", "protocol", "ratchet_key")
    private fun headerBytes(header: JSONObject): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            // X3DH identity AD prefix, then unambiguous application-specific fields.
            out.write("Carriex session v3\n".toByteArray(Charsets.US_ASCII))
            out.write(X3dhCrypto.encodeKey(X3dhCrypto.decode(header.getString("identity_key"), 32)))
            out.write(X3dhCrypto.encodeKey(X3dhCrypto.decode(header.getString("recipient_identity"), 32)))
            headerFields.forEach { field ->
                val bytes = header.getString(field).toByteArray(Charsets.US_ASCII)
                require(bytes.size <= 512) { "Oversized session header" }
                out.writeInt(bytes.size); out.write(bytes)
            }
        }; buffer.toByteArray()
    }
    private fun sessionId(header: JSONObject) = X3dhCrypto.hex(X3dhCrypto.hash(headerBytes(header)))
    private fun aad(message: Message, sid: String, mode: String, setup: JSONObject) =
        "Carriex message v3\n$sid\n$mode\n${message.from}\n${message.to}\n${message.id}\n".toByteArray(Charsets.US_ASCII) + headerBytes(setup)
    private fun preferred(peer: String): JSONObject? = store.keys("session/").map { JSONObject(store.get(it)!!) }
        .filter { it.getString("peer") == peer && it.getLong("send_until") > clock() &&
            it.getJSONObject("ratchet").getInt("ns") <= DoubleRatchet.MAX_COUNTER &&
            (allowWithoutOneTimeKey() || it.getJSONObject("header").getString("one_time_id").isNotEmpty()) }
        .sortedWith(compareByDescending<JSONObject> { it.getLong("created") }.thenBy { it.getString("sid") }).firstOrNull()

    /** Read-only preflight so group uploads can fetch bundles before taking a write transaction. */
    fun needsPrekeys(peer: String): Boolean = preferred(peer) == null

    /** Caller commits new session + exact wire ciphertext in the same transaction. */
    fun encrypt(message: Message, claim: () -> JSONObject): Message {
        require(message.from == ownId && idPattern.matches(message.to) && keyIdPattern.matches(message.id))
        MessageLimits.requirePlaintext(message.content)
        maintain()
        var session = preferred(message.to)
        if (session == null) {
            val bundle = claim()
            require(bundle.getInt("version") == 2)
            val remote = verifyCertificate(bundle, message.to)
            val signed = bundle.getJSONObject("signed_prekey")
            val signedKey = X3dhCrypto.decode(signed.getString("key"), 32)
            require(ops.verify(remote, X3dhCrypto.encodeKey(signedKey), X3dhCrypto.decode(signed.getString("signature"), 64))) {
                "Invalid signed prekey signature"
            }
            val oneTime = if (bundle.isNull("one_time_prekey")) null else bundle.getJSONObject("one_time_prekey")
            require(oneTime != null || allowWithoutOneTimeKey()) { "Contact has no one-time keys. Wait for replenishment or allow fallback in Chat security." }
            newSessionAllowed(message.to)
            require(keyIdPattern.matches(signed.getString("id")) &&
                (oneTime == null || keyIdPattern.matches(oneTime.getString("id"))))
            val identity = identity(); val ephemeral = ops.keyPair(); val initialRatchet = ops.keyPair()
            try {
                val root = X3dhCrypto.initiate(ops, identity, ephemeral, remote, signedKey,
                    oneTime?.let { X3dhCrypto.decode(it.getString("key"), 32) })
                try {
                    val state = ratchet.initializeAlice(root, signedKey, initialRatchet)
                    val cert = certificate(identity.publicKey)
                    val header = JSONObject().put("sender", ownId).put("recipient", message.to)
                    listOf("identity_key", "auth_key", "binding").forEach { header.put(it, cert.getString(it)) }
                    header.put("recipient_identity", X3dhCrypto.encode(remote)).put("ephemeral", X3dhCrypto.encode(ephemeral.publicKey))
                        .put("signed_id", signed.getString("id")).put("one_time_id", oneTime?.getString("id") ?: "").put("created_at", clock().toString())
                        .put("protocol", "Carriex Double Ratchet v1").put("ratchet_key", state.getString("dhs_public"))
                    session = JSONObject().put("sid", sessionId(header)).put("peer", message.to).put("role", "initiator")
                        .put("ratchet", state).put("header", header)
                        .put("created", clock()).put("admitted", clock()).put("send_until", clock() + 30 * day)
                        .put("retain_until", clock() + 37 * day + (store.get("local/ttl")?.toLongOrNull() ?: 30 * day))

                } finally { root.fill(0) }
            } finally { identity.secretKey.fill(0); ephemeral.secretKey.fill(0); initialRatchet.secretKey.fill(0) }
        }
        val selected = session!!
        val sid = selected.getString("sid")
        val initiator = selected.getString("role") == "initiator"
        val mode = if (initiator) "init" else "reply"
        val payload = MessagePayload.encode(message.content, message.sentAt, message.type)
        try {
            val encrypted = ratchet.encrypt(selected.getJSONObject("ratchet"), payload,
                aad(message, sid, mode, selected.getJSONObject("header")), clock())
            val envelope = JSONObject().put("version", 3).put("sid", sid).put("mode", mode)
                .put("ratchet", encrypted.header)
            if (initiator) envelope.put("header", selected.getJSONObject("header"))
            envelope.put("ciphertext", X3dhCrypto.encode(encrypted.ciphertext))
            val wire = envelope.toString().toByteArray(Charsets.UTF_8)
            MessageLimits.requireWire(wire)
            selected.put("ratchet", encrypted.state)
            store.put("session/$sid", selected.toString())
            if (initiator) store.put("peer/${message.to}", selected.getJSONObject("header").getString("recipient_identity"))
            store.put("peer-protection/${message.to}", if(selected.getJSONObject("header").getString("one_time_id").isEmpty()) "fallback" else "one-time")
            return message.copy(content = wire, type = Message.MessageType.DATA)
        } finally { payload.fill(0) }
    }

    /** Mutations only commit with authenticated plaintext and local message deduplication. */
    fun decrypt(message: Message): Message {
        require(message.type == Message.MessageType.DATA && message.to == ownId && idPattern.matches(message.from) && keyIdPattern.matches(message.id))
        require(message.content.size <= 1_000_000) { "Oversized encrypted message" }
        maintain()
        val envelope = JSONObject(String(message.content, Charsets.UTF_8))
        require(envelope.getInt("version") == 3) { "Contact must update Carriex to use Double Ratchet; fixed-key messages are rejected" }
        val sid = envelope.getString("sid"); require(idPattern.matches(sid))
        val mode = envelope.getString("mode"); require(mode == "init" || mode == "reply")
        val ratchetHeader = envelope.getJSONObject("ratchet")
        DoubleRatchet.headerBytes(ratchetHeader)
        var session = store.get("session/$sid")?.let(::JSONObject)
        var consume: String? = null
        var remoteKey: ByteArray? = null
        if (mode == "init") {
            val header = envelope.getJSONObject("header")
            require(header.getString("protocol") == "Carriex Double Ratchet v1")
            require(header.getString("sender") == message.from && header.getString("recipient") == ownId && sessionId(header) == sid)
            val cert = JSONObject().put("user_id", message.from)
            listOf("identity_key", "auth_key", "binding").forEach { cert.put(it, header.getString(it)) }
            remoteKey = verifyCertificate(cert, message.from)
            if (session == null) {
                newSessionAllowed(message.from)
                val ttl = store.get("local/ttl")?.toLongOrNull() ?: 30 * day
                val created = header.getString("created_at").toLong()
                require(created in 0..clock() + 300000 && created + 37 * day + ttl >= clock()) { "Initial session expired" }
                require(ratchetHeader.getString("dh") == header.getString("ratchet_key")) { "Unknown initial ratchet chain" }
                val spkId = header.getString("signed_id"); val opkId = header.getString("one_time_id")
                require(keyIdPattern.matches(spkId) && (opkId.isEmpty() || keyIdPattern.matches(opkId)))
                require(opkId.isNotEmpty() || allowWithoutOneTimeKey()) { "Session used no one-time key. Allow fallback in Chat security to accept it." }
                val identity = identity()
                var signed: KeyPair? = null
                var oneTime: KeyPair? = null
                try {
                    signed = prekey("local/spk/$spkId", "Signed prekey")
                    oneTime = if (opkId.isEmpty()) null else prekey("local/opk/$opkId", "One-time prekey")
                    require(header.getString("recipient_identity") == X3dhCrypto.encode(identity.publicKey)) { "Wrong recipient encryption identity" }
                    val root = X3dhCrypto.respond(ops, identity, signed, oneTime, remoteKey,
                        X3dhCrypto.decode(header.getString("ephemeral"), 32))
                    try {
                        session = JSONObject().put("sid", sid).put("peer", message.from).put("role", "responder")
                            .put("ratchet", ratchet.initializeBob(root, signed)).put("header", header)
                            .put("created", created).put("admitted", clock()).put("send_until", created + 30 * day).put("retain_until", created + 37 * day + ttl)
                    } finally { root.fill(0) }
                    if (oneTime != null) consume = "local/opk/$opkId"
                } finally { identity.secretKey.fill(0); signed?.secretKey?.fill(0); oneTime?.secretKey?.fill(0) }
            }
            require(session!!.getString("role") == "responder" &&
                headerBytes(session!!.getJSONObject("header")).contentEquals(headerBytes(header)))
        } else {
            require(session != null && session!!.getString("role") == "initiator") { "Unknown reply session" }
        }
        val selected = session!!
        require(selected.getString("peer") == message.from) { "Session belongs to another contact" }
        val decrypted = ratchet.decrypt(selected.getJSONObject("ratchet"), ratchetHeader,
            X3dhCrypto.decode(envelope.getString("ciphertext")), aad(message, sid, mode, selected.getJSONObject("header")),
            clock(), store.get("local/ttl")?.toLongOrNull() ?: 30 * day)
        val decoded = try { MessagePayload.decode(decrypted.plaintext) } finally { decrypted.plaintext.fill(0) }
        val otherSkipped = store.keys("session/").filter { it != "session/$sid" }.sumOf { key ->
            val other = JSONObject(store.get(key)!!)
            if (other.getString("peer") == message.from) other.getJSONObject("ratchet").getJSONObject("skipped").length() else 0
        }
        require(otherSkipped + decrypted.state.getJSONObject("skipped").length() <= 2048) { "Skipped message key limit for this contact reached" }
        selected.put("ratchet", decrypted.state)
        store.put("session/$sid", selected.toString())
        remoteKey?.let { store.put("peer/${message.from}", X3dhCrypto.encode(it)) }
        consume?.let(store::remove)
        store.put("peer-protection/${message.from}",if(selected.getJSONObject("header").getString("one_time_id").isEmpty()) "fallback" else "one-time")
        return message.copy(content = decoded.content, sentAt = decoded.sentAt, type = decoded.type)
    }
}
