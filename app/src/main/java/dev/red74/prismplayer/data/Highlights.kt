package dev.red74.prismplayer.data

import java.util.IdentityHashMap
import java.util.TimeZone

/** The three kinds of featured events, as streaming platforms group them. */
enum class HighlightKind { SPORT, FILM, ENTERTAINMENT }

/** The sport of a featured event (a label under the title). */
enum class SportTopic { FOOTBALL, TENNIS, MOTORS, BASKETBALL, RUGBY, CYCLING, VOLLEYBALL, GOLF, COMBAT, AMERICAN_FOOTBALL, HOCKEY, OLYMPICS }

/** One featured programme, with the channels of the user's list that carry it (best first, at most [MAX_CHANNELS]). */
data class HighlightEvent(
    val kind: HighlightKind,
    val title: String,
    val topic: SportTopic?,
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val channels: List<Channel>,
) {
    fun isLive(nowMs: Long): Boolean = startMs <= nowMs && nowMs < endMs

    companion object {
        const val MAX_CHANNELS = 4
    }
}

/** Featured events found in the guide, computed once in the background when the guide arrives. */
class HighlightsData(private val byKind: Map<HighlightKind, List<HighlightEvent>>) {
    val isEmpty: Boolean get() = byKind.values.all { it.isEmpty() }

    fun events(kind: HighlightKind): List<HighlightEvent> = byKind[kind].orEmpty()

    /** Kinds that have at least one event, in the fixed order Sport, Film, Entertainment. */
    fun kinds(): List<HighlightKind> = HighlightKind.entries.filter { events(it).isNotEmpty() }

    companion object {
        val Empty = HighlightsData(emptyMap())
    }
}

/** A row of the page: events already on air, or those starting [day] days from today (0 = today). */
data class HighlightRow(val live: Boolean, val day: Int, val events: List<HighlightEvent>, val forYou: Boolean = false)

/**
 * Picks the most awaited events out of the programme guide the list already carries: no network, no other service.
 * Only channels of the user's list are looked at, so every event shown can be watched.
 */
object Highlights {
    /** Events kept per kind: more than the page shows, so favourite sports and teams can still be found. */
    const val PER_KIND = 120

    /** Events the page shows per kind besides those picked for the viewer. */
    const val SHOWN = 30
    const val FOR_YOU_MAX = 12

    /** How far ahead the guide reaches; equal to the download window in the view model. */
    const val FUTURE_MS = 36L * 60 * 60 * 1000
    private const val MIN_MS = 40L * 60_000
    private const val DAY_MS = 86_400_000L

    fun build(
        epg: EpgData,
        channels: List<Channel>,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): HighlightsData {
        // Channels sharing one guide entry (HD/SD/regional copies) share the same list: classify it once.
        val groups = IdentityHashMap<List<Programme>, MutableList<Channel>>()
        for (channel in channels) {
            val list = epg.programmes(channel)
            if (list.isNotEmpty()) groups.getOrPut(list) { ArrayList() } += channel
        }
        val merged = HashMap<String, Candidate>()
        for ((list, owners) in groups) {
            val sportChannel = owners.any { isSportChannel(it) }
            val filmChannel = !sportChannel && owners.any { isFilmChannel(it) }
            for (programme in list) {
                if (programme.endMs <= nowMs || programme.startMs > nowMs + FUTURE_MS) continue
                if (programme.endMs - programme.startMs < MIN_MS) continue
                val found = classify(programme, sportChannel, filmChannel, zone) ?: continue
                val key = found.kind.name + "|" + (EpgData.normalize(programme.title) ?: continue) + "|" + programme.startMs / 300_000L
                val existing = merged[key]
                if (existing == null) {
                    merged[key] = Candidate(found, programme, owners.toMutableList())
                } else {
                    existing.channels += owners
                }
            }
        }
        val result = HashMap<HighlightKind, List<HighlightEvent>>()
        for (kind in HighlightKind.entries) {
            val picked = merged.values.filter { it.found.kind == kind }
                .map { it.toEvent() }
                .sortedWith(compareByDescending<HighlightEvent> { it.score }.thenBy { it.startMs })
                .take(PER_KIND)
            if (picked.isNotEmpty()) result[kind] = picked
        }
        return HighlightsData(result)
    }

    /** Events not yet over, as rows: on air first, then each day. Inside a row the best events come first when on air, else by time. */
    fun rows(events: List<HighlightEvent>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): List<HighlightRow> {
        val offset = zone.getOffset(nowMs).toLong()
        val today = Math.floorDiv(nowMs + offset, DAY_MS)
        val upcoming = events.filter { it.endMs > nowMs }
        val live = upcoming.filter { it.isLive(nowMs) }.sortedByDescending { it.score }
        val later = upcoming.filter { !it.isLive(nowMs) }.groupBy {
            (Math.floorDiv(it.startMs + zone.getOffset(it.startMs).toLong(), DAY_MS) - today).toInt().coerceAtLeast(0)
        }
        val rows = ArrayList<HighlightRow>()
        if (live.isNotEmpty()) rows += HighlightRow(true, 0, live)
        for (day in later.keys.sorted()) rows += HighlightRow(false, day, later.getValue(day).sortedBy { it.startMs })
        return rows
    }

