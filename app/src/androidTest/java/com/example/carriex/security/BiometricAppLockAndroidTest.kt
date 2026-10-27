package com.example.carriex.security

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.R
import com.example.carriex.app.MainActivity
import com.example.carriex.ui.chat.SetupProgress
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in hardware tests: run only on an enrolled test emulator with a host finger-event driver. */
@RunWith(AndroidJUnit4::class)
class BiometricAppLockAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Biometric test timed out")
    }
    private fun hardware(finger: String) {
        waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.systemui" }
        instrumentation.sendStatus(1, Bundle().apply { putString("CARRIEX_TEST_FINGER", finger) })
    }
    private fun gate(activity: MainActivity) = activity.window.decorView.findViewWithTag<View>("app_lock_gate")
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes) ?: emptyList() }

    @Test fun fingerprintMustAuthenticateBeforeEnablingAndUnlocksOnEveryReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("driveBiometrics") == "true")
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS, BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG))
        val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
        assumeTrue("Use an unprotected disposable emulator app", prefs.all.isEmpty())
        val chat = context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
        val savedChat = chat.all
        chat.edit().putBoolean("setup_completed", true).putInt("setup_revision", SetupProgress.REVISION)
            .putBoolean("startup_suggestion_seen", true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView)
                    views.filterIsInstance<NavigationView>().single().menu.performIdentifierAction(4, 0)
                    descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .single { it.isShown && it.text.toString() == activity.getString(R.string.lock_title) }
                        .parent.let { it as View }.performClick()
                    descendants(activity.window.decorView).filterIsInstance<SwitchMaterial>()
                        .single { it.text.toString() == activity.getString(R.string.lock_fingerprint) }.performClick()
                    assertFalse(AppLockStore(context).biometric)
                }
                hardware("1")
                waitFor { AppLockStore(context).biometric }
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .single { it.isShown && it.text.toString() == activity.getString(R.string.lock_now) }.performClick()
                }
                hardware("2") // An unregistered fingerprint must leave the app locked.
                Thread.sleep(700)
                scenario.onActivity { assertNotNull(gate(it)) }
                hardware("1")
                var unlocked = false
                waitFor { scenario.onActivity { unlocked = gate(it) == null }; unlocked }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { assertNotNull(gate(it)) }
                hardware("1")
                waitFor { scenario.onActivity { unlocked = gate(it) == null }; unlocked }

                // Fingerprint-only users retain access through Android's device credential.
                scenario.onActivity { it.appLock.lockNow() }
                waitFor {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val target = root?.takeIf { it.packageName?.toString() == "com.android.systemui" }?.let(::nodes)
                        ?.firstOrNull { it.text?.toString()?.contains("PIN", ignoreCase = true) == true && it.isClickable }
                    target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                waitFor {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root != null && root.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") &&
                        nodes(root).any { it.isPassword }
                }
                instrumentation.sendStatus(1, Bundle().apply { putString("CARRIEX_TEST_CREDENTIAL", "enter") })
                waitFor { scenario.onActivity { unlocked = gate(it) == null }; unlocked }

                // With both methods enabled, canceling fingerprint still permits the app password.
                AppLockStore(context).setPassword("combined test password".toCharArray())
                lateinit var usePassword: String
                scenario.onActivity { usePassword = it.getString(R.string.lock_use_password); it.appLock.lockNow() }
                waitFor {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.takeIf { it.packageName?.toString() == "com.android.systemui" }?.let(::nodes)
                        ?.firstOrNull { it.text?.toString() == usePassword && it.isClickable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView).filter { it.isShown }
                    views.filterIsInstance<android.widget.EditText>().single().setText("combined test password")
                    views.filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.lock_unlock_password) }.performClick()
                }
                waitFor { scenario.onActivity { unlocked = gate(it) == null }; unlocked }
                AppLockStore(context).removePassword()

                // Disabling the last method also requires successful authentication.
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<SwitchMaterial>()
                        .single { it.text.toString() == activity.getString(R.string.lock_fingerprint) }.performClick()
                    assertTrue(AppLockStore(context).biometric)
                }
                hardware("1")
                waitFor { !AppLockStore(context).enabled }
            }
        } finally {
            prefs.edit().clear().commit()
            val edit = chat.edit().clear()
            savedChat.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            edit.commit()
            AppLockController.session.lock()
        }
    }
}
