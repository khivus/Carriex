package com.example.carriex.auth

import org.json.JSONObject

/** Public device fingerprint only. Read it directly from a friend's phone to compare identities. */
object ContactQr {
    enum class Result { MATCH, MISMATCH, OWN_ID, NEW_CONTACT }

    private fun valid(id: String) = id.matches(Regex("[0-9a-f]{64}"))

    fun encode(userId: String): String {
        require(valid(userId)) { "Device identity is unavailable" }
        return JSONObject().put("type", "carriex-contact").put("version", 1).put("user_id", userId).toString()
    }

    fun parse(text: String): String {
        require(text.length in 1..1024) { "This is not a Carriex user ID QR" }
        val data = try { JSONObject(text) } catch (_: Exception) {
            throw IllegalArgumentException("Scan your friend's My ID QR, not a server pairing QR")
        }
        require(data.optString("type") == "carriex-contact" && data.opt("version") == 1) {
            "Scan your friend's My ID QR, not a server pairing QR"
        }
        val id = data.opt("user_id") as? String ?: throw IllegalArgumentException("Missing user ID")
        require(valid(id)) { "Invalid user ID in QR" }
        return id
    }

    fun compare(scannedId: String, ownId: String, expectedId: String?): Result {
        require(valid(scannedId) && valid(ownId) && (expectedId == null || valid(expectedId)))
        return when {
            scannedId == ownId -> Result.OWN_ID
            expectedId == null -> Result.NEW_CONTACT
            scannedId == expectedId -> Result.MATCH
            else -> Result.MISMATCH
        }
    }
}
