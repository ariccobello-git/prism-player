package dev.red74.prismplayer.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Looks on the web for a newer release of the app and for newer versions of the video/audio engine (Media3) it is
 * built on. The app never replaces itself or its libraries: it only reports what it found, so a new release can be
 * built and installed.
 */
object UpdateCheck {
    const val RELEASES_URL = "https://api.github.com/repos/ariccobello-git/prism-player/releases?per_page=30"
    const val MEDIA3_METADATA_URL = "https://repo1.maven.org/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml"
    const val DOWNLOAD_BASE = "https://github.com/ariccobello-git/prism-player/releases/download/"
    const val APK_NAME = "prism-player-debug.apk"

    class Report(
        val appCurrent: String,
        val appLatest: String?,
        val appNewer: Boolean,
        val engineCurrent: String,
        val engineLatest: String?,
        val engineNewer: Boolean,
        /** True when neither source could be reached. */
        val offline: Boolean,
    )

    private val digits = Regex("""\d+""")
    private val unstable = Regex("""(?i)alpha|beta|rc|dev|snapshot""")
    private val versionTag = Regex("""<version>([^<]+)</version>""")

    /** Numeric parts of "v0.1.18-debug" (0, 1, 18) or "1.10.0" (1, 10, 0). Empty for something like a commit id. */
    fun parts(version: String): List<Int> {
        val head = version.trim().substringBefore('-')
        if (!head.firstOrNull().let { it != null && (it.isDigit() || it == 'v' || it == 'V') }) return emptyList()
        return digits.findAll(head).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isStable(version: String): Boolean = !unstable.containsMatchIn(version)

    fun latestReleaseTag(json: String): String? = try {
        (Json.parseToJsonElement(json) as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("tag_name"))?.jsonPrimitive?.content }
            .filter { parts(it).isNotEmpty() }
            .maxWithOrNull { a, b -> compare(a, b) }
    } catch (e: Exception) {
        null
    }

    fun mavenVersions(xml: String): List<String> = versionTag.findAll(xml).map { it.groupValues[1].trim() }.toList()

    fun latestStable(versions: List<String>): String? =
        versions.filter { isStable(it) && parts(it).isNotEmpty() }.maxWithOrNull { a, b -> compare(a, b) }

    fun report(appLabel: String, engineVersion: String, releasesJson: String?, mavenXml: String?): Report {
        val appLatest = releasesJson?.let(::latestReleaseTag)
        val engineLatest = mavenXml?.let { latestStable(mavenVersions(it)) }
        return Report(
            appCurrent = appLabel,
            appLatest = appLatest,
            appNewer = appLatest != null && parts(appLabel).isNotEmpty() && compare(appLatest, appLabel) > 0,
            engineCurrent = engineVersion,
            engineLatest = engineLatest,
            engineNewer = engineLatest != null && compare(engineLatest, engineVersion) > 0,
            offline = appLatest == null && engineLatest == null,
        )
    }

    suspend fun check(appLabel: String, engineVersion: String): Report = withContext(Dispatchers.IO) {
        report(appLabel, engineVersion, fetchText(RELEASES_URL), fetchText(MEDIA3_METADATA_URL))
    }

    private fun fetchText(address: String): String? = try {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Prism/1 (Android TV)")
            connection.setRequestProperty("Accept", "application/json, application/xml, */*")
            if (connection.responseCode in 200..299) {
                connection.inputStream.use { input ->
                    val bytes = input.readNBytesCompat(MAX_BYTES)
                    String(bytes, Charsets.UTF_8)
                }
            } else null
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private const val MAX_BYTES = 2 * 1024 * 1024

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
