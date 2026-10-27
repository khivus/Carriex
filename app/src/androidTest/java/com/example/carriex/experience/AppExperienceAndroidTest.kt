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

    @Test fun updateScreenShowsNotesAndSkipDismissesExactlyOneVersion() {
        val setup = prefs.getBoolean("setup_completed", false)
        val skipped = prefs.getString("skipped_update", null)
        prefs.edit().putBoolean("setup_completed", true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var updates: com.example.carriex.updates.UpdateViewModel
                scenario.onActivity { updates = ViewModelProvider(it)[com.example.carriex.updates.UpdateViewModel::class.java] }
                // UI verification must not depend on GitHub visibility or network latency.
                val client = updates.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(updates) as okhttp3.OkHttpClient
                client.dispatcher.cancelAll()
                val deadline = System.currentTimeMillis() + 5000
                while(updates.checking && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertFalse(updates.checking)
                scenario.onActivity { activity ->
                    updates.javaClass.getDeclaredField("release").apply { isAccessible = true }.set(updates,
                        com.example.carriex.updates.ReleaseInfo("v999.0", "A visible release note", "Carriex-universal.apk",
                            "https://github.com/khivus/Carriex/releases/download/v999.0/Carriex-universal.apk", 100, null))
                    updates.onChanged?.invoke()
                    val texts = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    assertTrue(texts.any { it.isShown && it.text.toString() == "A visible release note" })
                    assertTrue(texts.any { it.isShown && it.text.toString() == activity.getString(R.string.ui_download_update) })
                    texts.single { it.text.toString() == activity.getString(R.string.ui_skip_version) }.performClick()
                    texts.single { it.text.toString() == activity.getString(R.string.ui_dismiss) }.performClick()
                    assertEquals("v999.0", prefs.getString("skipped_update", null))
                    assertFalse(updates.visible)
                }
            }
        } finally { prefs.edit().putBoolean("setup_completed", setup).putString("skipped_update", skipped).commit() }
    }

    @Test fun installerProviderSharesOnlyTheUpdateDirectory() {
        val file = java.io.File(context.cacheDir, "updates/fixture.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            assertEquals("content", uri.scheme)
            try {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", java.io.File(context.filesDir, "private.db"))
                fail("Private files must not be shared by the installer")
            } catch(_: IllegalArgumentException) { }
        } finally { file.delete() }
    }

    @Test fun firstRunShowsOnlyLanguageThenAppearanceAndNotificationEssentials() {
        val saved = prefs.all
        val language = AppLanguage.selected(context)
        prefs.edit().clear().commit()
        instrumentation.runOnMainSync { AppLanguage.set(context, "en") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun text(activity: MainActivity, id: Int) = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .single { it.isShown && it.text.toString() == activity.getString(id) }
                scenario.onActivity { activity ->
                    text(activity, R.string.language)
                    assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.isShown && it.text.toString() == activity.getString(R.string.ui_servers)
                    })
                    text(activity, R.string.ui_continue).performClick()
                    text(activity, R.string.ui_setup_appearance)
                    assertEquals(1, prefs.getInt("setup_step", -1))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    text(activity, R.string.ui_setup_appearance)
                    text(activity, R.string.ui_skip).performClick()
                    text(activity, R.string.ui_setup_notifications)
                    assertTrue(com.example.carriex.notifications.NotificationPolling.enabled(context))
                    assertEquals(900_000L, com.example.carriex.notifications.NotificationPolling.interval(context))
                    val texts = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                    assertTrue(texts.any { it == activity.getString(R.string.ui_battery_enabled) || it == activity.getString(R.string.ui_battery_disabled) })
                    assertTrue(texts.any { it == activity.getString(R.string.ui_notification_enabled) || it == activity.getString(R.string.ui_notification_disabled) })
                    text(activity, R.string.ui_finish_setup).performClick()
                    assertTrue(prefs.getBoolean("setup_completed", false))
                    text(activity, R.string.ui_scan_server_qr)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    text(activity, R.string.ui_scan_server_qr)
                    val nav = descendants(activity.window.decorView).filterIsInstance<NavigationView>().single()
                    nav.menu.performIdentifierAction(4, 0)
                    text(activity, R.string.ui_appearance).parent.let { it as View }.performClick()
                    text(activity, R.string.language)
                    assertEquals(3, descendants(activity.window.decorView).filterIsInstance<android.widget.RadioGroup>().size)
                }
            }
        } finally {
            val edit = prefs.edit().clear()
            saved.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            edit.commit()
            instrumentation.runOnMainSync { AppLanguage.set(context, language) }
        }
    }

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
