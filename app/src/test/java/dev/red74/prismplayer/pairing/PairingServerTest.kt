package dev.red74.prismplayer.pairing

import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the real pairing server on the loopback interface and talks to it over HTTP. */
class PairingServerTest {
    private lateinit var manager: PairingManager
    private lateinit var server: PairingServer
    private val received = CopyOnWriteArrayList<Pair<String, String>>()
    private var port = 0

    private data class Reply(val status: Int, val body: String, val headers: Map<String, String>)

    @Before
    fun setUp() {
        manager = PairingManager()
        server = PairingServer(manager, onPlaylist = { name, url -> received.add(name to url) })
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        port = server.listeningPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun get(path: String): Reply {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.setRequestProperty("Connection", "close")
        return read(connection)
    }

    private fun post(path: String, body: String): Reply {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val bytes = body.toByteArray()
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        return read(connection)
    }

    private fun read(connection: HttpURLConnection): Reply {
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
        val headers = HashMap<String, String>()
        for ((name, values) in connection.headerFields) {
            if (name != null) headers[name.lowercase()] = values.joinToString(",")
        }
        connection.disconnect()
        return Reply(status, text, headers)
    }

    private fun form(code: String, url: String, name: String = "My list") =
        "c=${enc(code)}&n=${enc(name)}&u=${enc(url)}"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test
    fun browserStyleKeepAliveSubmitWithSplitHeadersAndBodyIsAccepted() {
        val code = manager.newSession().code
        val body = form(code, "https://example.com/list.m3u")
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write(
                ("POST /submit HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: keep-alive\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n")
                    .toByteArray(),
            )
            out.flush()
            Thread.sleep(300) // headers and body arrive in separate packets, as some browsers send them
            out.write(body.toByteArray())
            out.flush()
            val response = socket.getInputStream().readBytes().decodeToString() // returns only if the server closes
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.lowercase().contains("connection: close"))
        }
        assertEquals(listOf("My list" to "https://example.com/list.m3u"), received.toList())
    }

    @Test
    fun validCodeServesTheFormAndSecurityHeaders() {
        val code = manager.newSession().code
        val reply = get("/pair?c=$code")
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("action=\"/submit\""))
        assertEquals("no-store", reply.headers["cache-control"])
        assertEquals("no-referrer", reply.headers["referrer-policy"])
        assertNotNull(reply.headers["content-security-policy"])
        assertTrue(reply.headers["content-security-policy"]!!.contains("default-src 'none'"))
    }

    @Test
    fun wrongOrMissingCodeIsRefused() {
        manager.newSession()
        assertEquals(403, get("/pair?c=WRONGCOD").status)
        assertEquals(403, get("/pair").status)
    }

    @Test
    fun submittingStoresThePlaylistOnceAndBurnsTheCode() {
        val code = manager.newSession().code
        val first = post("/submit", form(code, "https://example.com/list.m3u?token=secret"))
        assertEquals(200, first.status)
        assertEquals(listOf("My list" to "https://example.com/list.m3u?token=secret"), received.toList())

        val second = post("/submit", form(code, "https://example.com/other.m3u"))
        assertEquals("a code must work once", 403, second.status)
        assertEquals(1, received.size)
        assertEquals(403, get("/pair?c=$code").status)
    }

    @Test
    fun aTypoInTheLinkDoesNotBurnTheCode() {
        val code = manager.newSession().code
        val bad = post("/submit", form(code, "ftp://example.com/list.m3u"))
        assertEquals(400, bad.status)
        assertTrue(received.isEmpty())

        val good = post("/submit", form(code, "http://example.com/list.m3u"))
        assertEquals(200, good.status)
        assertEquals(1, received.size)
    }

    @Test
    fun newCodeInvalidatesTheOldOneAtTheServer() {
        val old = manager.newSession().code
        val fresh = manager.newSession().code
        assertEquals(403, post("/submit", form(old, "https://example.com/a.m3u")).status)
        assertEquals(200, post("/submit", form(fresh, "https://example.com/a.m3u")).status)
    }

    @Test
    fun bruteForceLocksTheCodeOut() {
        val code = manager.newSession().code
        repeat(PairingManager.MAX_FAILED_ATTEMPTS) {
            assertEquals(403, post("/submit", form("WRONGCOD", "https://example.com/a.m3u")).status)
        }
        assertEquals(403, post("/submit", form(code, "https://example.com/a.m3u")).status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun oversizedBodiesAreRejected() {
        val code = manager.newSession().code
        val huge = form(code, "https://example.com/" + "a".repeat(20_000))
        // The server may answer 400 or drop the connection before the body is fully sent; both are a refusal.
        val reply = try {
            post("/submit", huge)
        } catch (e: java.io.IOException) {
            null
        }
        if (reply != null) assertEquals(400, reply.status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun savedUrlsAreNeverServedBack() {
        val secretUrl = "https://example.com/private/list.m3u?token=SUPERSECRET"
        val code = manager.newSession().code
        assertEquals(200, post("/submit", form(code, secretUrl)).status)

        val probes = listOf("/", "/pair", "/pair?c=$code", "/playlists", "/playlists.m3u", "/list", "/api/playlists")
        for (path in probes) {
            val reply = get(path)
            assertFalse("$path leaked a saved URL", reply.body.contains("SUPERSECRET"))
            assertFalse("$path leaked a saved URL", reply.body.contains("example.com/private"))
        }
    }

    @Test
    fun unknownPathsAre404WithSecurityHeaders() {
        val reply = get("/etc/passwd")
        assertEquals(404, reply.status)
        assertEquals("no-store", reply.headers["cache-control"])
    }

    @Test
    fun codeEntryPageIsAvailableForManualPairing() {
        val reply = get("/")
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("action=\"/pair\""))
    }

    @Test
    fun nameIsOptionalAndDefaultsToTheHost() {
        val code = manager.newSession().code
        assertEquals(200, post("/submit", form(code, "https://media.example.org/x.m3u8", name = "  ")).status)
        assertEquals("media.example.org", received.single().first)
    }
}
