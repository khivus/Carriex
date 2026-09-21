package com.example.carriex.auth

import com.example.carriex.transport.closeOffThread

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ServerAuthentication {
    val userId: String
    fun token(): String
    fun invalidate(token: String)
    fun close() {}
}

/** Tokens live only in RAM; a new challenge is signed on expiry or server restart. */
class DeviceAuthentication(
    baseUrl: String,
    private val identity: DeviceIdentity,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build(),
) : ServerAuthentication {
    private val base = baseUrl.toHttpUrl()
    override val userId get() = identity.userId
    @Volatile private var cachedToken: String? = null
    @Volatile private var closed = false
    private var refreshAt = 0L

    init { requireSecureEndpoint(base) }

    @Synchronized
    override fun token(): String {
        check(!closed) { "Authentication is closed" }
        cachedToken?.let { if (System.nanoTime() < refreshAt) return it }
        val challenge = post("challenge", JSONObject().put("public_key", base64(identity.publicKey)))
        val challengeId = challenge.getString("challenge")
        val audience = base.toString().trimEnd('/')
        require(challenge.getString("audience") == audience) { "Server authentication address does not match the configured address" }
        val signature = identity.signChallenge(challenge.getString("user_id"), challengeId, audience)
        val verified = post("verify", JSONObject().put("challenge", challengeId).put("signature", base64(signature)))
        if (verified.getString("user_id") != userId) throw IOException("Server returned a different device identity")
        val token = verified.getString("token")
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) throw IOException("Invalid authentication token")
        val lifetime = verified.getLong("expires_in")
        if (lifetime !in 1..86400) throw IOException("Invalid authentication lifetime")
        cachedToken = token
        if (closed) { cachedToken = null; error("Authentication is closed") }
        refreshAt = System.nanoTime() + TimeUnit.SECONDS.toNanos((lifetime - minOf(60, lifetime / 10)).coerceAtLeast(1))
        return token
    }

    @Synchronized
    override fun invalidate(token: String) {
        // An old failed socket must not clear a newer session obtained by HTTP.
        if (cachedToken == token) { cachedToken = null; refreshAt = 0 }
    }

    override fun close() {
        closed = true
        cachedToken = null
        client.closeOffThread()
    }

    private fun post(action: String, body: JSONObject): JSONObject {
        val url = base.newBuilder().addPathSegment("auth").addPathSegment(action).build()
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Device authentication failed (HTTP ${response.code})")
            return JSONObject(response.body?.string() ?: throw IOException("Empty authentication response"))
        }
    }

    companion object {
        fun requireSecureEndpoint(url: HttpUrl) {
            require(url.isHttps || url.host in setOf("127.0.0.1", "::1", "localhost", "10.0.2.2")) {
                "Device authentication requires HTTPS. Plain HTTP is allowed only for localhost/emulator development."
            }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Server address must not contain credentials, a query or a fragment"
            }
        }

        // Android API 24 supports android.util.Base64, but java.util.Base64 starts
        // at 26. Okio is already supplied by OkHttp and works on both Android/JVM.
        private fun base64(bytes: ByteArray) = okio.ByteString.of(*bytes).base64()
    }
}
