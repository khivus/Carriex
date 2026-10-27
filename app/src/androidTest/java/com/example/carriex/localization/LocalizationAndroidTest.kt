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
    @Test fun switchLanguagesAndKeepCurrentSettingsPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = AppLanguage.selected(context)
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            val directory = java.io.File(context.filesDir, "localization-review").apply { mkdirs() }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try {
                java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        instrumentation.runOnMainSync { AppLanguage.set(context, "en") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withContentDescription("Open navigation menu")).perform(click())
                onView(allOf(withText("Settings"), isDisplayed())).perform(click())
                for ((tag, settings, language, appearance) in listOf(
                    listOf("en", "Settings", "Language", "Appearance"),
                    listOf("ru", "Настройки", "Язык", "Оформление"))) {
                    if(tag == "ru") {
                        onView(withText("Language")).perform(click())
                        onView(withText("Русский")).perform(click())
                    }
                    onView(allOf(withText(settings), isDisplayed())).check(matches(isDisplayed()))
                    onView(withText(language)).check(matches(isDisplayed()))
                    screenshot("$tag-settings")
                    onView(withText(appearance)).perform(click())
                    scenario.recreate()
                    onView(withText(if(tag == "ru") "Тема" else "Theme")).check(matches(isDisplayed()))
                    screenshot("$tag-appearance")
                    pressBack()
                    onView(withText(if(tag == "ru") "Конфиденциальность" else "Privacy")).perform(click())
                    screenshot("$tag-privacy")
                    pressBack()
                    onView(allOf(withText(if(tag == "ru") "Чаты" else "Chats"), isDisplayed())).perform(click())
                    screenshot("$tag-chats")
                    pressBack()
                }
                onView(withText("Язык")).perform(click())
                onView(withText("English")).perform(click())
                onView(withText("Language")).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText("Language")).check(matches(isDisplayed()))
            }
        } finally {
            instrumentation.runOnMainSync { AppLanguage.set(context, original) }
        }
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
