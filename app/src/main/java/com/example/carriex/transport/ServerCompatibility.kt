package com.example.carriex.transport

import org.json.JSONObject

data class ServerCompatibility(val version: String?, val level: Level, val action: Action) {
    enum class Level { FULL, LIMITED, INCOMPATIBLE }
    enum class Action { NONE, UPDATE_APP, OLDER_APP_OR_HOST, ASK_HOST }
    companion object {
        const val PROTOCOL = 3
        fun parse(health: JSONObject, appCode: Int): ServerCompatibility {
            val version = health.optString("version").takeIf { it.isNotBlank() }
            val info = health.optJSONObject("compatibility") ?: return ServerCompatibility(version, Level.LIMITED, Action.ASK_HOST)
            val min = info.optInt("min_app_version_code", Int.MAX_VALUE)
            val full = info.optInt("recommended_app_version_code", min)
            val max = info.optInt("max_app_version_code", Int.MAX_VALUE)
            return when {
                appCode < min -> ServerCompatibility(version, Level.INCOMPATIBLE, Action.UPDATE_APP)
                appCode > max -> ServerCompatibility(version, Level.INCOMPATIBLE, Action.OLDER_APP_OR_HOST)
                info.optInt("protocol", -1) != PROTOCOL -> ServerCompatibility(version, Level.INCOMPATIBLE, Action.ASK_HOST)
                appCode < full -> ServerCompatibility(version, Level.LIMITED, Action.UPDATE_APP)
                else -> ServerCompatibility(version, Level.FULL, Action.NONE)
            }
        }
    }
}
