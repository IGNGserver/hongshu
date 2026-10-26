package io.hongshu.app

import org.json.JSONObject

internal data class UpdateVersion(val core: List<Long>, val pre: List<String>) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        core.zip(other.core).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
        pre.zip(other.pre).forEach { (a, b) ->
            val cmp = when {
                a.all(Char::isDigit) && b.all(Char::isDigit) -> a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
                a.all(Char::isDigit) -> -1
                b.all(Char::isDigit) -> 1
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        fun parse(text: String): UpdateVersion? {
            val match = Regex("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(text) ?: return null
            val build = text.substringAfter('+', "")
            if (text.contains('+') && build.split('.').any { it.isEmpty() }) return null
            val core = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it[0] == '0') }) return null
            return UpdateVersion(core, pre)
        }
    }
}

internal data class AppUpdateCandidate(
    val version: String, val tag: String, val preview: Boolean, val notes: String,
    val url: String, val size: Long, val sha256: String, val versionCode: Long? = null,
)

internal object AppUpdatePolicy {
    const val REPOSITORY = "IGNGserver/hongshu"
    const val FEED_URL = "https://raw.githubusercontent.com/$REPOSITORY/updates/updates.json"
    const val MAX_FEED_BYTES = 4 * 1024 * 1024
    const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
    const val MANUAL_INTERVAL = 60_000L

    fun select(text: String, current: String, currentCode: Long, preview: Boolean): AppUpdateCandidate? {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FEED_BYTES) { "更新清单过大" }
        val root = JSONObject(text)
        require(root.opt("schemaVersion") == 1 && root.optString("repository") == REPOSITORY) { "更新清单来源或格式异常" }
        java.time.Instant.parse(root.getString("generatedAt"))
        val installed = UpdateVersion.parse(current) ?: error("当前版本格式异常")
        val releases = root.getJSONArray("releases")
        val candidates = mutableListOf<AppUpdateCandidate>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.opt("prerelease") !is Boolean || release.opt("draft") !is Boolean) continue
            val tag = release.optString("tag_name")
            val version = tag.removePrefix("v")
            val parsed = UpdateVersion.parse(version) ?: continue
            val isPreview = release.optBoolean("prerelease") || parsed.pre.isNotEmpty()
            if (release.optBoolean("draft") || release.optString("published_at").isBlank() || parsed <= installed || (!preview && isPreview)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                if (name !in setOf("Hongshu-Android-$version.apk", "hongshu-v$version-android.apk")) continue
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://github.com/$REPOSITORY/releases/download/$tag/") || url.substringAfterLast('/') != name) continue
                val rawSize = asset.opt("size")
                if (rawSize !is Int && rawSize !is Long) continue
                val size = (rawSize as Number).toLong()
                if (size !in 1..200 * 1024 * 1024L) continue
                val digest = asset.optString("digest")
                if (!Regex("sha256:[a-fA-F0-9]{64}").matches(digest) || asset.optString("state") != "uploaded") continue
                val rawCode = asset.opt("versionCode")
                if (asset.has("versionCode") && !asset.isNull("versionCode") && rawCode !is Int && rawCode !is Long) continue
                val code = (rawCode as? Number)?.toLong()
                if (code != null && code !in 1..2_100_000_000L) continue
                if (code != null && code <= currentCode) continue
                candidates += AppUpdateCandidate(version, tag, isPreview, release.optString("body").replace(Regex("(?s)<!--.*?-->"), "").trim(), url,
                    size, digest.substringAfter(':').lowercase(), code)
            }
        }
        return candidates.maxByOrNull { UpdateVersion.parse(it.version)!! }
    }

    fun retryAt(now: Long, failures: Int, retryAfter: String?, reset: String?): Long {
        val exponential = now + (60_000L * (1L shl failures.coerceIn(0, 8))).coerceAtMost(CHECK_INTERVAL)
        val after = retryAfter?.toLongOrNull()?.coerceIn(0, 7 * 24 * 60 * 60)?.let { now + it * 1000 }
            ?: retryAfter?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        val resetAt = reset?.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
        return maxOf(exponential, after ?: 0, resetAt ?: 0)
    }
}
