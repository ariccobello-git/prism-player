package dev.red74.prismplayer.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Looks on the web for a newer release of the app and for newer versions of the video/audio engine (Media3) it is
 * built on. Only GitHub and Google's Maven are contacted, never the provider of the lists. Installing a newer
 * release is [AppUpdater]'s job, and only when the user says yes.
 */
object UpdateCheck {
    const val RELEASES_URL = "https://api.github.com/repos/ariccobello-git/prism-player/releases?per_page=30"
    /**
     * Media3 is published only on Google's Maven repository (Maven Central has no androidx artifacts, which is why
     * the old address always failed). The GitHub releases of androidx/media are the fallback.
     */
    val MEDIA3_METADATA_URLS = listOf(
        "https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml",
        "https://maven.google.com/androidx/media3/media3-exoplayer/maven-metadata.xml",
    )
    const val MEDIA3_RELEASES_URL = "https://api.github.com/repos/androidx/media/releases?per_page=30"
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

    /** Highest stable tag in a GitHub releases list ("1.6.0", not "1.7.0-alpha01"). */
    fun latestStableTag(json: String): String? = try {
        latestStable(
            (Json.parseToJsonElement(json) as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("tag_name"))?.jsonPrimitive?.content },
        )
    } catch (e: Exception) {
        null
    }

    /** A published release of the app with its APK (and, when present, the file holding the APK's SHA-256). */
    data class AppRelease(val tag: String, val apkUrl: String, val shaUrl: String?, val size: Long)

    /**
     * The newest published release that carries the APK. Drafts and releases without the APK are skipped, and only
     * download addresses of this repository are accepted, so the app never fetches an APK from anywhere else.
     */
    fun latestAppRelease(json: String): AppRelease? = try {
        (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { element ->
            val release = element as? JsonObject ?: return@mapNotNull null
            if ((release["draft"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val tag = (release["tag_name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (parts(tag).isEmpty()) return@mapNotNull null
            val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun asset(name: String) = assets.firstOrNull { (it["name"] as? JsonPrimitive)?.content == name }
            fun url(asset: JsonObject?) = (asset?.get("browser_download_url") as? JsonPrimitive)?.content
                ?.takeIf { it.startsWith(DOWNLOAD_BASE + tag + "/") }
            val apk = asset(APK_NAME)
            val apkUrl = url(apk) ?: return@mapNotNull null
            AppRelease(
                tag = tag,
                apkUrl = apkUrl,
                shaUrl = url(asset("$APK_NAME.sha256")),
                size = (apk?.get("size") as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }.maxWithOrNull { a, b -> compare(a.tag, b.tag) }
    } catch (e: Exception) {
        null
    }

    /** True when [latest] is a real version newer than the installed [installedLabel] ("dev" builds never update). */
    fun isNewer(latest: String, installedLabel: String): Boolean =
        parts(installedLabel).isNotEmpty() && compare(latest, installedLabel) > 0

    /** The automatic check runs at most once every [AUTO_CHECK_INTERVAL_MS]; a clock moved backwards counts as due. */
    fun autoCheckDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS

    const val AUTO_CHECK_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000

    /** The SHA-256 written in a ".sha256" file ("<hex>  name"), lower case, or null when there is none. */
    fun shaFromFile(text: String): String? =
        Regex("""\b[0-9a-fA-F]{64}\b""").find(text)?.value?.lowercase()

    /** GitHub's list of releases, read once; null when it cannot be reached. Only GitHub is contacted. */
    suspend fun fetchReleases(): String? = withContext(Dispatchers.IO) { fetchText(RELEASES_URL) }

    fun mavenVersions(xml: String): List<String> = versionTag.findAll(xml).map { it.groupValues[1].trim() }.toList()

    fun latestStable(versions: List<String>): String? =
        versions.filter { isStable(it) && parts(it).isNotEmpty() }.maxWithOrNull { a, b -> compare(a, b) }

    fun report(
        appLabel: String,
        engineVersion: String,
        releasesJson: String?,
        mavenXml: String?,
        engineReleasesJson: String? = null,
    ): Report {
        val appLatest = releasesJson?.let(::latestReleaseTag)
        val engineLatest = mavenXml?.let { latestStable(mavenVersions(it)) }
            ?: engineReleasesJson?.let(::latestStableTag)
        return Report(
            appCurrent = appLabel,
            appLatest = appLatest,
            appNewer = appLatest != null && isNewer(appLatest, appLabel),
            engineCurrent = engineVersion,
            engineLatest = engineLatest,
            engineNewer = engineLatest != null && compare(engineLatest, engineVersion) > 0,
            offline = appLatest == null && engineLatest == null,
        )
    }

    suspend fun check(appLabel: String, engineVersion: String): Report = withContext(Dispatchers.IO) {
        val maven = MEDIA3_METADATA_URLS.firstNotNullOfOrNull { url ->
            fetchText(url)?.takeIf { latestStable(mavenVersions(it)) != null }
        }
        report(
            appLabel,
            engineVersion,
            fetchText(RELEASES_URL),
            maven,
            if (maven == null) fetchText(MEDIA3_RELEASES_URL) else null,
        )
    }

    internal fun fetchText(address: String): String? = try {
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
