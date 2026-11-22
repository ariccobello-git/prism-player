package dev.red74.prismplayer.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.R
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.FavoriteKey
import dev.red74.prismplayer.data.HighlightEvent
import dev.red74.prismplayer.data.HighlightKind
import dev.red74.prismplayer.data.HighlightRow
import dev.red74.prismplayer.data.Highlights
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.SportPrefs
import dev.red74.prismplayer.data.SportPrefsStore
import dev.red74.prismplayer.data.SportTopic
import dev.red74.prismplayer.data.Suggestions
import dev.red74.prismplayer.data.Taste
import dev.red74.prismplayer.data.VariantTags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The featured events found in the guide; filled in the background by the view model. */
val LocalHighlights = compositionLocalOf { HighlightsData.Empty }

private val CardWidth = 240.dp
private val CardHeight = 138.dp
private val HeroHeight = 226.dp
private val MenuWidth = 210.dp

/**
 * The "poster" of an event card: one colour per kind (green sport, violet film, magenta entertainment) fading to a dark
 * base, so the title at the bottom always sits on a calm, dark area. Shared constants: nothing is created per frame.
 */
private fun poster(color: Color): Brush =
    Brush.verticalGradient(listOf(color.copy(alpha = 0.78f), color.copy(alpha = 0.34f), Color(0xFF070B14).copy(alpha = 0.90f)))

private val PosterSport = poster(Color(0xFF1FA463))
private val PosterFilm = poster(Color(0xFF7C4DDB))
private val PosterShow = poster(Color(0xFFD1488A))

private fun posterFor(kind: HighlightKind): Brush = when (kind) {
    HighlightKind.SPORT -> PosterSport
    HighlightKind.FILM -> PosterFilm
    HighlightKind.ENTERTAINMENT -> PosterShow
}

/**
 * "In evidenza": the most awaited events of the next hours, by kind (Sport, Film, Entertainment), each with its time
 * and the channel of the list that carries it; a "for you" row first when favourite sports, teams or the viewer's
 * habits match. Two more tabs suggest films and series like the ones already watched. It only reads what was worked
 * out in the background: no preview player and no extra connection.
 */
@Composable
fun HighlightsView(
    nav: LibraryNav,
    onNav: ((LibraryNav) -> LibraryNav) -> Unit,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    catalog: Catalog = Catalog.Empty,
) {
    val data = LocalHighlights.current
    val kinds = remember(data) { data.kinds() }
    val tasteVersion = Taste.version
    val movieGroups = remember(tasteVersion) { Taste.top(ContentType.MOVIE, SUGGESTION_GROUPS) }
    val seriesGroups = remember(tasteVersion) { Taste.top(ContentType.SERIES, SUGGESTION_GROUPS) }
    val likedGroups = remember(tasteVersion) { Taste.top(ContentType.LIVE, 3).toSet() }
    val tabs = remember(kinds, movieGroups, seriesGroups) {
        kinds.map { HighlightTab.Events(it) } +
            (if (movieGroups.isNotEmpty()) listOf(HighlightTab.Movies) else emptyList()) +
            (if (seriesGroups.isNotEmpty()) listOf(HighlightTab.Series) else emptyList())
    }
    if (tabs.isEmpty()) {
        // Still looking for the events (the guide is being read): placeholders, not an "empty" message.
        if (LocalHighlightsLoading.current) HighlightsSkeleton()
        else PrismText(text = stringResource(R.string.highlights_empty), size = 20.sp, color = PrismColors.TextSecondary)
        return
    }
    val tabIndex = nav.highlightKind.coerceIn(0, tabs.size - 1)
    val tab = tabs[tabIndex]
    var editing by remember { mutableStateOf(false) }
    val prefsFocus = remember { FocusRequester() }
    val prefs = SportPrefsStore.current
    val sport = tab is HighlightTab.Events && tab.kind == HighlightKind.SPORT

    BackHandler(enabled = editing) { editing = false }
    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (!editing && wasEditing) prefsFocus.requestWhenReady()
        wasEditing = editing
    }

    val selectedFocus = remember { FocusRequester() }
    if (editing) {
        FollowScreen(prefs, onDone = { editing = false })
        return
    }
    // A side menu on the left (the kinds of page and "Follow teams"), the content on the right. Going back into the menu
    // always lands on the page being shown.
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Column(
            Modifier
                .width(MenuWidth)
                .focusProperties { enter = { selectedFocus } },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tabs.forEachIndexed { index, item ->
                MenuItem(
                    text = tabLabel(item),
                    selected = index == tabIndex,
                    focusRequester = if (index == tabIndex) selectedFocus else null,
                    onClick = { onNav { it.copy(highlightKind = index) } },
                )
            }
            MenuItem(
                text = stringResource(R.string.sport_prefs_button),
                selected = false,
                focusRequester = prefsFocus,
                onClick = { editing = true },
            )
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            when {
                tab is HighlightTab.Events -> EventRows(data.events(tab.kind), nav, onNav, onPlay, prefs, likedGroups)
                else -> SuggestionRow(catalog, tab == HighlightTab.Movies, if (tab == HighlightTab.Movies) movieGroups else seriesGroups, nav, onNav)
            }
        }
    }
}

