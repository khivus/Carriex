package com.example.carriex.crypto.x3dh

import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.messaging.MessageLimits
import com.example.carriex.messaging.MessageSizeException
import com.example.carriex.model.Message
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** Real JCA DH/AEAD and device certificates; signature vectors are covered on Android. */
class ProtocolLifecycleTest {
    private val day = 86_400_000L
    private var now = 1_700_000_000_000L
    private val ops = object : X3dhPrimitives by DoubleRatchetTest.JcaOps() {
        override fun sign(secret: ByteArray, message: ByteArray) = ByteArray(64)
        override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) = true
    }
    private class Store : X3dhStateStore {
        val values = sortedMapOf<String, String>()
        var prekeyReads = 0
        override fun get(key: String): String? { if(key.startsWith("local/opk/")) prekeyReads++; return values[key] }
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }
    }
    private fun device() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair())
    private fun protocol(device: DeviceIdentity, store: Store) = X3dhProtocol(device, store, ops, { now })
    private fun message(a: DeviceIdentity, b: DeviceIdentity, bytes: ByteArray = "message".toByteArray()) =
        Message(a.userId, b.userId, bytes, Message.MessageType.DATA, sentAt = now)
    private fun publish(protocol: X3dhProtocol, revision: Long = 0): JSONObject = protocol.publication(0, 30 * 86400, revision).also {
        protocol.published(JSONObject().put("revision", it.getLong("revision")).put("publication_id", it.getString("publication_id"))
            .put("signed_prekey_id", it.getJSONObject("signed_prekey").getString("id")))
    }
    private fun claim(upload: JSONObject, index: Int = 0) = JSONObject(upload.toString())
        .put("one_time_prekey", upload.getJSONArray("one_time_prekeys").getJSONObject(index))

    @Test fun oversizedMessagesNeverClaimOrAdvanceAChainAndBoundaryFitsTheRelay() {
        val a=device(); val b=device(); val sa=Store(); val sb=Store()
        val alice=protocol(a,sa); val bob=protocol(b,sb); val upload=publish(bob)
        try {
            alice.encrypt(message(a,b,ByteArray(110_000))) { error("Must reject before claim") }
            fail("Oversized plaintext accepted")
        } catch (_: MessageSizeException) { }
        assertTrue(sa.keys("session/").isEmpty())
        val boundary = alice.encrypt(message(a,b,ByteArray(MessageLimits.MAX_PLAINTEXT_BYTES))) { claim(upload) }
        MessageLimits.requireWire(boundary.content)
        assertEquals(MessageLimits.MAX_PLAINTEXT_BYTES,bob.decrypt(boundary).content.size)
        val before=sa.values.toMap()
        repeat(DoubleRatchet.MAX_SKIP + 2) {
            try { alice.encrypt(message(a,b,ByteArray(MessageLimits.MAX_PLAINTEXT_BYTES+1))) { error("Must not claim") }; fail() }
            catch (_: MessageSizeException) { }
        }
        assertEquals(before,sa.values)
        val normal=alice.encrypt(message(a,b)) { error("Reuse session") }
        assertEquals(1,JSONObject(String(normal.content)).getJSONObject("ratchet").getInt("n"))
        assertEquals("message",String(bob.decrypt(normal).content))
    }

    @Test fun exactCiphertextRetriesRemainUsableAfterSendingDeadline() {
        val a=device();val b=device();val sa=Store();val sb=Store()
        val alice=protocol(a,sa);val bob=protocol(b,sb);val upload=publish(bob)
        bob.decrypt(alice.encrypt(message(a,b)) { claim(upload) })
        now+=29*day
        val pending=alice.encrypt(message(a,b)) { error("Reuse session") }
        val bytes=pending.content.copyOf()
        now+=2*day
        assertTrue(alice.wireUsable(pending))
        assertEquals("message",String(bob.decrypt(pending).content))
        assertArrayEquals(bytes,pending.content)
        now+=37*day
        assertFalse(alice.wireUsable(pending))
    }

    @Test fun backdatedAuthenticatedSessionsUseReceiverAdmissionTime() {
        val a=device();val b=device();val sb=Store();val bob=protocol(b,sb);val upload=publish(bob)
        val receiverTime=now
        var identity: String?=null
        repeat(5) { index ->
            val sa=Store();identity?.let { sa.put("local/identity",it) };val alice=protocol(a,sa)
            now=receiverTime-2*day
            val wire=alice.encrypt(message(a,b)) { claim(upload,index) }
            identity=sa.get("local/identity");now=receiverTime
            if(index<4) bob.decrypt(wire)
            else try { bob.decrypt(wire);fail("Daily limit bypassed") }
                catch (e:IllegalArgumentException) { assertTrue(e.message!!.contains("Too many new sessions")) }
        }
        assertEquals(4,sb.keys("session/").size)
    }

    @Test fun depletionAcrossReconnectsAndDaysIsBoundedAndMessagesDoNotScanPrivateKeys() {
        val a=device();val b=device();val sa=Store();val sb=Store();val bob=protocol(b,sb)
        val upload=publish(bob)
        repeat(20) { publish(bob,(it+1).toLong()) }
        assertEquals(50,sb.keys("local/opk/").size)
        assertNotNull(bob.prekeyWarning())
        val alice=protocol(a,sa)
        bob.decrypt(alice.encrypt(message(a,b)) { claim(upload) })
        sb.prekeyReads=0
        bob.decrypt(alice.encrypt(message(a,b)) { error("Reuse session") })
        assertEquals(0,sb.prekeyReads)
        repeat(50*4) { now+=6*3_600_000L;publish(bob,(it+21).toLong()) }
        assertEquals(X3dhProtocol.MAX_PRIVATE_PREKEYS,sb.keys("local/opk/").size)
        assertNotNull(bob.prekeyWarning())
    }

    @Test fun manualRestartCreatesFreshSessionWithoutDeletingHistoryKeysOrResealingOldPackets() {
        val a=device();val b=device();val sa=Store();val sb=Store();val alice=protocol(a,sa);val bob=protocol(b,sb)
        val upload=publish(bob);val old=alice.encrypt(message(a,b)) { claim(upload) }
        bob.decrypt(old)
        val oldSid=JSONObject(String(old.content)).getString("sid")
        repeat(DoubleRatchet.MAX_SKIP+1) { alice.encrypt(message(a,b)) { error("Reuse session") } }
        val beyondGap=alice.encrypt(message(a,b)) { error("Reuse session") }
        try { bob.decrypt(beyondGap);fail("Expected unrecoverable gap") }
        catch (e:IllegalArgumentException) { assertTrue(e.message!!.contains("Too many skipped")) }
        alice.restartSending(b.userId)
        assertTrue(alice.wireUsable(old))
        assertNotNull(sa.get("session/$oldSid"))
        val fresh=alice.encrypt(message(a,b)) { claim(upload,1) }
        assertNotEquals(oldSid,JSONObject(String(fresh.content)).getString("sid"))
        assertEquals("message",String(bob.decrypt(fresh).content))
    }

    @Test fun referencedExpiredPrekeyIsRejectedWithoutScanningTheInventory() {
        val a=device();val b=device();val sa=Store();val sb=Store()
        val alice=protocol(a,sa);val bob=protocol(b,sb);val upload=publish(bob)
        val id=upload.getJSONArray("one_time_prekeys").getJSONObject(0).getString("id")
        val key="local/opk/$id"
        sb.put(key,JSONObject(sb.get(key)!!).put("retain_until",now-1).toString())
        val wire=alice.encrypt(message(a,b)) { claim(upload) }
        sb.prekeyReads=0
        try { bob.decrypt(wire);fail("Expired OPK accepted between maintenance runs") }
        catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("expired")) }
        assertEquals(1,sb.prekeyReads)
        assertTrue(sb.keys("session/").isEmpty())
    }
}
