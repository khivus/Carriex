package com.example.carriex.experience

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.R
import com.example.carriex.app.MainActivity
import com.example.carriex.auth.AndroidDeviceIdentity
import com.example.carriex.localization.AppLanguage
import com.example.carriex.messaging.ChatStore
import com.example.carriex.model.Message
import com.example.carriex.presentation.chat.ChatViewModel
import com.example.carriex.servers.PairedServer
import com.example.carriex.servers.ServerListStore
import com.google.android.material.navigation.NavigationView
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppExperienceAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    private fun descendants(view: View): List<View> = listOf(view) + if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun drawerShowsServerChatTreeAndDirectlySelectsCachedChat() {
        val cert = HeldCertificate.Builder().ecdsa256().commonName("Navigation fixture")
            .addSubjectAlternativeName("127.0.0.1").build()
        val server = PairedServer.parse(org.json.JSONObject().put("type", "carriex-server").put("version", 1)
            .put("name", "Navigation server").put("url", "https://127.0.0.1:1")
            .put("certificate", android.util.Base64.encodeToString(cert.certificate.encoded, android.util.Base64.NO_WRAP)).toString())
        val store = ServerListStore({ prefs.getString("paired_servers_v1", null) }, { prefs.edit().putString("paired_servers_v1", it).commit() })
        val originalServers = prefs.getString("paired_servers_v1", null)
        val originalSetup = prefs.getBoolean("setup_completed", false)
        val originalLanguage = AppLanguage.selected(context)
        val peer = "b".repeat(64)
        prefs.edit().putBoolean("setup_completed", true).commit()
        instrumentation.runOnMainSync { AppLanguage.set(context, "en") }
        store.save(server)
        ChatStore(context).use { it.addIncoming(server.fingerprint, Message(peer, AndroidDeviceIdentity.load().userId,
            "drawer fixture".toByteArray(), Message.MessageType.DATA, sentAt = 1234)) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = System.currentTimeMillis() + 8000
                var ready = false
                while(!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity -> ready = ViewModelProvider(activity)[ChatViewModel::class.java].navigationChats(server).isNotEmpty() }
                    if(!ready) Thread.sleep(50)
                }
                assertTrue("Cached chats loaded for drawer", ready)
                scenario.onActivity { activity ->
                    val navigation = descendants(activity.window.decorView).filterIsInstance<NavigationView>().single()
                    val menu = navigation.menu
                    assertEquals(3, menu.getItem(0).itemId)
                    assertEquals(context.getString(R.string.ui_settings), menu.getItem(1).title)
                    assertEquals(context.getString(R.string.ui_servers), menu.getItem(2).title)
                    val chat = (0 until menu.size()).map(menu::getItem).single { it.title.toString().trim().startsWith(peer.take(8)) }
                    assertTrue(menu.performIdentifierAction(chat.itemId, 0))
                    val model = ViewModelProvider(activity)[ChatViewModel::class.java]
                    assertEquals(server.fingerprint, model.viewedServer?.fingerprint)
                    assertEquals(peer, model.selectedPeer)
                    assertTrue(navigation.menu.findItem(chat.itemId).isChecked)
                    model.showServers()
                    val texts = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    for(id in listOf(R.string.ui_scan_server_qr, R.string.ui_import_pairing_text)) {
                        val button = texts.single { it.text.toString() == activity.getString(id) }
                        assertTrue(button.isShown)
                        assertTrue(button.isClickable)
                    }
                }
            }
        } finally {
            ChatStore(context).use { it.removeChat(server.fingerprint, peer) }
            prefs.edit().putString("paired_servers_v1", originalServers).putBoolean("setup_completed", originalSetup)
                .remove("contacts_${server.fingerprint}").commit()
            instrumentation.runOnMainSync { AppLanguage.set(context, originalLanguage) }
        }
    }
}
