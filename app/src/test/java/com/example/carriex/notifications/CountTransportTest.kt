package com.example.carriex.notifications

import com.example.carriex.auth.ServerAuthentication
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CountTransportTest {
    private fun MockWebServer.localUrl() = url("/").newBuilder().host("127.0.0.1").build().toString()
    private class Auth : ServerAuthentication {
        override val userId = "a".repeat(64)
        var requests = 0
        override fun token(): String { requests++; return "f".repeat(43) }
        override fun invalidate(token: String) { }
    }
    private fun issued(auth: Auth) = """{"user_id":"${auth.userId}","scope":"notifications.count","token":"${"s".repeat(43)}","expires_in":604800}"""

    @Test fun cachedCredentialUsesOnlyCountGetWithoutFullAuthentication() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pending_count":2,"revision":9}"""))
            val auth = Auth()
            val transport = CountTransport(server.localUrl(), auth, OkHttpClient(),
                { CountCredential("s".repeat(43), Long.MAX_VALUE) }, { error("Must not renew") })
            assertEquals(PendingCount(2,9), transport.fetch())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/messages/${auth.userId}/count", request.path)
            assertEquals("Bearer ${"s".repeat(43)}", request.getHeader("Authorization"))
            assertEquals(0, auth.requests)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingCredentialIsIssuedOnceAndReusedAcrossInstances() {
        MockWebServer().use { server ->
            val auth = Auth()
            var stored: CountCredential? = null
            server.enqueue(MockResponse().setBody(issued(auth)))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"pending_count":0,"revision":0}""")) }
            repeat(2) {
                CountTransport(server.localUrl(), auth, OkHttpClient(), { stored }, { stored=it }).fetch()
            }
            assertEquals(1, auth.requests)
            assertEquals("POST", server.takeRequest().method)
            repeat(2) { assertEquals("/messages/${auth.userId}/count", server.takeRequest().path) }
            assertEquals(3, server.requestCount)
            assertTrue(stored!!.expiresAt > System.currentTimeMillis())
        }
    }

    @Test fun revokedCountTokenRenewsOnceAndNeverRequestsMessageBodies() {
        MockWebServer().use { server ->
            val auth = Auth()
            var stored: CountCredential? = CountCredential("r".repeat(43), Long.MAX_VALUE)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(issued(auth)))
            server.enqueue(MockResponse().setBody("""{"pending_count":3,"revision":11}"""))
            assertEquals(3,CountTransport(server.localUrl(),auth,OkHttpClient(),{stored},{stored=it}).fetch().count)
            assertEquals(listOf("/messages/${auth.userId}/count","/notifications/token","/messages/${auth.userId}/count"),
                (1..3).map { server.takeRequest().path })
        }
    }

    @Test fun malformedOrOversizedResponseDoesNotProduceCount() {
        MockWebServer().use { server ->
            val auth = Auth()
            val transport = CountTransport(server.localUrl(),auth,OkHttpClient(),{CountCredential("s".repeat(43),Long.MAX_VALUE)},{})
            listOf("""{"pending_count":-1,"revision":1}""", """{"pending_count":1.5,"revision":1}""",
                """{"pending_count":1,"revision":-1}""", "x".repeat(5000)).forEach { body ->
                server.enqueue(MockResponse().setBody(body))
                assertTrue(runCatching { transport.fetch() }.isFailure)
            }
            assertEquals(0,auth.requests)
        }
    }

    @Test fun arbitraryCustomIntervalsAreNotClampedToFifteenMinutes() {
        assertEquals(900_000L,PollInterval.DEFAULT)
        assertEquals(90_000L,PollInterval.parse("1.5",60_000))
        assertEquals(1000L,PollInterval.parse("1",1000))
        assertEquals(1001L,PollInterval.parse("1.0001",1000))
        assertEquals(4_320_000_000L,PollInterval.parse("1200",3_600_000))
        listOf("0","-1","NaN","999999999999999999999999999999").forEach {
            assertTrue(runCatching { PollInterval.parse(it,60_000) }.isFailure)
        }
    }
}
