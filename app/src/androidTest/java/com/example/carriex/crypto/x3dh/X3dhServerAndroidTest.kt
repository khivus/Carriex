package com.example.carriex.crypto.x3dh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.messaging.ChatStore
import com.example.carriex.messaging.MessagingService
import com.example.carriex.model.Message
import com.example.carriex.transport.ServerTransport
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual Android JNI + encrypted SQLite + Kotlin HTTP/WSS against a live Flask relay. */
@RunWith(AndroidJUnit4::class)
class X3dhServerAndroidTest {
    private fun device() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair())
    @Test fun offlineFirstMessageAndRestartedRecipientUseActualServer() {
        val url = InstrumentationRegistry.getArguments().getString("x3dhServer") ?: ""
        assumeTrue("Pass -Pandroid.testInstrumentationRunnerArguments.x3dhServer=http://10.0.2.2:PORT", url.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alice = device(); val bob = device(); val scopeA = UUID.randomUUID().toString(); val scopeB = UUID.randomUUID().toString()
        val tx = ServerTransport(url, DeviceAuthentication(url, alice))
        var rx = ServerTransport(url, DeviceAuthentication(url, bob))
        val db = ChatStore(context)
        val acceptedByBob = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val sender = MessagingService(alice.userId, alice, tx, db, scopeA,contacts={setOf(bob.userId)})
        var recipient = MessagingService(bob.userId, bob, rx, db, scopeB,contacts={acceptedByBob})
        val replies = LinkedBlockingQueue<Message>()
        val arrivals = LinkedBlockingQueue<Message>()
        val errors = LinkedBlockingQueue<String>()
        try {
            assertTrue(tx.testServerActive()); assertTrue(rx.testServerActive())
            // Publish Bob's bundle while online, then disconnect before Alice starts.
            val bobKeys = X3dhProtocol(bob, db.x3dh(scopeB))
            val status = rx.prekeyStatus()
            val upload = db.atomic { bobKeys.publication(status.getInt("available"), status.getLong("message_ttl_seconds"),status.getLong("revision")) }
            val receipt=rx.publishPrekeys(upload); db.atomic { bobKeys.published(receipt) }; rx.close()
            sender.onMessageDecrypted = { replies.offer(it); true }
            sender.onError = { errors.offer(it) }
            sender.start()
            val first = Message(alice.userId, bob.userId, "offline over Flask".toByteArray(), Message.MessageType.DATA,
                status = Message.DeliveryStatus.SENDING, sentAt = 1_700_000_000_123)
            db.addOutgoing(scopeA, first); sender.send(first)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (db.pending(scopeA).isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue("First message must be queued without waiting for Bob: $errors", db.pending(scopeA).isEmpty())
            rx = ServerTransport(url, DeviceAuthentication(url, bob))
            val queued = rx.getAllMessages(bob.userId).single()
            assertFalse(String(queued.content).contains("offline over Flask"))
            assertEquals("init", org.json.JSONObject(String(queued.content)).getString("mode"))
            assertEquals(3, org.json.JSONObject(String(queued.content)).getInt("version"))
            recipient = MessagingService(bob.userId, bob, rx, db, scopeB,contacts={acceptedByBob})
            recipient.onMessageDecrypted = { arrivals.offer(it); true }; recipient.onError = { errors.offer(it) }
            recipient.start(); recipient.fetchNewMessages()
            val requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (db.requests(scopeB).isEmpty() && System.nanoTime() < requestDeadline) Thread.sleep(50)
            assertEquals(alice.userId, db.requests(scopeB).single().peer)
            assertTrue("Unknown sender must not create sessions",db.x3dh(scopeB).keys("session/").isEmpty())
            assertTrue(arrivals.isEmpty())
            assertNotEquals("received",tx.statuses(alice.userId).first { it.first==first.id }.second)
            acceptedByBob.add(alice.userId)
            recipient.refreshPolicy(); recipient.acceptRequests(alice.userId)
            val received = arrivals.poll(15, TimeUnit.SECONDS)
            assertNotNull("Recipient did not decrypt: $errors", received)
            assertArrayEquals(first.content, received!!.content); assertEquals(first.sentAt, received.sentAt)
            assertEquals(49, db.x3dh(scopeB).keys("local/opk/").size)
            assertTrue(db.requests(scopeB).isEmpty())
            val receiptDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(tx.statuses(alice.userId).first { it.first==first.id }.second!="received" && System.nanoTime()<receiptDeadline) Thread.sleep(50)
            assertEquals("received",tx.statuses(alice.userId).first { it.first==first.id }.second)
            val reply = Message(bob.userId, alice.userId, "reply over Flask".toByteArray(), Message.MessageType.DATA,
                status = Message.DeliveryStatus.SENDING)
            db.addOutgoing(scopeB, reply); recipient.send(reply)
            sender.fetchNewMessages()
            assertEquals("reply over Flask", String(replies.poll(15, TimeUnit.SECONDS)!!.content))
            recipient.close(); rx.close()
            rx = ServerTransport(url, DeviceAuthentication(url, bob))
            recipient = MessagingService(bob.userId, bob, rx, db, scopeB,contacts={setOf(alice.userId)})
            recipient.onMessageDecrypted = { arrivals.offer(it); true }; recipient.onError = { errors.offer(it) }
            recipient.start()
            val second = first.copy(id = UUID.randomUUID().toString().replace("-", ""), content = "after restart".toByteArray())
            db.addOutgoing(scopeA, second); sender.send(second); recipient.fetchNewMessages()
            assertEquals("after restart", String(arrivals.poll(15, TimeUnit.SECONDS)!!.content))
            val sessionKey=db.x3dh(scopeB).keys("session/").single()
            val ratchetAfterRestart=org.json.JSONObject(db.x3dh(scopeB).get(sessionKey)!!).getJSONObject("ratchet")
            val freshReply=reply.copy(id=UUID.randomUUID().toString().replace("-",""),content="ratcheted reply after restart".toByteArray())
            db.addOutgoing(scopeB,freshReply);recipient.send(freshReply);sender.fetchNewMessages()
            assertEquals("ratcheted reply after restart",String(replies.poll(15,TimeUnit.SECONDS)!!.content))
            val aliceRatchet=org.json.JSONObject(db.x3dh(scopeA).get(sessionKey)!!).getJSONObject("ratchet")
            assertEquals(ratchetAfterRestart.getString("dhs_public"),aliceRatchet.getString("dhr"))
            assertEquals(49, db.x3dh(scopeB).keys("local/opk/").size)
            assertTrue("No protocol errors: $errors", errors.isEmpty())
        } finally { sender.close(); recipient.close(); tx.close(); rx.close(); db.close() }
    }
}
