package dev.red74.prismplayer.data

import fi.iki.elonen.NanoHTTPD
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pretend Xtream-style panel with invented credentials. It reproduces the behaviours that make a live list work
 * in one player and fail in another (user-agent filtering, slow start, redirects, HTML answers, dropped
 * connections, CRLF and BOM) so each one can be checked against the real fetcher and parser. It never talks to
 * any real server.
 */
class MockPanelTest {
    private val body = buildString {
        append('﻿')
        append("#EXTM3U\r\n")
        append("#EXTINF:-1 tvg-id=\"rai1.it\" tvg-name=\"IT| Rai 1\" tvg-logo=\"http://img.example/1.png\" group-title=\"IT| Italia, Generali\",IT| Rai 1 \"HD\"\r\n")
        append("http://panel.example:23000/demo/demo-pass/101\r\n")
        append("#EXTINF:-1 tvg-id=\"\" tvg-name=\"Sport 2\" tvg-logo=\"\" group-title=\"Sport\",Sport 2\r\n")
        append("http://panel.example:23000/demo/demo-pass/102.ts\r\n")
        append("#EXTINF:-1 tvg-logo=\"http://img.example/m.png\" group-title=\"Film\",Heat (1995)\r\n")
        append("http://panel.example:23000/movie/demo/demo-pass/201.mkv\r\n")
        append("#EXTINF:-1 group-title=\"Serie\",Show S01E01\r\n")
        append("http://panel.example:23000/series/demo/demo-pass/301.mkv\r\n")
    }

    private var http: NanoHTTPD? = null
    private var raw: ServerSocket? = null

    @After
    fun tearDown() {
        http?.stop()
        raw?.close()
    }

    private fun panel(handler: (NanoHTTPD.IHTTPSession, Int) -> NanoHTTPD.Response): String {
        val hits = AtomicInteger()
        val s = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession) = handler(session, hits.incrementAndGet())
        }
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        http = s
        return "http://127.0.0.1:${s.listeningPort}"
    }

    private fun load(url: String): M3uParser.Result = runBlocking {
        PlaylistFetcher().fetch(url) { M3uParser.parse(it, url, keepChannels = true) }.getOrThrow()
    }

    private fun ok(text: String = body, mime: String = "application/octet-stream") =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, text)

    private fun checkContent(result: M3uParser.Result) {
        assertEquals(4, result.count)
        val types = result.channels.map { it.type }
        assertEquals(listOf(ContentType.LIVE, ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES), types)
        assertEquals("http://panel.example:23000/demo/demo-pass/101", result.channels[0].url)
        assertTrue(result.sawPlaylistMarker)
    }

    @Test
    fun plainPanelWithCrlfAndBom() {
        val base = panel { _, _ -> ok() }
        checkContent(load("$base/get.php?username=demo&password=demo-pass&type=m3u_plus&output=mpegts"))
    }

    @Test
    fun panelThatOnlyAnswersKnownPlayers() {
        val base = panel { session, _ ->
            val agent = session.headers["user-agent"].orEmpty()
            if (agent.startsWith("VLC") || agent.contains("Lavf")) ok()
            else NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "no")
        }
        checkContent(load("$base/get.php?username=demo&password=demo-pass&type=m3u_plus&output=mpegts"))
    }

    @Test
    fun panelThatRedirectsToAnotherPort() {
        val target = panel { _, _ -> ok() }
        val first = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                r.addHeader("Location", "$target/get.php?username=demo&password=demo-pass&type=m3u_plus&output=mpegts")
                return r
            }
        }
        first.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            checkContent(load("http://127.0.0.1:${first.listeningPort}/get.php?username=demo"))
        } finally {
            first.stop()
        }
    }

    @Test
    fun panelThatThinksBeforeAnswering() {
        val base = panel { _, _ ->
            Thread.sleep(3_000)
            ok()
        }
        checkContent(load("$base/get.php?username=demo&password=demo-pass&type=m3u_plus&output=mpegts"))
    }

    @Test
    fun panelThatDropsTheFirstConnection() {
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        raw = server
        val count = AtomicInteger()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (reader.readLine().let { it != null && it.isNotEmpty() }) { /* skip headers */ }
                        if (count.incrementAndGet() > 1) {
                            val payload = body.toByteArray()
                            s.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                            )
                            s.getOutputStream().write(payload)
                            s.getOutputStream().flush()
                        }
                    }
                }
            }
        }
        checkContent(load("http://127.0.0.1:${server.localPort}/get.php?username=demo"))
        assertEquals(2, count.get())
    }

    @Test
    fun htmlAnswerIsNotTakenForAPlaylist() {
        val base = panel { _, _ -> ok("<html><body>Account expired</body></html>", "text/html") }
        val result = load("$base/get.php?username=demo")
        assertEquals(0, result.count)
        assertTrue(!result.sawPlaylistMarker)
    }

    @Test
    fun namesWithQuotesCommasAndBarsAreKept() {
        val base = panel { _, _ -> ok() }
        val first = load("$base/get.php?username=demo").channels.first()
        assertEquals("IT| Italia, Generali", first.group)
        assertTrue(first.name.startsWith("IT| Rai 1"))
        assertEquals("rai1.it", first.tvgId)
    }
}
