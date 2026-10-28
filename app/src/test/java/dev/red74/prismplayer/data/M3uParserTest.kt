package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private val source = "http://example.com/lists/main.m3u"

    @Test
    fun parsesNamesGroupsAndUrls() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-logo="http://x/l.png" group-title="News",Channel One
            http://example.com/one.m3u8
            #EXTINF:-1 group-title="Music, Live",Rock, Roll & Co
            https://example.com/two.ts
        """.trimIndent()

        val channels = M3uParser.parse(text, source)

        assertEquals(2, channels.size)
        assertEquals(Channel("Channel One", "http://example.com/one.m3u8", "News"), channels[0])
        assertEquals(Channel("Rock, Roll & Co", "https://example.com/two.ts", "Music, Live"), channels[1])
    }

    @Test
    fun resolvesRelativeUrlsAgainstTheSource() {
        val text = "#EXTM3U\n#EXTINF:-1,Relative\nch1.m3u8\n#EXTINF:-1,Root\n/abs/ch2.m3u8"
        val channels = M3uParser.parse(text, source)
        assertEquals("http://example.com/lists/ch1.m3u8", channels[0].url)
        assertEquals("http://example.com/abs/ch2.m3u8", channels[1].url)
    }

    @Test
    fun singleHlsPlaylistBecomesOneStream() {
        val text = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg1.ts\n#EXTINF:10.0,\nseg2.ts"
        val channels = M3uParser.parse(text, "https://cdn.example.com/live/stream.m3u8")
        assertEquals(1, channels.size)
        assertEquals("https://cdn.example.com/live/stream.m3u8", channels[0].url)
        assertEquals("cdn.example.com", channels[0].name)
    }

    @Test
    fun hlsMasterPlaylistBecomesOneStream() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh.m3u8"
        val channels = M3uParser.parse(text, "https://cdn.example.com/master.m3u8")
        assertEquals(listOf("https://cdn.example.com/master.m3u8"), channels.map { it.url })
    }

    @Test
    fun plainUrlListWorksWithoutExtinf() {
        val text = "http://a.example.com/one.mp4\nhttp://b.example.com/two.mp4"
        val channels = M3uParser.parse(text, source)
        assertEquals(listOf("one.mp4", "two.mp4"), channels.map { it.name })
    }

    @Test
    fun dropsNonHttpSchemes() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Local file
            file:///etc/passwd
            #EXTINF:-1,Content provider
            content://media/external/video/1
            #EXTINF:-1,RTSP
            rtsp://camera.example.com/stream
            #EXTINF:-1,Good
            https://example.com/good.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(text, source)
        assertEquals(listOf("Good"), channels.map { it.name })
    }

    @Test
    fun metadataDoesNotLeakToTheNextEntry() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",Named\nhttp://a/1\nhttp://a/2"
        val channels = M3uParser.parse(text, source)
        assertEquals("Named", channels[0].name)
        assertEquals("G", channels[0].group)
        assertEquals("2", channels[1].name)
        assertNull(channels[1].group)
    }

    @Test
    fun handlesBomWindowsLineEndingsAndBlankLines() {
        val text = "﻿#EXTM3U\r\n\r\n#EXTINF:-1,One\r\nhttp://a/1\r\n\r\n"
        val channels = M3uParser.parse(text, source)
        assertEquals(listOf("One"), channels.map { it.name })
    }

    @Test
    fun extgrpSetsTheGroup() {
        val text = "#EXTM3U\n#EXTINF:-1,One\n#EXTGRP:Sports\nhttp://a/1"
        assertEquals("Sports", M3uParser.parse(text, source)[0].group)
    }

    @Test
    fun emptyAndGarbageInputYieldNothing() {
        assertTrue(M3uParser.parse("", source).isEmpty())
        assertTrue(M3uParser.parse("   \n\n", source).isEmpty())
        assertTrue(M3uParser.parse("#EXTM3U\n#EXTINF:-1,No url", source).isEmpty())
    }

    @Test
    fun capsTheNumberOfChannels() {
        val text = buildString {
            repeat(M3uParser.MAX_CHANNELS + 50) { append("http://a.example.com/").append(it).append('\n') }
        }
        assertEquals(M3uParser.MAX_CHANNELS, M3uParser.parse(text, source).size)
    }

    @Test
    fun streamingCountsWithoutKeepingEntries() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",One\nhttp://a/1\n#EXTINF:-1,Two\nhttp://a/2\n"
        val counted = M3uParser.parse(java.io.BufferedReader(java.io.StringReader(text)), source, keepChannels = false)
        assertEquals(2, counted.count)
        assertTrue(counted.channels.isEmpty())
        assertFalse(counted.truncated)
    }

    @Test
    fun oversizedListIsTruncatedNotRejected() {
        val text = buildString {
            repeat(M3uParser.MAX_CHANNELS + 10) { append("http://a.example.com/").append(it).append('\n') }
        }
        val result = M3uParser.parse(java.io.BufferedReader(java.io.StringReader(text)), source, keepChannels = false)
        assertEquals(M3uParser.MAX_CHANNELS, result.count)
        assertTrue(result.truncated)
    }
}
