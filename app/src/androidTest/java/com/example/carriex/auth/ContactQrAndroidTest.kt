package com.example.carriex.auth

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.app.MainActivity
import com.example.carriex.servers.PairedServer
import com.journeyapps.barcodescanner.CaptureActivity
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class ContactQrAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun displayedQrDecodesToThisDevicesFullIdentity() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("My ID QR")).perform(scrollTo(), click())
            onView(withContentDescription("Your Carriex user ID QR")).check { view, error ->
                if (error != null) throw error
                val bitmap = ((view as ImageView).drawable as BitmapDrawable).bitmap
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                    RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
                assertEquals(AndroidDeviceIdentity.load().userId, ContactQr.parse(decoded))
            }
            onView(withId(android.R.id.button1)).perform(click())
        }
    }

    private fun scan(button: String, text: String) {
        // Exercise the real ActivityResult callback; camera decoding is covered by QR round-trip tests.
        val result = Intent().putExtra("SCAN_RESULT", text).putExtra("SCAN_RESULT_FORMAT", "QR_CODE")
        val monitor = instrumentation.addMonitor(CaptureActivity::class.java.name, ActivityResult(Activity.RESULT_OK, result), true)
        try {
            onView(withText(button)).perform(click())
            instrumentation.waitForIdleSync()
            assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun scanningAddsFriendAndVerificationShowsMatchMismatchAndInvalidQr() {
        val preferences = instrumentation.targetContext.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
        val original = preferences.getString("paired_servers_v1", null)
        val identity = AndroidDeviceIdentity.load()
        val friend = "b".repeat(64)
        val cert = HeldCertificate.Builder().ecdsa256().commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1").build()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val url = "https://127.0.0.1:${server.port}"
            // Local TLS fixture for UI navigation. Authentication itself has separate real-server tests.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/health" -> MockResponse().setBody("{\"status\":\"alive\"}")
                    request.path == "/auth/challenge" -> MockResponse().setBody(JSONObject()
                        .put("user_id", identity.userId).put("audience", url).put("challenge", "a".repeat(43)).put("expires_in", 60).toString())
                    request.path == "/auth/verify" -> MockResponse().setBody(JSONObject()
                        .put("user_id", identity.userId).put("token", "t".repeat(43)).put("expires_in", 3600).toString())
                    request.path?.startsWith("/ws/") == true -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    request.path?.startsWith("/messages/") == true -> MockResponse().setBody("{\"messages\":[]}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val profile = PairedServer.parse(JSONObject().put("type", "carriex-server").put("version", 1)
                .put("name", "Contact QR test").put("url", url)
                .put("certificate", cert.certificate.encoded.toByteString().base64()).toString())
            try {
                assertTrue(preferences.edit().putString("paired_servers_v1", JSONArray().put(profile.toJson()).toString()).commit())
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    onView(withText("Connect")).perform(scrollTo(), click())
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                    while (true) {
                        try { onView(withText("Chats")).check(matches(isDisplayed())); break }
                        catch (e: androidx.test.espresso.NoMatchingViewException) {
                            if (System.nanoTime() >= deadline) throw e
                            Thread.sleep(50)
                        }
                    }
                    scan("Scan friend's ID", ContactQr.encode(friend))
                    onView(withText("Chat with this device?")).check(matches(isDisplayed()))
                    onView(withId(android.R.id.button1)).perform(click())
                    onView(withText(friend)).check(matches(isDisplayed()))
                    scenario.recreate()
                    scan("Verify friend's ID", ContactQr.encode(friend))
                    onView(withText("ID matches this chat")).check(matches(isDisplayed()))
                    onView(withId(android.R.id.button1)).perform(click())
                    scan("Verify friend's ID", ContactQr.encode("c".repeat(64)))
                    onView(withText("ID does not match")).check(matches(isDisplayed()))
                    onView(withId(android.R.id.button1)).perform(click())
                    onView(withText(friend)).check(matches(isDisplayed()))
                    scan("Verify friend's ID", profile.toJson().toString())
                    onView(withText("Cannot read user ID")).check(matches(isDisplayed()))
                    onView(withId(android.R.id.button1)).perform(click())
                    scan("Verify friend's ID", ContactQr.encode(identity.userId))
                    onView(withText("This is your own ID")).check(matches(isDisplayed()))
                    onView(withId(android.R.id.button1)).perform(click())
                }
            } finally {
                val editor = preferences.edit().remove("contacts_${profile.fingerprint}")
                if (original == null) editor.remove("paired_servers_v1") else editor.putString("paired_servers_v1", original)
                assertTrue(editor.commit())
            }
        }
    }
}
