package com.example.carriex.servers

import android.content.Context
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
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
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeUp
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
import com.example.carriex.auth.DeviceIdentity
import com.example.carriex.crypto.x3dh.X3dhProtocol
import com.example.carriex.crypto.x3dh.X3dhStateStore
import com.example.carriex.messaging.ChatStore
import com.example.carriex.model.Message
import com.example.carriex.notifications.MessageRequestNotifications
import com.example.carriex.notifications.NotificationPolling
import com.example.carriex.presentation.chat.ChatViewModel
import com.example.carriex.transport.ServerTransport.ConnectionStatus
import com.example.carriex.ui.chat.ChatScreen
import com.example.carriex.ui.chat.BackSwipeDrawerLayout
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
import org.junit.Before
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ServerConnectionAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)

    @Before fun allowMessageRequestNotifications() {
        if(Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Real TLS and WebSocket connections; only the server protocol responses are fixtures. */
    private class TestServer(name: String, private val userId: String) : AutoCloseable {
        val server = MockWebServer()
        val socketCount = AtomicInteger()
        val ackCount = AtomicInteger()
        val closed = CountDownLatch(1)
        val healthEntered = CountDownLatch(1)
        @Volatile var holdHealth: CountDownLatch? = null
        @Volatile var socket: WebSocket? = null
        @Volatile var sentFrame: JSONObject? = null
        val groupEvents = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val groupMessages = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        @Volatile var holdGroupListing: Pair<CountDownLatch, CountDownLatch>? = null
        @Volatile var holdGroupState: Pair<CountDownLatch, CountDownLatch>? = null
        private val certificate = HeldCertificate.Builder().ecdsa256().commonName("Carriex test")
            .addSubjectAlternativeName("127.0.0.1").build()
        val pairing: PairedServer
        @Volatile var healthy = true
        val friend = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair())
        private val peerValues = mutableMapOf<String, String>()
        private val peerProtocol = X3dhProtocol(friend, object : X3dhStateStore {
            override fun get(key: String) = peerValues[key]
            override fun put(key: String, value: String) { peerValues[key] = value }
            override fun remove(key: String) { peerValues.remove(key) }
            override fun keys(prefix: String) = peerValues.keys.filter { it.startsWith(prefix) }.sorted()
        },allowWithoutOneTimeKey={true})
        private val peerBundle = peerProtocol.publication(0, 2_592_000)
        @Volatile private var clientBundle: JSONObject? = null

        private fun claim(bundle: JSONObject) = JSONObject(bundle.toString()).apply {
            val keys = getJSONArray("one_time_prekeys")
            put("one_time_prekey", if (keys.length() > 0) keys.getJSONObject(0) else JSONObject.NULL)
            remove("one_time_prekeys")
        }
        @Synchronized fun encryptFromFriend(text: String, sentAt: Long?): Message = peerProtocol.encrypt(
            Message(friend.userId, userId, text.toByteArray(), Message.MessageType.DATA, sentAt = sentAt)
        ) { claim(clientBundle ?: error("Client has not published keys")) }
        @Synchronized fun decryptFromClient(frame: JSONObject): Message {
            val bytes = frame.getString("ciphertext").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(bytes[0] == Message.MessageType.DATA.ordinal.toByte())
            return peerProtocol.decrypt(Message(userId, friend.userId, bytes.copyOfRange(1, bytes.size),
                Message.MessageType.DATA, id = frame.getString("id")))
        }

        private fun privateGroupHistory(after: Int): JSONObject {
            val ranges = org.json.JSONArray()
            val visible = mutableListOf<JSONObject>()
            var inside = false
            var period: JSONObject? = null
            var terminal: JSONObject? = null
            for(event in groupEvents) {
                val members = event.getJSONArray("members")
                val member = (0 until members.length()).any { members.getString(it) == userId }
                if(member && !inside) {
                    period = JSONObject().put("first_revision", event.getInt("revision")).put("last_revision", JSONObject.NULL)
                    ranges.put(period)
                }
                if(inside && !member) { period!!.put("last_revision", event.getInt("revision")); terminal = event }
                if(member || inside) visible.add(event)
                inside = member
            }
            val invitation = groupEvents.lastOrNull { it.getString("action") == "invite" && it.getString("target") == userId }
            val latest = groupEvents.last()
            if(!inside && latest.getString("action") in setOf("close", "decline", "remove")) terminal = latest
            val batch = visible.filter { it.getInt("revision") > after }.take(100)
            val proofs = batch.filter { it.getString("action") == "join" && it.getString("target") == userId }.map { value ->
                val joined = com.example.carriex.crypto.groups.GroupState.parse(value, verifyChain = false)
                val admin = if(joined.admin == friend.userId) friend else AndroidDeviceIdentity.load()
                com.example.carriex.crypto.groups.GroupAdmission.create(joined, admin)
            }
            return JSONObject().put("protocol", 2).put("events", org.json.JSONArray(batch)).put("periods", ranges)
                .put("invitation", invitation ?: JSONObject.NULL).put("terminal", if(inside) JSONObject.NULL else terminal ?: JSONObject.NULL)
                .put("admissions", org.json.JSONArray(proofs))
        }

        init {
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val url = "https://127.0.0.1:${server.port}"
            pairing = PairedServer.parse(JSONObject().put("type", "carriex-server").put("version", 1)
                .put("name", name).put("url", url)
                .put("certificate", certificate.certificate.encoded.toByteString().base64()).toString())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/groups/state" -> {
                        holdGroupState?.let { (entered, release) -> entered.countDown(); release.await(15, TimeUnit.SECONDS) }
                        groupEvents.add(JSONObject(request.body.readUtf8()))
                        json("{\"status\":\"ok\"}")
                    }
                    request.path == "/groups" -> {
                        holdGroupListing?.let { (entered, release) -> entered.countDown(); release.await(15, TimeUnit.SECONDS) }
                        val latest = groupEvents.lastOrNull()
                        json(JSONObject().put("protocol", 2).put("groups", org.json.JSONArray().apply {
                            latest?.let { put(JSONObject().put("group_id", it.getString("id")).put("revision", it.getInt("revision"))) }
                        }).toString())
                    }
                    request.path?.startsWith("/groups/") == true -> {
                        val latest = groupEvents.last()
                        when {
                            request.path!!.contains("/events") -> json(privateGroupHistory(request.requestUrl?.queryParameter("after")?.toIntOrNull() ?: 0).toString())
                            request.path!!.endsWith("/join-proposal") || request.path!!.endsWith("/decline-proposal") -> {
                                val current = com.example.carriex.crypto.groups.GroupState.parse(latest, verifyChain = false)
                                val action = if(request.path!!.endsWith("/join-proposal")) "join" else "decline"
                                val next = current.next(AndroidDeviceIdentity.load(), action, userId).json
                                next.remove("auth_key"); next.remove("signature")
                                json(JSONObject().put("state", next).toString())
                            }
                            request.path!!.contains("/keys") -> json(JSONObject().put("epoch", latest.getInt("epoch")).put("packets", org.json.JSONArray()).toString())
                            request.path!!.endsWith("/messages") -> {
                                if(request.method == "POST") groupMessages.add(JSONObject(request.body.readUtf8()))
                                json("{\"messages\":[]}")
                            }
                            else -> json("{\"statuses\":[]}")
                        }
                    }
                    request.path == "/health" -> {
                        healthEntered.countDown()
                        holdHealth?.await(15, TimeUnit.SECONDS)
                        if (healthy) json("{\"status\":\"alive\"}") else MockResponse().setResponseCode(503)
                    }
                    request.path == "/auth/challenge" -> json(JSONObject().put("user_id", userId)
                        .put("challenge", "c".repeat(43)).put("audience", url).toString())
                    request.path == "/auth/verify" -> json(JSONObject().put("user_id", userId)
                        .put("token", "t".repeat(43)).put("expires_in", 3600).toString())
                    request.path == "/prekeys/status" -> json("{\"available\":0,\"message_ttl_seconds\":2592000}")
                    request.path == "/prekeys" -> {
                        clientBundle = JSONObject(request.body.readUtf8())
                        json(JSONObject().put("revision",clientBundle!!.getLong("revision"))
                            .put("publication_id",clientBundle!!.getString("publication_id"))
                            .put("signed_prekey_id",clientBundle!!.getJSONObject("signed_prekey").getString("id")).toString())
                    }
                    request.path == "/prekeys/${friend.userId}/claim" -> json(claim(peerBundle).toString())
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
                    request.path?.endsWith("/ack") == true -> {
                        ackCount.incrementAndGet()
                        json("{}")
                    }
                    request.path?.endsWith("/policy") == true -> json("{}")
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
            result = ViewModelProvider(activity)[ChatViewModel::class.java]
        }
        return result
    }

    private fun navigate(title: String) {
        onView(withContentDescription("Open navigation menu")).perform(click())
        onView(withText(title)).perform(click())
    }

    private fun serverOption(server: PairedServer, option: String) {
        if(option == "Connect" || option == "Disconnect") {
            val action = if(option == "Connect") "Connect to" else "Disconnect from"
            onView(withContentDescription("$action ${server.name}")).perform(scrollTo(), click())
        } else {
            onView(withContentDescription("Options for ${server.name}")).perform(scrollTo(), click())
            onView(withText(option)).perform(click())
        }
    }

    private fun captureNavigation(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
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
        val liveFriend = first.friend.userId
        val userId = AndroidDeviceIdentity.load().userId
        val saved = Message(friend, userId, "Saved on second server".toByteArray(), Message.MessageType.DATA)
        val outgoing = Message(userId, liveFriend, "Track this receipt".toByteArray(), Message.MessageType.DATA)
        ChatStore(instrumentation.targetContext).use {
            it.addIncoming(second.pairing.fingerprint, saved)
            it.addOutgoing(first.pairing.fingerprint, outgoing)
            it.mark(first.pairing.fingerprint, outgoing.id, Message.DeliveryStatus.SENT)
        }
        val originalSendTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        fun incoming(text: String, sentAt: Long? = null) {
            val encrypted = first.encryptFromFriend(text, sentAt)
            val frame = JSONObject().put("type", "message").put("message", JSONObject()
                .put("from", liveFriend).put("id", encrypted.id)
                .put("ciphertext", (byteArrayOf(Message.MessageType.DATA.ordinal.toByte()) + encrypted.content)
                    .joinToString("") { "%02x".format(it) }))
            assertTrue(first.socket!!.send(frame.toString()))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("startup connection") { model.connectionStatus == ConnectionStatus.CONNECTED }
            navigate("Servers")
            onView(withText("Carriex - servers")).check(matches(isDisplayed()))
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
            onView(withText(first.pairing.name)).perform(scrollTo(), click())
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
            onView(withText("Carriex - servers")).check(matches(isDisplayed()))
            assertEquals(1L, first.closed.count)
            assertEquals(1, first.socketCount.get())
            serverOption(second.pairing, "Connect")
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
            serverOption(second.pairing, "Disconnect")
            awaitState("explicit disconnection") { !model.connected && model.activeServer == null }
            // Reopening after disconnect reloads the store, including a final persisted message.
            val lastMessage = Message(liveFriend, userId, "Saved as connection closed".toByteArray(), Message.MessageType.DATA)
            ChatStore(instrumentation.targetContext).use { it.addIncoming(second.pairing.fingerprint, lastMessage) }
            instrumentation.runOnMainSync { model.viewSaved(second.pairing) }
            awaitState("fresh history after disconnect") { model.conversations[liveFriend]?.lastOrNull()?.id == lastMessage.id }
        }
    }

    @Test fun chatListUsesLatestSenderTimeAndReordersIncomingChatsAfterReload() = withSavedServers { first, _, store ->
        val userId = AndroidDeviceIdentity.load().userId
        val recent = "a".repeat(64)
        val live = first.friend.userId
        val legacy = "c".repeat(64)
        val empty = "d".repeat(64)
        val now = System.currentTimeMillis()
        val hour = TimeUnit.HOURS.toMillis(1)
        ChatStore(instrumentation.targetContext).use { history ->
            fun save(peer: String, text: String, time: Long?) = history.addIncoming(first.pairing.fingerprint,
                Message(peer, userId, text.toByteArray(), Message.MessageType.DATA, sentAt = time))
            save(live, "Older chat", now - 48 * hour)
            save(recent, "Recent chat preview", now - hour)
            save(recent, "Delayed old preview", now - 72 * hour)
            save(legacy, "Legacy message", null)
        }
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("loaded chat list") { model.connectionStatus == ConnectionStatus.CONNECTED && model.conversations[recent]?.size == 2 }
            instrumentation.runOnMainSync { model.openChat(empty); model.showChats() }
            instrumentation.waitForIdleSync()
            fun assertRows(expected: List<String>, timedPeer: String, time: Long) {
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    val headings = expected.map { peer -> views.single { it.text.toString().startsWith(model.contactLabel(peer)) } }
                    val positions = headings.map { heading -> IntArray(2).also(heading::getLocationOnScreen)[1] }
                    assertEquals("Newest chats first", positions.sorted(), positions)
                    val heading = headings[expected.indexOf(timedPeer)]
                    val row = heading.parent.parent as View
                    val timestamp = descendants(row).filterIsInstance<TextView>().single {
                        it.contentDescription?.startsWith("Last message sent ") == true
                    }
                    val date = java.util.Date(time)
                    val clock = android.text.format.DateFormat.getTimeFormat(activity).format(date)
                    val day = android.text.format.DateFormat.getDateFormat(activity).format(date)
                    assertEquals(if (android.text.format.DateUtils.isToday(time)) clock else "$day, $clock", timestamp.text.toString())
                    listOf(legacy, empty).forEach { peer ->
                        val undatedRow = headings[expected.indexOf(peer)].parent.parent as View
                        assertTrue(descendants(undatedRow).none { it.contentDescription?.startsWith("Last message sent ") == true })
                    }
                }
            }
            assertRows(listOf(recent, live, legacy, empty), recent, now - hour)
            onView(withText("Recent chat preview")).check(matches(isDisplayed()))
            onView(withText("Delayed old preview")).check(doesNotExist())
            fun incoming(text: String, time: Long): Message {
                val encrypted = first.encryptFromFriend(text, time)
                val frame = JSONObject().put("type", "message").put("message", JSONObject()
                    .put("from", live).put("id", encrypted.id).put("ciphertext",
                        (byteArrayOf(Message.MessageType.DATA.ordinal.toByte()) + encrypted.content).joinToString("") { "%02x".format(it) }))
                assertTrue(first.socket!!.send(frame.toString()))
                return encrypted
            }
            val newest = incoming("New live preview", now)
            awaitState("new message moves chat to top") { model.unread[live] == 1 }
            assertRows(listOf(live, recent, legacy, empty), live, now)
            incoming("Late old live message", now - 96 * hour)
            awaitState("late message saved") { model.unread[live] == 2 }
            assertRows(listOf(live, recent, legacy, empty), live, now)
            onView(withText("New live preview")).check(matches(isDisplayed()))
            onView(withText("Late old live message")).check(doesNotExist())
            instrumentation.runOnMainSync { model.disconnect(); model.conversations.clear(); model.viewSaved(first.pairing) }
            awaitState("history reloaded") { model.conversations[live]?.any { it.id == newest.id } == true }
            assertRows(listOf(live, recent, legacy, empty), live, now)
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
            navigate("Servers")
            onView(withText("Carriex - servers")).check(matches(isDisplayed()))
            serverOption(first.pairing, "Connect on app startup")
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
                serverOption(server, "Connect on app startup")
            }
            toggle(first.pairing); toggle(second.pairing)
            instrumentation.runOnMainSync { assertEquals(second.pairing.url, model.autoConnectUrl) }
            instrumentation.runOnMainSync { assertNotEquals(first.pairing.url, model.autoConnectUrl) }
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
                navigate("Chats")
                navigate("Servers")
                onView(withContentDescription("Options for ${first.pairing.name}")).check(matches(isDisplayed()))
                first.holdHealth!!.countDown()
                awaitState("startup background connection") { model.connectionStatus == ConnectionStatus.CONNECTED }
                onView(withText("Carriex - servers")).check(matches(isDisplayed()))
                onView(withContentDescription("Options for ${first.pairing.name}")).check(matches(isDisplayed()))
                instrumentation.runOnMainSync {
                    model.viewSaved(first.pairing)
                    model.openChat(friend)
                    assertEquals("Draft before connection", model.drafts[friend])
                }
                onView(withText("Send")).check(matches(isEnabled()))
            }
        } finally { first.holdHealth!!.countDown() }
    }

    @Test fun visibleServerButtonsQuickConnectAndSwipesKeepNavigationAndEditingSafe() = withSavedServers { first, _, store ->
        store.setAutoConnect(null)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            val open = "View messages on ${first.pairing.name}"
            onView(withContentDescription("Connect to ${first.pairing.name}")).check(matches(isDisplayed()))
            onView(withContentDescription(open)).check(matches(isDisplayed()))
            captureNavigation("server-visible-controls")
            // A swipe starting on a button must cancel its click before opening the drawer.
            onView(withContentDescription(open)).perform(swipeRight())
            onView(withText("Servers")).check(matches(isDisplayed()))
            instrumentation.runOnMainSync { assertTrue(model.serversVisible) }
            assertEquals(0, first.server.requestCount)
            pressBack()
            onView(withContentDescription(open)).perform(click())
            awaitState("offline chats") { !model.serversVisible && model.viewedServer?.url == first.pairing.url }
            assertEquals("Viewing messages must not connect", 0, first.server.requestCount)
            captureNavigation("chats-quick-connect")
            onView(withContentDescription("Connect to viewed server")).perform(click())
            awaitState("connect directly from chats") { model.connectionStatus == ConnectionStatus.CONNECTED }
            onView(withContentDescription("Connect to viewed server")).check(doesNotExist())
            val friend = "f".repeat(64)
            instrumentation.runOnMainSync { model.openChat(friend) }
            onView(isAssignableFrom(TextInputEditText::class.java))
                .perform(replaceText("Keep this draft"), closeSoftKeyboard(), swipeRight())
            instrumentation.runOnMainSync {
                assertEquals("Composer gestures keep editing", friend, model.selectedPeer)
                assertEquals("Keep this draft", model.drafts[friend])
            }
            onView(isAssignableFrom(BackSwipeDrawerLayout::class.java)).perform(swipeLeft(), swipeUp())
            instrumentation.runOnMainSync { assertEquals(friend, model.selectedPeer) }
            onView(isAssignableFrom(BackSwipeDrawerLayout::class.java)).perform(swipeRight())
            awaitState("swipe from message to chats") { model.selectedPeer == null && !model.serversVisible }
            instrumentation.runOnMainSync {
                for(index in 100..120) model.openChat("%064x".format(index))
                model.showChats()
            }
            onView(isAssignableFrom(ScrollView::class.java)).perform(swipeUp())
            scenario.onActivity { activity ->
                assertTrue(descendants(activity.window.decorView).filterIsInstance<ScrollView>().single().scrollY > 0)
                assertFalse("Vertical scrolling stays in chats", model.serversVisible)
            }
            onView(isAssignableFrom(BackSwipeDrawerLayout::class.java)).perform(swipeRight())
            awaitState("swipe from chats to servers") { model.serversVisible }
            onView(withContentDescription("Disconnect from ${first.pairing.name}")).check(matches(isDisplayed()))
            onView(withContentDescription(open)).check(matches(isDisplayed()))
            onView(withContentDescription("Options for ${first.pairing.name}")).perform(click())
            onView(withText("Connect on app startup")).check(matches(isDisplayed()))
            onView(withText("Server details")).check(matches(isDisplayed()))
            onView(withText("Open chats")).check(doesNotExist())
            pressBack()
            onView(withContentDescription("Disconnect from ${first.pairing.name}")).perform(click())
            awaitState("visible disconnect") { !model.connected && model.serversVisible }
        }
    }

    @Test fun backgroundClosesLiveSocketAndReturnPreservesOfflinePageAndDraft() = withSavedServers { first, second, store ->
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("foreground connection") { model.connectionStatus == ConnectionStatus.CONNECTED && first.socket != null }
            val friend = "d".repeat(64)
            instrumentation.runOnMainSync {
                model.viewSaved(second.pairing)
                model.openChat(friend)
                model.drafts[friend] = "Keep my offline draft"
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertTrue("Background closes the message socket", first.closed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertFalse(model.connected)
                assertEquals(second.pairing.url, model.viewedServer?.url)
                assertEquals(friend, model.selectedPeer)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitState("resumed original connection") { model.connectionStatus == ConnectionStatus.CONNECTED && first.socketCount.get() == 2 }
            instrumentation.runOnMainSync {
                assertEquals(first.pairing.url, model.activeServer?.url)
                assertEquals(second.pairing.url, model.viewedServer?.url)
                assertEquals(friend, model.selectedPeer)
                assertEquals("Keep my offline draft", model.drafts[friend])
            }
            // Notification tap must select its server instead of resuming another one.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            com.example.carriex.notifications.NotificationPolling.openIntent(instrumentation.targetContext, second.pairing).send()
            awaitState("notification server") { model.activeServer?.url == second.pairing.url && model.viewedServer?.url == second.pairing.url && model.connected }
            onView(withText("${second.pairing.name} - chats")).check(matches(isDisplayed()))
            com.example.carriex.notifications.NotificationPolling.openIntent(instrumentation.targetContext).send()
            onView(withText("Carriex - notifications")).check(matches(isDisplayed()))
        }
    }

    @Test fun newContactRequestsNotifyWhileOpenAndTapReviewsTheCorrectServer() = withSavedServers { first, second, store ->
        val context = instrumentation.targetContext
        val originalAlerts = preferences.getBoolean("message_request_alerts", true)
        val originalPolling = preferences.getBoolean("notifications_enabled", false)
        val manager = context.getSystemService(NotificationManager::class.java)
        preferences.edit().putBoolean("message_request_alerts", true).putBoolean("notifications_enabled", false).commit()
        store.setAutoConnect(first.pairing.url)
        val other = "b".repeat(64)
        fun alert() = manager.activeNotifications.singleOrNull {
            it.tag == "requests:${first.pairing.fingerprint}" && it.id == MessageRequestNotifications.ID
        }
        fun send(wire: Message) {
            val frame = JSONObject().put("type", "message").put("message", JSONObject()
                .put("from", wire.from).put("id", wire.id).put("ciphertext",
                    (byteArrayOf(Message.MessageType.DATA.ordinal.toByte()) + wire.content).joinToString("") { "%02x".format(it) }))
            assertTrue(first.socket!!.send(frame.toString()))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                awaitState("foreground connection") { model.connectionStatus == ConnectionStatus.CONNECTED && first.socket != null }
                instrumentation.runOnMainSync { model.viewSaved(second.pairing); model.openChat("a".repeat(64)) }
                assertTrue(NotificationPolling.appVisible)
                assertFalse("Request alerts do not require background polling", NotificationPolling.enabled(context))
                val firstMessage = first.encryptFromFriend("Private contact attempt", System.currentTimeMillis())
                send(firstMessage)
                awaitState("contact request notification while app is open") { alert() != null }
                val notification = alert()!!.notification
                assertEquals("Someone is trying to contact you", notification.extras.getString(Notification.EXTRA_TITLE))
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(first.pairing.name))
                assertFalse(notification.extras.toString().contains("Private contact attempt"))
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                if(Build.VERSION.SDK_INT >= 26) assertEquals(NotificationManager.IMPORTANCE_HIGH,
                    manager.getNotificationChannel(MessageRequestNotifications.CHANNEL).importance)
                NotificationPolling.schedule(context)
                assertNotNull("Turning off count checks must not clear request alerts", alert())
                manager.cancel("requests:${first.pairing.fingerprint}", MessageRequestNotifications.ID)
                awaitState("dismissed request alert") { alert() == null }
                send(firstMessage) // Duplicate delivery must not re-alert.
                send(first.encryptFromFriend("Second message from same requester", System.currentTimeMillis()))
                awaitState("three acknowledged deliveries") { first.ackCount.get() >= 3 }
                instrumentation.waitForIdleSync()
                assertNull("An existing pending requester must not repeatedly notify", alert())
                send(firstMessage.copy(from = other, id = java.util.UUID.randomUUID().toString().replace("-", "")))
                awaitState("new requester alerts separately") { alert() != null }
                alert()!!.notification.contentIntent.send()
                awaitState("request notification selects the original server") { model.viewedServer?.url == first.pairing.url }
                onView(withText("Message requests")).check(matches(isDisplayed()))
                awaitState("review clears request notification") { alert() == null }
                onView(withText("${first.friend.userId.take(8)} · 2")).perform(click())
                onView(withText("Accept / retry")).perform(click())
                awaitState("accepted request decrypts both saved messages") { model.conversations[first.friend.userId]?.size == 2 }
                onView(withText("Private contact attempt")).check(matches(isDisplayed()))
                instrumentation.runOnMainSync { model.blockContact(other) }
                val beforeBlocked = first.ackCount.get()
                send(firstMessage.copy(from = other, id = java.util.UUID.randomUUID().toString().replace("-", "")))
                awaitState("blocked attempt acknowledged") { first.ackCount.get() > beforeBlocked }
                instrumentation.waitForIdleSync()
                assertNull("Blocked senders must not notify", alert())
                instrumentation.runOnMainSync { assertTrue(model.messageRequests().isEmpty()) }
                preferences.edit().putBoolean("message_request_alerts", false).commit()
                val beforeMuted = first.ackCount.get()
                send(firstMessage.copy(from = "c".repeat(64), id = java.util.UUID.randomUUID().toString().replace("-", "")))
                awaitState("muted request still saved") { first.ackCount.get() > beforeMuted && model.messageRequests().size == 1 }
                instrumentation.waitForIdleSync()
                assertNull("Request alert preference is respected", alert())
            }
        } finally {
            MessageRequestNotifications.clear(context, first.pairing.fingerprint)
            preferences.edit().putBoolean("message_request_alerts", originalAlerts)
                .putBoolean("notifications_enabled", originalPolling)
                .remove("contacts_${first.pairing.fingerprint}").remove("contacts_${second.pairing.fingerprint}")
                .remove("blocked_${first.pairing.fingerprint}").commit()
            ChatStore(context).use { db ->
                db.discardRequests(first.pairing.fingerprint, other)
                db.discardRequests(first.pairing.fingerprint, "c".repeat(64))
            }
        }
    }

    @Test fun groupMenusCreateInviteRevokeAndCloseWithPermanentAdmin() = withSavedServers { first, _, store ->
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("group-capable connection") { model.connectionStatus == ConnectionStatus.CONNECTED && first.socket != null }
            onView(withContentDescription("New chat")).perform(click())
            onView(withText("New group")).perform(click())
            onView(isAssignableFrom(TextInputEditText::class.java)).perform(replaceText("Practice team"), closeSoftKeyboard())
            onView(withText("Create")).perform(click())
            awaitState("group created and opened") { model.currentGroup?.let { it.state.name == "Practice team" && it.ready } == true }
            onView(withText("Practice team")).check(matches(isDisplayed()))
            onView(withContentDescription("More options")).perform(click())
            onView(withText("Invite person")).perform(click())
            // The real camera backgrounds MainActivity. Hold its return connection
            // so the QR confirmation must survive until the group is ready again.
            val reconnect = CountDownLatch(1)
            first.holdHealth = reconnect
            instrumentation.runOnMainSync { model.onBackground(); model.onForeground() }
            val scanResult = android.content.Intent().putExtra("SCAN_RESULT", com.example.carriex.auth.ContactQr.encode(first.friend.userId))
                .putExtra("SCAN_RESULT_FORMAT", "QR_CODE")
            val monitor = instrumentation.addMonitor(com.example.carriex.ui.chat.PortraitQrCaptureActivity::class.java.name,
                android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, scanResult), true)
            try {
                onView(withText("Scan QR code")).perform(click())
                instrumentation.waitForIdleSync()
                assertEquals(1, monitor.hits)
                onView(withText("Joining group…")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            } finally {
                instrumentation.removeMonitor(monitor)
                first.holdHealth = null; reconnect.countDown()
            }
            onView(withText("Invite to Practice team?")).check(matches(isDisplayed()))
            awaitState("group connection restored after scanning") { model.viewingConnectedServer && model.currentGroup?.ready == true }
            onView(withText("Invite")).perform(click())
            awaitState("invitation published") { model.currentGroup?.state?.invited?.contains(first.friend.userId) == true }
            onView(withContentDescription("More options")).perform(click())
            onView(withText("Group members")).perform(click())
            onView(withText("${first.friend.userId.take(8)} · Invited")).perform(click())
            onView(withText("Rename member")).perform(click())
            onView(isAssignableFrom(TextInputEditText::class.java)).perform(replaceText("Alex"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            onView(withText("Alex (${first.friend.userId.take(8)}) · Invited")).perform(click())
            onView(withText("Remove")).perform(click())
            onView(withText("Remove")).perform(click())
            awaitState("admin revoked invitation") { model.currentGroup?.state?.invited?.isEmpty() == true }
            onView(withContentDescription("More options")).perform(click())
            onView(withText("Transfer admin")).check(doesNotExist())
            onView(withText("Leave group")).check(doesNotExist())
            onView(withText("Delete group")).perform(click())
            onView(withText("Delete")).perform(click())
            awaitState("group closed") { model.currentGroup?.state?.closed == true }
            onView(withText("Send")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            assertEquals(listOf("create", "invite", "remove", "close"), first.groupEvents.map { it.getString("action") })
            onView(withContentDescription("Back to chats")).perform(click())
            onView(withText("Group closed · saved history")).check(matches(isDisplayed()))
        }
    }

    @Test fun groupSendRespondsBeforeSlowSyncAndRepeatedTapsQueueOnlyOnce() = withSavedServers { first, _, store ->
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("connected") { model.viewingConnectedServer && first.socket != null }
            instrumentation.runOnMainSync { model.createGroup("Responsive group") }
            awaitState("ready group") { model.currentGroup?.ready == true }
            val groupId = model.currentGroup!!.state.id
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            first.holdGroupListing = entered to release
            try {
                first.socket!!.send("{\"type\":\"groups\"}")
                assertTrue("Sync is held by the relay", entered.await(10, TimeUnit.SECONDS))
                onView(withHint("Message")).perform(replaceText("Send this once"), closeSoftKeyboard())
                scenario.onActivity { activity ->
                    val input = descendants(activity.window.decorView).filterIsInstance<android.widget.EditText>().single { it.hint == "Message" }
                    val button = descendants(activity.window.decorView).filterIsInstance<com.google.android.material.button.MaterialButton>().single { it.text == "Send" }
                    button.performClick(); button.performClick(); button.performClick()
                    // Also protect the IME/hardware path, which does not consult button state.
                    model.send("Send this once")
                    assertEquals("", input.text.toString())
                    assertTrue(model.groupSending)
                    assertFalse(button.isEnabled)
                    assertEquals("Sending…", button.text.toString())
                    assertEquals(1, model.currentGroup!!.messages.count { String(it.content) == "Send this once" })
                    assertEquals(Message.DeliveryStatus.SENDING, model.currentGroup!!.messages.last().status)
                }
                onView(withText("Send this once")).check(matches(isDisplayed()))
                awaitState("slow sync does not block saving the local queue") { !model.groupSending }
                // Edits made while the old message is being queued must survive its callback.
                onView(withHint("Message")).perform(replaceText("Next draft"), closeSoftKeyboard())
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            } finally { first.holdGroupListing = null; release.countDown() }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitState("one message reaches relay") { first.groupMessages.size == 1 && !model.groupSending &&
                model.currentGroup?.messages?.lastOrNull()?.status == Message.DeliveryStatus.SENT }
            onView(withHint("Message")).check(matches(withText("Next draft")))
            scenario.recreate()
            awaitState("saved group restored") { model.groups.any { it.state.id == groupId && it.ready } }
            assertEquals(1, model.groups.single { it.state.id == groupId }.messages.size)
            assertEquals(1, model.groups.single { it.state.id == groupId }.events.size)
            assertEquals(1, first.groupMessages.size)
        }
    }

    @Test fun groupSendNeverChangesAudienceWhenMembershipChangesDuringSubmission() = withSavedServers { first, _, store ->
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("connected") { model.viewingConnectedServer && first.socket != null }
            instrumentation.runOnMainSync { model.createGroup("Pinned audience") }
            awaitState("ready group") { model.currentGroup?.ready == true }
            val group = model.currentGroup!!.state
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            first.holdGroupListing = entered to release
            try {
                onView(withHint("Message")).perform(replaceText("Only the original members"), closeSoftKeyboard())
                onView(withText("Send")).perform(click())
                assertTrue("Send refresh is delayed after local queueing", entered.await(10, TimeUnit.SECONDS))
                awaitState("local message saved first") { !model.groupSending && model.currentGroup?.messages?.size == 1 }
                val invited = group.next(AndroidDeviceIdentity.load(), "invite", first.friend.userId)
                val joined = invited.next(first.friend, "join", first.friend.userId)
                first.groupEvents.addAll(listOf(invited.json, joined.json))
            } finally { first.holdGroupListing = null; release.countDown() }
            awaitState("uncertain message fails instead of changing audience") {
                model.currentGroup?.let { it.state.epoch == 2 && it.messages.singleOrNull()?.status == Message.DeliveryStatus.FAILED } == true
            }
            assertTrue("The new member was never sent the old message", first.groupMessages.isEmpty())
            assertEquals(3, model.currentGroup!!.events.size)
            onView(withText("Failed")).check(matches(isDisplayed()))
        }
    }

    @Test fun renamedGroupSenderFitsShortMessageAndPersistsAfterReopening() = withSavedServers { first, _, store ->
        val device = AndroidDeviceIdentity.load()
        val created = com.example.carriex.crypto.groups.GroupState.create(first.friend, "Names group")
        val invited = created.next(first.friend, "invite", device.userId)
        val joined = invited.next(device, "join", device.userId)
        first.groupEvents.addAll(listOf(created.json, invited.json, joined.json))
        ChatStore(instrumentation.targetContext).use { db -> db.addIncoming(
            com.example.carriex.messaging.GroupMessagingService.historyScope(first.pairing.fingerprint, created.id),
            Message(first.friend.userId, created.id, "x".toByteArray(), Message.MessageType.DATA)) }
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("group loaded") { model.groups.any { it.state.id == created.id && it.ready } }
            instrumentation.runOnMainSync { model.openGroup(created.id) }
            onView(withContentDescription("More options")).perform(click())
            onView(withText("Group members")).perform(click())
            onView(withText("${first.friend.userId.take(8)} · Admin")).perform(click())
            onView(withText("Rename member")).perform(click())
            val name = "Alexandra Catherine Montgomery from the research team"
            onView(isAssignableFrom(TextInputEditText::class.java)).perform(replaceText(name), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            onView(withText("Done")).perform(click())
            fun checkName() {
                onView(withText("$name (${first.friend.userId.take(8)})")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val sender = descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>()
                        .single { it.text.toString() == "$name (${first.friend.userId.take(8)})" }
                    val layout = sender.layout!!
                    assertEquals(sender.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue("No ellipsis", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                    assertTrue("Sender has enough height", sender.height >= layout.height + sender.compoundPaddingTop + sender.compoundPaddingBottom)
                    assertTrue("Name controls the short bubble width", (sender.parent as View).width > 180 * activity.resources.displayMetrics.density)
                }
            }
            checkName()
            scenario.recreate()
            awaitState("reopened group") { model.currentGroup?.ready == true }
            checkName()
        }
    }

    @Test fun invitationCanBeDeclinedAfterHiddenMembershipChanges() = withSavedServers { first, _, store ->
        val device = AndroidDeviceIdentity.load()
        val third = DeviceIdentity(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())
        var state = com.example.carriex.crypto.groups.GroupState.create(first.friend, "Private invitation")
        first.groupEvents.add(state.json)
        state = state.next(first.friend, "invite", device.userId); first.groupEvents.add(state.json)
        state = state.next(first.friend, "invite", third.userId); first.groupEvents.add(state.json)
        state = state.next(third, "join", third.userId); first.groupEvents.add(state.json)
        state = state.next(third, "leave", third.userId); first.groupEvents.add(state.json)
        store.setAutoConnect(first.pairing.url)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val model = model(scenario)
            awaitState("personal invitation loaded") { model.groups.any { model.userId in it.state.invited } }
            assertTrue(model.groups.single().events.isEmpty())
            onView(withText("Decline")).perform(click())
            awaitState("declined without replaying hidden events") { model.groups.singleOrNull()?.state?.json?.getString("action") == "decline" }
            assertTrue(model.groups.single().events.isEmpty())
            assertNull(model.error)
            assertEquals("decline", first.groupEvents.last().getString("action"))
        }
    }

    @Test fun groupInvitationAlertsWhileOpenAndMemberCanJoinAndLeaveWithoutAdminRights() = withSavedServers { first, _, store ->
        val initial = com.example.carriex.crypto.groups.GroupState.create(first.friend, "Invited team")
        first.groupEvents.add(initial.json)
        first.groupEvents.add(initial.next(first.friend, "invite", AndroidDeviceIdentity.load().userId).json)
        val originalAlerts = preferences.getBoolean("message_request_alerts", true)
        preferences.edit().putBoolean("message_request_alerts", true).commit()
        store.setAutoConnect(first.pairing.url)
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        fun invitation() = manager.activeNotifications.firstOrNull {
            it.id == MessageRequestNotifications.GROUP_INVITE_ID && it.tag == "group-invite:${first.pairing.fingerprint}:${initial.id}"
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                awaitState("group invitation alert while open") { invitation() != null }
                assertTrue(NotificationPolling.appVisible)
                assertEquals("You have a group invitation", invitation()!!.notification.extras.getString(Notification.EXTRA_TITLE))
                invitation()!!.notification.contentIntent.send()
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                first.holdGroupState = entered to release
                try {
                    onView(withText("Join group")).perform(click())
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    onView(withText("Joining group…")).check(matches(isDisplayed()))
                    assertEquals(initial.id, model.selectedGroup)
                } finally { first.holdGroupState = null; release.countDown() }
                awaitState("invitation accepted and new sender keys ready") { model.groups.any { it.state.id == initial.id && it.state.epoch == 2 && it.ready } }
                awaitState("accepted invitation clears alert") { invitation() == null }
                awaitState("joined group opened") { model.currentGroup?.let { it.state.id == initial.id && model.userId in it.state.members } == true }
                assertEquals(listOf("join"), model.currentGroup!!.events.map { it.state.json.getString("action") })
                onView(withText("You joined the group")).check(matches(isDisplayed()))
                // Heads-up dismissal can still intercept screen-coordinate taps.
                // Invoke the real toolbar control while testing its menu actions.
                scenario.onActivity { activity -> descendants(activity.window.decorView)
                    .single { it.contentDescription == "More options" }.performClick() }
                onView(withText("Group members")).check(matches(isDisplayed()))
                onView(withText("Invite person")).check(doesNotExist())
                onView(withText("Delete group")).check(doesNotExist())
                onView(withText("Leave group")).perform(click())
                onView(withText("Leave")).perform(click())
                awaitState("member leaves without moving admin rights") { model.currentGroup?.let {
                    it.state.epoch == 3 && model.userId !in it.state.members && it.state.admin == first.friend.userId
                } == true }
                onView(withText("Send")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                onView(withText("You left the group")).check(matches(isDisplayed()))
            }
        } finally {
            MessageRequestNotifications.clearInvitations(context, first.pairing.fingerprint)
            preferences.edit().putBoolean("message_request_alerts", originalAlerts).commit()
        }
    }

    @Test fun receiptsKeepScrollComposerAndSelectionAndEnterSendsOnce() = withSavedServers { first, _, store ->
        val userId = AndroidDeviceIdentity.load().userId
        val friend = first.friend.userId
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
            val decoded = first.decryptFromClient(frame)
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
