package dev.red74.prismplayer.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The key of a list on the shared semaphore: the provider account found in an address (user and password of
 * `get.php`, `player_api.php`, `/live|movie|series/U/P/…`, `/U/P/id`), hashed, or the host when there is none.
 * Every address of the same account gives the same key, in this app and in iPrism (keep in step with iPrism's
 * `src/line.js`: the tests share the same vectors).
 */
object LineKey {
    fun of(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return accountOf(uri) ?: hash("prism-line-host|${host.lowercase()}")
    }

    /** The key of the provider account in [url]; null when the address carries none. */
    fun account(url: String): String? = runCatching { URI(url) }.getOrNull()?.let { accountOf(it) }

    private fun accountOf(uri: URI): String? {
        var user = query(uri.rawQuery, "username")
        var pass = query(uri.rawQuery, "password")
        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            user = null
            pass = null
            val seg = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            val at = seg.indexOfFirst { it.lowercase() in KINDS }
            if (at >= 0 && seg.size >= at + 3) {
                user = segment(seg[at + 1]); pass = segment(seg[at + 2])
            } else if (seg.size == 3) {
                user = segment(seg[0]); pass = segment(seg[1])
            }
        }
        return if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) hash("prism-line|$user|$pass") else null
    }

    private fun hash(basis: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private val KINDS = setOf("live", "movie", "series", "timeshift")

    private fun query(raw: String?, name: String): String? {
        for (part in raw.orEmpty().split('&')) {
            val eq = part.indexOf('=')
            val k = if (eq < 0) part else part.substring(0, eq)
            if (runCatching { URLDecoder.decode(k, "UTF-8") }.getOrNull() == name) {
                return runCatching { URLDecoder.decode(if (eq < 0) "" else part.substring(eq + 1), "UTF-8") }.getOrNull()
            }
        }
        return null
    }

    /** Like JavaScript's decodeURIComponent: a '+' stays a '+'. */
    private fun segment(raw: String): String? = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
}

/** Where iPrism's semaphore is and the TV's key to it (an Access service token), from the owner's iPrism page. */
data class IprismLink(val endpoint: String, val clientId: String, val secret: String) {
    fun encode(): String = "$endpoint#$clientId|$secret"

    companion object {
        private val FORMAT = Regex("""^(https://[^\s#]+/api/tv-line)#([^\s|]+)\|(\S+)$""")

        /**
         * Pasting on a phone can add spaces or line breaks, and some keyboards or pages encode '#' and '|':
         * those are undone before reading the code.
         */
        fun parse(text: String): IprismLink? =
            FORMAT.matchEntire(clean(text))?.destructured?.let { (e, id, secret) -> IprismLink(e, id, secret) }

        /** True when [text] is meant as iPrism's code (its address), even if it arrived incomplete. */
        fun looksLikeOne(text: String): Boolean = "/api/tv-line" in clean(text)

        /** What arrived, for the error page, without showing the key itself. */
        fun describe(text: String): String {
            val c = clean(text)
            val hash = c.indexOf('#')
            val after = if (hash < 0) "" else c.substring(hash + 1)
            return "#: ${if (hash < 0) "no" else "sì"}, |: ${if ('|' in after) "sì" else "no"}, " +
                "caratteri dopo #: ${after.length}"
        }

        private fun clean(text: String): String = PlaylistUrl.stripInvisible(text)
            .replace("%23", "#").replace("%7C", "|").replace("%7c", "|")
            .replace("\uFF03", "#").replace("\uFF5C", "|").replace("\u2223", "|").replace("\u01C0", "|")
    }
}

/** The link to iPrism, kept encrypted like the playlists (see [SecureBox]). */
class IprismLinkStore(private val file: File, private val box: SecureBox) {
    fun load(): IprismLink? = runCatching { IprismLink.parse(box.decrypt(file.readBytes()).decodeToString()) }.getOrNull()

    fun save(link: IprismLink) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(box.encrypt(link.encode().encodeToByteArray()))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not save the iPrism link")
        }
    }
}

/**
 * The provider allows ONE connection per list in the whole world: this app and iPrism (on the iPhone) take the
 * list's turn on iPrism's server before connecting, keep it alive while connected and free it when done. Turns
 * are shared by everything in this app that uses the same list ([PanelLine] requests and players) and kept a few
 * seconds after the last use, so a run of requests asks only once. If iPrism's server cannot be reached the app
 * does not connect: it retries. Without a link to iPrism (none pasted) nothing is asked.
 */
object SharedLine {
    data class Answer(val ok: Boolean, val waitMs: Long = 0, val by: String? = null, val ttlMs: Long = 0)

    private const val BEAT_MS = 15_000L
    private const val LINGER_MS = 3_000L
    private const val RETRY_MS = 3_000L
    private const val MARGIN_MS = 5_000L

    /** Loads the saved link the first time it is needed (on a background thread). */
    @Volatile var loader: () -> IprismLink? = { null }
    @Volatile private var loaded = false
    @Volatile private var link: IprismLink? = null

    /** The network call; replaced in tests. Null means the server could not be reached. */
    @Volatile var transport: (IprismLink, String, String, String) -> Answer? = ::post
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private val holder = "tv-" + UUID.randomUUID().toString().replace("-", "")
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var key: String? = null
    private var users = 0
    private var leaseUntil = 0L
    private var beat: Job? = null
    private var linger: Job? = null
    private val lostHandlers = CopyOnWriteArraySet<() -> Unit>()

