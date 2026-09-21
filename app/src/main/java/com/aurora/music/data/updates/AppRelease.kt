package com.aurora.music.data.updates

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

data class AppVersion private constructor(val numbers: List<Long>, val prerelease: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease == other.prerelease -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        prerelease.zip(other.prerelease).forEach { (a, b) ->
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val result = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^[vV]?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
                .matchEntire(value.trim()) ?: return null
            val numbers = (1..3).map { match.groupValues[it].ifEmpty { "0" }.toLongOrNull() ?: return null }
            return AppVersion(numbers, match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList())
        }
    }
}

data class ReleaseApk(val url: String, val size: Long, val sha256: String?)
data class AppRelease(val tag: String, val version: AppVersion, val pageUrl: String, val apk: ReleaseApk?)

object GitHubRelease {
    const val REPOSITORY_URL = "https://github.com/nessli420/aurora"
    const val LATEST_API = "https://api.github.com/repos/nessli420/aurora/releases/latest"
    const val RELEASES_URL = "$REPOSITORY_URL/releases/latest"

    fun parse(json: String): AppRelease {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("draft")?.asBoolean != true && root.get("prerelease")?.asBoolean != true) { "No stable release available." }
        val tag = root.string("tag_name") ?: error("The release has no version.")
        val version = AppVersion.parse(tag) ?: error("The release version is not supported.")
        require(version.prerelease.isEmpty()) { "No stable release available." }
        val page = "$REPOSITORY_URL/releases/tag/$tag"
        val assets = root.getAsJsonArray("assets")?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
            .orEmpty().filter { it.string("name")?.endsWith(".apk", ignoreCase = true) == true }
        val asset = assets.firstOrNull { it.string("name").equals("Aurora.apk", ignoreCase = true) }
            ?: assets.firstOrNull { it.string("name")?.endsWith("universal.apk", ignoreCase = true) == true }
            ?: assets.singleOrNull()
        val apk = asset?.let {
            val url = it.string("browser_download_url") ?: return@let null
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
                uri.path.startsWith("/nessli420/aurora/releases/download/$tag/")) { "The APK is not from Aurora's releases." }
            val size = it.get("size")?.asLong ?: 0
            require(size > 0) { "The release APK is empty." }
            val digest = it.string("digest")
            val sha256 = digest?.removePrefix("sha256:")?.also { hash ->
                require(digest.startsWith("sha256:") && hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid APK checksum." }
            }
            ReleaseApk(url, size, sha256)
        }
        return AppRelease(tag, version, page, apk)
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
}
