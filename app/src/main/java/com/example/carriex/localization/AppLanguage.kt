package com.example.carriex.localization

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** AppCompat owns activity recreation; background components use the same language. */
object AppLanguage {
    private const val KEY = "app_language"
    val tags = arrayOf("", "en", "ru")

    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
    } else context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun set(context: Context, tag: String) {
        require(tag in tags)
        context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun restore(context: Context) {
        if (Build.VERSION.SDK_INT < 33) {
            val locales = LocaleListCompat.forLanguageTags(selected(context))
            if (AppCompatDelegate.getApplicationLocales() != locales) AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    fun context(context: Context): Context {
        val tag = selected(context)
        // The empty choice follows the OS; never reuse a previously wrapped locale.
        val config = Configuration(context.resources.configuration)
        if (tag.isNotEmpty()) config.setLocale(Locale.forLanguageTag(tag))
        else if (Build.VERSION.SDK_INT >= 33) config.setLocales(context.getSystemService(LocaleManager::class.java).systemLocales)
        else config.setLocales(android.content.res.Resources.getSystem().configuration.locales)
        return context.createConfigurationContext(config)
    }
}
