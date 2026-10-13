package com.gng.app.update

import com.gng.core.ReleaseNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

/** Reads the latest GitHub release of the repo. No account or token: it's a public API. */
class UpdateChecker(private val apiUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Only download from GitHub's release pages, or from the test server named in [apiUrl] (debug builds). */
    private val trustedPrefixes: List<String> = buildList {
        add("https://github.com/")
        val api = URL(apiUrl)
        if (api.host != "api.github.com") add("${api.protocol}://${api.authority}/")
    }

    /** The newest published release's APK, or null when there is no release or no APK on it. */
    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "gng-android")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            when (val code = connection.responseCode) {
                200 -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                404 -> null // the repo has no releases yet
                else -> throw IllegalStateException("GitHub answered $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String): UpdateInfo? {
        val release = json.decodeFromString<GhRelease>(body)
        if (release.draft || release.prerelease) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("debug", ignoreCase = true) }
            ?: return null
        if (trustedPrefixes.none { apk.url.startsWith(it) }) return null
        return UpdateInfo(
            version = release.tag.removePrefix("v").removePrefix("V"),
            notes = ReleaseNotes.bullets(release.body),
            apkUrl = apk.url,
            sizeBytes = apk.size,
        )
    }
}
