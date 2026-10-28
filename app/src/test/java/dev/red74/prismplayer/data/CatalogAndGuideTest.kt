package dev.red74.prismplayer.data

import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class CatalogAndGuideTest {
    private val source = "http://panel.example:8080/get.php?username=u&password=p&type=m3u_plus&output=mpegts"

    private val list = """
        #EXTM3U url-tvg="http://guide.example/epg.xml"
        #EXTINF:-1 tvg-id="rai1.it" tvg-name="Rai 1" tvg-logo="http://img/rai1.png" group-title="Italia",Rai 1 HD
        http://panel.example:8080/u/p/101
        #EXTINF:-1 tvg-id="rai2.it" tvg-logo="http://img/rai2.png" group-title="Italia",Rai 2 HD
        http://panel.example:8080/live/u/p/102.ts
        #EXTINF:-1 group-title="Film Azione",Heat
        http://panel.example:8080/movie/u/p/201.mp4
        #EXTINF:-1 tvg-logo="http://img/s.png" group-title="Serie Drama",Show Name S02E10
        http://panel.example:8080/series/u/p/302.mkv
        #EXTINF:-1 tvg-logo="http://img/s.png" group-title="Serie Drama",Show Name S01E02
        http://panel.example:8080/series/u/p/301.mkv
        #EXTINF:-1 group-title="Serie Drama",Show Name S01E01
        http://panel.example:8080/series/u/p/300.mkv
    """.trimIndent()

    @Test
    fun readsAttributesAndTheGuideAddressFromTheHeader() {
        val result = M3uParser.parse(java.io.BufferedReader(StringReader(list)), source, keepChannels = true)
        assertEquals("http://guide.example/epg.xml", result.epgUrl)
        val first = result.channels.first()
        assertEquals("Rai 1 HD", first.name)
        assertEquals("rai1.it", first.tvgId)
        assertEquals("http://img/rai1.png", first.logo)
        assertEquals("Italia", first.group)
        assertEquals(ContentType.LIVE, first.type)
    }

    @Test
    fun tellsLiveMoviesAndEpisodesApart() {
        val channels = M3uParser.parse(list, source)
        assertEquals(
            listOf(ContentType.LIVE, ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES, ContentType.SERIES, ContentType.SERIES),
            channels.map { it.type },
        )
        val episode = channels[3]
        assertEquals("Show Name", episode.seriesTitle)
        assertEquals(2, episode.season)
        assertEquals(10, episode.episode)
    }

    @Test
    fun organisesTheCatalogByCategoryAndGroupsEpisodesIntoSeasons() {
        val catalog = CatalogBuilder.build(M3uParser.parse(list, source), "http://guide.example/epg.xml", false)
        assertEquals(listOf("Italia"), catalog.live.map { it.name })
        assertEquals(2, catalog.live.single().items.size)
        assertEquals(listOf("Film Azione"), catalog.movies.map { it.name })

        val show = catalog.series.single().shows.single()
        assertEquals("Show Name", show.title)
        assertEquals(listOf(1, 2), show.seasons)
        // sorted by season, then episode, whatever the order in the file
        assertEquals(listOf(1, 2, 10), show.episodes.map { it.episode })
        assertEquals(listOf(1, 2), show.episodesOf(1).map { it.episode })
        assertEquals("http://img/s.png", show.logo)
    }

    @Test
    fun favouriteKeysAreStableAndDoNotContainTheUrl() {
        val channel = M3uParser.parse(list, source).first()
        val key = FavoriteKey.of(channel)
        assertEquals(key, FavoriteKey.of(channel.copy(name = "Renamed")))
        assertEquals(24, key.length)
        assertFalse(key.contains("panel"))
    }

    @Test
    fun guideAddressComesFromTheHeaderOrFromTheXtreamStylePanel() {
        assertEquals("http://x/epg.xml", EpgUrl.derive(source, "http://x/epg.xml"))
        assertEquals(
            "http://panel.example:8080/xmltv.php?username=u&password=p",
            EpgUrl.derive(source, null),
        )
        assertNull(EpgUrl.derive("http://panel.example/list.m3u", null))
    }

    @Test
    fun parsesXmltvTimes() {
        // 2026-10-28 03:15:00 at +01:00 is 02:15:00 UTC
        assertEquals(1_793_153_700_000L, XmltvTime.parse("20261028031500 +0100"))
        assertEquals(1_793_157_300_000L, XmltvTime.parse("20261028031500"))
        assertEquals(0L, XmltvTime.parse("19700101000000 +0000"))
        assertNull(XmltvTime.parse("garbage"))
        assertNull(XmltvTime.parse(null))
    }

    private fun parser(xml: String): XmlPullParser {
        val p = org.kxml2.io.KXmlParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        return p
    }

    @Test
    fun readsOnlyWantedChannelsInsideTheTimeWindowAndCleansText() {
        val now = XmltvTime.parse("20261028120000 +0000")!!
        val xml = """
            <tv>
              <channel id="Rai1.it"><display-name>Rai 1</display-name></channel>
              <channel id="other"><display-name>Other</display-name></channel>
              <channel id="by-name"><display-name>Rai 2 HD</display-name></channel>
              <programme start="20261028110000 +0000" stop="20261028130000 +0000" channel="Rai1.it">
                <title>Telegiornale ?</title><desc>Notizie &amp; sport ★</desc>
              </programme>
              <programme start="20261028130000 +0000" stop="20261028140000 +0000" channel="Rai1.it"><title>Film</title></programme>
              <programme start="20251028130000 +0000" stop="20251028140000 +0000" channel="Rai1.it"><title>Old</title></programme>
              <programme start="20261028110000 +0000" stop="20261028130000 +0000" channel="other"><title>Nope</title></programme>
              <programme start="20261028110000 +0000" stop="20261028130000 +0000" channel="by-name"><title>Matched by name</title></programme>
            </tv>
        """.trimIndent()
        val epg = XmltvParser.parse(
            parser(xml),
            wantedIds = setOf("rai1.it"),
            wantedNames = setOf(EpgData.normalize("Rai 2 HD")!!),
            windowStartMs = now - 3 * 3_600_000L,
            windowEndMs = now + 36 * 3_600_000L,
        )

        val rai1 = Channel(name = "Rai 1 HD", url = "http://h/1", tvgId = "RAI1.IT")
        val nowNext = epg.nowNext(rai1, now)
        assertEquals("Telegiornale", nowNext.now!!.title)
        assertEquals("Notizie & sport", nowNext.now!!.description)
        assertEquals("Film", nowNext.next!!.title)
        assertEquals(0.5f, nowNext.progress(now), 0.001f)
        assertEquals(2, epg.programmes(rai1).size) // the old one is outside the window

        val byName = Channel(name = "Rai 2 HD", url = "http://h/2")
        assertEquals("Matched by name", epg.nowNext(byName, now).now!!.title)

        val unwanted = Channel(name = "Other", url = "http://h/3", tvgId = "other")
        assertTrue(epg.programmes(unwanted).isEmpty())
        assertNotNull(epg.nowNext(unwanted, now))
    }

    @Test
    fun sameListIgnoresCaseDefaultPortAndTrailingSlash() {
        assertTrue(PlaylistUrl.sameList("http://Panel.Example:80/get.php?u=1", "http://panel.example/get.php?u=1"))
        assertTrue(PlaylistUrl.sameList("https://a.example/list/", "https://A.example:443/list"))
        assertFalse(PlaylistUrl.sameList("http://a.example/get.php?u=1", "http://a.example/get.php?u=2"))
    }

    @Test
    fun storeRefusesTheSameListTwice() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("prism").toFile()
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = PlaylistStore(java.io.File(folder, "p.bin"), SecureBox { key })
        store.load()
        val first = store.addIfNew("One", "http://a.example/get.php?u=1")
        assertTrue(first is PlaylistStore.AddResult.Added)
        val again = store.addIfNew("Other name", "http://A.example:80/get.php?u=1")
        assertTrue(again is PlaylistStore.AddResult.Duplicate)
        assertEquals(1, store.items.value.size)
        assertTrue(store.contains("http://a.example/get.php?u=1"))
        folder.deleteRecursively()
    }
}
