package com.example.carriex.ui.chat

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Opaque bubble colors, readable text, and collision-free local sender assignments. */
object BubbleColors {
    val presets = linkedMapOf("Purple" to "#6750A4", "Blue" to "#1565C0", "Green" to "#2E7D32",
        "Orange" to "#EF6C00", "Rose" to "#C2185B", "Teal" to "#00796B", "Amber" to "#FBC02D",
        "Red" to "#D32F2F", "Indigo" to "#3949AB", "Slate" to "#546E7A")

    fun parse(value: String?): Int? {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        if(!hex.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return hex.toInt(16) or -0x1000000
    }
    fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if(value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    fun foreground(background: Int): Int {
        val light = luminance(background) + 0.05
        return if(light / 0.05 >= 1.05 / light) -0x1000000 else -1
    }

    private fun automatic(index: Int): Int {
        // Widely separated hues with alternating tones make adjacent senders distinct.
        val hue = ((210.0 + index * 137.507764) % 360) / 60
        val light = if(index % 2 == 0) 0.70 else 0.40
        val saturation = 0.60 + (index / 48 % 3) * 0.08
        val chroma = (1 - abs(2 * light - 1)) * saturation
        val x = chroma * (1 - abs(hue % 2 - 1))
        val rgb = when(hue.toInt()) {
            0 -> doubleArrayOf(chroma, x, 0.0)
            1 -> doubleArrayOf(x, chroma, 0.0)
            2 -> doubleArrayOf(0.0, chroma, x)
            3 -> doubleArrayOf(0.0, x, chroma)
            4 -> doubleArrayOf(x, 0.0, chroma)
            else -> doubleArrayOf(chroma, 0.0, x)
        }.map { ((it + light - chroma / 2) * 255).roundToInt().coerceIn(0, 255) }
        return -0x1000000 or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    fun assign(peers: Collection<String>, saved: Map<String, Int>, excluded: Set<Int>): Map<String, Int> {
        val assigned = saved.filterValues { it !in excluded }.toMutableMap()
        val used = (assigned.values + excluded).toMutableSet()
        var candidate = 0
        peers.distinct().sorted().forEach { peer ->
            if(peer !in assigned) {
                var fill: Int
                do { fill = automatic(candidate++) } while(fill in used)
                assigned[peer] = fill; used.add(fill)
            }
        }
        return assigned
    }
}
