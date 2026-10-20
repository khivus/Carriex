package com.example.carriex.crypto.x3dh

import com.example.carriex.crypto.model.KeyPair
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JCA X25519 and test-only AES-GCM keep state-machine tests independent of JNI.
 * Android tests separately exercise the production XChaCha20-Poly1305 primitives.
 */
class DoubleRatchetTest {
    internal class JcaOps : X3dhPrimitives {
        val keyPairs = ArrayDeque<KeyPair>()
        val encryptionKeys = mutableListOf<ByteArray>()
        override fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
        override fun keyPair(): KeyPair {
            if (keyPairs.isNotEmpty()) return keyPairs.removeFirst().let { KeyPair(it.publicKey.copyOf(), it.secretKey.copyOf()) }
            val secret = random(32)
            return KeyPair(dh(secret, ByteArray(32).apply { this[0] = 9 }), secret)
        }
        override fun dh(secret: ByteArray, publicKey: ByteArray): ByteArray {
            val factory = KeyFactory.getInstance("XDH")
            val privateKey = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, secret))
            val remote = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, publicKey.reversedArray())))
            return KeyAgreement.getInstance("X25519").run { init(privateKey); doPhase(remote, true); generateSecret() }
        }
        override fun sign(secret: ByteArray, message: ByteArray): ByteArray = error("Not used by Double Ratchet")
        override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = error("Not used by Double Ratchet")
        override fun encrypt(plain: ByteArray, aad: ByteArray, key: ByteArray): ByteArray {
            encryptionKeys.add(key.copyOf())
            val nonce = random(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return nonce + cipher.doFinal(plain)
        }
        override fun decrypt(wire: ByteArray, aad: ByteArray, key: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wire.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(wire.copyOfRange(12, wire.size))
        }
    }
    private val ops = JcaOps()
    private val engine = DoubleRatchet(ops)
    private val ad = "authenticated conversation and message identity".toByteArray()
    private val now = 1_700_000_000_000L
    private val lifetime = 86_400_000L
    private fun unhex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected authentication or bounds rejection") } catch (_: Exception) { }
    }
    private fun sessions(): Pair<JSONObject, JSONObject> {
        val bob = ops.keyPair(); val alice = ops.keyPair(); val root = ops.random(32)
        return engine.initializeAlice(root, bob.publicKey, alice) to engine.initializeBob(root, bob)
    }
    private fun send(state: JSONObject, text: String) = engine.encrypt(state, text.toByteArray(), ad, now)
    private fun receive(state: JSONObject, wire: DoubleRatchet.Encrypted, time: Long = now) =
        engine.decrypt(state, wire.header, wire.ciphertext, ad, time, lifetime)

    @Test fun rootAndMessageKeysMatchIndependentPythonVectorsAcrossTwoDhTurns() {
        val path = listOf(File("src/androidTest/assets/double-ratchet-reference.json"),
            File("app/src/androidTest/assets/double-ratchet-reference.json")).first { it.isFile }
        val vector = JSONObject(path.readText())
        fun pair(name: String) = vector.getJSONObject(name).let { KeyPair(unhex(it.getString("public")), unhex(it.getString("secret"))) }
        fun checkState(state: JSONObject, name: String, chain: String) {
            val expected = vector.getJSONObject(name)
            assertEquals(expected.getString("root"), X3dhCrypto.hex(X3dhCrypto.decode(state.getString("rk"))))
            assertEquals(expected.getString("chain"), X3dhCrypto.hex(X3dhCrypto.decode(state.getString(chain))))
        }
        val sk = unhex(vector.getString("sk"))
        var alice = engine.initializeAlice(sk, pair("bob0").publicKey, pair("alice0"))
        var bob = engine.initializeBob(sk, pair("bob0"))
        checkState(alice, "alice0_chain", "cks")
        ops.keyPairs.add(pair("bob1")); ops.keyPairs.add(pair("alice1"))
        val a0 = send(alice, "first"); alice = a0.state
        assertEquals(vector.getJSONObject("alice0_chain").getJSONArray("message_keys").getString(0), X3dhCrypto.hex(ops.encryptionKeys.last()))
        bob = receive(bob, a0).state
        checkState(bob, "bob1_chain", "cks")
        val b0 = send(bob, "reply")
        alice = receive(alice, b0).state
        checkState(alice, "alice1_chain", "cks")
        val a1 = send(alice, "new DH chain")
        assertEquals(vector.getJSONObject("alice1_chain").getJSONArray("message_keys").getString(0), X3dhCrypto.hex(ops.encryptionKeys.last()))
        assertEquals(1, a1.header.getInt("pn")); assertEquals(0, a1.header.getInt("n"))
        assertFalse(alice.toString().contains(X3dhCrypto.encode(sk)))
    }

    @Test fun delayedMessagesAcrossOldAndNewChainsDecryptExactlyOnce() {
        var (alice, bob) = sessions()
        val first = send(alice, "first"); alice = first.state
        val delayed = send(alice, "old-chain delayed"); alice = delayed.state
        bob = receive(bob, first).state
        val reply = send(bob, "reply"); bob = reply.state
        alice = receive(alice, reply).state
        val turn = send(alice, "new-chain first"); alice = turn.state
        val later = send(alice, "new-chain later")
        val received = receive(bob, later); bob = received.state
        assertEquals("new-chain later", String(received.plaintext))
        assertEquals(2, bob.getJSONObject("skipped").length())
        val old = receive(bob, delayed); bob = old.state
        assertEquals("old-chain delayed", String(old.plaintext))
        val earlier = receive(bob, turn); bob = earlier.state
        assertEquals("new-chain first", String(earlier.plaintext))
        assertEquals(0, bob.getJSONObject("skipped").length())
        expectFailure { receive(bob, delayed) }
        expectFailure { receive(bob, later) }
        assertNotEquals(first.header.getString("dh"), turn.header.getString("dh"))
        assertEquals(2, turn.header.getInt("pn"))
    }

    @Test fun tamperingDoesNotAdvanceStateOrConsumeSkippedKey() {
        val (alice, originalBob) = sessions()
        val first = send(alice, "delayed"); val next = send(first.state, "arrives first")
        val bob = receive(originalBob, next).state
        val snapshot = bob.toString()
        val damaged = first.ciphertext.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        expectFailure { engine.decrypt(bob, first.header, damaged, ad, now, lifetime) }
        expectFailure { engine.decrypt(bob, first.header, first.ciphertext, "different ID".toByteArray(), now, lifetime) }
        val changedHeader = JSONObject(first.header.toString()).put("pn", 1)
        expectFailure { engine.decrypt(bob, changedHeader, first.ciphertext, ad, now, lifetime) }
        assertEquals(snapshot, bob.toString())
        assertEquals("delayed", String(receive(bob, first).plaintext))
        val wrongDh = JSONObject(next.header.toString()).put("dh", X3dhCrypto.encode(ops.keyPair().publicKey))
        val before = originalBob.toString()
        expectFailure { engine.decrypt(originalBob, wrongDh, next.ciphertext, ad, now, lifetime) }
        assertEquals(before, originalBob.toString())
    }

    @Test fun maliciousCountersAndSkipFloodsAreRejectedBeforeUnboundedWork() {
        val (alice, bob) = sessions(); val wire = send(alice, "valid")
        listOf<Any>(-1, 1.25, "0", Long.MAX_VALUE, true, DoubleRatchet.MAX_SKIP + 1).forEach { value ->
            val header = JSONObject(wire.header.toString()).put("n", value)
            expectFailure { engine.decrypt(bob, header, wire.ciphertext, ad, now, lifetime) }
        }
        val invalid = JSONObject(wire.header.toString()).put("dh", X3dhCrypto.encode(ByteArray(32)))
        expectFailure { engine.decrypt(bob, invalid, wire.ciphertext, ad, now, lifetime) }
        assertEquals("valid", String(receive(bob, wire).plaintext))
    }

    @Test fun skippedKeysExpireAndTheirUseDoesNotResurrectChainKeys() {
        val (alice, bob) = sessions()
        val first = send(alice, "late"); val second = send(first.state, "early")
        val state = receive(bob, second).state
        val root = state.getString("rk"); val chain = state.getString("ckr")
        val consumed = receive(state, first).state
        assertEquals(root, consumed.getString("rk")); assertEquals(chain, consumed.getString("ckr"))
        assertEquals(0, consumed.getJSONObject("skipped").length())
        expectFailure { receive(state, first, now + lifetime) }
        assertTrue(DoubleRatchet.prune(state, now + lifetime))
        assertEquals(0, state.getJSONObject("skipped").length())
    }

    @Test fun storedSkipLimitCoversPreviousChainsAndExpiredEntriesFreeCapacity() {
        val (alice, bob) = sessions()
        val first = send(alice, "skipped"); val second = send(first.state, "after gap")
        val skipped = bob.getJSONObject("skipped")
        repeat(DoubleRatchet.MAX_SKIP) { index ->
            skipped.put("older-chain:$index", JSONObject().put("key", X3dhCrypto.encode(ops.random(32)))
                .put("expires", now + lifetime))
        }
        expectFailure { receive(bob, second) }
        assertEquals(DoubleRatchet.MAX_SKIP, skipped.length())
        assertEquals("after gap", String(receive(bob, second, now + lifetime).plaintext))
    }

    @Test fun everySendAdvancesKeyAndCleanDhTurnBreaksOldSendingChainPrediction() {
        var (alice, bob) = sessions()
        val first = send(alice, "first"); alice = first.state
        val captured = JSONObject(alice.toString())
        val reply = send(receive(bob, first).state, "fresh Bob key")
        alice = receive(alice, reply).state
        // A passive observer can process the reply using captured state, but cannot
        // reproduce the clean device's newly sampled Alice ratchet private key.
        val observer = receive(captured, reply).state
        assertNotEquals(observer.getString("dhs_public"), alice.getString("dhs_public"))
        assertNotEquals(observer.getString("cks"), alice.getString("cks"))
        val after = send(alice, "after fresh DH")
        assertEquals(3, ops.encryptionKeys.map(X3dhCrypto::hex).toSet().size)
        assertFalse(after.state.toString().contains(X3dhCrypto.encode(ops.encryptionKeys.first())))
        val (unusedChain, guessed) = DoubleRatchet.chainKdf(X3dhCrypto.decode(captured.getString("cks"), 32))
        try { expectFailure { ops.decrypt(after.ciphertext, ad + DoubleRatchet.headerBytes(after.header), guessed) } }
        finally { unusedChain.fill(0); guessed.fill(0) }
    }
}
