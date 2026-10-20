package com.example.carriex.notifications

import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.auth.ServerAuthentication
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

data class CountCredential(val token: String, val expiresAt: Long)
data class PendingCount(val count: Int, val revision: Long)

/** No message download/ack APIs. Persisted credentials authorize count GET only. */
class CountTransport(
    baseUrl: String,
    private val auth: ServerAuthentication,
    private val client: OkHttpClient,
    private val read: () -> CountCredential?,
    private val write: (CountCredential?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val base = baseUrl.toHttpUrl().also(DeviceAuthentication::requireSecureEndpoint)
    private fun json(response: Response): JSONObject {
        if (response.body?.source()?.request(4097) == true) throw IOException("Oversized notification response")
        return JSONObject(response.body?.string() ?: throw IOException("Empty notification response"))
    }
    private fun issue(): CountCredential {
        val request = Request.Builder().url(base.newBuilder().addPathSegments("notifications/token").build())
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        repeat(2) { attempt ->
            val token = auth.token()
            client.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute().use { response ->
                if (response.code == 401 && attempt == 0) { auth.invalidate(token); return@repeat }
                if (response.code == 404) throw IOException("Update this relay to support count notifications")
                if (response.code != 200) throw IOException("Notification authentication failed (HTTP ${response.code})")
                val data = json(response)
                require(data.getString("scope") == "notifications.count" && data.getString("user_id") == auth.userId)
                val secret = data.getString("token")
                require(secret.matches(Regex("[A-Za-z0-9_-]{43}")))
                val ttl = data.getLong("expires_in"); require(ttl in 1..30*86400)
                return CountCredential(secret, now() + (ttl - minOf(60L, ttl / 10)) * 1000).also(write)
            }
        }
        throw IOException("Notification authentication failed")
    }
    @Synchronized fun fetch(): PendingCount {
        repeat(2) { attempt ->
            val credential = read()?.takeIf { it.expiresAt > now() } ?: issue()
            val request = Request.Builder().url(base.newBuilder().addPathSegment("messages")
                .addPathSegment(auth.userId).addPathSegment("count").build())
                .header("Authorization", "Bearer ${credential.token}").get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) { write(null); return@repeat }
                if (response.code == 404) throw IOException("Update this relay to support count notifications")
                if (response.code != 200) throw IOException("Count check failed (HTTP ${response.code})")
                val data = json(response)
                val count = data.get("pending_count"); val revision = data.get("revision")
                require(count is Int || count is Long); require(revision is Int || revision is Long)
                require((count as Number).toLong() in 0..Int.MAX_VALUE.toLong() && (revision as Number).toLong() >= 0)
                return PendingCount(count.toInt(), revision.toLong())
            }
        }
        throw IOException("Count check authentication failed")
    }
}
