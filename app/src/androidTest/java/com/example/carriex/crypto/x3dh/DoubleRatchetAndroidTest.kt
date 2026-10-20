package com.example.carriex.crypto.x3dh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.auth.DeviceIdentity
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
class DoubleRatchetAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun device() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair())
    private fun unhex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }

    @Test fun nativeRatchetDhAndMessageKeysMatchIndependentPythonThroughReplies() {
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("double-ratchet-reference.json").bufferedReader().use { it.readText() })
        fun pair(name: String) = fixture.getJSONObject(name).let { KeyPair(unhex(it.getString("public")), unhex(it.getString("secret"))) }
        val pairs = java.util.ArrayDeque(listOf(pair("bob1"), pair("alice1")))
        val native = SodiumX3dhPrimitives()
        val used = mutableListOf<ByteArray>()
        val ops = object : X3dhPrimitives by native {
            override fun keyPair() = pairs.removeFirst()
            override fun encrypt(plain: ByteArray, aad: ByteArray, key: ByteArray): ByteArray {
                used.add(key.copyOf()); return native.encrypt(plain, aad, key)
            }
        }
        val engine = DoubleRatchet(ops); val ad = "native vector".toByteArray()
        var alice = engine.initializeAlice(unhex(fixture.getString("sk")), pair("bob0").publicKey, pair("alice0"))
        var bob = engine.initializeBob(unhex(fixture.getString("sk")), pair("bob0"))
        val a0 = engine.encrypt(alice, "a0".toByteArray(), ad, 1); alice = a0.state
        bob = engine.decrypt(bob, a0.header, a0.ciphertext, ad, 1, 86400000).state
        val b0 = engine.encrypt(bob, "b0".toByteArray(), ad, 1)
        alice = engine.decrypt(alice, b0.header, b0.ciphertext, ad, 1, 86400000).state
        val a1 = engine.encrypt(alice, "a1".toByteArray(), ad, 1)
        listOf("alice0_chain", "bob1_chain", "alice1_chain").forEachIndexed { index, name ->
            assertEquals(fixture.getJSONObject(name).getJSONArray("message_keys").getString(0), X3dhCrypto.hex(used[index]))
        }
        assertEquals(fixture.getJSONObject("alice1_chain").getString("root"), X3dhCrypto.hex(X3dhCrypto.decode(a1.state.getString("rk"))))
    }

    @Test fun skippedKeysAndExactOutboxSurviveRestartAndFailedAuthenticationRollsBack() {
        val a=device(); val b=device(); val sa=UUID.randomUUID().toString(); val sb=UUID.randomUUID().toString()
        val name="ratchet-${UUID.randomUUID()}.db"
        lateinit var first: Message; lateinit var second: Message; lateinit var third: Message
        try {
            ChatStore(context,name).use { db ->
                val alice=X3dhProtocol(a,db.x3dh(sa)); val bob=X3dhProtocol(b,db.x3dh(sb))
                val upload=db.atomic { bob.publication(0,2592000) }
                val bundle=JSONObject(upload.toString()).put("one_time_prekey",upload.getJSONArray("one_time_prekeys").getJSONObject(0))
                fun send(text:String):Message {
                    val plain=Message(a.userId,b.userId,text.toByteArray(),Message.MessageType.DATA,status=Message.DeliveryStatus.SENDING)
                    db.addOutgoing(sa,plain)
                    return db.atomic { alice.encrypt(plain) { bundle }.also { db.setWire(sa,it) } }
                }
                first=send("first");second=send("second");third=send("third")
                db.atomic { db.addIncoming(sb,bob.decrypt(third)) }
                val key=db.x3dh(sb).keys("session/").single()
                assertEquals(2,JSONObject(db.x3dh(sb).get(key)!!).getJSONObject("ratchet").getJSONObject("skipped").length())
            }
            ChatStore(context,name).use { db ->
                val bob=X3dhProtocol(b,db.x3dh(sb))
                val senderKey=db.x3dh(sa).keys("session/").single()
                val beforeSender=db.x3dh(sa).get(senderKey)
                assertArrayEquals(third.content,db.savedWire(sa,third)!!.content)
                assertEquals(beforeSender,db.x3dh(sa).get(senderKey))
                val key=db.x3dh(sb).keys("session/").single();val before=db.x3dh(sb).get(key)
                val tampered=JSONObject(String(first.content)).apply {
                    val bytes=X3dhCrypto.decode(getString("ciphertext"));bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
                    put("ciphertext",X3dhCrypto.encode(bytes))
                }
                expectFailure { db.atomic { bob.decrypt(first.copy(content=tampered.toString().toByteArray())) } }
                assertEquals(before,db.x3dh(sb).get(key))
                db.atomic { db.addIncoming(sb,bob.decrypt(second)) }
                db.atomic { db.addIncoming(sb,bob.decrypt(first)) }
                assertEquals(setOf("first","second","third"),db.history(sb).map { String(it.content) }.toSet())
                assertEquals(0,JSONObject(db.x3dh(sb).get(key)!!).getJSONObject("ratchet").getJSONObject("skipped").length())
                expectFailure { db.atomic { bob.decrypt(first) } }
                assertEquals(49,db.x3dh(sb).keys("local/opk/").size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun fixedKeyWireCannotDowngradeRatchetSession() {
        val a=device();val b=device();val sa=UUID.randomUUID().toString();val sb=UUID.randomUUID().toString()
        ChatStore(context).use { db ->
            val alice=X3dhProtocol(a,db.x3dh(sa));val bob=X3dhProtocol(b,db.x3dh(sb))
            val upload=db.atomic { bob.publication(0,2592000) }
            val bundle=JSONObject(upload.toString()).put("one_time_prekey",upload.getJSONArray("one_time_prekeys").getJSONObject(0))
            val wire=db.atomic { alice.encrypt(Message(a.userId,b.userId,"valid".toByteArray(),Message.MessageType.DATA)) { bundle } }
            val legacy=JSONObject(String(wire.content)).put("version",2)
            expectFailure { db.atomic { bob.decrypt(wire.copy(content=legacy.toString().toByteArray())) } }
            assertTrue(db.x3dh(sb).keys("session/").isEmpty())
            assertEquals("valid",String(db.atomic { bob.decrypt(wire) }.content))
        }
    }
}
