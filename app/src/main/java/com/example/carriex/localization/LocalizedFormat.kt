package com.example.carriex.localization

import android.content.Context
import com.example.carriex.R
import java.text.NumberFormat

object LocalizedFormat {
    fun interval(context: Context, millis: Long): String {
        val (number, unit) = when {
            millis % 3_600_000L == 0L -> millis / 3_600_000.0 to R.plurals.interval_hours
            millis % 60_000L == 0L -> millis / 60_000.0 to R.plurals.interval_minutes
            else -> millis / 1000.0 to R.plurals.interval_seconds
        }
        val formatted = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
            maximumFractionDigits = 3
        }.format(number)
        // CLDR Russian fractions use "other"; integer quantities exercise one/few/many.
        val quantity = if (number % 1.0 == 0.0) number.toLong().let { if(it > Int.MAX_VALUE) (it % 100 + 100).toInt() else it.toInt() } else 0
        return if(number % 1.0 == 0.0) context.resources.getQuantityString(unit, quantity, formatted)
        else context.getString(R.string.interval_fraction, formatted, context.getString(R.string.interval_seconds_unit))
    }
}
