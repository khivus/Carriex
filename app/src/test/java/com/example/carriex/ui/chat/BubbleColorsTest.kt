package com.example.carriex.ui.chat

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class BubbleColorsTest {
    @Test fun groupSendersHaveUniqueStableColorsAcrossOrderAndMembershipChanges() {
        val peers = (0 until 32).map { "person-$it" }
        val own = BubbleColors.parse("#6750A4")!!
        val assigned = BubbleColors.assign(peers, emptyMap(), setOf(own))
        assertEquals(32, assigned.values.toSet().size)
        assertFalse(own in assigned.values)
        assertEquals(assigned, BubbleColors.assign(peers.reversed(), emptyMap(), setOf(own)))
        val newcomers = BubbleColors.assign(listOf("newcomer") + peers.drop(1), assigned, setOf(own))
        peers.forEach { assertEquals(assigned[it], newcomers[it]) }
        assertFalse(newcomers["newcomer"] in assigned.values)
        val changedOwn = assigned.getValue(peers.first())
        val recolored = BubbleColors.assign(peers, assigned, setOf(changedOwn))
        assertFalse(changedOwn in recolored.values)
        assertEquals(32, peers.map(recolored::getValue).toSet().size)
    }

    @Test fun customAndAutomaticColorsAlwaysHaveReadableOpaqueText() {
        val automatic = BubbleColors.assign((0 until 1024).map { "person-$it" }, emptyMap(), emptySet())
        assertEquals(1024, automatic.values.toSet().size)
        val colors = automatic.values + BubbleColors.presets.values.map { BubbleColors.parse(it)!! } + listOf(-1, -0x1000000)
        colors.forEach { fill ->
            val ink = BubbleColors.foreground(fill)
            val background = BubbleColors.luminance(fill)
            val foreground = BubbleColors.luminance(ink)
            val contrast = (max(background, foreground) + 0.05) / (min(background, foreground) + 0.05)
            assertEquals(255, fill ushr 24)
            assertTrue("Text contrast for ${BubbleColors.hex(fill)} is $contrast", contrast >= 4.5)
            assertEquals(fill, BubbleColors.parse(BubbleColors.hex(fill)))
        }
        assertEquals(BubbleColors.parse("#12ABef"), BubbleColors.parse("12abEF"))
        listOf("", "#123", "#GG0000", "#12345678", "transparent").forEach { assertNull(BubbleColors.parse(it)) }
    }
}
