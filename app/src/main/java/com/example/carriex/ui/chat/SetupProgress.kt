package com.example.carriex.ui.chat

import android.content.SharedPreferences

/** This setup revision is intentionally requested once for all installs of this update. */
object SetupProgress {
    const val REVISION = 1
    const val KEY = "setup_revision"
    fun completed(preferences: SharedPreferences) = preferences.getInt(KEY, 0) >= REVISION

    fun prepare(preferences: SharedPreferences) {
        if(!completed(preferences) && preferences.getInt("setup_active_revision", 0) != REVISION) {
            preferences.edit().putInt("setup_active_revision", REVISION).putInt("setup_step", 0)
                .putBoolean("setup_completed", false).commit()
        }
    }

    fun finish(preferences: SharedPreferences) {
        preferences.edit().putInt(KEY, REVISION).putBoolean("setup_completed", true)
            .remove("setup_step").remove("setup_active_revision").commit()
    }
}
