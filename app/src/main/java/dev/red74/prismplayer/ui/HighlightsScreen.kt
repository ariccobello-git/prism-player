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
import androidx.compose.ui.platform.LocalContext
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

private val CardWidth = 290.dp
private val CardHeight = 132.dp

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
        PrismText(text = stringResource(R.string.highlights_empty), size = 20.sp, color = PrismColors.TextSecondary)
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tabs.forEachIndexed { index, item ->
                    GlassButton(
                        text = tabLabel(item),
                        onClick = { onNav { it.copy(highlightKind = index) } },
                        emphasized = index == tabIndex,
                    )
                }
                if (sport) {
                    GlassButton(
                        text = stringResource(R.string.sport_prefs_button),
                        onClick = { editing = true },
                        focusRequester = prefsFocus,
                    )
                }
            }
        }
        when {
            editing -> SportPrefsEditor(prefs, onDone = { editing = false })
            tab is HighlightTab.Events -> EventRows(data.events(tab.kind), nav, onNav, onPlay, prefs, likedGroups)
            else -> SuggestionRow(catalog, tab == HighlightTab.Movies, if (tab == HighlightTab.Movies) movieGroups else seriesGroups, nav, onNav)
        }
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
    val rows = remember(events, minute, prefs, likedGroups) { Highlights.pageRows(events, nowMs, prefs, likedGroups) }
    val played = nav.played
    val target = remember(rows, played) { locate(rows, played) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = target.first)
    val cardFocus = remember { FocusRequester() }

    LaunchedEffect(nav.resume) {
        if (nav.resume && rows.isNotEmpty()) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target.first }) listState.scrollToItem(target.first)
            cardFocus.requestWhenReady()
            onNav { it.copy(resume = false) }
        }
    }
    if (rows.isEmpty()) {
        PrismText(text = stringResource(R.string.search_none), size = 20.sp, color = PrismColors.TextSecondary)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> "${row.forYou}-${row.live}-${row.day}" }) { rowIndex, row ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrismText(text = rowTitle(row), size = 20.sp, weight = FontWeight.SemiBold)
                val rowState = rememberLazyListState(initialFirstVisibleItemIndex = if (rowIndex == target.first) target.second else 0)
                LazyRow(
                    Modifier.fillMaxWidth(),
                    state = rowState,
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(row.events, key = { _, event -> event.title + event.startMs }) { column, event ->
                        EventCard(
                            event = event,
                            live = event.isLive(nowMs),
                            liked = row.forYou,
                            focusRequester = if (rowIndex == target.first && column == target.second) cardFocus else null,
                            onClick = {
                                onNav { it.copy(resume = true) }
                                onPlay(event.channels, 0, false)
                            },
                        )
                    }
                }
            }
        }
    }
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
    val items by produceState<List<Suggestion>?>(null, catalog, movies, groups) {
        value = withContext(Dispatchers.IO) { suggestionsFor(catalog, movies, groups) }
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

/** Choose the sports followed and add teams or competitions to follow. */
@Composable
private fun SportPrefsEditor(prefs: SportPrefs, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    var word by remember { mutableStateOf("") }
    val add = {
        SportPrefsStore.update { it.withKeyword(word) }
        word = ""
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(text = stringResource(R.string.sport_prefs_done), onClick = onDone, focusRequester = first, emphasized = true)
            PrismText(text = stringResource(R.string.sport_prefs_hint), size = 18.sp, color = PrismColors.TextSecondary, maxLines = 2)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().height(230.dp),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SportTopic.entries.toList(), key = { it.name }) { topic ->
                val on = topic in prefs.topics
                GlassButton(
                    text = (if (on) "✓ " else "") + topicLabel(topic),
                    onClick = { SportPrefsStore.update { it.withTopic(topic, !on) } },
                    modifier = Modifier.fillMaxWidth(),
                    emphasized = on,
                )
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
        if (prefs.keywords.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(prefs.keywords, key = { it }) { keyword ->
                    GlassButton(text = "✕ $keyword", onClick = { SportPrefsStore.update { it.withoutKeyword(keyword) } }, emphasized = true)
                }
            }
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
private fun EventCard(event: HighlightEvent, live: Boolean, liked: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val channel = event.channels.first().name
    val more = event.channels.size - 1
    GlassSurface(
        onClick = onClick,
        modifier = Modifier.width(CardWidth).height(CardHeight),
        focusRequester = focusRequester,
        focusScale = 1.04f,
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeBadge(text = if (live) stringResource(R.string.highlights_on_air) else clockText(event.startMs), live = live)
                val topicText = (if (liked) "★ " else "") + (event.topic?.let { topicLabel(it) }.orEmpty())
                if (topicText.isNotEmpty()) PrismText(text = topicText, size = 16.sp, color = PrismColors.TextSecondary, maxLines = 1)
            }
            PrismText(text = event.title, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 2)
            PrismText(
                text = "▶ " + channel + if (more > 0) "  +$more" else "",
                size = 16.sp,
                color = PrismColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TimeBadge(text: String, live: Boolean) {
    val color = if (live) PrismColors.Danger else PrismColors.Accent
    Box(Modifier.background(color.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        PrismText(text = text, size = 15.sp, weight = FontWeight.Bold, maxLines = 1)
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
