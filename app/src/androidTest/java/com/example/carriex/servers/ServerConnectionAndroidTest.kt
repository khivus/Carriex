package com.example.carriex.servers

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.app.MainActivity
import com.example.carriex.auth.AndroidDeviceIdentity
import com.example.carriex.crypto.keystore.AndroidKeyStoreKeyManager
import com.example.carriex.crypto.libsodium.LibsodiumCryptoEngine
import com.example.carriex.crypto.model.SessionKeys
import com.example.carriex.messaging.ChatStore
import com.example.carriex.messaging.MessagePayload
import com.example.carriex.model.Message
import com.example.carriex.presentation.chat.ChatViewModel
import com.example.carriex.session.Session
import com.example.carriex.session.SessionState
import com.example.carriex.transport.ServerTransport.ConnectionStatus
import com.example.carriex.ui.chat.ChatScreen
import com.google.android.material.textfield.TextInputEditText
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ServerConnectionAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)

    /** Real TLS and WebSocket connections; only the server protocol responses are fixtures. */
    private class TestServer(name: String, userId: String) : AutoCloseable {
        val server = MockWebServer()
        val socketCount = AtomicInteger()
        val closed = CountDownLatch(1)
        val healthEntered = CountDownLatch(1)
        @Volatile var holdHealth: CountDownLatch? = null
        @Volatile var socket: WebSocket? = null
        @Volatile var sentFrame: JSONObject? = null
        private val certificate = HeldCertificate.Builder().ecdsa256().commonName("Carriex test")
            .addSubjectAlternativeName("127.0.0.1").build()
        val pairing: PairedServer
        @Volatile var healthy = true

        init {
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val url = "https://127.0.0.1:${server.port}"
            pairing = PairedServer.parse(JSONObject().put("type", "carriex-server").put("version", 1)
                .put("name", name).put("url", url)
                .put("certificate", certificate.certificate.encoded.toByteString().base64()).toString())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/health" -> {
                        healthEntered.countDown()
                        holdHealth?.await(15, TimeUnit.SECONDS)
                        if (healthy) json("{\"status\":\"alive\"}") else MockResponse().setResponseCode(503)
                    }
                    request.path == "/auth/challenge" -> json(JSONObject().put("user_id", userId)
                        .put("challenge", "c".repeat(43)).put("audience", url).toString())
                    request.path == "/auth/verify" -> json(JSONObject().put("user_id", userId)
                        .put("token", "t".repeat(43)).put("expires_in", 3600).toString())
                    request.path == "/send" -> {
                        sentFrame = JSONObject(request.body.readUtf8())
                        MockResponse().setResponseCode(404)
                    }
                    request.path?.startsWith("/ws/") == true -> {
                        socketCount.incrementAndGet()
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(code, reason); closed.countDown()
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
                        })
                    }
                    request.path?.endsWith("/ack") == true -> json("{}")
                    request.path?.startsWith("/messages/") == true -> json("{\"messages\":[]}")
                    request.path?.startsWith("/statuses/") == true -> json("{\"statuses\":[]}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        override fun close() = server.shutdown()
    }

    private fun model(scenario: ActivityScenario<MainActivity>): ChatViewModel {
        lateinit var result: ChatViewModel
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().single()
            val screen = host.childFragmentManager.fragments.filterIsInstance<ChatScreen>().single()
            result = ViewModelProvider(screen)[ChatViewModel::class.java]
        }
        return result
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun awaitState(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var matched = false
            instrumentation.runOnMainSync { matched = condition() }
            if (matched) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $description")
    }

    private fun withSavedServers(test: (TestServer, TestServer, ServerListStore) -> Unit) {
        val original = preferences.getString("paired_servers_v1", null)
        val userId = AndroidDeviceIdentity.load().userId
        try {
            assertTrue(preferences.edit().remove("paired_servers_v1").commit())
            TestServer("First test server", userId).use { first ->
                TestServer("Second test server", userId).use { second ->
                    val store = ServerListStore({ preferences.getString("paired_servers_v1", null) },
                        { preferences.edit().putString("paired_servers_v1", it).commit() })
                    store.save(first.pairing); store.save(second.pairing)
                    test(first, second, store)
                }
            }
        } finally {
            val editor = preferences.edit()
            if (original == null) editor.remove("paired_servers_v1") else editor.putString("paired_servers_v1", original)
            assertTrue(editor.commit())
        }
    }

    @Test fun serverListAndOfflineHistoryKeepSocketOpenThenSwitchClosesPreviousSession() = withSavedServers { first, second, store ->
        store.setAutoConnect(first.pairing.url)
        val friend = "a".repeat(64)
        val liveFriend = "b".repeat(64)
        val userId = AndroidDeviceIdentity.load().userId
        val saved = Message(friend, userId, "Saved on second server".toByteArray(), Message.MessageType.DATA)
        val outgoing = Message(userId, liveFriend, "Track this receipt".toByteArray(), Message.MessageType.DATA)
        ChatStore(instrumentation.targetContext).use {
            it.addIncoming(second.pairing.fingerprint, saved)
            it.addOutgoing(first.pairing.fingerprint, outgoing)
            it.mark(first.pairing.fingerprint, outgoing.id, Message.DeliveryStatus.SENT)
        }
        val crypto = LibsodiumCryptoEngine(AndroidKeyStoreKeyManager(instrumentation.targetContext, first.pairing.fingerprint))
        val keys = SessionKeys(rx = ByteArray(32) { 3 }, tx = ByteArray(32) { 5 })
        crypto.saveSessionKeys(userId, liveFriend, keys)
        val originalSendTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        fun incoming(text: String, sentAt: Long? = null) {
            val encrypted = crypto.encrypt(MessagePayload.encode(text.toByteArray(), sentAt), Session(state = SessionState.ESTABLISHED,
                sessionKeys = SessionKeys(rx = keys.tx, tx = keys.rx)))
            val frame = JSONObject().put("type", "message").put("message", JSONObject()
                .put("from", liveFriend).put("id", java.util.UUID.randomUUID().toString().replace("-", ""))
                .put("ciphertext", (byteArrayOf(Message.MessageType.DATA.ordinal.toByte()) + encrypted)
                    .joinToString("") { "%02x".format(it) }))
            assertTrue(first.socket!!.send(frame.toString()))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("startup connection") { model.connectionStatus == ConnectionStatus.CONNECTED }
            onView(withText("Servers")).perform(click())
            onView(withText("Saved servers")).check(matches(isDisplayed()))
            awaitState("server WebSocket") { first.socket != null }
            incoming("Arrived while browsing servers", originalSendTime)
            awaitState("unread on server list") { model.unreadFor(first.pairing) == 1 }
            instrumentation.runOnMainSync {
                assertTrue(model.connected)
                assertEquals(ConnectionStatus.CONNECTED, model.statusFor(first.pairing))
                assertEquals(ConnectionStatus.DISCONNECTED, model.statusFor(second.pairing))
                model.viewSaved(second.pairing)
            }
            awaitState("second server saved history") { model.conversations[friend]?.singleOrNull()?.id == saved.id }
            assertTrue(first.socket!!.send(JSONObject().put("type", "receipt")
                .put("id", outgoing.id).put("state", "received").toString()))
            incoming("Arrived while browsing another server's history")
            awaitState("unread stays assigned to connected server") { model.unreadFor(first.pairing) == 2 }
            instrumentation.runOnMainSync {
                assertTrue(model.connected)
                assertTrue(model.offlineView)
                assertFalse(model.viewingConnectedServer)
                assertEquals(first.pairing.url, model.activeServer?.url)
                model.openChat(friend)
                model.send("Must not send through the first server")
                assertEquals(1, model.conversations[friend]?.size)
                assertFalse(model.conversations.containsKey(liveFriend))
                model.showServers()
            }
            onView(allOf(withText("Chats"), withParent(withParent(hasDescendant(withText(first.pairing.name))))))
                .perform(scrollTo(), click())
            instrumentation.runOnMainSync {
                assertTrue(model.viewingConnectedServer)
                assertFalse(model.conversations.containsKey(friend))
                assertEquals(3, model.conversations[liveFriend]?.size)
                assertEquals(Message.DeliveryStatus.RECEIVED, model.conversations[liveFriend]?.first()?.status)
                val delayed = model.conversations[liveFriend]!!.single { String(it.content) == "Arrived while browsing servers" }
                assertEquals("Use sender time despite delayed delivery", originalSendTime, delayed.sentAt)
                val legacy = model.conversations[liveFriend]!!.single { String(it.content) == "Arrived while browsing another server's history" }
                assertNull("Legacy incoming messages have no send time", legacy.sentAt)
            }
            // Android Back from Chats also opens Servers without a dialog or disconnect.
            pressBack()
            onView(withText("Saved servers")).check(matches(isDisplayed()))
            assertEquals(1L, first.closed.count)
            assertEquals(1, first.socketCount.get())
            onView(withText("Connect")).perform(scrollTo(), click())
            instrumentation.runOnMainSync {
                assertEquals(ConnectionStatus.DISCONNECTED, model.statusFor(first.pairing))
                assertEquals(second.pairing.url, model.activeServer?.url)
            }
            awaitState("second server connection") { model.connectionStatus == ConnectionStatus.CONNECTED }
            assertTrue("Old socket must close", first.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, first.socketCount.get())
            instrumentation.runOnMainSync {
                assertEquals(first.pairing.url, model.autoConnectUrl) // Manual switching does not change startup choice.
                model.showServers()
            }
            scenario.recreate()
            instrumentation.runOnMainSync {
                assertTrue(model.serversVisible)
                assertEquals(ConnectionStatus.CONNECTED, model.statusFor(second.pairing))
            }
            assertEquals(1, second.socketCount.get())
            onView(withText("Disconnect")).perform(scrollTo(), click())
            awaitState("explicit disconnection") { !model.connected && model.activeServer == null }
            // Reopening after disconnect reloads the store, including a final persisted message.
            val lastMessage = Message(liveFriend, userId, "Saved as connection closed".toByteArray(), Message.MessageType.DATA)
            ChatStore(instrumentation.targetContext).use { it.addIncoming(second.pairing.fingerprint, lastMessage) }
            instrumentation.runOnMainSync { model.viewSaved(second.pairing) }
            awaitState("fresh history after disconnect") { model.conversations[liveFriend]?.lastOrNull()?.id == lastMessage.id }
        }
    }

    @Test fun startupFailureKeepsOfflineChatsAndUncheckingPreventsNextStartupConnection() = withSavedServers { first, _, store ->
        first.healthy = false
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("failed startup connection") { !model.connecting && model.error != null }
            instrumentation.runOnMainSync {
                assertFalse(model.serversVisible)
                assertEquals(first.pairing.url, model.viewedServer?.url)
                assertTrue(model.offlineView)
            }
            onView(withText("Servers")).perform(click())
            onView(withText("Saved servers")).check(matches(isDisplayed()))
            onView(allOf(withText("Connect on app startup"), withParent(hasDescendant(withText(first.pairing.name)))))
                .perform(scrollTo(), click())
            instrumentation.runOnMainSync {
                assertEquals(ConnectionStatus.DISCONNECTED, model.statusFor(first.pairing))
                assertNull(model.autoConnectUrl)
            }
        }
        val previousRequests = first.server.requestCount
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            instrumentation.runOnMainSync {
                assertTrue(model.serversVisible)
                assertFalse(model.connecting)
                assertNull(model.activeServer)
            }
            assertEquals(previousRequests, first.server.requestCount)
        }
    }

    @Test fun checkboxSelectionIsExclusiveAndSwitchingDuringSetupIgnoresStaleResults() = withSavedServers { first, second, _ ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            fun toggle(server: PairedServer) {
                onView(allOf(withText("Connect on app startup"), withParent(hasDescendant(withText(server.name)))))
                    .perform(scrollTo(), click())
            }
            toggle(first.pairing); toggle(second.pairing)
            instrumentation.runOnMainSync { assertEquals(second.pairing.url, model.autoConnectUrl) }
            onView(allOf(withText("Connect on app startup"), withParent(hasDescendant(withText(first.pairing.name)))))
                .check(matches(isNotChecked()))
            toggle(second.pairing)
            first.holdHealth = CountDownLatch(1)
            instrumentation.runOnMainSync { model.connect(first.pairing) }
            instrumentation.runOnMainSync {
                assertFalse(model.serversVisible)
                assertEquals(first.pairing.url, model.viewedServer?.url)
            }
            assertTrue(first.healthEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                model.connect(second.pairing)
                assertEquals(ConnectionStatus.DISCONNECTED, model.statusFor(first.pairing))
                assertEquals(ConnectionStatus.CONNECTING, model.statusFor(second.pairing))
                assertFalse(model.serversVisible)
                assertEquals(second.pairing.url, model.viewedServer?.url)
            }
            first.holdHealth!!.countDown()
            awaitState("replacement connection after pending setup") { model.connectionStatus == ConnectionStatus.CONNECTED }
            instrumentation.runOnMainSync {
                assertEquals(second.pairing.url, model.activeServer?.url)
                assertNull(model.error)
                assertNull(model.autoConnectUrl)
            }
            assertEquals(0, first.socketCount.get())
            assertEquals(1, second.socketCount.get())
        }
    }

    @Test fun contactNamesSurviveNewActivityAndStayScopedToServer() = withSavedServers { first, second, _ ->
        val friend = "c".repeat(64)
        val named = "First friend (${friend.take(8)})"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val state = model(scenario)
                instrumentation.runOnMainSync {
                    state.viewSaved(first.pairing)
                    state.openChat(friend)
                    assertTrue(state.setContactName(friend, " First friend "))
                    assertEquals(named, state.contactLabel(friend))
                    state.viewSaved(second.pairing)
                    state.openChat(friend)
                    assertNull(state.contactName(friend))
                    assertEquals(friend.take(8), state.contactLabel(friend))
                    assertTrue(state.setContactName(friend, "Second friend"))
                    assertEquals("Second friend (${friend.take(8)})", state.contactLabel(friend))
                    assertTrue(state.setContactName(friend, "  "))
                    assertEquals("", state.contactName(friend))
                    assertEquals(friend.take(8), state.contactLabel(friend))
                }
            }
            // A fresh ViewModel must load the saved name and the explicit unnamed choice.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val state = model(scenario)
                instrumentation.runOnMainSync { state.viewSaved(first.pairing) }
                awaitState("named contact after reopening") { state.conversations.containsKey(friend) }
                onView(withText(named)).perform(click())
                onView(withText("Name chat")).check(doesNotExist())
                onView(withText(named)).check(matches(isDisplayed()))
                instrumentation.runOnMainSync { state.viewSaved(second.pairing) }
                awaitState("unnamed contact after reopening") { state.conversations.containsKey(friend) }
                onView(withText(friend.take(8))).perform(click())
                onView(withText("Name chat")).check(doesNotExist())
                onView(withText(friend.take(8))).check(matches(isDisplayed()))
            }
        } finally {
            assertTrue(preferences.edit()
                .remove("contact_name_${first.pairing.fingerprint}_$friend")
                .remove("contact_name_${second.pairing.fingerprint}_$friend")
                .remove("contacts_${first.pairing.fingerprint}")
                .remove("contacts_${second.pairing.fingerprint}").commit())
        }
    }

    @Test fun autoconnectOpensSavedChatsBeforeHealthAndCompletionKeepsServersVisible() = withSavedServers { first, _, store ->
        val friend = "e".repeat(64)
        val saved = Message(friend, AndroidDeviceIdentity.load().userId, "Read while connecting".toByteArray(), Message.MessageType.DATA)
        ChatStore(instrumentation.targetContext).use { it.addIncoming(first.pairing.fingerprint, saved) }
        first.holdHealth = CountDownLatch(1)
        store.setAutoConnect(first.pairing.url)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                assertTrue(first.healthEntered.await(5, TimeUnit.SECONDS))
                awaitState("history before connection completes") { model.conversations[friend]?.singleOrNull()?.id == saved.id }
                instrumentation.runOnMainSync {
                    assertTrue(model.connecting)
                    assertFalse(model.serversVisible)
                    assertEquals(first.pairing.url, model.viewedServer?.url)
                    model.openChat(friend)
                }
                onView(withText("Read while connecting")).check(matches(isDisplayed()))
                onView(withText("Send")).check(matches(isNotEnabled()))
                onView(isAssignableFrom(TextInputEditText::class.java)).perform(replaceText("Draft before connection"))
                onView(withText("All chats")).perform(click())
                onView(withText("Servers")).perform(click())
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView)
                    val cancel = views.filterIsInstance<TextView>().single { it.text.toString() == "Cancel" }
                    val chats = descendants(cancel.parent as View).filterIsInstance<TextView>().single { it.text.toString() == "Chats" }
                    assertTrue("Connection action stays left of Chats", cancel.left < chats.left)
                }
                first.holdHealth!!.countDown()
                awaitState("startup background connection") { model.connectionStatus == ConnectionStatus.CONNECTED }
                onView(withText("Saved servers")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val disconnect = descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Disconnect" }
                    val chats = descendants(disconnect.parent as View).filterIsInstance<TextView>().single { it.text.toString() == "Chats" }
                    assertTrue("Disconnect stays left of Chats", disconnect.left < chats.left)
                }
                instrumentation.runOnMainSync {
                    model.viewSaved(first.pairing)
                    model.openChat(friend)
                    assertEquals("Draft before connection", model.drafts[friend])
                }
                onView(withText("Send")).check(matches(isEnabled()))
            }
        } finally { first.holdHealth!!.countDown() }
    }

    @Test fun receiptsKeepScrollComposerAndSelectionAndEnterSendsOnce() = withSavedServers { first, _, store ->
        val userId = AndroidDeviceIdentity.load().userId
        val friend = "f".repeat(64)
        val crypto = LibsodiumCryptoEngine(AndroidKeyStoreKeyManager(instrumentation.targetContext, first.pairing.fingerprint))
        val keys = SessionKeys(rx = ByteArray(32) { 7 }, tx = ByteArray(32) { 9 })
        crypto.saveSessionKeys(userId, friend, keys)
        val messages = (0 until 60).map { index ->
            Message(userId, friend, (if (index == 59) "x" else "Saved message $index").toByteArray(), Message.MessageType.DATA,
                sentAt = 1_700_000_000_123L + index * 60_000)
        }
        ChatStore(instrumentation.targetContext).use { history ->
            messages.forEach { history.addOutgoing(first.pairing.fingerprint, it); history.mark(first.pairing.fingerprint, it.id, Message.DeliveryStatus.SENT) }
        }
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("connected long history") { model.connectionStatus == ConnectionStatus.CONNECTED && first.socket != null }
            instrumentation.runOnMainSync { model.openChat(friend) }
            instrumentation.waitForIdleSync()
            lateinit var input: TextInputEditText
            lateinit var scroll: ScrollView
            lateinit var timestamp: TextView
            val originalPosition = IntArray(2)
            var originalTime = ""
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                input = views.filterIsInstance<TextInputEditText>().single()
                scroll = views.filterIsInstance<ScrollView>().single()
                assertTrue("History must scroll", scroll.getChildAt(0).height > scroll.height)
                input.requestFocus()
                input.setText("Preserve this selection")
                input.setSelection(2, 9)
                scroll.scrollTo(0, 0)
                val shortBody = views.filterIsInstance<TextView>().single { it.text.toString() == "x" }
                val footer = (shortBody.parent as ViewGroup).getChildAt(1) as ViewGroup
                timestamp = footer.getChildAt(footer.childCount - 1) as TextView
                timestamp.getLocationOnScreen(originalPosition)
                originalTime = timestamp.text.toString()
                assertEquals(android.text.format.DateFormat.getTimeFormat(activity).format(java.util.Date(messages.last().sentAt!!)), originalTime)
            }
            assertTrue(first.socket!!.send(JSONObject().put("type", "receipt").put("id", messages.last().id).put("state", "received").toString()))
            awaitState("receipt applied") { model.conversations[friend]?.lastOrNull()?.status == Message.DeliveryStatus.RECEIVED }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                assertSame("Composer is retained", input, views.filterIsInstance<TextInputEditText>().single())
                assertTrue(input.hasFocus())
                assertEquals(2, input.selectionStart)
                assertEquals(9, input.selectionEnd)
                assertEquals("Receipt must not jump to bottom", 0, scroll.scrollY)
                val status = views.filterIsInstance<TextView>().single { it.text.toString() == "Received" }
                assertEquals(Gravity.END, status.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                assertEquals("Short bubble fits its status", 1, status.lineCount)
                assertTrue(status.layout.getLineWidth(0) <= status.width - status.paddingLeft - status.paddingRight)
                assertTrue(status.right <= (status.parent as View).width)
                val statusPosition = IntArray(2)
                val scrollPosition = IntArray(2)
                status.getLocationOnScreen(statusPosition)
                scroll.getLocationOnScreen(scrollPosition)
                assertTrue("Status stays inside the screen", statusPosition[0] + status.width <= scrollPosition[0] + scroll.width)
                val newPosition = IntArray(2)
                timestamp.getLocationOnScreen(newPosition)
                assertEquals(originalTime, timestamp.text.toString())
                assertArrayEquals("Receipt must not move timestamp", originalPosition, newPosition)
                assertTrue("Time stays nearer the screen edge than status", statusPosition[0] + status.width < newPosition[0])
            }
            // The soft-keyboard Send action and a hardware Enter both use the same send path.
            onView(isAssignableFrom(TextInputEditText::class.java))
                .perform(replaceText("IME message"), pressImeActionButton(), closeSoftKeyboard())
            awaitState("one IME message") { model.conversations[friend]?.count { String(it.content) == "IME message" } == 1 }
            awaitState("encrypted timestamp sent over HTTPS") { first.sentFrame != null }
            val frame = first.sentFrame!!
            val wire = frame.getString("ciphertext").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertEquals(Message.MessageType.DATA.ordinal.toByte(), wire[0])
            val decoded = MessagePayload.decode(crypto.decrypt(wire.copyOfRange(1, wire.size),
                Session(state = SessionState.ESTABLISHED, sessionKeys = SessionKeys(rx = keys.tx, tx = keys.rx))))
            assertEquals("IME message", String(decoded.content))
            instrumentation.runOnMainSync {
                val sent = model.conversations[friend]!!.single { it.id == frame.getString("id") }
                assertNotNull(sent.sentAt)
                assertEquals("Sender's exact original time is encrypted", sent.sentAt, decoded.sentAt)
            }
            onView(isAssignableFrom(TextInputEditText::class.java)).perform(replaceText("Hardware message"))
            instrumentation.runOnMainSync {
                input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            awaitState("one hardware message") { model.conversations[friend]?.count { String(it.content) == "Hardware message" } == 1 }
            instrumentation.runOnMainSync {
                assertEquals(62, model.conversations[friend]?.size)
                assertTrue(input.text.isNullOrEmpty())
                input.setText("Line one")
                input.setSelection(input.text!!.length)
                input.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON))
                input.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON))
                assertEquals("Line one\n", input.text.toString())
                assertEquals("Shift+Enter must not send", 62, model.conversations[friend]?.size)
            }
        }
    }
}
