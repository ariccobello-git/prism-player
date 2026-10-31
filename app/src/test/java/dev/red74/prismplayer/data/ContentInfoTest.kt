package dev.red74.prismplayer.data

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Invented panel and credentials only. */
class ContentInfoTest {
    @Test
    fun filmAndSeriesAddressesBecomePanelRequests() {
        assertEquals(
            "http://panel.example:23000/player_api.php?username=demo&password=demo-pass&action=get_vod_info&vod_id=201",
            PanelAddress.vodInfo("http://panel.example:23000/movie/demo/demo-pass/201.mkv"),
        )
        assertEquals(
            "http://panel.example:23000/player_api.php?username=demo&password=demo-pass&action=get_series",
            PanelAddress.seriesList("http://panel.example:23000/series/demo/demo-pass/301.mkv"),
        )
    }

    @Test
    fun otherAddressesAskNothing() {
        assertNull(PanelAddress.vodInfo("http://panel.example:23000/demo/demo-pass/101"))
        assertNull(PanelAddress.vodInfo("http://cdn.example/films/heat.mp4"))
        assertNull(PanelAddress.vodInfo("http://panel.example/movie/demo/demo-pass/abc.mkv"))
        assertNull(PanelAddress.seriesList("http://panel.example:23000/movie/demo/demo-pass/201.mkv"))
    }

    @Test
    fun theFilmPlotIsReadFromTheInfoBlock() {
        val json = """{"info":{"name":"Heat","plot":"Un ladro e un poliziotto\ta Los Angeles. È \"epico\"","rating":7.5,"cast":null},"movie_data":{"stream_id":201}}"""
        assertEquals("Un ladro e un poliziotto a Los Angeles. È \"epico\"", PanelJson.filmPlot(StringReader(json)))
    }

    @Test
    fun anEmptyPlotFallsBackToTheDescriptionOrNothing() {
        assertEquals("Testo", PanelJson.filmPlot(StringReader("""{"info":{"plot":"","description":"Testo"}}""")))
        assertNull(PanelJson.filmPlot(StringReader("""{"info":[],"movie_data":{}}""")))
        assertNull(PanelJson.filmPlot(StringReader("<html>not json")))
    }

    @Test
    fun seriesPlotsAreMatchedByTitle() {
        val json = """[{"num":1,"name":"Dr. House","series_id":7,"plot":"Medico geniale.","backdrop_path":["a","b"]},""" +
            """{"name":"Senza trama","plot":null},{"name":"Altra","plot":"Storia","info":{"plot":"interna"}}]"""
        val plots = PanelJson.seriesPlots(StringReader(json))
        assertEquals("Medico geniale.", plots[PanelJson.titleKey("DR HOUSE")])
        assertEquals("Storia", plots[PanelJson.titleKey("Altra")])
        assertNull(plots[PanelJson.titleKey("Senza trama")])
        assertEquals(2, plots.size)
    }

    @Test
    fun veryLongPlotsAreCut() {
        val long = "parola ".repeat(400)
        val plot = PanelJson.filmPlot(StringReader("""{"info":{"plot":"$long"}}"""))!!
        assertEquals(true, plot.length <= 901 && plot.endsWith("…"))
    }

    @Test
    fun seriesKeepTheirPanelId() {
        val json = """[{"name":"Dr. House","series_id":7,"plot":"Medico geniale."},{"name":"Senza trama","series_id":"8"}]"""
        val series = PanelJson.series(StringReader(json))
        assertEquals("7", series[PanelJson.titleKey("Dr. House")]!!.id)
        assertEquals("8", series[PanelJson.titleKey("Senza trama")]!!.id)
        assertNull(series[PanelJson.titleKey("Senza trama")]!!.plot)
        assertEquals(
            "http://panel.example:23000/player_api.php?username=demo&password=demo-pass&action=get_series_info&series_id=7",
            PanelAddress.seriesInfo("http://panel.example:23000/series/demo/demo-pass/301.mkv", "7"),
        )
        assertEquals("301", PanelAddress.streamId("http://panel.example:23000/series/demo/demo-pass/301.mkv"))
    }

    @Test
    fun episodeDetailsAreFoundByStreamId() {
        val json = """{"seasons":[{"name":"Stagione 1","overview":"x"}],"info":{"name":"Dr. House","plot":"Serie"},""" +
            """"episodes":{"1":[{"id":"301","episode_num":1,"title":"Pilota","info":{"plot":"Il primo caso.","duration":"00:44:10","releasedate":"2004-11-16"}},""" +
            """{"id":302,"episode_num":2,"info":{"duration_secs":2600,"duration":"00:43:20"}},{"id":"303","info":[]}]}}"""
        val episodes = PanelJson.episodes(StringReader(json))
        assertEquals(EpisodeInfo("Il primo caso.", "00:44:10", "2004-11-16"), episodes["301"])
        assertEquals(EpisodeInfo(null, "00:43:20", null), episodes["302"])
        assertNull(episodes["303"])
    }
}
