package com.example.carriex.crypto.x3dh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.libsodium.NativeCrypto
import com.example.carriex.crypto.model.KeyPair
import com.example.carriex.messaging.ChatStore
import com.example.carriex.model.Message
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class X3dhAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ops = SodiumX3dhPrimitives()
    private fun device() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair())
    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (expected: Exception) { assertNotNull(expected.message) }
    }
    private fun claim(publication: JSONObject, oneTime: Boolean = true): JSONObject = JSONObject(publication.toString()).apply {
        val keys = getJSONArray("one_time_prekeys")
        put("one_time_prekey", if (oneTime) keys.getJSONObject(0) else JSONObject.NULL)
        remove("one_time_prekeys")
    }
    private fun receipt(upload:JSONObject) = JSONObject().put("revision",upload.getLong("revision"))
        .put("publication_id",upload.getString("publication_id"))
        .put("signed_prekey_id",upload.getJSONObject("signed_prekey").getString("id"))
    private fun message(a: DeviceIdentity, b: DeviceIdentity, text: String) = Message(a.userId, b.userId,
        text.toByteArray(), Message.MessageType.DATA, sentAt = 1_700_000_000_123)

    @Test fun nativeXeddsaAndX3dhAgreeWithIndependentPythonVectors() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val fixture = JSONObject(testContext.assets.open("x3dh-reference.json").bufferedReader().use { it.readText() })
        val keys = fixture.getJSONArray("keys")
        val pairs = (0 until keys.length()).map { index -> keys.getJSONObject(index).let {
            KeyPair(unhex(it.getString("public")), unhex(it.getString("secret")))
        } }
        val msg = unhex(fixture.getString("message")); val random = unhex(fixture.getString("random"))
        listOf(0, 2).forEachIndexed { index, key ->
            val signature = NativeCrypto.xeddsaSign(pairs[key].secretKey, msg, random)!!
            assertArrayEquals(unhex(fixture.getJSONArray("signatures").getString(index)), signature)
            assertTrue(ops.verify(pairs[key].publicKey, msg, signature))
            signature[0] = (signature[0].toInt() xor 1).toByte()
            assertFalse(ops.verify(pairs[key].publicKey, msg, signature))
        }
        listOf(false, true).forEach { fourth ->
            val sender = X3dhCrypto.initiate(ops, pairs[0], pairs[1], pairs[2].publicKey, pairs[3].publicKey,
                if (fourth) pairs[4].publicKey else null)
            val recipient = X3dhCrypto.respond(ops, pairs[2], pairs[3], if (fourth) pairs[4] else null,
                pairs[0].publicKey, pairs[1].publicKey)
            assertArrayEquals(unhex(fixture.getString(if (fourth) "root4" else "root3")), sender)
            assertArrayEquals(sender, recipient)
        }
        expectFailure { ops.dh(pairs[0].secretKey, ByteArray(32)) }
        assertNull(NativeCrypto.xeddsaSign(ByteArray(2), msg, random))
    }

    @Test fun offlineSetupReplyAndRestartPreserveHistoryAndConsumePrekeyOnce() {
        val a = device(); val b = device(); val sa = UUID.randomUUID().toString(); val sb = UUID.randomUUID().toString()
        lateinit var initial: Message
        ChatStore(context).use { db ->
            val alice = X3dhProtocol(a, db.x3dh(sa)); val bob = X3dhProtocol(b, db.x3dh(sb))
            val bundle = db.atomic { claim(bob.publication(0, 2_592_000)) }
            val plain = message(a, b, "offline first message")
            db.addOutgoing(sa, plain)
            initial = db.atomic { alice.encrypt(plain) { bundle }.also { db.setWire(sa, it) } }
            assertEquals(50, db.x3dh(sb).keys("local/opk/").size)
        }
        ChatStore(context).use { reopened ->
            val bob = X3dhProtocol(b, reopened.x3dh(sb)); val alice = X3dhProtocol(a, reopened.x3dh(sa))
            assertArrayEquals(initial.content, reopened.savedWire(sa, initial)!!.content)
            val decoded = reopened.atomic { bob.decrypt(initial).also { reopened.addIncoming(sb, it) } }
            assertEquals("offline first message", String(decoded.content))
            assertEquals(1_700_000_000_123L, decoded.sentAt)
            assertEquals(49, reopened.x3dh(sb).keys("local/opk/").size)
            val replay = initial.copy(id = "a".repeat(32))
            expectFailure { reopened.atomic { bob.decrypt(replay) } }
            assertEquals(49, reopened.x3dh(sb).keys("local/opk/").size)
            val reply = message(b, a, "reply")
            val wire = reopened.atomic { bob.encrypt(reply) { error("No new claim") } }
            assertEquals("reply", String(reopened.atomic { alice.decrypt(wire) }.content))
            val replyHeader = JSONObject(String(wire.content)).getJSONObject("ratchet")
            assertEquals(32, X3dhCrypto.decode(replyHeader.getString("dh")).size)
            assertNotEquals(JSONObject(String(initial.content)).getJSONObject("ratchet").getString("dh"), replyHeader.getString("dh"))
            val later = reopened.atomic { alice.encrypt(message(a, b, "later")) { error("No new claim") } }
            assertEquals("later", String(reopened.atomic { bob.decrypt(later) }.content))
            assertEquals(49, reopened.x3dh(sb).keys("local/opk/").size)
        }
    }

    @Test fun invalidSignaturesAndTamperingLeaveKeysAndSessionUnchanged() {
        val a = device(); val b = device(); val sa = UUID.randomUUID().toString(); val sb = UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val alice = X3dhProtocol(a, db.x3dh(sa)); val bob = X3dhProtocol(b, db.x3dh(sb))
            val bundle = db.atomic { claim(bob.publication(0, 2_592_000)) }
            val changed = JSONObject(bundle.toString()).apply {
                getJSONObject("signed_prekey").put("signature", X3dhCrypto.encode(ByteArray(64)))
            }
            expectFailure { db.atomic { alice.encrypt(message(a, b, "bad")) { changed } } }
            assertTrue(db.x3dh(sa).keys("session/").isEmpty())
            val wire = db.atomic { alice.encrypt(message(a, b, "authentic")) { bundle } }
            val bad = JSONObject(String(wire.content)).apply {
                val ciphertext = X3dhCrypto.decode(getString("ciphertext")); ciphertext[24] = (ciphertext[24].toInt() xor 1).toByte()
                put("ciphertext", X3dhCrypto.encode(ciphertext))
            }
            expectFailure { db.atomic { bob.decrypt(wire.copy(content = bad.toString().toByteArray())) } }
            assertEquals(50, db.x3dh(sb).keys("local/opk/").size)
            assertTrue(db.x3dh(sb).keys("session/").isEmpty())
            expectFailure { db.atomic { bob.decrypt(wire.copy(from = "a".repeat(64))) } }
            assertEquals("authentic", String(db.atomic { bob.decrypt(wire) }.content))
            val rootBefore = db.x3dh(sb).get(db.x3dh(sb).keys("session/").single())
            expectFailure { db.atomic { bob.decrypt(wire.copy(id = "b".repeat(32))) } }
            assertEquals(rootBefore, db.x3dh(sb).get(db.x3dh(sb).keys("session/").single()))
            val plaintext = String(wire.content)
            assertFalse(plaintext.contains("authentic"))
        }
    }

    @Test fun simultaneousInitiationAndOutOfOrderMessagesRemainDecryptableWithRatchet() {
        val a = device(); val b = device(); val sa = UUID.randomUUID().toString(); val sb = UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val alice = X3dhProtocol(a, db.x3dh(sa),allowWithoutOneTimeKey={true}); val bob = X3dhProtocol(b, db.x3dh(sb),allowWithoutOneTimeKey={true})
            val ba = db.atomic { claim(alice.publication(0, 2_592_000), false) }
            val bb = db.atomic { claim(bob.publication(0, 2_592_000), false) }
            val a1 = db.atomic { alice.encrypt(message(a, b, "a1")) { bb } }
            val a2 = db.atomic { alice.encrypt(message(a, b, "a2")) { error("No claim") } }
            val b1 = db.atomic { bob.encrypt(message(b, a, "b1")) { ba } }
            assertEquals("a2", String(db.atomic { bob.decrypt(a2) }.content))
            assertEquals("b1", String(db.atomic { alice.decrypt(b1) }.content))
            assertEquals("a1", String(db.atomic { bob.decrypt(a1) }.content))
            repeat(4) {
                val aw = db.atomic { alice.encrypt(message(a, b, "alice $it")) { error("No claim") } }
                val bw = db.atomic { bob.encrypt(message(b, a, "bob $it")) { error("No claim") } }
                assertEquals("alice $it", String(db.atomic { bob.decrypt(aw) }.content))
                assertEquals("bob $it", String(db.atomic { alice.decrypt(bw) }.content))
            }
        }
    }

    @Test fun transactionRollbackAndIdentityPinsRejectKeyReplacement() {
        val a = device(); val b = device(); val sa = UUID.randomUUID().toString(); val sb = UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val alice = X3dhProtocol(a, db.x3dh(sa)); val bob = X3dhProtocol(b, db.x3dh(sb))
            val bundle = db.atomic { claim(bob.publication(0, 2_592_000)) }
            lateinit var wire: Message
            expectFailure { db.atomic { wire = alice.encrypt(message(a, b, "rollback")) { bundle }; error("Simulated crash before commit") } }
            assertTrue(db.x3dh(sa).keys("session/").isEmpty())
            wire = db.atomic { alice.encrypt(message(a, b, "committed")) { bundle } }
            expectFailure { db.atomic { bob.decrypt(wire); error("Simulated crash before saving history") } }
            assertEquals(50, db.x3dh(sb).keys("local/opk/").size)
            assertTrue(db.x3dh(sb).keys("session/").isEmpty())
            db.atomic { bob.decrypt(wire) }
            val replacement = X3dhProtocol(a, db.x3dh(UUID.randomUUID().toString()),allowWithoutOneTimeKey={true})
            val bb = db.atomic { claim(bob.publication(50, 2_592_000), false) }
            // Use the original published key because the second upload contains no new batch.
            bb.put("one_time_prekey", JSONObject.NULL)
            val replaced = db.atomic { replacement.encrypt(message(a, b, "replacement")) { bb } }
            expectFailure { db.atomic { bob.decrypt(replaced) } }
        }
    }

    @Test fun rotationRetainsDelayedMessageKeysThenExpiresRetiredPrivatePrekeys() {
        var now = 1_700_000_000_000L
        val scope = UUID.randomUUID().toString()
        val day = 86_400_000L
        ChatStore(context).use { db ->
            val protocol = X3dhProtocol(device(), db.x3dh(scope), clock = { now })
            val first = db.atomic { protocol.publication(0, 86400) }
            val original = first.getJSONObject("signed_prekey").getString("id")
            val oldOpk = first.getJSONArray("one_time_prekeys").getJSONObject(0).getString("id")
            db.atomic { protocol.published(receipt(first)) }
            now += 8 * day
            val rotated = db.atomic { protocol.publication(0, 86400) }
            assertNotEquals(original, rotated.getJSONObject("signed_prekey").getString("id"))
            assertNotNull(db.x3dh(scope).get("local/spk/$original"))
            assertNotNull(db.x3dh(scope).get("local/opk/$oldOpk"))
            db.atomic { protocol.published(receipt(rotated)) }
            now += 46 * day
            db.atomic { protocol.publication(0, 86400) }
            assertNull(db.x3dh(scope).get("local/spk/$original"))
            assertNull(db.x3dh(scope).get("local/opk/$oldOpk"))
        }
    }

    @Test fun delayedUnconfirmedUploadKeepsItsPrivateKeysAndExactManifest() {
        var now = 1_700_000_000_000L
        val scope = UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val protocol = X3dhProtocol(device(), db.x3dh(scope), clock = { now })
            val original = db.atomic { protocol.publication(0, 86400) }
            val oldId = original.getJSONArray("one_time_prekeys").getJSONObject(0).getString("id")
            // Simulate an upload with no confirmation followed by a long offline period.
            now += 46 * 86_400_000L
            val fresh = db.atomic { protocol.publication(0, 86400) }
            assertEquals(50, fresh.getJSONArray("one_time_prekeys").length())
            assertNotNull(db.x3dh(scope).get("local/opk/$oldId"))
            assertEquals(original.toString(),fresh.toString())
            db.atomic { protocol.published(receipt(fresh)) }
            assertNotNull(db.x3dh(scope).get("local/opk/$oldId"))
        }
    }

    @Test fun delayedPublicationDoesNotDeleteKeyBeforeAValidQueuedMessageArrives() {
        var now=1_700_000_000_000L
        val day=86_400_000L
        val a=device();val b=device();val sa=UUID.randomUUID().toString();val sb=UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val alice=X3dhProtocol(a,db.x3dh(sa),clock={now});val bob=X3dhProtocol(b,db.x3dh(sb),clock={now})
            val upload=db.atomic { bob.publication(0,2_592_000) }
            val oldSpk=upload.getJSONObject("signed_prekey").getString("id")
            now+=10*day
            db.atomic { bob.published(receipt(upload)) }
            now+=6*day
            val wire=db.atomic { alice.encrypt(message(a,b,"queued until day 45")) { claim(upload) } }
            now+=29*day
            val replacement=db.atomic { bob.publication(0,2_592_000,1) }
            // Starting publication cannot retire the key still advertised by the server.
            assertFalse(JSONObject(db.x3dh(sb).get("local/spk/$oldSpk")!!).has("retired"))
            db.atomic { bob.published(receipt(replacement)) }
            assertEquals("queued until day 45",String(db.atomic { bob.decrypt(wire) }.content))
        }
    }

    @Test fun legacyOrphanedSignedKeysReceiveBoundedRetentionAfterConfirmation() {
        var now=1_700_000_000_000L
        val scope=UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val protocol=X3dhProtocol(device(),db.x3dh(scope),clock={now})
            val upload=db.atomic { protocol.publication(0,86400) }
            val signed=upload.getJSONObject("signed_prekey").getString("id")
            db.x3dh(scope).put("local/spk/legacy-orphan",db.x3dh(scope).get("local/spk/$signed")!!)
            db.atomic { protocol.published(receipt(upload)) }
            assertNotNull(db.x3dh(scope).get("local/spk/legacy-orphan"))
            now+=39*86_400_000L
            db.atomic { protocol.publication(0,86400,1) }
            assertNull(db.x3dh(scope).get("local/spk/legacy-orphan"))
        }
    }

    @Test fun lateFirstArrivalRetainsPrekeyThroughSessionSendingWindowAndQueueTtl() {
        var now=1_700_000_000_000L
        val day=86_400_000L
        val a=device();val b=device();val sa=UUID.randomUUID().toString();val sb=UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val alice=X3dhProtocol(a,db.x3dh(sa),clock={now});val bob=X3dhProtocol(b,db.x3dh(sb),clock={now})
            val upload=db.atomic { bob.publication(0,2_592_000) };db.atomic { bob.published(receipt(upload)) }
            now+=6*day
            db.atomic { alice.encrypt(message(a,b,"first packet never delivered")) { claim(upload) } }
            now+=29*day
            val later=db.atomic { alice.encrypt(message(a,b,"first arrival on day 64")) { error("Must reuse session") } }
            now+=29*day
            db.atomic { bob.publication(0,2_592_000,1) }
            assertEquals("first arrival on day 64",String(db.atomic { bob.decrypt(later) }.content))
        }
    }

    @Test fun fallbackRequiresExplicitOptInAndIsRecorded() {
        val a=device();val b=device();val sa=UUID.randomUUID().toString();val sb=UUID.randomUUID().toString()
        var allow=false
        ChatStore(context).use { db ->
            val strict=X3dhProtocol(a,db.x3dh(sa),allowWithoutOneTimeKey={allow})
            val bob=X3dhProtocol(b,db.x3dh(sb),allowWithoutOneTimeKey={allow})
            val bundle=db.atomic { claim(bob.publication(0,2_592_000),false) }
            expectFailure { db.atomic { strict.encrypt(message(a,b,"blocked fallback")) { bundle } } }
            allow=true
            val wire=db.atomic { strict.encrypt(message(a,b,"explicit fallback")) { bundle } }
            allow=false
            expectFailure { db.atomic { bob.decrypt(wire) } }
            allow=true
            assertEquals("explicit fallback",String(db.atomic { bob.decrypt(wire) }.content))
            assertEquals("fallback",db.x3dh(sb).get("peer-protection/${a.userId}"))
        }
    }

    @Test fun abusivePeerCannotConsumeOtherContactsSessionAllowance() {
        val attacker=device();val friend=device();val b=device();val sb=UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val bob=X3dhProtocol(b,db.x3dh(sb),allowWithoutOneTimeKey={true})
            val upload=db.atomic { bob.publication(0,2_592_000) }
            var identity:String?=null
            repeat(5) { index ->
                val scope=UUID.randomUUID().toString();val state=db.x3dh(scope)
                identity?.let { state.put("local/identity",it) }
                val sender=X3dhProtocol(attacker,state,allowWithoutOneTimeKey={true})
                val wire=db.atomic { sender.encrypt(message(attacker,b,"session $index")) { claim(upload,false) } }
                identity=state.get("local/identity")
                if(index<4) db.atomic { bob.decrypt(wire) }
                else expectFailure { db.atomic { bob.decrypt(wire) } }
            }
            assertEquals(4,db.x3dh(sb).keys("session/").size)
            val sender=X3dhProtocol(friend,db.x3dh(UUID.randomUUID().toString()))
            val valid=db.atomic { sender.encrypt(message(friend,b,"legitimate contact")) { claim(upload) } }
            assertEquals("legitimate contact",String(db.atomic { bob.decrypt(valid) }.content))
        }
    }

    @Test fun expiredSessionsAreRemovedWithoutDeletingSavedHistory() {
        var now=1_700_000_000_000L
        val a=device();val b=device();val sa=UUID.randomUUID().toString();val sb=UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val alice=X3dhProtocol(a,db.x3dh(sa),clock={now});val bob=X3dhProtocol(b,db.x3dh(sb),clock={now})
            val upload=db.atomic { bob.publication(0,86400) };db.atomic { bob.published(receipt(upload)) }
            val wire=db.atomic { alice.encrypt(message(a,b,"saved history")) { claim(upload) } }
            db.atomic { db.addIncoming(sb,bob.decrypt(wire)) }
            val sid=JSONObject(String(wire.content)).getString("sid")
            now+=39*86_400_000L
            db.atomic { bob.publication(0,86400,1) }
            assertNull(db.x3dh(sb).get("session/$sid"))
            assertEquals("saved history",String(db.history(sb).single().content))
            expectFailure { db.atomic { bob.decrypt(wire.copy(id="e".repeat(32))) } }
        }
    }
}
