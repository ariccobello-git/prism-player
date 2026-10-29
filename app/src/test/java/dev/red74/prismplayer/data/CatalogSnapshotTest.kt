package dev.red74.prismplayer.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSnapshotTest {
    private fun sample(n: Int): M3uParser.Result {
        val channels = (1..n).map { i ->
            val series = i % 5 == 0
            Channel(
                name = "Canale $i è però ☆",
                url = "http://example.test/live/u/p/$i.ts",
                group = "GRUPPO ${i % 40}",
                logo = if (i % 3 == 0) "http://example.test/l/$i.png" else null,
                tvgId = if (i % 7 == 0) "id$i" else null,
                tvgName = null,
                type = when { series -> ContentType.SERIES; i % 2 == 0 -> ContentType.MOVIE; else -> ContentType.LIVE },
                seriesTitle = if (series) "Serie ${i % 9}" else null,
                season = if (series) i % 4 else 0,
                episode = if (series) i % 300 else 0,
            )
        }
        return M3uParser.Result(channels, n, truncated = n > 3, epgUrl = "http://example.test/epg.xml", sawPlaylistMarker = true)
    }

    private fun bytes(r: M3uParser.Result) = ByteArrayOutputStream().also { CatalogSnapshot.write(it, r) }.toByteArray()

    @Test
    fun roundTripKeepsEveryField() {
        val original = sample(2_000)
        val back = CatalogSnapshot.read(ByteArrayInputStream(bytes(original)))
        assertNotNull(back)
        back!!
        assertEquals(original.channels, back.channels)
        assertEquals(original.count, back.count)
        assertEquals(original.truncated, back.truncated)
        assertEquals(original.epgUrl, back.epgUrl)
    }

    @Test
    fun repeatedGroupNamesShareOneString() {
        val back = CatalogSnapshot.read(ByteArrayInputStream(bytes(sample(200))))!!
        val first = back.channels.first { it.group == "GRUPPO 1" }
        val second = back.channels.last { it.group == "GRUPPO 1" }
        assertSame(first.group, second.group)
    }

    @Test
    fun hugeListRoundTripsQuickly() {
        val original = sample(250_000)
        val started = System.nanoTime()
        val data = bytes(original)
        val back = CatalogSnapshot.read(ByteArrayInputStream(data))!!
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(250_000, back.channels.size)
        assertEquals(original.channels.last(), back.channels.last())
        println("snapshot 250000 entries: ${data.size / 1024} KB, write+read $ms ms")
    }

    @Test
    fun garbageAndTruncationAreRejected() {
        assertNull(CatalogSnapshot.read(ByteArrayInputStream("#EXTM3U\n#EXTINF:-1,x\nhttp://a".toByteArray())))
        val data = bytes(sample(100))
        assertNull(CatalogSnapshot.read(ByteArrayInputStream(data.copyOf(data.size / 2))))
        assertNull(CatalogSnapshot.read(ByteArrayInputStream(ByteArray(0))))
        assertTrue(true)
    }
}
