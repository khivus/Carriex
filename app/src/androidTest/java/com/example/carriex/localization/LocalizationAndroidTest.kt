package com.example.carriex.localization

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.allOf
import com.example.carriex.app.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carriex.R
import com.example.carriex.notifications.PollInterval
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationAndroidTest {
    @org.junit.Before fun finishSetupForExistingUserFixtures() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("setup_completed", true).commit()
    }
    private fun setTestLanguage(tag: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            // A fixture may run before any AppCompat activity exists.
            if(android.os.Build.VERSION.SDK_INT >= 33)
                context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(tag)
            else AppLanguage.set(context, tag)
        }
    }

    @Test fun switchLanguagesAndKeepCurrentSettingsPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = AppLanguage.selected(context)
        setTestLanguage("en")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withContentDescription("Open navigation menu")).perform(click())
                onView(allOf(withText("Settings"), isDisplayed())).perform(click())
                onView(withText("Appearance")).perform(click())
                onView(withText(context.getString(R.string.language_ru))).perform(click())
                instrumentation.waitForIdleSync()
                val ru = localized("ru")
                onView(withText(ru.getString(R.string.language))).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText(ru.getString(R.string.language))).check(matches(isDisplayed()))
                onView(withText(context.getString(R.string.language_en))).perform(click())
                instrumentation.waitForIdleSync()
                onView(withText("Language")).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText("Language")).check(matches(isDisplayed()))
                pressBack()
                onView(withText("Privacy")).perform(click())
                onView(withText("Send message read status")).check(matches(isDisplayed()))
            }
        } finally { setTestLanguage(original) }
    }

    private fun localized(tag: String): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }

    @Test fun resourcesResolveAndFormatInBothLanguages() {
        for (tag in listOf("en", "ru")) {
            val context = localized(tag)
            assertEquals(tag, context.resources.configuration.locales[0].language)
            for (spec in errorResources) {
                val raw = context.getString(spec.id)
                val tokens = Regex("%(\\d+)\\\$([sd])").findAll(raw).toList()
                val max = tokens.maxOfOrNull { it.groupValues[1].toInt() } ?: 0
                val formatted = context.getString(spec.id, *Array<Any>(max) { index ->
                    if(tokens.first { it.groupValues[1].toInt() == index + 1 }.groupValues[2] == "d") 123 else "TEST_$index"
                })
                assertFalse("Unexpanded placeholder in $tag/${context.resources.getResourceEntryName(spec.id)}", Regex("%\\d+\\\$[sd]").containsMatchIn(formatted))
            }
            assertTrue(errorResources.size > 500)
        }
    }

    @Test fun russianPluralsAndDecimalInput() {
        val ru = localized("ru")
        for ((number, suffix) in listOf(1 to "секунда", 2 to "секунды", 5 to "секунд", 11 to "секунд", 21 to "секунда", 22 to "секунды", 25 to "секунд", 101 to "секунда")) {
            assertEquals("$number $suffix", LocalizedFormat.interval(ru, number * 1000L))
        }
        assertEquals("1,5 секунды", LocalizedFormat.interval(ru, 1500))
        assertEquals("1 hour", LocalizedFormat.interval(localized("en"), 3_600_000))
        assertEquals(1500L, PollInterval.parse("1,5", 1000))
        for (number in listOf(0, 1, 2, 5, 11, 21, 22, 25, 101)) {
            val text = ru.resources.getQuantityString(R.plurals.pending_request_notification, number, number, "Blue")
            assertTrue(text.contains("Blue"))
            assertFalse(text.contains("pending"))
        }
    }

    @Test fun errorsTranslateNestedDiagnosticsAndPreserveUserLabels() {
        val ru = localized("ru")
        val original = "Could not send to Blue: Decryption failed"
        assertEquals("Не удалось отправить сообщение Blue: Не удалось расшифровать сообщение", UiErrors.text(ru, original))
        assertEquals(original, UiErrors.text(localized("en"), original))
        assertEquals("Не удалось выполнить действие. Технические сведения: ECONNRESET", UiErrors.text(ru, "ECONNRESET"))
        assertEquals("Ошибка", UiErrors.text(ru, "Ошибка"))
    }
}
