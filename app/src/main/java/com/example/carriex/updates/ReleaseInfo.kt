package com.example.carriex.updates

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class ReleaseInfo(val tag: String, val notes: String, val assetName: String, val downloadUrl: String, val size: Long, val sha256: String?) {
    companion object {
        fun version(value: String): List<Int>? = value.removePrefix("v").takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+){1,3}")) }
            ?.split('.')?.map { it.toIntOrNull() ?: return null }

        fun newer(candidate: String, installed: String): Boolean {
            val a = version(candidate) ?: return false
            val b = version(installed) ?: return false
            for(i in 0 until maxOf(a.size, b.size)) {
                val difference = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if(difference != 0) return difference > 0
            }
            return false
        }

        fun parse(json: JSONObject, repository: String, abis: List<String>): ReleaseInfo? {
            if(json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            if(version(tag) == null) return null
            val assets = json.optJSONArray("assets") ?: return null
            val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
                val name = it.optString("name").lowercase()
                val url = it.optString("browser_download_url").toHttpUrlOrNull()
                name.endsWith(".apk") && "debug" !in name && "androidtest" !in name &&
                    url?.isHttps == true && url.host == "github.com" && url.username.isEmpty() && url.password.isEmpty() &&
                    url.encodedPath.startsWith("/$repository/releases/download/") && it.optLong("size") in 1..300_000_000 &&
                    (listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").firstOrNull(name::contains)?.let { abi -> abi in abis } ?: true)
            }.sortedBy { asset ->
                val name = asset.getString("name").lowercase()
                if("universal" in name || listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").none(name::contains)) -1
                else abis.indexOfFirst(name::contains).takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
            val asset = candidates.firstOrNull() ?: return null
            val rawDigest = asset.optString("digest").takeIf { it != "null" && it.isNotBlank() }
            val digest = rawDigest?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            if(rawDigest != null && digest == null) return null
            return ReleaseInfo(tag, json.optString("body").take(100_000), asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"), digest)
        }
    }
}
