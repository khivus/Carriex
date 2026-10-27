package com.example.carriex.transport

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerCompatibilityTest {
    private fun health(protocol: Int = 3) = JSONObject().put("version", "0.6.0").put("compatibility",
        JSONObject().put("protocol", protocol).put("min_app_version_code", 10)
            .put("recommended_app_version_code", 11).put("max_app_version_code", 11))

    @Test fun independentlyVersionedAppAndServerHaveFullLimitedOrIncompatibleStatus() {
        assertEquals(ServerCompatibility.Level.FULL, ServerCompatibility.parse(health(), 11).level)
        assertEquals(ServerCompatibility.Level.LIMITED, ServerCompatibility.parse(health(), 10).level)
        assertEquals(ServerCompatibility.Action.UPDATE_APP, ServerCompatibility.parse(health(), 9).action)
        assertEquals(ServerCompatibility.Action.OLDER_APP_OR_HOST, ServerCompatibility.parse(health(), 12).action)
        assertEquals(ServerCompatibility.Level.INCOMPATIBLE, ServerCompatibility.parse(health(4), 11).level)
        assertEquals(ServerCompatibility.Level.LIMITED, ServerCompatibility.parse(JSONObject(), 11).level)
    }
}
