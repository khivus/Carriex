package com.example.carriex.notifications

import java.math.BigDecimal

/** Custom input is never silently rounded to Android's periodic-job minimum. */
object PollInterval {
    const val DEFAULT = 15 * 60_000L
    fun parse(value: String, unitMillis: Long): Long {
        val exact = try { BigDecimal(value.trim().replace(',', '.')).multiply(BigDecimal.valueOf(unitMillis)) }
            catch (_: Exception) { throw IllegalArgumentException("Enter a positive interval in the selected unit.") }
        require(exact >= BigDecimal.valueOf(1000)) { "The interval must be at least one second." }
        return try { exact.setScale(0, java.math.RoundingMode.CEILING).longValueExact() }
            catch (_: ArithmeticException) { throw IllegalArgumentException("This interval is too large to schedule.") }
    }
    fun label(millis: Long): String = when {
        millis % 3_600_000L == 0L -> "${millis / 3_600_000L} hours"
        millis % 60_000L == 0L -> "${millis / 60_000L} minutes"
        else -> "${BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()} seconds"
    }
}
