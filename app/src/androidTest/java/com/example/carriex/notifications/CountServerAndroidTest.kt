package com.example.carriex.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.model.Message
import com.example.carriex.transport.ServerTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Android auth and encrypted credential storage against the actual Flask API. */
@RunWith(AndroidJUnit4::class)
class CountServerAndroidTest {
    private fun identity() = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair())

    @Test fun actualRelayAuthenticatesCountCredentialsAndKeepsCiphertextQueued() {
        val url = InstrumentationRegistry.getArguments().getString("x3dhServer").orEmpty()
        assumeTrue("Pass the localhost relay URL as x3dhServer", url.isNotEmpty())
        val sender = identity(); val recipient = identity()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "live-count-${UUID.randomUUID()}"
        val credentials = CountCredentialStore(context, scope)
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val auth = DeviceAuthentication(url, recipient, client)
        val counts = CountTransport(url, auth, client, credentials::read, credentials::write)
        val transport = ServerTransport(url, DeviceAuthentication(url, sender))
        try {
            assertEquals(0, counts.fetch().count)
            val message = Message(sender.userId, recipient.userId, "opaque-test-envelope".toByteArray(), Message.MessageType.DATA)
            assertTrue(transport.sendMessageResult(message).accepted)
            val before = counts.fetch()
            assertEquals(1, before.count)
            assertTrue(before.revision > 0)
            assertTrue(transport.sendMessageResult(message).accepted)
            assertEquals(before, counts.fetch())
            val restored = CountCredentialStore(context, scope).read()!!
            assertEquals(credentials.read(), restored)
            client.newCall(Request.Builder().url("${url.trimEnd('/')}/messages/${recipient.userId}")
                .header("Authorization", "Bearer ${restored.token}").build()).execute().use {
                assertEquals("Count credential cannot download", 401, it.code)
            }
            client.newCall(Request.Builder().url("${url.trimEnd('/')}/messages/${recipient.userId}")
                .header("Authorization", "Bearer ${auth.token()}").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("Checks leave ciphertext queued", 1, org.json.JSONObject(it.body!!.string()).getJSONArray("messages").length())
            }
        } finally {
            credentials.write(null); transport.close(); auth.close()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        }
    }
}
