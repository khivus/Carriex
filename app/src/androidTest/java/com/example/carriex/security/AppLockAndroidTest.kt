package com.example.carriex.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.R
import com.example.carriex.app.MainActivity
import com.example.carriex.ui.chat.SetupProgress
import com.google.android.material.navigation.NavigationView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private val chatPrefs get() = context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    private lateinit var savedLock: Map<String, *>
    private lateinit var savedChat: Map<String, *>
    private val password = "1234"

    @Before fun prepare() {
        savedLock = prefs.all; savedChat = chatPrefs.all
        prefs.edit().clear().commit()
        chatPrefs.edit().putBoolean("setup_completed", true).putInt("setup_revision", SetupProgress.REVISION)
            .putBoolean("startup_suggestion_seen", true).remove("auto_connect_server").commit()
        AppLockController.session.lock()
    }
    @After fun restore() {
        fun restore(name: String, values: Map<String, *>) {
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            edit.commit()
        }
        restore("app_lock", savedLock); restore("chat_settings", savedChat)
        AppLockController.session.lock()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun visible(activity: MainActivity) = descendants(activity.window.decorView).filter { it.isShown }
    private fun click(activity: MainActivity, id: Int) = visible(activity).filterIsInstance<TextView>()
        .single { it.text.toString() == activity.getString(id) }.performClick()
    private fun gate(activity: MainActivity) = activity.window.decorView.findViewWithTag<View>("app_lock_gate")
    private fun enter(scenario: ActivityScenario<MainActivity>, value: String) {
        scenario.onActivity { activity ->
            visible(activity).filterIsInstance<EditText>().single().setText(value)
            click(activity, R.string.lock_unlock_password)
        }
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            scenario.onActivity { done = condition(it) }
            if (!done) Thread.sleep(50)
        }
        assertTrue("Expected UI state before timeout", done)
    }
    private fun unlock(scenario: ActivityScenario<MainActivity>) {
        enter(scenario, password)
        waitFor(scenario) { gate(it) == null }
    }
    private fun openSettings(activity: MainActivity) {
        descendants(activity.window.decorView).filterIsInstance<NavigationView>().single().menu.performIdentifierAction(4, 0)
        visible(activity).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.lock_title) }
            .parent.let { it as View }.performClick()
    }

    @Test fun passwordCanBeCreatedConfirmedAndRemovedThroughSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openSettings(it); click(it, R.string.lock_add_password) }
            onView(withHint(R.string.lock_new_password)).inRoot(isDialog()).check(matches(hasFocus()))
            onView(withHint(R.string.lock_new_password)).inRoot(isDialog()).perform(replaceText(password))
            onView(withHint(R.string.lock_repeat_password)).inRoot(isDialog()).perform(replaceText("different password"), closeSoftKeyboard())
            onView(withText(R.string.ui_save)).inRoot(isDialog()).perform(click())
            onView(withText(R.string.lock_password_mismatch)).inRoot(isDialog()).check(matches(isDisplayed()))
            assertFalse(AppLockStore(context).enabled)
            onView(withHint(R.string.lock_repeat_password)).inRoot(isDialog()).perform(replaceText(password), closeSoftKeyboard())
            onView(withText(R.string.ui_save)).inRoot(isDialog()).perform(click())
            waitFor(scenario) { AppLockStore(context).hasPassword && visible(it).filterIsInstance<TextView>()
                .any { view -> view.text.toString() == it.getString(R.string.lock_change_password) } }
            scenario.onActivity { click(it, R.string.lock_now) }
            unlock(scenario)
            scenario.onActivity { click(it, R.string.lock_remove_password) }
            unlock(scenario)
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            assertFalse(AppLockStore(context).enabled)
            scenario.onActivity { activity ->
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Test fun passwordEntryIsFocusedAndKeyboardOpensOnLaunchAndEveryReturn() {
        AppLockStore(context).setPassword(password.toCharArray())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun keyboardReady(activity: MainActivity) = visible(activity).filterIsInstance<EditText>().singleOrNull()?.hasFocus() == true &&
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            waitFor(scenario, ::keyboardReady)
            unlock(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(scenario, ::keyboardReady)
            unlock(scenario)
            var unavailableBiometric = false
            scenario.onActivity { activity ->
                unavailableBiometric = !activity.appLock.canEnableBiometric()
                if (unavailableBiometric) {
                    // Existing fingerprint protection must fall back to a focused password
                    // when Android no longer has an enrolled fingerprint.
                    activity.appLock.store.biometric = true
                    activity.appLock.lockNow()
                }
            }
            if (unavailableBiometric) {
                waitFor(scenario, ::keyboardReady)
                unlock(scenario)
            }
        }
    }

    @Test fun encryptedPasswordAndThrottleSurviveReopeningTheStore() {
        val store = AppLockStore(context)
        store.setPassword(password.toCharArray())
        assertTrue(store.verifyPassword(password.toCharArray()))
        assertFalse(store.verifyPassword("incorrect".toCharArray()))
        assertFalse(prefs.getString("password", "")!!.contains(password))
        repeat(5) { store.passwordAttempt(false) }
        assertTrue(AppLockStore(context).retryAfterMillis() > 0)
        store.passwordAttempt(true)
        assertEquals(0L, store.retryAfterMillis())
        prefs.edit().putString("password", "corrupt").commit()
        assertTrue(store.enabled)
        assertTrue(store.hasPassword)
        assertTrue(runCatching { store.verifyPassword(password.toCharArray()) }.isFailure)
    }

    @Test fun launchAndNotificationLinksStayLockedUntilCorrectPasswordAndRotationPreservesUnlock() {
        AppLockStore(context).setPassword(password.toCharArray())
        val intent = Intent(context, MainActivity::class.java).setData(Uri.parse("carriex://app/settings"))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(gate(activity))
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(visible(activity).filterIsInstance<NavigationView>().any())
                assertFalse(visible(activity).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.ui_background_checks) })
            }
            scenario.recreate()
            scenario.onActivity { assertNotNull(gate(it)) }
            enter(scenario, "wrong password")
            waitFor(scenario) { activity -> visible(activity).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.lock_wrong_password) } }
            unlock(scenario)
            scenario.recreate()
            scenario.onActivity { assertNull(gate(it)) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { it.onActivity { activity -> assertNotNull(gate(activity)) } }
    }

    @Test fun everyReturnRelocksButLaunchOnlyKeepsTheForegroundSession() {
        val store = AppLockStore(context)
        store.setPassword(password.toCharArray())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertNotNull(gate(it)) }
            unlock(scenario)
            store.everyReturn = false
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertNull(gate(it)) }
        }
    }

    @Test fun passwordResultCannotUnlockAfterAppHasGoneIntoBackground() {
        AppLockStore(context).setPassword(password.toCharArray())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            enter(scenario, password)
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(1000)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertNotNull(gate(it)) }
        }
    }

    @Test fun settingsChangesRequireReauthenticationAndDialogsCannotRemainAboveTheLock() {
        AppLockStore(context).setPassword(password.toCharArray())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock(scenario)
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<NavigationView>().single().menu.performIdentifierAction(4, 0)
                visible(activity).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.lock_title) }
                    .parent.let { it as View }.performClick()
                click(activity, R.string.lock_change_password)
                assertNotNull(gate(activity))
                click(activity, R.string.ui_cancel)
                assertNull(gate(activity))
                val launch = visible(activity).filterIsInstance<RadioButton>().single { it.text.toString() == activity.getString(R.string.lock_on_launch) }
                launch.performClick()
                assertTrue(AppLockStore(context).everyReturn)
            }
            unlock(scenario)
            assertFalse(AppLockStore(context).everyReturn)
            scenario.recreate() // Restore the app-lock settings route before testing dialog handling.
            scenario.onActivity { activity ->
                click(activity, R.string.lock_change_password)
            }
            unlock(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                // Launch-only keeps the session; the password dialog must still have been dismissed.
                assertNull(gate(activity))
                assertTrue(visible(activity).filterIsInstance<EditText>().isEmpty())
                click(activity, R.string.lock_now)
                assertNotNull(gate(activity))
            }
        }
    }
}
