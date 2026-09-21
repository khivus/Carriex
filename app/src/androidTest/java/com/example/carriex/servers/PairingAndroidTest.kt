package com.example.carriex.servers

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.carriex.app.MainActivity
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.CaptureActivity
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PairingAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun payload(cert: HeldCertificate, url: String) = JSONObject()
        .put("type", "carriex-server").put("version", 1).put("name", "Pairing UI test")
        .put("url", url).put("certificate", cert.certificate.encoded.toByteString().base64()).toString()

    @Test fun textImportSurvivesActivityAndViewModelRecreationThenCanBeForgotten() {
        val preferences = instrumentation.targetContext.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
        val original = preferences.getString("paired_servers_v1", null)
        val cert = HeldCertificate.Builder().ecdsa256().commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1").build()
        val text = payload(cert, "https://127.0.0.1:18443")
        try {
            assertTrue(preferences.edit().remove("paired_servers_v1").commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withText("Import pairing text")).perform(click())
                onView(isAssignableFrom(TextInputEditText::class.java)).perform(replaceText(text), closeSoftKeyboard())
                onView(withId(android.R.id.button1)).perform(click())
                onView(withText("Save server?")).check(matches(isDisplayed()))
                onView(withId(android.R.id.button1)).perform(click())
                onView(withText("Pairing UI test")).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText("Pairing UI test")).check(matches(isDisplayed()))
            }
            // A fresh activity gets a new ViewModel and must reload persisted data.
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withText("Pairing UI test")).check(matches(isDisplayed()))
                assertEquals(1, ServerListStore({ preferences.getString("paired_servers_v1", null) }, { false }).list().size)
                onView(withText("Forget")).perform(scrollTo(), click())
                onView(withId(android.R.id.button1)).perform(click())
                onView(withText("No saved servers yet")).check(matches(isDisplayed()))
                assertTrue(ServerListStore({ preferences.getString("paired_servers_v1", null) }, { false }).list().isEmpty())
            }
        } finally {
            val editor = preferences.edit()
            if (original == null) editor.remove("paired_servers_v1") else editor.putString("paired_servers_v1", original)
            assertTrue(editor.commit())
        }
    }

    @Test fun qrScannerIsEmbeddedAndCancellationReturnsToSavedServers() {
        instrumentation.uiAutomation.executeShellCommand("pm grant com.example.carriex android.permission.CAMERA").close()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Scan server QR")).perform(click())
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { it is CaptureActivity })
            }
            pressBack()
            onView(withText("Saved servers")).check(matches(isDisplayed()))
        }
    }

    @Test fun androidTlsAcceptsRenewedPinnedKeyButRejectsReplacementKeyAndWrongHost() {
        val now = System.currentTimeMillis()
        val original = HeldCertificate.Builder().ecdsa256().commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1")
            .validityInterval(now - TimeUnit.DAYS.toMillis(2), now - TimeUnit.DAYS.toMillis(1)).build()
        val renewed = HeldCertificate.Builder().keyPair(original.keyPair).commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1").build()
        val replaced = HeldCertificate.Builder().ecdsa256().commonName("Carriex paired server")
            .addSubjectAlternativeName("127.0.0.1").build()
        val wrongHost = HeldCertificate.Builder().keyPair(original.keyPair).commonName("Carriex paired server")
            .addSubjectAlternativeName("wrong.example").build()
        for (served in listOf(renewed, replaced, wrongHost, original)) {
            MockWebServer().use { server ->
                server.useHttps(HandshakeCertificates.Builder().heldCertificate(served).build().sslSocketFactory(), false)
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val url = "https://127.0.0.1:${server.port}"
                val client = PairedServer.parse(payload(original, url)).client()
                server.enqueue(MockResponse().setBody("paired"))
                try {
                    if (served === renewed) {
                        client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("paired", it.body!!.string()) }
                    } else {
                        try {
                            client.newCall(Request.Builder().url(url).build()).execute().use { }
                            fail("Untrusted server was accepted")
                        } catch (_: IOException) { }
                        assertEquals(0, server.requestCount)
                    }
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }
    }
}