/** One entry of the side menu: the page shown is filled, the cursor adds the white border. */
@Composable
private fun MenuItem(text: String, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    GlassSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        focusRequester = focusRequester,
        selected = selected,
        focusScale = 1.04f,
    ) {
        PrismText(
            text = text,
            size = 20.sp,
            weight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

private const val SUGGESTION_GROUPS = 2

private sealed interface HighlightTab {
    data class Events(val kind: HighlightKind) : HighlightTab
    data object Movies : HighlightTab
    data object Series : HighlightTab
}

@Composable
private fun EventRows(
    all: List<HighlightEvent>,
    nav: LibraryNav,
    onNav: ((LibraryNav) -> LibraryNav) -> Unit,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    prefs: SportPrefs,
    likedGroups: Set<String>,
) {
    val nowMs = rememberMinuteNow()
    val minute = nowMs / 60_000L
    val query = nav.query.trim().lowercase()
    val events = remember(all, query) {
        all.filter { event ->
            query.isEmpty() || event.title.lowercase().contains(query) || event.channels.any { it.name.lowercase().contains(query) }
        }
    }
    val pageRows = remember(events, minute, prefs, likedGroups) { Highlights.pageRows(events, nowMs, prefs, likedGroups) }
    // The first event of the page (the one the viewer is most likely after) is shown large on top, once.
    val hero = pageRows.firstOrNull()?.events?.firstOrNull()
    val rows = remember(pageRows, hero) {
        if (hero == null) pageRows else pageRows.map { row -> row.copy(events = row.events.filter { it !== hero }) }.filter { it.events.isNotEmpty() }
    }
    val played = nav.played
    val heroPlayed = remember(hero, played) { hero != null && played != null && hero.channels.any { it.url == played } }
    val target = remember(rows, played) { locate(rows, played) }
    // The cursor goes to the large card when it was the one watched, and also when nothing was (arriving from a menu).
    val onHero = hero != null && (heroPlayed || played == null || rows.none { row -> row.events.any { e -> e.channels.any { it.url == played } } })
    val offset = if (hero != null) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (onHero) 0 else target.first + offset)
    val cardFocus = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    // A programme that has not started: OK sets or removes a reminder (a notification a few minutes before it begins).
    val toggleReminder = rememberReminderToggle()
    val reminders = dev.red74.prismplayer.reminders.ReminderStore.current
    var notice by remember { mutableStateOf<String?>(null) }
    val noticeOn = stringResource(R.string.reminder_toast_on)
    val noticeOff = stringResource(R.string.reminder_toast_off)
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }

    LaunchedEffect(nav.resume) {
        if (nav.resume && (rows.isNotEmpty() || hero != null)) {
            val index = if (onHero) 0 else target.first + offset
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            (if (onHero) heroFocus else cardFocus).requestWhenReady()
            onNav { it.copy(resume = false) }
        }
    }
    if (rows.isEmpty() && hero == null) {
        PrismText(text = stringResource(R.string.search_none), size = 20.sp, color = PrismColors.TextSecondary)
        return
    }
    // The notice floats over the rows: nothing moves when it appears, so the cursor never shifts.
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hero != null) item(key = "hero") {
            val onAir = hero.isLive(nowMs)
            val reminder = remember(hero) { reminderFor(hero.title, hero.startMs, hero.channels.first()) }
            HeroCard(
                event = hero,
                live = onAir,
                liked = pageRows.first().forYou,
                reminder = if (onAir) null else reminders.any { it.id == reminder.id },
                focusRequester = heroFocus,
                onClick = {
                    if (onAir) {
                        onNav { it.copy(resume = true) }
                        onPlay(hero.channels, 0, false)
                    } else {
                        notice = if (toggleReminder(reminder)) noticeOn else noticeOff
                    }
                },
            )
        }
        itemsIndexed(rows, key = { _, row -> "${row.forYou}-${row.live}-${row.day}" }) { rowIndex, row ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrismText(text = rowTitle(row), size = 24.sp, weight = FontWeight.Bold)
                val rowState = rememberLazyListState(initialFirstVisibleItemIndex = if (rowIndex == target.first) target.second else 0)
                LazyRow(
                    Modifier.fillMaxWidth(),
                    state = rowState,
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(row.events, key = { _, event -> event.title + event.startMs }) { column, event ->
                        val onAir = event.isLive(nowMs)
                        val reminder = remember(event) { reminderFor(event.title, event.startMs, event.channels.first()) }
                        EventCard(
                            event = event,
                            live = onAir,
                            liked = row.forYou,
                            reminder = if (onAir) null else reminders.any { it.id == reminder.id },
                            focusRequester = if (!onHero && rowIndex == target.first && column == target.second) cardFocus else null,
                            onClick = {
                                if (onAir) {
                                    onNav { it.copy(resume = true) }
                                    onPlay(event.channels, 0, false)
                                } else {
                                    notice = if (toggleReminder(reminder)) noticeOn else noticeOff
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    notice?.let {
        GlassPanel(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), cornerRadius = 18.dp) {
            PrismText(text = it, size = 18.sp, weight = FontWeight.SemiBold, color = PrismColors.Accent, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        }
    }
    }
}

private data class SuggestionKey(val catalog: Int, val movies: Boolean, val groups: List<String>, val day: Long)

/** The last few rows worked out, kept in memory for the run. */
private val suggestionCache = object : LinkedHashMap<SuggestionKey, List<Suggestion>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SuggestionKey, List<Suggestion>>?): Boolean = size > 6
}

private class Suggestion(val title: String, val group: String, val logo: String?, val key: String, val open: ((LibraryNav) -> LibraryNav))

/** Films or series from the categories watched most, not seen yet: "You might like". Worked out off the main thread. */
@Composable
private fun SuggestionRow(
    catalog: Catalog,
    movies: Boolean,
    groups: List<String>,
    nav: LibraryNav,
    onNav: ((LibraryNav) -> LibraryNav) -> Unit,
) {
    // Worked out once per list and day: coming back to the tab shows the same row at once.
    val cacheKey = remember(catalog, movies, groups) { SuggestionKey(System.identityHashCode(catalog), movies, groups, System.currentTimeMillis() / 86_400_000L) }
    val items by produceState<List<Suggestion>?>(suggestionCache[cacheKey], cacheKey) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { suggestionsFor(catalog, movies, groups) }.also { suggestionCache[cacheKey] = it }
        }
    }
    val focus = remember { FocusRequester() }
    val list = items
    LaunchedEffect(nav.resume, list) {
        if (nav.resume && !list.isNullOrEmpty()) {
            focus.requestWhenReady()
            onNav { it.copy(resume = false) }
        }
    }
    when {
        list == null -> PrismText(text = stringResource(R.string.favorites_loading), size = 20.sp, color = PrismColors.TextSecondary)
        list.isEmpty() -> PrismText(text = stringResource(R.string.suggestions_none), size = 20.sp, color = PrismColors.TextSecondary)
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PrismText(text = stringResource(R.string.suggestions_title), size = 20.sp, weight = FontWeight.SemiBold)
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(list, key = { _, s -> s.key }) { index, s ->
                    GlassSurface(
                        onClick = { onNav(s.open) },
                        modifier = Modifier.width(CardWidth).height(CardHeight),
                        focusRequester = if (index == 0) focus else null,
                        focusScale = 1.04f,
                    ) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Poster(s.logo, Modifier.width(72.dp).height(104.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PrismText(text = s.title, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 3)
                                PrismText(text = s.group, size = 15.sp, color = PrismColors.TextSecondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun suggestionsFor(catalog: Catalog, movies: Boolean, groups: List<String>): List<Suggestion> {
    val seen = Taste.seen()
    val day = System.currentTimeMillis() / 86_400_000L
    val out = ArrayList<Suggestion>()
    if (movies) {
        for (group in groups) {
            val index = catalog.movies.indexOfFirst { it.name == group }
            if (index < 0) continue
            val pool = catalog.channels(ContentType.MOVIE, listOf(index))
            for (film in Suggestions.pick(pool, { FavoriteKey.of(it) }, seen, day, PER_GROUP)) {
                out += Suggestion(VariantTags.of(film.name).title, group, film.logo, film.url) { nav ->
                    nav.copy(tab = LibraryTab.MOVIES, movieUrl = film.url, itemIndex = 0, resume = true, origin = nav.copy(resume = true, origin = null))
                }
            }
        }
    } else {
        for (group in groups) {
            val index = catalog.series.indexOfFirst { it.name == group }
            if (index < 0) continue
            val pool = catalog.shows(listOf(index))
            for (show in Suggestions.pick(pool, { FavoriteKey.ofShow(it) }, seen, day, PER_GROUP)) {
                out += Suggestion(VariantTags.of(show.title).title, group, show.logo, FavoriteKey.ofShow(show)) { nav ->
                    nav.copy(tab = LibraryTab.SERIES, showTitle = show.title, showGroup = show.group, season = -1, query = "", origin = nav.copy(resume = true, origin = null))
                }
            }
        }
    }
    return out.distinctBy { it.key }
}

private const val PER_GROUP = 6

/** Teams, riders and competitions offered to follow with one tap (any other word can be added below). */
internal val FOLLOW_SUGGESTIONS = listOf(
    "Juventus", "Inter", "Milan", "Napoli", "Roma", "Lazio", "Atalanta", "Fiorentina",
    "Real Madrid", "Barcellona", "Bayern", "Liverpool", "Ferrari", "Formula 1", "MotoGP", "Sinner",
    "Serie A", "Champions League", "NBA", "Nazionale",
)

/**
 * "Follow": one page, one grid. Tap a sport, a team or a competition to follow it (a filled star), tap again to stop;
 * the "For you" row of the Sport page then shows only what is followed. Any other name can be typed at the bottom.
 */
@Composable
private fun FollowScreen(prefs: SportPrefs, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    var word by remember { mutableStateOf("") }
    val add = {
        SportPrefsStore.update { it.withKeyword(word) }
        word = ""
    }
    val clean = remember(prefs) { prefs.keywords.toSet() }
    val names = remember(prefs) { FOLLOW_SUGGESTIONS + prefs.keywords.filter { k -> FOLLOW_SUGGESTIONS.none { Highlights.clean(it) == k } } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(text = stringResource(R.string.sport_prefs_done), onClick = onDone, focusRequester = first, emphasized = true)
            PrismText(text = stringResource(R.string.sport_prefs_hint), size = 18.sp, color = PrismColors.TextSecondary, maxLines = 2)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-sports") {
                PrismText(text = stringResource(R.string.follow_sports), size = 22.sp, weight = FontWeight.Bold)
            }
            items(SportTopic.entries.toList(), key = { it.name }) { topic ->
                val on = topic in prefs.topics
                FollowTile(topicLabel(topic), on) { SportPrefsStore.update { it.withTopic(topic, !on) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-teams") {
                PrismText(text = stringResource(R.string.follow_teams), size = 22.sp, weight = FontWeight.Bold)
            }
            items(names, key = { "t-$it" }) { name ->
                val key = Highlights.clean(name)
                val on = key in clean
                FollowTile(name, on) { SportPrefsStore.update { if (on) it.withoutKeyword(key) else it.withKeyword(name) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(
                value = word,
                onValueChange = { word = it.take(SportPrefs.MAX_LENGTH) },
                label = stringResource(R.string.sport_prefs_team),
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Done,
                onDone = { add() },
            )
            GlassButton(text = stringResource(R.string.sport_prefs_add), onClick = { add() })
        }
    }
}

/** One thing to follow: a star (filled when followed) and its name. */
@Composable
private fun FollowTile(label: String, followed: Boolean, onClick: () -> Unit) {
    GlassSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(68.dp),
        selected = followed,
        tint = if (followed) PrismColors.Accent else null,
        focusScale = 1.05f,
    ) {
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LineIcon(if (followed) LineIcon.STAR_ON else LineIcon.STAR, size = 26.dp, color = if (followed) PrismColors.Accent else PrismColors.TextSecondary)
            PrismText(text = label, size = 20.sp, weight = if (followed) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** Row and column of the card whose channel was played last, else the first card. */
internal fun locate(rows: List<HighlightRow>, played: String?): Pair<Int, Int> {
    if (played != null) {
        for ((rowIndex, row) in rows.withIndex()) {
            for ((column, event) in row.events.withIndex()) {
                if (event.channels.any { it.url == played }) return rowIndex to column
            }
        }
    }
    return 0 to 0
}

@Composable
private fun EventCard(event: HighlightEvent, live: Boolean, liked: Boolean, reminder: Boolean?, focusRequester: FocusRequester?, onClick: () -> Unit) {
    GlassSurface(
        onClick = onClick,
        modifier = Modifier.width(CardWidth).height(CardHeight),
        focusRequester = focusRequester,
        tint = if (live) PrismColors.Danger else PrismColors.Accent,
        focusScale = 1.06f,
        art = posterFor(event.kind),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            EventHeader(event, live, liked, compact = true)
            PrismText(text = event.title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
            EventAction(event, reminder, 15.sp)
        }
    }
}

/** The big card at the top of the page, like the featured banner of a streaming app. */
@Composable
private fun HeroCard(event: HighlightEvent, live: Boolean, liked: Boolean, reminder: Boolean?, focusRequester: FocusRequester?, onClick: () -> Unit) {
    GlassSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(HeroHeight).testTag("hero"),
        focusRequester = focusRequester,
        tint = PrismColors.Accent,
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        focusScale = 1.02f,
        art = posterFor(event.kind),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            EventHeader(event, live, liked)
            PrismText(text = event.title, size = 40.sp, weight = FontWeight.Bold, maxLines = 2)
            EventAction(event, reminder, 24.sp, strong = true)
        }
    }
}

@Composable
private fun EventHeader(event: HighlightEvent, live: Boolean, liked: Boolean, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TimeBadge(text = if (live) stringResource(R.string.highlights_on_air) else clockText(event.startMs), live = live)
        if (liked) {
            LineIcon(LineIcon.STAR_ON, size = 20.dp, color = PrismColors.Accent)
            if (!compact) PrismText(text = stringResource(R.string.highlights_for_you), size = 17.sp, weight = FontWeight.SemiBold, color = PrismColors.Accent, maxLines = 1)
        }
        val topic = event.topic?.let { topicLabel(it) }.orEmpty()
        if (topic.isNotEmpty() && !compact) PrismText(text = topic, size = 17.sp, color = PrismColors.TextSecondary, maxLines = 1)
    }
}

/** What OK does on a card: watch it now (on air) or set / remove the reminder (not started yet). */
@Composable
private fun EventAction(event: HighlightEvent, reminder: Boolean?, size: androidx.compose.ui.unit.TextUnit, strong: Boolean = false) {
    val more = event.channels.size - 1
    val weight = if (strong) FontWeight.SemiBold else FontWeight.Normal
    when (reminder) {
        null -> IconLabel(
            LineIcon.PLAY,
            stringResource(R.string.highlights_watch_on, event.channels.first().name) + if (more > 0) "  +$more" else "",
            size = size, color = if (strong) PrismColors.TextPrimary else PrismColors.TextSecondary, weight = weight,
        )
        true -> IconLabel(LineIcon.BELL_ON, stringResource(R.string.reminder_active), size = size, color = PrismColors.Accent, weight = FontWeight.SemiBold)
        false -> IconLabel(LineIcon.BELL, stringResource(R.string.reminder_remind), size = size, color = if (strong) PrismColors.TextPrimary else PrismColors.TextSecondary, weight = weight)
    }
}

@Composable
private fun TimeBadge(text: String, live: Boolean) {
    val color = if (live) PrismColors.Danger else PrismColors.Accent
    Box(Modifier.background(color.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        PrismText(text = text, size = 17.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun clockText(ms: Long): String {
    val context = LocalContext.current
    return remember(ms) { DateFormat.getTimeFormat(context).format(Date(ms)) }
}

@Composable
private fun rowTitle(row: HighlightRow): String = when {
    row.forYou -> stringResource(R.string.highlights_for_you)
    row.live -> stringResource(R.string.highlights_now)
    row.day == 0 -> stringResource(R.string.highlights_today)
    row.day == 1 -> stringResource(R.string.highlights_tomorrow)
    else -> remember(row) {
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(row.events.first().startMs))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

@Composable
private fun tabLabel(tab: HighlightTab): String = stringResource(
    when (tab) {
        is HighlightTab.Events -> when (tab.kind) {
            HighlightKind.SPORT -> R.string.highlights_sport
            HighlightKind.FILM -> R.string.highlights_film
            HighlightKind.ENTERTAINMENT -> R.string.highlights_entertainment
        }
        HighlightTab.Movies -> R.string.suggestions_movies
        HighlightTab.Series -> R.string.suggestions_series
    },
)

@Composable
private fun topicLabel(topic: SportTopic): String = stringResource(
    when (topic) {
        SportTopic.FOOTBALL -> R.string.topic_football
        SportTopic.TENNIS -> R.string.topic_tennis
        SportTopic.MOTORS -> R.string.topic_motors
        SportTopic.BASKETBALL -> R.string.topic_basketball
        SportTopic.RUGBY -> R.string.topic_rugby
        SportTopic.CYCLING -> R.string.topic_cycling
        SportTopic.VOLLEYBALL -> R.string.topic_volleyball
        SportTopic.GOLF -> R.string.topic_golf
        SportTopic.COMBAT -> R.string.topic_combat
        SportTopic.AMERICAN_FOOTBALL -> R.string.topic_american_football
        SportTopic.HOCKEY -> R.string.topic_hockey
        SportTopic.OLYMPICS -> R.string.topic_olympics
    },
)

@Composable
private fun rememberMinuteNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}
