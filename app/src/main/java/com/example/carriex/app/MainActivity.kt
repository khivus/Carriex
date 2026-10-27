package com.example.carriex.app

import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.carriex.databinding.ActivityMainBinding
import androidx.lifecycle.ViewModelProvider
import com.example.carriex.presentation.chat.ChatViewModel
import com.example.carriex.notifications.NotificationPolling
import com.example.carriex.notifications.CountPoller
import androidx.navigation.fragment.NavHostFragment
import com.example.carriex.ui.chat.ChatScreen

/**
 * Main entry point for the application.
 * 
 * Hosts chat navigation inside the system bars, cutouts and on-screen keyboard.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.carriex.localization.AppLanguage.restore(this)
        delegate.localNightMode = com.example.carriex.ui.chat.Appearance.nightMode(this)
        setTheme(com.example.carriex.ui.chat.Appearance.theme(this))
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        val lightTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        ViewCompat.requestApplyInsets(binding.root)
        if (savedInstanceState == null) openNotification(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openNotification(intent)
    }

    private fun openNotification(intent: Intent) {
        if(intent.data?.path == "/settings") {
            binding.root.post {
                supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().firstOrNull()
                    ?.childFragmentManager?.fragments?.filterIsInstance<ChatScreen>()?.firstOrNull()?.showNotificationSettings()
            }
            intent.data = null
        }
        val url = intent.getStringExtra(NotificationPolling.EXTRA_SERVER) ?: return
        val model = ViewModelProvider(this)[ChatViewModel::class.java]
        val requests = intent.getBooleanExtra(NotificationPolling.EXTRA_REQUESTS, false)
        model.savedServers.firstOrNull { it.url == url }?.let { server ->
            if(requests) binding.root.post {
                supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().firstOrNull()
                    ?.childFragmentManager?.fragments?.filterIsInstance<ChatScreen>()?.firstOrNull()?.showMessageRequests(server)
            } else model.connect(server)
        }
        intent.removeExtra(NotificationPolling.EXTRA_SERVER)
        intent.removeExtra(NotificationPolling.EXTRA_REQUESTS)
    }

    override fun onStart() {
        super.onStart()
        NotificationPolling.appVisible = true
        CountPoller.cancel()
        NotificationPolling.clearAlerts(this)
        ViewModelProvider(this)[ChatViewModel::class.java].onForeground()
        runCatching { NotificationPolling.schedule(this) }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            NotificationPolling.appVisible = false
            ViewModelProvider(this)[ChatViewModel::class.java].onBackground()
        }
        super.onStop()
    }
}
