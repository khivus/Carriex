package com.example.carriex.localization

import android.content.Context
import android.content.res.Configuration
import com.example.carriex.R
import java.util.Locale

/**
 * Adapts existing English domain diagnostics at the display boundary. Wire values,
 * saved quarantine reasons and cryptographic code stay language independent.
 * Unknown OS/library/server details remain available under a translated label.
 */
object UiErrors {
    private data class Entry(val id: Int, val pattern: Regex, val nested: Set<Int>, val types: List<String>)
    @Volatile private var entries: List<Entry>? = null

    private fun entries(context: Context): List<Entry> = entries ?: synchronized(this) {
        entries ?: run {
            val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
            errorResources.map { spec ->
                val source = english.getString(spec.id)
                val tokens = Regex("%(\\d+)\\\$([sd])").findAll(source).toList()
                var offset = 0
                val pattern = buildString {
                    for (token in tokens) {
                        append(Regex.escape(source.substring(offset, token.range.first).replace("%%", "%")))
                        append("(.*?)")
                        offset = token.range.last + 1
                    }
                    append(Regex.escape(source.substring(offset).replace("%%", "%")))
                }
                Entry(spec.id, Regex(pattern, RegexOption.DOT_MATCHES_ALL), spec.nested.toSet(), tokens.map { it.groupValues[2] })
            }.sortedByDescending { english.getString(it.id).replace(Regex("%\\d+\\\$[sd]"), "").length }
                .also { entries = it }
        }
    }

    fun text(context: Context, value: String?): String = resolve(context, value.orEmpty(), 0)

    private fun resolve(context: Context, value: String, depth: Int): String {
        if (value.isEmpty() || context.resources.configuration.locales[0].language != "ru") return value
        if (depth < 6) for (entry in entries(context)) {
            val match = entry.pattern.matchEntire(value) ?: continue
            val args = match.groupValues.drop(1).mapIndexed { index, argument ->
                if (entry.types[index] == "d") argument.toIntOrNull() ?: 0
                else if (index in entry.nested) resolve(context, argument, depth + 1) else argument
            }
            return context.getString(entry.id, *args.toTypedArray())
        }
        // Already localized application errors are also passed through this boundary.
        if (value.any { it in '\u0400'..'\u04ff' }) return value
        return context.getString(R.string.error_technical_details, value)
    }
}

internal class ErrorResource(val id: Int, vararg val nested: Int)