    /**
     * The rows of one kind: first "for you" (events matching the favourite sports/teams, or carried by channels from
     * the groups watched most), then the best [SHOWN] events by day.
     */
    fun pageRows(
        all: List<HighlightEvent>,
        nowMs: Long,
        prefs: SportPrefs,
        likedGroups: Set<String>,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<HighlightRow> {
        val forYou = all.filter { it.endMs > nowMs && isForYou(it, prefs, likedGroups) }
            .sortedWith(compareBy<HighlightEvent> { !it.isLive(nowMs) }.thenBy { it.startMs })
            .take(FOR_YOU_MAX)
        val main = rows(all.take(SHOWN), nowMs, zone)
        return if (forYou.isEmpty()) main else listOf(HighlightRow(forYou.any { it.isLive(nowMs) }, 0, forYou, forYou = true)) + main
    }

    /**
     * Sport: only what the viewer follows (sports, teams) is "for you"; the groups watched most say nothing about a
     * team, and on a sports list they would make every match "for you". Films and shows: the groups watched most.
     */
    fun isForYou(event: HighlightEvent, prefs: SportPrefs, likedGroups: Set<String>): Boolean =
        if (event.kind == HighlightKind.SPORT) prefs.matches(event)
        else likedGroups.isNotEmpty() && event.channels.any { it.group != null && it.group in likedGroups }

    // --- classification -------------------------------------------------------------------------------------

    internal class Found(val kind: HighlightKind, val topic: SportTopic?, val score: Int)

    private class Candidate(val found: Found, val programme: Programme, val channels: MutableList<Channel>) {
        fun toEvent(): HighlightEvent {
            val unique = channels.distinctBy { it.url }
            val best = unique.sortedBy { if (isLowQuality(it.name)) 1 else 0 }.take(HighlightEvent.MAX_CHANNELS)
            val extra = ((unique.size.coerceAtMost(4)) - 1) * 4
            return HighlightEvent(found.kind, programme.title, found.topic, programme.startMs, programme.endMs, found.score + extra, best)
        }
    }

    private fun isLowQuality(name: String): Boolean = clean(name).let { it.endsWith(" sd") || " sd " in it }

    internal fun classify(programme: Programme, sportChannel: Boolean, filmChannel: Boolean, zone: TimeZone): Found? {
        val title = clean(programme.title)
        val text = " " + title + " " + clean(programme.description.orEmpty().take(200)) + " "
        val padded = " $title "
        val hour = (Math.floorMod(programme.startMs + zone.getOffset(programme.startMs).toLong(), DAY_MS) / 3_600_000L).toInt()
        val primeTime = hour in 20..22
        val minutes = (programme.endMs - programme.startMs) / 60_000L

        if (sportChannel || topicOf(padded) != null) {
            val topic = topicOf(padded)
            if (hasAny(padded, EXCLUDE_SPORT)) return null
            val versus = hasAny(padded, VERSUS) || " - " in programme.title || " – " in programme.title
            val accepted = if (sportChannel) topic != null || versus else topic != null && (hasAny(padded, LIVE_WORDS) || versus)
            if (accepted) {
                var score = 10
                if (hasAny(padded, BIG_EVENTS)) score += 30
                if (padded.contains(" serie a ")) score += 12
                score += when (topClubs(padded)) { 0 -> 0; 1 -> 10; else -> 22 }
                if (topic == SportTopic.MOTORS) {
                    if (hasAny(padded, listOf("gara", "gran premio", "race"))) score += 15
                    if (hasAny(padded, listOf("prove libere", "practice"))) score -= 10
                }
                if (hasAny(padded, LIVE_WORDS)) score += 4
                if (primeTime) score += 6
                return Found(HighlightKind.SPORT, topic, score)
            }
            if (sportChannel) return null
        }
        if (filmChannel && minutes in 70..240 && !hasAny(padded, EXCLUDE_FILM)) {
            var score = 10
            if (primeTime) score += 25
            if (hasAny(text, PREMIERE)) score += 15
            if (!programme.description.isNullOrBlank()) score += 3
            return Found(HighlightKind.FILM, null, score)
        }
        if (!sportChannel && minutes in 45..300 && hasAny(padded, ENTERTAINMENT)) {
            var score = 15
            if (hasAny(padded, listOf("finale", "eurovision", "sanremo", "capodanno", "concertone"))) score += 20
            if (primeTime) score += 15
            return Found(HighlightKind.ENTERTAINMENT, null, score)
        }
        return null
    }

    fun isSportChannel(channel: Channel): Boolean = hasAny(" " + clean(channel.name) + " " + clean(channel.group.orEmpty()) + " ", SPORT_CHANNEL)

    fun isFilmChannel(channel: Channel): Boolean = hasAny(" " + clean(channel.name) + " " + clean(channel.group.orEmpty()) + " ", FILM_CHANNEL)

    /** Lower case, every run of non-letters/digits turned into one space. */
    internal fun clean(text: String): String {
        val out = StringBuilder(text.length)
        var space = true
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) { out.append(c); space = false } else if (!space) { out.append(' '); space = true }
        }
        return out.toString().trimEnd()
    }

    private fun hasAny(padded: String, words: List<String>): Boolean = words.any { padded.contains(" $it ") }

    private fun topClubs(padded: String): Int = TOP_CLUBS.count { padded.contains(" $it ") }

    private fun topicOf(padded: String): SportTopic? {
        for ((topic, words) in TOPICS) if (hasAny(padded, words)) return topic
        return null
    }

    private val SPORT_CHANNEL = listOf("sport", "sports", "dazn", "eurosport", "tennis", "f1", "motogp", "calcio", "nba", "golf", "supertennis", "bein", "bein sports")
    private val FILM_CHANNEL = listOf("cinema", "film", "films", "movie", "movies", "cine", "hollywood")
    private val VERSUS = listOf("vs", "v", "contro")
    private val LIVE_WORDS = listOf("diretta", "live", "in diretta")
    private val EXCLUDE_SPORT = listOf(
        "replay", "differita", "magazine", "studio", "notizie", "highlights", "sintesi", "rivivi", "rubrica", "tg",
        "news", "prepartita", "postpartita", "pre partita", "post partita", "moviola", "processo", "best of", "classic",
    )
    private val EXCLUDE_FILM = listOf("tg", "notiziario", "televendita", "telegiornale", "documentario", "cartoni", "cartoon")
    private val PREMIERE = listOf("prima tv", "anteprima", "prima visione", "in prima", "premiere", "prima serata")
    private val BIG_EVENTS = listOf(
        "derby", "finale", "final", "champions league", "europa league", "supercoppa", "clasico", "el clasico",
        "gran premio", "wimbledon", "roland garros", "us open", "australian open", "mondiali", "europei", "nations league",
        "coppa italia", "olimpiadi", "super bowl", "ryder cup", "giro d italia", "tour de france", "sei nazioni", "six nations",
        "atp finals", "wta finals", "semifinale", "quarti di finale",
    )
    private val TOP_CLUBS = listOf(
        "juventus", "inter", "milan", "napoli", "roma", "lazio", "atalanta", "fiorentina", "italia",
        "real madrid", "barcellona", "barcelona", "bayern", "psg", "paris saint germain", "manchester city",
        "manchester united", "liverpool", "arsenal", "chelsea", "ferrari", "sinner",
    )
    private val ENTERTAINMENT = listOf(
        "x factor", "amici", "grande fratello", "gf vip", "sanremo", "festival", "isola dei famosi", "masterchef", "bake off",
        "ballando con le stelle", "tale e quale show", "the voice", "got talent", "striscia la notizia", "le iene",
        "che tempo che fa", "concerto", "concertone", "eurovision", "capodanno", "talent", "reality", "show", "serata finale",
    )
    private val TOPICS: List<Pair<SportTopic, List<String>>> = listOf(
        SportTopic.FOOTBALL to listOf(
            "serie a", "serie b", "serie c", "champions league", "europa league", "conference league", "coppa italia",
            "supercoppa", "premier league", "la liga", "liga", "bundesliga", "ligue 1", "mondiali", "europei", "nations league",
            "qualificazioni", "amichevole", "calcio", "football", "soccer",
        ),
        SportTopic.TENNIS to listOf("atp", "wta", "wimbledon", "roland garros", "us open", "australian open", "davis cup", "tennis", "atp finals"),
        SportTopic.MOTORS to listOf("formula 1", "f1", "gran premio", "motogp", "moto gp", "moto2", "moto3", "superbike", "wrc", "formula e", "gp"),
        SportTopic.BASKETBALL to listOf("nba", "basket", "eurolega", "euroleague", "lega basket"),
        SportTopic.RUGBY to listOf("rugby", "six nations", "sei nazioni"),
        SportTopic.CYCLING to listOf("giro d italia", "tour de france", "vuelta", "ciclismo", "tirreno adriatico", "milano sanremo"),
        SportTopic.VOLLEYBALL to listOf("volley", "pallavolo", "superlega"),
        SportTopic.GOLF to listOf("golf", "ryder cup", "pga tour"),
        SportTopic.COMBAT to listOf("boxe", "pugilato", "ufc", "mma"),
        SportTopic.AMERICAN_FOOTBALL to listOf("nfl", "super bowl"),
        SportTopic.HOCKEY to listOf("nhl"),
        SportTopic.OLYMPICS to listOf("olimpiadi", "olympics"),
    )
}
