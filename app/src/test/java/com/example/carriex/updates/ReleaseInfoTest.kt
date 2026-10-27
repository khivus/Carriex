package com.example.carriex.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseInfoTest {
    private fun release(url: String = "https://github.com/khivus/Carriex/releases/download/v1.10/app-release.apk") =
        JSONObject().put("tag_name", "v1.10").put("body", "Changes").put("assets", JSONArray().put(JSONObject()
            .put("name", "app-release.apk").put("size", 1234).put("browser_download_url", url)))

    @Test fun releaseVersionsAreNumericAndOnlyStableNewerVersionsQualify() {
        assertTrue(ReleaseInfo.newer("v1.10", "1.9"))
        assertFalse(ReleaseInfo.newer("1.9", "1.10"))
        assertFalse(ReleaseInfo.newer("1.10.0", "1.10"))
        assertFalse(ReleaseInfo.newer("v1.11-beta", "1.10"))
        assertNull(ReleaseInfo.parse(release().put("draft", true), "khivus/Carriex", listOf("arm64-v8a")))
        assertNull(ReleaseInfo.parse(release().put("prerelease", true), "khivus/Carriex", listOf("arm64-v8a")))
    }

    @Test fun apkMustComeFromTheConfiguredPublicRepositoryAndBeBounded() {
        assertNotNull(ReleaseInfo.parse(release(), "khivus/Carriex", listOf("arm64-v8a")))
        for(url in listOf("http://github.com/khivus/Carriex/releases/download/v1.10/app-release.apk",
            "https://github.com/other/app/releases/download/v1.10/app-release.apk", "https://example.com/app.apk"))
            assertNull(ReleaseInfo.parse(release(url), "khivus/Carriex", listOf("arm64-v8a")))
        val tooBig = release()
        tooBig.getJSONArray("assets").getJSONObject(0).put("size", 300_000_001)
        assertNull(ReleaseInfo.parse(tooBig, "khivus/Carriex", listOf("arm64-v8a")))
    }
}
