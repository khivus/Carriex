package com.example.carriex.notifications

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.app.MainActivity
import com.example.carriex.auth.AndroidDeviceIdentity
import com.example.carriex.presentation.chat.ChatViewModel
import com.example.carriex.servers.PairedServer
import com.google.android.material.textfield.TextInputEditText
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NotificationAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = NotificationPolling.preferences(context)
    private lateinit var original: Map<String, *>
    private lateinit var relay: MockWebServer
    private lateinit var profile: PairedServer
    private val paths = CopyOnWriteArrayList<String>()
    @Volatile private var pending = 2
    @Volatile private var revision = 9L

    @Before fun setup() {
        original = preferences.all
        preferences.edit().putBoolean("notifications_enabled", false).commit()
        NotificationPolling.schedule(context)
        if(Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val identity = AndroidDeviceIdentity.load()
        val certificate = HeldCertificate.Builder().ecdsa256().commonName("Notification test")
            .addSubjectAlternativeName("127.0.0.1").build()
        relay = MockWebServer()
        relay.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        relay.start(InetAddress.getByName("127.0.0.1"), 0)
        val url = "https://127.0.0.1:${relay.port}"
        profile = PairedServer.parse(JSONObject().put("type", "carriex-server").put("version", 1)
            .put("name", "Practice server").put("url", url)
            .put("certificate", certificate.certificate.encoded.toByteString().base64()).toString())
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add(request.path!!)
                val body = when(request.path) {
                    "/auth/challenge" -> JSONObject().put("user_id", identity.userId).put("audience", url)
                        .put("challenge", "a".repeat(43)).put("expires_in", 60)
                    "/auth/verify" -> JSONObject().put("user_id", identity.userId).put("token", "f".repeat(43)).put("expires_in", 3600)
                    "/notifications/token" -> JSONObject().put("user_id", identity.userId).put("token", "s".repeat(43))
                        .put("expires_in", 604800).put("scope", "notifications.count")
                    "/messages/${identity.userId}/count" -> {
                        assertEquals("GET", request.method)
                        assertEquals("Bearer ${"s".repeat(43)}", request.getHeader("Authorization"))
                        JSONObject().put("pending_count", pending).put("revision", revision)
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
            }
        }
        preferences.edit().clear().putString("paired_servers_v1", JSONArray().put(profile.toJson()).toString())
            .putBoolean("notifications_enabled", true).putBoolean("battery_prompt_shown", true).commit()
        NotificationPolling.appVisible = false
        NotificationPolling.channels(context)
    }

    @After fun cleanup() {
        preferences.edit().putBoolean("notifications_enabled", false).commit()
        NotificationPolling.schedule(context)
        CountPoller.cancel()
        relay.shutdown()
        val editor = preferences.edit().clear()
        original.forEach { (key, value) ->
            when(value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    private fun alert() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .singleOrNull { it.tag == profile.fingerprint && it.id == 1 }

    private fun awaitAlert(present: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while((alert() != null) != present) {
            assertTrue("Android applies the notification update", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }

    @Test fun encryptedCredentialAndCountOnlyChecksDeduplicateAndDetectSameCountNewArrival() {
        assertFalse(CountPoller.poll(context))
        awaitAlert(true)
        assertEquals(listOf("/auth/challenge", "/auth/verify", "/notifications/token",
            "/messages/${AndroidDeviceIdentity.load().userId}/count"), paths.toList())
        val store = CountCredentialStore(context, "${profile.fingerprint}:${AndroidDeviceIdentity.load().userId}")
        assertEquals("s".repeat(43), store.read()!!.token)
        val persisted = context.getSharedPreferences("notification_credentials", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(persisted.contains("s".repeat(43)))
        context.getSystemService(NotificationManager::class.java).cancel(profile.fingerprint, 1)
        awaitAlert(false)
        CountPoller.poll(context)
        assertNull("Dismissed counts must not alert again", alert())
        revision++
        CountPoller.poll(context)
        awaitAlert(true)
        pending = 0; revision = 0
        CountPoller.poll(context)
        awaitAlert(false)
        assertEquals(1, paths.count { it == "/auth/challenge" })
        assertEquals(1, paths.count { it == "/notifications/token" })
        assertTrue(paths.drop(3).all { it.endsWith("/count") })
        val before = paths.size
        NotificationPolling.appVisible = true
        CountPoller.poll(context)
        assertEquals("Visible app skips background polling", before, paths.size)
        NotificationPolling.appVisible = false
        preferences.edit().putBoolean("notify_${profile.fingerprint}", false).commit()
        CountPoller.poll(context)
        assertEquals("Deselected server is never queried", before, paths.size)
    }

    @Test fun defaultUsesPersistentFifteenMinuteJobAndCustomSecondsUseForegroundService() {
        NotificationPolling.appVisible = true
        NotificationPolling.schedule(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = scheduler.getPendingJob(NotificationPolling.JOB_ID)!!
        assertEquals(900_000L, job.intervalMillis)
        assertTrue(job.isPersisted)
        NotificationPolling.schedule(context)
        assertEquals(job, scheduler.getPendingJob(NotificationPolling.JOB_ID))
        preferences.edit().putLong("notification_interval", 12_345L).commit()
        instrumentation.runOnMainSync { NotificationPolling.schedule(context) }
        assertNull(scheduler.getPendingJob(NotificationPolling.JOB_ID))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == NotificationPolling.SERVICE_ID }) {
            assertTrue("Foreground status notification appears", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
        assertTrue(paths.isEmpty())
        preferences.edit().putLong("notification_interval", 1_800_000L).commit()
        NotificationPolling.schedule(context)
        assertEquals(1_800_000L, scheduler.getPendingJob(NotificationPolling.JOB_ID)!!.intervalMillis)
    }

    private fun navigate(title: String) {
        onView(withContentDescription("Open navigation menu")).perform(click())
        onView(withText(title)).perform(click())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun drawerTitlesSettingsCustomInputAndOfflineConversationStayOrganized() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Carriex - servers")).check(matches(isDisplayed()))
            screenshot("navigation-servers")
            onView(withContentDescription("Open navigation menu")).perform(click())
            onView(withText("My ID (${AndroidDeviceIdentity.load().userId.take(8)})")).check(matches(isDisplayed()))
            onView(withText("Settings")).check(matches(isDisplayed()))
            screenshot("navigation-drawer")
            pressBack()
            onView(withText(profile.name)).perform(click())
            onView(withText("${profile.name} - chats")).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ChatViewModel::class.java]
                model.setContactName("a".repeat(64), "Alex")
                model.openChat("a".repeat(64))
            }
            onView(withText("Alex (aaaaaaaa)")).check(matches(isDisplayed()))
            onView(withText("All chats")).check(doesNotExist())
            screenshot("navigation-conversation")
            pressBack()
            navigate("Settings")
            onView(withText("Notifications")).perform(click())
            onView(withText("Notifications")).check(matches(isDisplayed()))
            onView(isAssignableFrom(TextInputEditText::class.java)).check(matches(withText("15")))
            screenshot("navigation-notifications")
            onView(isAssignableFrom(TextInputEditText::class.java)).perform(replaceText("2.5"), closeSoftKeyboard())
            onView(withText("Apply interval")).perform(scrollTo(), click())
            assertEquals(150_000L, NotificationPolling.interval(context))
            scenario.recreate()
            onView(withText("Notifications")).check(matches(isDisplayed()))
            onView(isAssignableFrom(TextInputEditText::class.java)).check(matches(withText("2.5")))
            pressBack()
            pressBack()
            scenario.onActivity { activity -> ViewModelProvider(activity)[ChatViewModel::class.java].openChat("a".repeat(64)) }
            onView(withText("Alex (aaaaaaaa)")).check(matches(isDisplayed()))
            onView(withText("Send")).check(matches(isDisplayed()))
            pressBack()
            navigate("Servers")
            onView(withText("Carriex - servers")).check(matches(isDisplayed()))
        }
    }
}
