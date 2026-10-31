package dev.red74.prismplayer.data

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Serves a generated list over loopback to check that big playlists stream through without a size limit. */
class PlaylistFetcherTest {
    private class Generated(private val entries: Int) : InputStream() {
        private var index = 0
        private var buffer = "#EXTM3U\n".toByteArray()
        private var position = 0

        override fun read(): Int {
            while (position >= buffer.size) {
                if (index >= entries) return -1
                buffer = ("#EXTINF:-1 group-title=\"G\",Channel $index\nhttp://example.com/live/$index.ts\n").toByteArray()
                position = 0
                index++
            }
            return buffer[position++].toInt() and 0xFF
        }
    }

    private class TestServer(private val entries: Int) : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = when (session.uri) {
            "/big.m3u" -> newChunkedResponse(Response.Status.OK, "audio/x-mpegurl", Generated(entries))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no")
        }
    }

    private var server: TestServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun start(entries: Int): String {
        val s = TestServer(entries)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = s
        return "http://127.0.0.1:${s.listeningPort}"
    }

    @Test
    fun streamsAListFarBeyondTheOldEightMegabyteLimit() = runBlocking {
        // 200k entries is roughly 15 MB of text.
        val base = start(200_000)
        val result = PlaylistFetcher().fetch("$base/big.m3u") { reader ->
            M3uParser.parse(reader, "$base/big.m3u", keepChannels = false)
        }.getOrThrow()
        assertEquals(200_000, result.count)
        assertFalse(result.truncated)
    }

    @Test
    fun keepsEntriesWhenAskedTo() = runBlocking {
        val base = start(500)
        val result = PlaylistFetcher().fetch("$base/big.m3u") { reader ->
            M3uParser.parse(reader, "$base/big.m3u", keepChannels = true)
        }.getOrThrow()
        assertEquals(500, result.channels.size)
        assertEquals("Channel 0", result.channels.first().name)
        assertEquals("G", result.channels.first().group)
    }

    @Test
    fun httpErrorsBecomeFetchFailures() = runBlocking {
        val base = start(1)
        val result = PlaylistFetcher().fetch("$base/missing.m3u") { reader -> reader.readText() }
        val failure = result.exceptionOrNull() as FetchException
        assertEquals(FetchException.Kind.HTTP, failure.kind)
        assertEquals(404, failure.httpCode)
        assertTrue(failure.message!!.none { it == '/' })
    }

    @Test
    fun aCancelledDownloadStopsAndFreesTheLineAtOnce() = runBlocking(Dispatchers.Default) {
        // Tens of millions of entries: without stopping, this download would hold the line for minutes.
        val base = start(50_000_000)
        val read = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val download = async {
            PlaylistFetcher().fetch("$base/big.m3u") { reader ->
                M3uParser.parse(reader, "$base/big.m3u", keepChannels = false) { count ->
                    read.set(count)
                    started.complete(Unit)
                }
            }
        }
        started.await()
        download.cancel()
        // The next request gets the line within moments, so the old download really let go of its connection.
        assertEquals("next", withTimeout(5_000) { PanelLine.request { "next" } })
        assertTrue(download.isCancelled)
        assertTrue(read.get() < 50_000_000)
    }
}
