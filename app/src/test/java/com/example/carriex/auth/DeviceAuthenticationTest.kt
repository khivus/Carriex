package com.example.carriex.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceAuthenticationTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun identityIsStableUniqueAndProofIsBoundToIdentityAndChallenge() {
        val pair = keyPair()
        val identity = DeviceIdentity(pair)
        assertEquals(identity.userId, DeviceIdentity(pair).userId)
        assertNotEquals(identity.userId, DeviceIdentity(keyPair()).userId)
        assertTrue(identity.userId.matches(Regex("[0-9a-f]{64}")))
        val nonce = "n".repeat(43)
        val proof = identity.signChallenge(identity.userId, nonce, "https://chat.example")
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public) }
        verifier.update("Carriex device authentication v1\nhttps://chat.example\n${identity.userId}\n$nonce".toByteArray())
        assertTrue(verifier.verify(proof))
        verifier.update("Carriex device authentication v1\nhttps://chat.example\n${identity.userId}\n${"x".repeat(43)}".toByteArray())
        assertFalse(verifier.verify(proof))
        assertThrows(IllegalArgumentException::class.java) { identity.signChallenge("a".repeat(64), nonce, "https://chat.example") }
        assertThrows(IllegalArgumentException::class.java) { identity.signChallenge(identity.userId, "bad\nchallenge", "https://chat.example") }
    }

    private fun enqueueAuth(server: MockWebServer, id: String, token: String) {
        server.enqueue(MockResponse().setBody(JSONObject().put("user_id", id).put("challenge", "n".repeat(43)).put("audience", server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')).toString()))
        server.enqueue(MockResponse().setBody(JSONObject().put("user_id", id).put("token", token).put("expires_in", 3600).toString()))
    }

    @Test fun challengeUsesPublicKeyOnlyAndConcurrentCallsShareOneSession() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val pair = keyPair()
            val identity = DeviceIdentity(pair)
            val token = "t".repeat(43)
            enqueueAuth(server, identity.userId, token)
            val auth = DeviceAuthentication(server.url("/").newBuilder().host("127.0.0.1").build().toString(), identity)
            val pool = Executors.newFixedThreadPool(6)
            try {
                val calls = (1..6).map { pool.submit<String> { auth.token() } }
                calls.forEach { assertEquals(token, it.get(5, TimeUnit.SECONDS)) }
                assertEquals(2, server.requestCount)
                val challenge = server.takeRequest()
                assertEquals("/auth/challenge", challenge.path)
                val challengeBody = JSONObject(challenge.body.readUtf8())
                assertEquals(setOf("public_key"), challengeBody.keys().asSequence().toSet())
                assertArrayEquals(identity.publicKey, Base64.getDecoder().decode(challengeBody.getString("public_key")))
                val verify = server.takeRequest()
                assertEquals("/auth/verify", verify.path)
                val proof = JSONObject(verify.body.readUtf8())
                val signature = Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public) }
                signature.update("Carriex device authentication v1\n${server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')}\n${identity.userId}\n${proof.getString("challenge")}".toByteArray())
                assertTrue(signature.verify(Base64.getDecoder().decode(proof.getString("signature"))))
            } finally { pool.shutdownNow(); auth.close() }
        }
    }

    @Test fun invalidationRefreshesSessionButStaleFailureCannotInvalidateNewToken() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val identity = DeviceIdentity(keyPair())
            val first = "a".repeat(43)
            val second = "b".repeat(43)
            enqueueAuth(server, identity.userId, first)
            enqueueAuth(server, identity.userId, second)
            val auth = DeviceAuthentication(server.url("/").newBuilder().host("127.0.0.1").build().toString(), identity)
            try {
                assertEquals(first, auth.token())
                auth.invalidate(first)
                assertEquals(second, auth.token())
                auth.invalidate(first)
                assertEquals(second, auth.token())
                assertEquals(4, server.requestCount)
            } finally { auth.close() }
        }
    }

    @Test fun refusesChangedIdentityWithoutSendingSignature() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody(JSONObject().put("user_id", "a".repeat(64)).put("challenge", "n".repeat(43)).put("audience", server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')).toString()))
            val auth = DeviceAuthentication(server.url("/").newBuilder().host("127.0.0.1").build().toString(), DeviceIdentity(keyPair()))
            try {
                assertThrows(IllegalArgumentException::class.java) { auth.token() }
                assertEquals(1, server.requestCount)
            } finally { auth.close() }
        }
    }

    @Test fun neverFollowsAuthenticationRedirects() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "https://other.example/auth/challenge"))
            val auth = DeviceAuthentication(server.url("/").newBuilder().host("127.0.0.1").build().toString(), DeviceIdentity(keyPair()))
            try { assertThrows(java.io.IOException::class.java) { auth.token() } }
            finally { auth.close() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun refusesAnotherServersChallengeBeforeSigning() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val identity = DeviceIdentity(keyPair())
            server.enqueue(MockResponse().setBody(JSONObject().put("user_id", identity.userId)
                .put("challenge", "n".repeat(43)).put("audience", "https://victim.example").toString()))
            val auth = DeviceAuthentication(server.url("/").newBuilder().host("127.0.0.1").build().toString(), identity)
            try {
                assertThrows(IllegalArgumentException::class.java) { auth.token() }
                assertEquals(1, server.requestCount)
            } finally { auth.close() }
        }
    }

    @Test fun refusesCleartextRemoteServers() {
        for (url in listOf("http://example.com", "http://192.168.1.5", "http://127.0.0.1.evil.example")) {
            assertThrows(IllegalArgumentException::class.java) { DeviceAuthentication.requireSecureEndpoint(url.toHttpUrl()) }
        }
        DeviceAuthentication.requireSecureEndpoint("https://example.com".toHttpUrl())
        DeviceAuthentication.requireSecureEndpoint("http://127.0.0.1".toHttpUrl())
    }
}
