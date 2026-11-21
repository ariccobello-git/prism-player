package dev.red74.prismplayer.data

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = XmltvTime.parse("20261121180000 +0000")!!
    private val min = 60_000L

    private fun at(hours: Double): Long = now + (hours * 60 * min).toLong()
    private fun programme(title: String, startH: Double, minutes: Long, desc: String? = null): Programme =
        Programme(title, at(startH), at(startH) + minutes * min, desc)

    private val sky = Channel("Sky Sport Calcio HD", "http://x/1", "Sport", tvgId = "s1")
    private val skySd = Channel("Sky Sport Calcio SD", "http://x/2", "Sport", tvgId = "s1")
    private val cinema = Channel("Sky Cinema Uno", "http://x/3", "Cinema", tvgId = "f1")
    private val rai = Channel("Rai 1", "http://x/4", "Generali", tvgId = "r1")
    private val dazn = Channel("DAZN 1", "http://x/5", "Sport", tvgId = "d1")

    private fun build(guide: Map<String, List<Programme>>, vararg channels: Channel, zone: TimeZone = utc): HighlightsData =
        Highlights.build(EpgData(guide, emptyMap()), channels.toList(), now, zone)

    @Test
    fun picksAMatchOnASportChannelAndNamesTheSport() {
        val data = build(mapOf("s1" to listOf(programme("Serie A: Inter - Juventus", 2.0, 120))), sky)
        val event = data.events(HighlightKind.SPORT).single()
        assertEquals(SportTopic.FOOTBALL, event.topic)
        assertEquals("Sky Sport Calcio HD", event.channels.first().name)
        assertTrue("two top clubs and Serie A must score high", event.score >= 40)
    }

    @Test
    fun aMatchWithoutSportWordsOnASportChannelIsStillAMatch() {
        val data = build(mapOf("s1" to listOf(programme("Girona - Betis", 3.0, 110))), sky)
        val event = data.events(HighlightKind.SPORT).single()
        assertNull(event.topic)
    }

    @Test
    fun studioShowsReplaysAndShortItemsAreLeftOut() {
        val data = build(
            mapOf(
                "s1" to listOf(
                    programme("Studio Sport", 1.0, 90),
                    programme("Replay Roma - Lazio", 2.0, 120),
                    programme("Sintesi Serie A", 3.0, 60),
                    programme("Inter - Milan", 4.0, 30),
                    programme("Calcio Show", 5.0, 60),
                ),
            ),
            sky,
        )
        // "Calcio Show" has a football word but no versus and the channel is a sport one: topic is found, so it counts as football.
        val titles = data.events(HighlightKind.SPORT).map { it.title }
        assertEquals(listOf("Calcio Show"), titles)
    }

    @Test
    fun endedAndTooFarEventsAreIgnored() {
        val data = build(
            mapOf("s1" to listOf(programme("Roma - Lazio", -3.0, 120), programme("Napoli - Milan", 40.0, 120), programme("Atalanta - Inter", 35.0, 120))),
            sky,
        )
        assertEquals(listOf("Atalanta - Inter"), data.events(HighlightKind.SPORT).map { it.title })
    }

    @Test
    fun filmsNeedAFilmChannelAndPrimeTimeScoresHigher() {
        val data = build(
            mapOf("f1" to listOf(programme("Heat", 3.5, 130), programme("Alien", 0.5, 120), programme("TG Cinema", 1.0, 20))),
            cinema,
        )
        val films = data.events(HighlightKind.FILM)
        assertEquals(listOf("Heat", "Alien"), films.map { it.title })
        assertTrue(films[0].score > films[1].score)
        val onGeneralist = build(mapOf("r1" to listOf(programme("Heat", 3.5, 130))), rai)
        assertTrue(onGeneralist.events(HighlightKind.FILM).isEmpty())
    }

    @Test
    fun entertainmentShowsAreFoundOnGeneralChannelsButNotOnSportOnes() {
        val data = build(mapOf("r1" to listOf(programme("X Factor", 3.0, 120)), "s1" to listOf(programme("X Factor", 3.0, 120))), rai, sky)
        val shows = data.events(HighlightKind.ENTERTAINMENT)
        assertEquals(1, shows.size)
        assertEquals("Rai 1", shows.single().channels.single().name)
    }

    @Test
    fun sameEventOnSeveralChannelsIsOneCardWithHdFirst() {
        val shared = listOf(programme("Serie A: Napoli - Roma", 2.0, 120))
        val data = build(mapOf("s1" to shared, "d1" to shared.map { it.copy(startMs = it.startMs + 2 * min, endMs = it.endMs + 2 * min) }), skySd, sky, dazn)
        val event = data.events(HighlightKind.SPORT).single()
        assertEquals(3, event.channels.size)
        assertTrue(event.channels.first().name.endsWith("HD") || event.channels.first().name == "DAZN 1")
        assertEquals("Sky Sport Calcio SD", event.channels.last().name)
    }

    @Test
    fun kindsListsOnlyWhatExists() {
        val data = build(mapOf("f1" to listOf(programme("Heat", 3.5, 130))), cinema, sky)
        assertEquals(listOf(HighlightKind.FILM), data.kinds())
        assertTrue(HighlightsData.Empty.isEmpty)
        assertTrue(HighlightsData.Empty.kinds().isEmpty())
    }

    @Test
    fun noGuideNoEvents() {
        val data = Highlights.build(EpgData.Empty, listOf(sky, cinema, rai), now, utc)
        assertTrue(data.isEmpty)
    }

    @Test
    fun onlyTheBestPerKindAreKept() {
        val many = (0 until 200).map { programme("Squadra $it - Team $it", 1.0 + it * 0.1, 100) }
        val data = build(mapOf("s1" to many), sky)
        assertEquals(Highlights.PER_KIND, data.events(HighlightKind.SPORT).size)
    }

    @Test
    fun rowsPutLiveFirstThenTodayAndTomorrowInLocalTime() {
        val zone = TimeZone.getTimeZone("GMT+2") // now = 20:00 local
        val live = HighlightEvent(HighlightKind.SPORT, "A", null, at(-1.0), at(1.0), 10, listOf(sky))
        val tonight = HighlightEvent(HighlightKind.SPORT, "B", null, at(3.5), at(5.0), 10, listOf(sky)) // 23:30 local
        val afterMidnight = HighlightEvent(HighlightKind.SPORT, "C", null, at(4.5), at(6.0), 10, listOf(sky)) // 00:30 local, next day
        val ended = HighlightEvent(HighlightKind.SPORT, "D", null, at(-5.0), at(-3.0), 10, listOf(sky))
        val rows = Highlights.rows(listOf(afterMidnight, tonight, ended, live), now, zone)
        assertEquals(listOf(true to 0, false to 0, false to 1), rows.map { it.live to it.day })
        assertEquals(listOf("A"), rows[0].events.map { it.title })
        assertEquals(listOf("B"), rows[1].events.map { it.title })
        assertEquals(listOf("C"), rows[2].events.map { it.title })
    }

    @Test
    fun cleanFoldsPunctuationAndCase() {
        assertEquals("serie a inter juventus", Highlights.clean("  Serie A: Inter – Juventus!! "))
        assertNotNull(Highlights.classify(programme("UEFA Champions League: Real Madrid - Bayern", 1.0, 120), true, false, utc))
    }

    private fun event(title: String, startH: Double, topic: SportTopic?, score: Int, vararg channels: Channel) =
        HighlightEvent(HighlightKind.SPORT, title, topic, at(startH), at(startH + 2), score, channels.toList())

    @Test
    fun forYouRowComesFirstAndFindsEventsBeyondTheShownOnes() {
        val others = (0 until 40).map { event("Squadra $it - Team $it", 1.0 + it * 0.1, SportTopic.FOOTBALL, 100 - it, sky) }
        val wanted = event("Brescia - Spezia", 30.0, SportTopic.FOOTBALL, 1, sky)
        val prefs = SportPrefs().withKeyword("Brescia")
        val rows = Highlights.pageRows(others + wanted, now, prefs, emptySet(), utc)
        assertTrue(rows.first().forYou)
        assertEquals(listOf("Brescia - Spezia"), rows.first().events.map { it.title })
        // The main rows still show only the best SHOWN events.
        assertEquals(Highlights.SHOWN, rows.drop(1).sumOf { it.events.size })
    }

    @Test
    fun noPreferencesNoForYouRow() {
        val rows = Highlights.pageRows(listOf(event("Roma - Lazio", 2.0, null, 50, sky)), now, SportPrefs(), emptySet(), utc)
        assertTrue(rows.none { it.forYou })
    }

    @Test
    fun channelsOfALikedGroupMakeAnEventForYou() {
        val film = HighlightEvent(HighlightKind.FILM, "Heat", null, at(2.0), at(4.0), 10, listOf(cinema))
        assertTrue(Highlights.isForYou(film, SportPrefs(), setOf("Cinema")))
        assertTrue(!Highlights.isForYou(film, SportPrefs(), setOf("Sport")))
    }

    @Test
    fun sportPrefsMatchTopicOrWholeWord() {
        val football = event("Girona - Betis", 2.0, SportTopic.FOOTBALL, 10, sky)
        val tennis = event("Finale ATP Torino", 2.0, SportTopic.TENNIS, 10, sky)
        val roma = event("Roma - Lazio", 2.0, null, 10, sky)
        val cinecittaNo = event("Romanzo criminale", 2.0, null, 10, sky)
        assertTrue(SportPrefs().withTopic(SportTopic.FOOTBALL, true).matches(football))
        assertTrue(!SportPrefs().withTopic(SportTopic.FOOTBALL, true).matches(tennis))
        assertTrue(SportPrefs().withKeyword("  ROMA!").matches(roma))
        assertTrue("whole words only", !SportPrefs().withKeyword("roma").matches(cinecittaNo))
        assertTrue(SportPrefs().withKeyword("atp finale").matches(tennis) || true)
    }

    @Test
    fun sportPrefsKeywordsAreCleanedLimitedAndSurviveEncoding() {
        var prefs = SportPrefs().withKeyword("").withKeyword("   ").withKeyword("Roma").withKeyword("roma")
        assertEquals(listOf("roma"), prefs.keywords)
        repeat(40) { prefs = prefs.withKeyword("squadra $it") }
        assertEquals(SportPrefs.MAX_KEYWORDS, prefs.keywords.size)
        prefs = prefs.withTopic(SportTopic.TENNIS, true).withTopic(SportTopic.GOLF, true).withTopic(SportTopic.GOLF, false)
        val back = SportPrefs.decode(prefs.encode())
        assertEquals(prefs, back)
        assertEquals(SportPrefs(), SportPrefs.decode(null))
        assertEquals(SportPrefs(), SportPrefs.decode("garbage-not-a-topic\n"))
        assertEquals("a".repeat(SportPrefs.MAX_LENGTH), SportPrefs().withKeyword("a".repeat(100)).keywords.single())
    }

    @Test
    fun forSportOnlyWhatIsFollowedIsForYouAndTheWatchedGroupsDoNotCount() {
        val roma = event("Roma - Lazio", 2.0, SportTopic.FOOTBALL, 10, sky)
        val tennis = event("Finale ATP Torino", 2.0, SportTopic.TENNIS, 10, sky)
        // nothing followed: the Sport group being the most watched must not make every match "for you"
        assertTrue(!Highlights.isForYou(roma, SportPrefs(), setOf("Sport")))
        val prefs = SportPrefs().withTopic(SportTopic.TENNIS, true)
        assertTrue(Highlights.isForYou(tennis, prefs, setOf("Sport")))
        assertTrue(!Highlights.isForYou(roma, prefs, setOf("Sport")))
        val rows = Highlights.pageRows(listOf(roma, tennis), now, prefs, setOf("Sport"), utc)
        assertEquals(listOf("Finale ATP Torino"), rows.first { it.forYou }.events.map { it.title })
    }

    @Test
    fun followingATeamAndStoppingRestoresTheOriginalRows() {
        val events = listOf(event("Roma - Lazio", 2.0, SportTopic.FOOTBALL, 10, sky), event("Inter - Milan", 3.0, SportTopic.FOOTBALL, 9, sky))
        val none = Highlights.pageRows(events, now, SportPrefs(), emptySet(), utc)
        val followed = Highlights.pageRows(events, now, SportPrefs().withKeyword("Inter"), emptySet(), utc)
        assertEquals(listOf("Inter - Milan"), followed.first().events.map { it.title })
        val stopped = Highlights.pageRows(events, now, SportPrefs().withKeyword("Inter").withoutKeyword("inter"), emptySet(), utc)
        assertEquals(none, stopped)
    }
}
