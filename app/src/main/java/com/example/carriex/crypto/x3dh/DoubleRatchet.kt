package com.example.carriex.crypto.x3dh

import com.example.carriex.crypto.model.KeyPair
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Classical Double Ratchet (Signal section 3). Returned state is committed by ChatStore.
 * Operations copy their input: authentication failures never consume keys or advance counters.
 */
class DoubleRatchet(private val ops: X3dhPrimitives) {
    companion object {
        const val MAX_SKIP = 1000
        const val MAX_COUNTER = Int.MAX_VALUE - 1
        private val HEADER_FIELDS = setOf("dh", "pn", "n")

        internal fun rootKdf(root: ByteArray, dh: ByteArray): Pair<ByteArray, ByteArray> {
            require(root.size == 32 && dh.size == 32)
            val output = X3dhCrypto.hkdf(dh, root, "Carriex Double Ratchet v1 root", 64)
            return try { output.copyOfRange(0, 32) to output.copyOfRange(32, 64) }
            finally { output.fill(0) }
        }

        internal fun chainKdf(chain: ByteArray): Pair<ByteArray, ByteArray> {
            require(chain.size == 32)
            fun hmac(value: Byte) = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(chain, "HmacSHA256")); doFinal(byteArrayOf(value))
            }
            return hmac(2) to hmac(1) // next chain key, one-use message key
        }

        private fun counter(json: JSONObject, name: String): Int {
            val number = json.get(name)
            require(number is Int || number is Long) { "Invalid ratchet counter" }
            val value = (number as Number).toLong()
            val maximum = if (name == "pn") Int.MAX_VALUE.toLong() else MAX_COUNTER.toLong()
            require(value in 0..maximum) { "Ratchet counter out of range" }
            return value.toInt()
        }

        fun headerBytes(header: JSONObject): ByteArray {
            require(header.keys().asSequence().toSet() == HEADER_FIELDS) { "Invalid ratchet header" }
            val publicKey = X3dhCrypto.decode(header.getString("dh"), 32)
            X3dhCrypto.edwardsPublic(publicKey) // Reject noncanonical X25519 encodings.
            val pn = counter(header, "pn"); val n = counter(header, "n")
            return ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.write(X3dhCrypto.encodeKey(publicKey)); out.writeInt(pn); out.writeInt(n)
                }; buffer.toByteArray()
            }
        }

        /** Call during normal maintenance as well as message processing. */
        fun prune(state: JSONObject, now: Long): Boolean {
            val skipped = state.getJSONObject("skipped")
            val expired = skipped.keys().asSequence().filter {
                skipped.getJSONObject(it).getLong("expires") <= now
            }.toList()
            expired.forEach(skipped::remove)
            return expired.isNotEmpty()
        }
    }

    data class Encrypted(val state: JSONObject, val header: JSONObject, val ciphertext: ByteArray)
    data class Decrypted(val state: JSONObject, val plaintext: ByteArray)

    private fun initial(root: ByteArray, own: KeyPair): JSONObject {
        require(root.size == 32 && own.publicKey.size == 32 && own.secretKey.size == 32)
        return JSONObject()
        .put("rk", X3dhCrypto.encode(root)).put("dhs_public", X3dhCrypto.encode(own.publicKey))
        .put("dhs_secret", X3dhCrypto.encode(own.secretKey)).put("dhr", JSONObject.NULL)
        .put("cks", JSONObject.NULL).put("ckr", JSONObject.NULL)
        .put("ns", 0).put("nr", 0).put("pn", 0).put("skipped", JSONObject())
    }

    fun initializeAlice(root: ByteArray, remote: ByteArray, own: KeyPair): JSONObject =
        initial(root, own).also { state ->
            X3dhCrypto.edwardsPublic(remote)
            state.put("dhr", X3dhCrypto.encode(remote))
            mix(state, "cks", remote)
        }

    /** The signed prekey is used only until the first authenticated receive replaces DHs. */
    fun initializeBob(root: ByteArray, signed: KeyPair): JSONObject = initial(root, signed)

    private fun mix(state: JSONObject, chain: String, remote: ByteArray) {
        val secret = X3dhCrypto.decode(state.getString("dhs_secret"), 32)
        val root = X3dhCrypto.decode(state.getString("rk"), 32)
        try {
            val dh = ops.dh(secret, remote)
            try {
                val (nextRoot, nextChain) = rootKdf(root, dh)
                try { state.put("rk", X3dhCrypto.encode(nextRoot)).put(chain, X3dhCrypto.encode(nextChain)) }
                finally { nextRoot.fill(0); nextChain.fill(0) }
            } finally { dh.fill(0) }
        } finally { secret.fill(0); root.fill(0) }
    }

    private fun step(state: JSONObject, chain: String): ByteArray {
        require(!state.isNull(chain)) { "Ratchet chain is not initialized" }
        val current = X3dhCrypto.decode(state.getString(chain), 32)
        try {
            val (next, key) = chainKdf(current)
            try { state.put(chain, X3dhCrypto.encode(next)); return key }
            finally { next.fill(0) }
        } finally { current.fill(0) }
    }

    fun encrypt(saved: JSONObject, plain: ByteArray, ad: ByteArray, now: Long): Encrypted {
        val state = JSONObject(saved.toString())
        prune(state, now)
        val number = state.getInt("ns")
        require(number <= MAX_COUNTER) { "Ratchet sending chain exhausted; open a fresh session" }
        val header = JSONObject().put("dh", state.getString("dhs_public"))
            .put("pn", state.getInt("pn")).put("n", number)
        val associated = ad + headerBytes(header)
        val key = step(state, "cks")
        try {
            val cipher = ops.encrypt(plain, associated, key)
            state.put("ns", number + 1)
            return Encrypted(state, header, cipher)
        } finally { key.fill(0) }
    }

    private fun skip(state: JSONObject, until: Int, expires: Long) {
        val current = state.getInt("nr")
        require(until >= current && until.toLong() - current <= MAX_SKIP) { "Too many skipped messages or invalid previous-chain length" }
        if (state.isNull("ckr")) {
            require(until == 0) { "No previous receiving chain" }; return
        }
        val skipped = state.getJSONObject("skipped")
        require(skipped.length().toLong() + until - current <= MAX_SKIP) { "Skipped message key storage is full" }
        while (state.getInt("nr") < until) {
            val key = step(state, "ckr")
            try {
                val number = state.getInt("nr")
                skipped.put("${state.getString("dhr")}:$number", JSONObject()
                    .put("key", X3dhCrypto.encode(key)).put("expires", expires))
                state.put("nr", number + 1)
            } finally { key.fill(0) }
        }
    }

    fun decrypt(saved: JSONObject, header: JSONObject, ciphertext: ByteArray, ad: ByteArray,
                now: Long, skippedLifetime: Long): Decrypted {
        require(skippedLifetime > 0)
        val associated = ad + headerBytes(header)
        val state = JSONObject(saved.toString())
        prune(state, now)
        val number = counter(header, "n"); val previous = counter(header, "pn")
        val remoteText = header.getString("dh")
        val keyId = "$remoteText:$number"
        val skipped = state.getJSONObject("skipped")
        if (skipped.has(keyId)) {
            val key = X3dhCrypto.decode(skipped.getJSONObject(keyId).getString("key"), 32)
            try {
                val plain = ops.decrypt(ciphertext, associated, key)
                skipped.remove(keyId)
                return Decrypted(state, plain)
            } finally { key.fill(0) }
        }
        if (state.optString("dhr") != remoteText) {
            skip(state, previous, now + skippedLifetime)
            state.put("pn", state.getInt("ns")).put("ns", 0).put("nr", 0).put("dhr", remoteText)
            val remote = X3dhCrypto.decode(remoteText, 32)
            mix(state, "ckr", remote)
            val fresh = ops.keyPair()
            try {
                state.put("dhs_public", X3dhCrypto.encode(fresh.publicKey))
                    .put("dhs_secret", X3dhCrypto.encode(fresh.secretKey))
                mix(state, "cks", remote)
            } finally { fresh.secretKey.fill(0) }
        }
        require(number >= state.getInt("nr")) { "Message key was already consumed or expired" }
        skip(state, number, now + skippedLifetime)
        val key = step(state, "ckr")
        try {
            val plain = ops.decrypt(ciphertext, associated, key)
            state.put("nr", number + 1)
            return Decrypted(state, plain)
        } finally { key.fill(0) }
    }
}
