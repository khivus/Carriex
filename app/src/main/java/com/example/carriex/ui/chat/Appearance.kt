package com.example.carriex.ui.chat

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.example.carriex.R

object Appearance {
    const val OWN_BUBBLE = "appearance_own_bubble"
    const val INCOMING_BUBBLE = "appearance_incoming_bubble"
    const val GROUP_COLORS = "appearance_group_colors"
    fun modes(context: Context) = arrayOf(context.getString(R.string.ui_system_default), context.getString(R.string.ui_white), context.getString(R.string.ui_black))
    fun accents(context: Context) = arrayOf(context.getString(R.string.ui_purple), context.getString(R.string.ui_blue), context.getString(R.string.ui_green), context.getString(R.string.ui_orange), context.getString(R.string.ui_rose))
    private fun prefs(context: Context) = context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    fun mode(context: Context) = prefs(context).getInt("appearance_mode", 0).coerceIn(0, 2)
    fun accent(context: Context) = prefs(context).getInt("appearance_accent", 0).coerceIn(0, 4)
    fun nightMode(context: Context) = when(mode(context)) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
    fun theme(context: Context) = intArrayOf(R.style.Theme_Carriex, R.style.Theme_Carriex_Blue,
        R.style.Theme_Carriex_Green, R.style.Theme_Carriex_Orange, R.style.Theme_Carriex_Rose)[accent(context)]
    fun bubble(context: Context, own: Boolean, default: Int): Int =
        BubbleColors.parse(prefs(context).getString(if(own) OWN_BUBBLE else INCOMING_BUBBLE, null)) ?: default
    fun bubbleLabel(context: Context, own: Boolean): String {
        val color = BubbleColors.parse(prefs(context).getString(if(own) OWN_BUBBLE else INCOMING_BUBBLE, null))
            ?: return if(own) context.getString(R.string.ui_accent_color_default) else context.getString(R.string.ui_theme_surface_default)
        return BubbleColors.presets.entries.firstOrNull { BubbleColors.parse(it.value) == color }?.key?.let { colorName(context, it) } ?: BubbleColors.hex(color)
    }
    fun colorName(context: Context, id: String): String = when(id) {
        "Purple" -> context.getString(R.string.ui_purple)
        "Blue" -> context.getString(R.string.ui_blue)
        "Green" -> context.getString(R.string.ui_green)
        "Orange" -> context.getString(R.string.ui_orange)
        "Rose" -> context.getString(R.string.ui_rose)
        "Teal" -> context.getString(R.string.ui_teal)
        "Amber" -> context.getString(R.string.ui_amber)
        "Red" -> context.getString(R.string.ui_red)
        "Indigo" -> context.getString(R.string.ui_indigo)
        "Slate" -> context.getString(R.string.ui_slate)
        else -> id
    }
    fun groupColorsEnabled(context: Context) = prefs(context).getBoolean(GROUP_COLORS, false)
    fun groupColors(context: Context, server: String, group: String, peers: Collection<String>, ownColor: Int): Map<String, Int> {
        val preferences = prefs(context)
        val prefix = "group_bubble_${server}_${group}_"
        val saved = preferences.all.filterKeys { it.startsWith(prefix) }
            .mapNotNull { (key, value) -> (value as? Int)?.let { key.removePrefix(prefix) to it } }.toMap()
        val assigned = BubbleColors.assign(peers, saved, setOf(ownColor))
        if(assigned != saved) preferences.edit().also { edit ->
            (saved.keys - assigned.keys).forEach { edit.remove(prefix + it) }
            assigned.forEach { (peer, color) -> if(saved[peer] != color) edit.putInt(prefix + peer, color) }
        }.apply()
        return assigned.filterKeys { it in peers }
    }
}