    private val _note = MutableStateFlow<String?>(null)
    /** A short line for the screen while the app waits for a list's turn; null otherwise. */
    val note: StateFlow<String?> = _note.asStateFlow()

    fun setLink(value: IprismLink?) {
        link = value
        loaded = true
    }

    val linked: Boolean get() = currentLink() != null

    /** Called (on a background thread) when a held turn is lost: every connection to that list must close now. */
    fun onLost(handler: () -> Unit) { lostHandlers += handler }

    fun removeOnLost(handler: () -> Unit) { lostHandlers -= handler }

    /** Takes [key]'s turn, or joins it if this app already holds it; waits while another app holds it. */
    suspend fun take(key: String?, stop: () -> Boolean = { false }): Boolean {
        val link = withContext(Dispatchers.IO) { currentLink() } ?: return false
        if (key == null) return false
        while (true) {
            if (stop()) throw IOException("cut")
            val waitMs = lock.withLock {
                if (this.key == key && clock() < leaseUntil) {
                    users++
                    linger?.cancel()
                    return true
                }
                if (this.key != null && this.key != key) {
                    if (users > 0) return@withLock 500L
                    freeLocked(link)
                }
                if (this.key == key) dropLocked() // our turn ran out: ask again
                val answer = withContext(Dispatchers.IO) { transport(link, "take", key, holder) }
                when {
                    answer == null -> {
                        _note.value = "Il semaforo di iPrism non risponde: riprovo…"
                        RETRY_MS
                    }
                    answer.ok -> {
                        this.key = key
                        users = 1
                        leaseUntil = clock() + answer.ttlMs - MARGIN_MS
                        startBeat(link, key)
                        _note.value = null
                        return true
                    }
                    else -> {
                        _note.value = if (answer.by == "iphone") "La lista è in uso su iPrism: aspetto che si liberi…" else "La lista è in uso altrove: aspetto…"
                        answer.waitMs.coerceIn(500L, 5_000L)
                    }
                }
            }
            delay(waitMs)
        }
    }

    /** One use of [key] is over; the turn is freed a few seconds after the last one. */
    fun release(key: String?) {
        if (key == null) return
        scope.launch {
            lock.withLock {
                if (this@SharedLine.key != key || users == 0) return@launch
                users--
                if (users > 0) return@launch
                linger?.cancel()
                linger = scope.launch {
                    delay(LINGER_MS)
                    lock.withLock {
                        linger = null // this job: freeing must not cancel it halfway
                        val l = currentLink()
                        if (users == 0 && this@SharedLine.key == key && l != null) freeLocked(l)
                    }
                }
            }
        }
    }

    private fun currentLink(): IprismLink? {
        if (!loaded) {
            link = runCatching { loader() }.getOrNull()
            loaded = true
        }
        return link
    }

    private fun startBeat(link: IprismLink, key: String) {
        beat?.cancel()
        beat = scope.launch {
            while (true) {
                delay(BEAT_MS)
                val answer = transport(link, "beat", key, holder)
                val lost = lock.withLock {
                    if (this@SharedLine.key != key) return@launch
                    when {
                        answer?.ok == true -> { leaseUntil = clock() + answer.ttlMs - MARGIN_MS; false }
                        answer != null -> true
                        else -> clock() >= leaseUntil
                    }
                }
                if (lost) {
                    lock.withLock {
                        beat = null // this job: dropping the turn must not cancel it halfway
                        dropLocked()
                    }
                    _note.value = "Turno della lista perso: riprovo…"
                    lostHandlers.forEach { runCatching { it() } }
                    return@launch
                }
            }
        }
    }

    private suspend fun freeLocked(link: IprismLink) {
        val k = key ?: return
        dropLocked()
        withContext(Dispatchers.IO) { transport(link, "free", k, holder) }
    }

    private fun dropLocked() {
        beat?.cancel()
        beat = null
        linger?.cancel()
        linger = null
        key = null
        users = 0
        leaseUntil = 0L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun post(link: IprismLink, op: String, key: String, holder: String): Answer? {
        val connection = runCatching { URL(link.endpoint).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.setRequestProperty("CF-Access-Client-Id", link.clientId)
            connection.setRequestProperty("CF-Access-Client-Secret", link.secret)
            connection.outputStream.use { it.write("""{"op":"$op","key":"$key","holder":"$holder"}""".toByteArray()) }
            if (connection.responseCode != 200) return null
            val body = json.parseToJsonElement(connection.inputStream.use { it.readBytes().decodeToString() }).jsonObject
            body.toAnswer()
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.toAnswer() = Answer(
        ok = this["ok"]?.jsonPrimitive?.booleanOrNull == true,
        waitMs = this["wait"]?.jsonPrimitive?.longOrNull ?: 0L,
        by = this["by"]?.jsonPrimitive?.content,
        ttlMs = this["ttl"]?.jsonPrimitive?.longOrNull ?: 0L,
    )

    /** For tests: forgets every turn. */
    internal fun resetForTest() {
        beat?.cancel(); linger?.cancel()
        key = null; users = 0; leaseUntil = 0L; beat = null; linger = null
        _note.value = null
    }
}
