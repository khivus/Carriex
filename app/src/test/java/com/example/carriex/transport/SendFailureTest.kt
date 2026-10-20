package com.example.carriex.transport

import com.example.carriex.auth.ServerAuthentication
import com.example.carriex.model.Message
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SendFailureTest {
    @Test fun permanentFailuresRemainDistinctFromCapacityAndConnectionFailures() {
        MockWebServer().use { server ->
            server.start()
            val auth=object : ServerAuthentication {
                override val userId="a".repeat(64)
                override fun token()="test"
                override fun invalidate(token:String) { }
            }
            val transport=ServerTransport(server.url("/").newBuilder().host("127.0.0.1").build().toString(),auth)
            val message=Message(auth.userId,"b".repeat(64),"wire".toByteArray(),Message.MessageType.DATA)
            try {
                listOf(413 to false,409 to false,429 to true,503 to true).forEach { (code,retryable) ->
                    server.enqueue(MockResponse().setResponseCode(code).setBody("{\"error\":\"test error\"}"))
                    val result=transport.sendMessageResult(message)
                    assertFalse(result.accepted);assertEquals(retryable,result.retryable);assertEquals("test error",result.detail)
                }
                server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":\"Encrypted message exceeds 256 KiB\"}"))
                assertFalse(transport.sendMessageResult(message).retryable)
            } finally { transport.close() }
        }
    }
}
