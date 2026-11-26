package dev.red74.prismplayer.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.red74.prismplayer.data.Taste
import dev.red74.prismplayer.data.VariantTags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.widthIn
import dev.red74.prismplayer.data.Novelty
import dev.red74.prismplayer.data.VodPlatforms
import dev.red74.prismplayer.data.WatchProgress
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope

/** The featured events found in the guide; filled in the background by the view model. */
val LocalHighlights = compositionLocalOf { HighlightsData.Empty }

private val CardWidth = 240.dp
private val CardHeight = 138.dp
private val HeroHeight = 226.dp
private val MenuWidth = 210.dp

/** How long the cursor rests on a card before its preview starts (Netflix, Disney+ and Apple TV wait about two seconds). */
internal const val PREVIEW_DWELL_MS = 2_000L

/**
 * Title size in sp for a text of [length] characters: long titles get smaller type instead of being cut or shouting.
 * [narrow] is for the large card while its preview takes part of the width.
 */
internal fun titleSp(length: Int, large: Boolean, narrow: Boolean = false): Int {
    val n = if (narrow) length * 3 / 2 else length
    return if (large) when {
        n <= 28 -> 30
        n <= 44 -> 26
        n <= 64 -> 22
        else -> 20
    } else when {
        n <= 22 -> 18
        n <= 36 -> 16
        n <= 52 -> 14
        else -> 13
    }
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
    val likedGroups = remember(tasteVersion) { Taste.top(ContentType.LIVE, 3).toSet() }
    val tabs = remember(kinds, catalog) {
        kinds.map { HighlightTab.Events(it) } +
            // The events of the followed teams (chosen on the Favourites page), only for a list with live events.
            (if (kinds.isNotEmpty()) listOf(HighlightTab.Following) else emptyList()) +
            (if (catalog.movies.isNotEmpty()) listOf(HighlightTab.Movies) else emptyList()) +
            (if (catalog.series.isNotEmpty()) listOf(HighlightTab.Series) else emptyList())
    }
    if (tabs.isEmpty()) {
        // Still looking for the events (the guide is being read): placeholders, not an "empty" message.
        if (LocalHighlightsLoading.current) HighlightsSkeleton()
        else PrismText(text = stringResource(R.string.highlights_empty), size = 20.sp, color = PrismColors.TextSecondary)
        return
    }
    val tabIndex = nav.highlightKind.coerceIn(0, tabs.size - 1)
    val tab = tabs[tabIndex]
    val prefs = SportPrefsStore.current

    val selectedFocus = remember { FocusRequester() }
    // The entry the cursor has rested on for a moment: the page on the right follows it by itself (OK is not needed),
    // as the large card follows a live card. A quick pass over the menu changes nothing.
    var menuFocus by remember { mutableIntStateOf(-1) }
    var pendingEnter by remember { mutableIntStateOf(-1) }
    var enterHero by remember { mutableIntStateOf(0) }
    LaunchedEffect(menuFocus, tabIndex) {
        if (menuFocus in tabs.indices && menuFocus != tabIndex) {
            delay(MENU_DWELL_MS)
            onNav { it.copy(highlightKind = menuFocus) }
            pendingEnter = menuFocus
        }
    }
    // After the page followed the cursor by itself and the cursor still rests on that entry, it goes on to the large
    // card so its preview starts. Never when the viewer just came back to the menu or keeps moving along it.
    LaunchedEffect(pendingEnter, menuFocus) {
        if (pendingEnter >= 0 && menuFocus == pendingEnter && pendingEnter == tabIndex) {
            delay(HERO_ENTER_MS)
            enterHero++
            pendingEnter = -1
        } else if (menuFocus != pendingEnter) {
            pendingEnter = -1
        }
    }
    // A side menu on the left (the kinds of page), the content on the right. Going back into the menu always lands on
    // the page being shown.
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Column(
            Modifier
                .width(MenuWidth)
                .onFocusChanged { if (!it.hasFocus) menuFocus = -1 }
                .focusProperties { enter = { selectedFocus } },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tabs.forEachIndexed { index, item ->
                MenuItem(
                    text = tabLabel(item),
                    selected = index == tabIndex,
                    focusRequester = if (index == tabIndex) selectedFocus else null,
                    onFocused = { menuFocus = index },
                    onClick = { onNav { it.copy(highlightKind = index) } },
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            when {
                tab is HighlightTab.Events -> EventRows(data.events(tab.kind), nav, onNav, onPlay, prefs, likedGroups, enterHero)
                tab == HighlightTab.Following -> FollowedRows(kinds.flatMap { data.events(it) }, prefs, nav, onNav, onPlay, enterHero)
                else -> NewArrivals(catalog, tab == HighlightTab.Movies, nav, onNav)
            }
        }
    }
}

/** One entry of the side menu: the page shown is filled, the cursor adds the white border. */
@Composable
private fun MenuItem(text: String, selected: Boolean, focusRequester: FocusRequester?, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    GlassSurface(
        onClick = onClick,
        onFocused = onFocused,
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


private sealed interface HighlightTab {
    data class Events(val kind: HighlightKind) : HighlightTab
    data object Following : HighlightTab
    data object Movies : HighlightTab
    data object Series : HighlightTab
}

/** Seconds the cursor rests on a menu entry before the page follows it. */
internal const val MENU_DWELL_MS = 500L

/** After the page changed, how long the cursor must still rest on the menu entry before it goes to the large card. */
internal const val HERO_ENTER_MS = 600L

/**
 * The events of the sports and teams the viewer follows, on air now or later, laid out like the Sport, Film and
 * Entertainment pages (same rows, same priority for DAZN and Sky). Nothing followed yet: a hint to the Favourites page.
 */
@Composable
private fun FollowedRows(
    all: List<HighlightEvent>,
    prefs: SportPrefs,
    nav: LibraryNav,
    onNav: ((LibraryNav) -> LibraryNav) -> Unit,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    enter: Int,
) {
    val followed = remember(all, prefs) { if (prefs.isEmpty) emptyList() else all.filter { prefs.matches(it) } }
    if (followed.isEmpty()) {
        PrismText(
            text = stringResource(if (prefs.isEmpty) R.string.following_choose else R.string.following_none),
            size = 20.sp,
            color = PrismColors.TextSecondary,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        return
    }
    // The followed events are the whole page, so no "For you" row is added on top of them.
    EventRows(followed, nav, onNav, onPlay, SportPrefs(), emptySet(), enter)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRows(
    all: List<HighlightEvent>,
    nav: LibraryNav,
    onNav: ((LibraryNav) -> LibraryNav) -> Unit,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    prefs: SportPrefs,
    likedGroups: Set<String>,
    enter: Int = 0,
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
    // The large card is always something on air right now (the most awaited of them, in page order); only when nothing
    // is on air does it fall back to the first event of the page.
    val defaultHero = pageRows.firstNotNullOfOrNull { row -> row.events.firstOrNull { it.isLive(nowMs) } }
        ?: pageRows.firstOrNull()?.events?.firstOrNull()
    // A card the cursor rested on for a while takes the place of the large one (with its preview).
    var promoted by remember { mutableStateOf<HighlightEvent?>(null) }
    val hero = promoted?.takeIf { p -> pageRows.any { row -> p in row.events } } ?: defaultHero
    val rows = remember(pageRows, hero) {
        if (hero == null) pageRows else pageRows.map { row -> row.copy(events = row.events.filter { it != hero }) }.filter { it.events.isNotEmpty() }
    }
    // The event the cursor is on (null when it is elsewhere) and whether the large card is playing its preview.
    var focusedEvent by remember { mutableStateOf<HighlightEvent?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var refocus by remember { mutableIntStateOf(0) }
    // The row the cursor is on (-1: the large card), so the page moves up or down once per row change and never when the cursor moves along a row.
    var focusedRow by remember { mutableIntStateOf(-2) }
    val played = nav.played
    val heroPlayed = remember(hero, played) { hero != null && played != null && hero.channels.any { it.url == played } }
    val target = remember(rows, played) { locate(rows, played) }
    // The cursor goes to the large card when it was the one watched, and also when nothing was (arriving from a menu).
    val onHero = hero != null && (heroPlayed || played == null || rows.none { row -> row.events.any { e -> e.channels.any { it.url == played } } })
    val offset = if (hero != null) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (onHero) 0 else target.first + offset)
    val cardFocus = remember { FocusRequester() }
    // Moving along a row slides the row by exactly one card and keeps the cursor on the same spot (like the big apps do).
    val density = LocalDensity.current
    val anchoredRow = remember(density) { AnchoredScroll(with(density) { 4.dp.toPx() }) }
    val heroFocus = remember { FocusRequester() }
    // The first card of the first row (the large card's "Down" goes there), and the sideways position of every row.
    val firstFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rowStates = remember { HashMap<String, androidx.compose.foundation.lazy.LazyListState>() }
    val firstRequester = if (!onHero && target.first == 0 && target.second == 0) cardFocus else firstFocus
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

    LaunchedEffect(focusedEvent, hero, nowMs / 60_000L) {
        val f = focusedEvent
        val live = f != null && f.isLive(nowMs)
        if (f == null || !live) {
            previewing = false
            return@LaunchedEffect
        }
        if (f == hero) {
            if (!previewing) {
                delay(PREVIEW_DWELL_MS)
                previewing = true
            }
        } else {
            previewing = false
            delay(PREVIEW_DWELL_MS)
            // The card goes up into the large one and the cursor follows it, so the next move starts from there.
            promoted = f
            previewing = true
            refocus++
        }
    }

    // The cursor follows the promoted card. It runs in its own effect: the promotion changes `hero`, which restarts the
    // effect above, and a focus move made there was cancelled half way (the cursor ended on "Indietro").
    var heroHasFocus by remember { mutableStateOf(false) }
    // The menu asks to move on to the large card (the page followed the cursor and the cursor rested there).
    LaunchedEffect(enter) {
        if (enter == 0 || hero == null) return@LaunchedEffect
        listState.scrollToItem(0)
        repeat(60) {
            withFrameNanos { }
            if (heroHasFocus) return@LaunchedEffect
            runCatching { heroFocus.requestFocus() }
        }
    }

    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        repeat(60) {
            withFrameNanos { }
            if (heroHasFocus) return@LaunchedEffect
            runCatching { heroFocus.requestFocus() }
        }
    }

    LaunchedEffect(focusedRow) {
        if (focusedRow < -1) return@LaunchedEffect
        val index = if (focusedRow < 0) 0 else focusedRow + offset
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
        val delta = when {
            item.offset < info.viewportStartOffset -> (item.offset - info.viewportStartOffset).toFloat()
            item.offset + item.size > info.viewportEndOffset -> (item.offset + item.size - info.viewportEndOffset).toFloat()
            else -> 0f
        }
        if (delta != 0f) listState.animateScrollBy(delta, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
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
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
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
                liked = pageRows.firstOrNull { hero in it.events }?.forYou == true,
                reminder = if (onAir) null else reminders.any { it.id == reminder.id },
                focusRequester = heroFocus,
                previewing = previewing,
                onDown = {
                    val first = rows.firstOrNull()
                    if (first == null) false else {
                        // The first row may have slid to the side: bring it back to its first card, then go there.
                        scope.launch {
                            rowStates["${first.forYou}-${first.live}-${first.day}"]?.scrollToItem(0)
                            withFrameNanos { }
                            withFrameNanos { }
                            runCatching { firstRequester.requestFocus() }
                        }
                        true
                    }
                },
                onFocusChange = { has ->
                    heroHasFocus = has
                    if (has) focusedRow = -1
                    focusedEvent = if (has) hero else if (focusedEvent == hero) null else focusedEvent
                },
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
                val rowState = rowStates.getOrPut("${row.forYou}-${row.live}-${row.day}") {
                    androidx.compose.foundation.lazy.LazyListState(if (rowIndex == target.first) target.second else 0)
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides anchoredRow) {
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
                            focusRequester = when {
                                !onHero && rowIndex == target.first && column == target.second -> cardFocus
                                rowIndex == 0 && column == 0 -> firstFocus
                                else -> null
                            },
                            onFocusChange = { has ->
                                if (has) focusedRow = rowIndex
                                focusedEvent = if (has) event else if (focusedEvent == event) null else focusedEvent
                            },
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
    }
    }
    notice?.let {
        GlassPanel(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), cornerRadius = 18.dp) {
            PrismText(text = it, size = 18.sp, weight = FontWeight.SemiBold, color = PrismColors.Accent, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        }
    }
    }
}

/** The page never scrolls by itself when the cursor moves: [HighlightsView] moves it once per change of row. */
@OptIn(ExperimentalFoundationApi::class)
internal object NoAutoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Keeps the card with the cursor at a fixed place ([anchor], the start of the row): a step to the side slides the row by one card. */
@OptIn(ExperimentalFoundationApi::class)
internal class AnchoredScroll(private val anchor: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - anchor
}

private data class NewArrivalsKey(val catalog: Int, val movies: Boolean, val hour: Long)

/** The last few rows worked out, kept in memory for the run. */
private val arrivalsCache = object : LinkedHashMap<NewArrivalsKey, List<Arrival>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NewArrivalsKey, List<Arrival>>?): Boolean = size > 6
}

private class Arrival(val title: String, val group: String, val logo: String?, val key: String, val open: ((LibraryNav) -> LibraryNav))

/**
 * Films or series that came into the list in the last 7 days, newest first (see [dev.red74.prismplayer.data.Novelty]),
 * never from groups whose name has "XXX". Read from the list database off the main thread.
 */
@Composable
private fun NewArrivals(
    catalog: Catalog,
    movies: Boolean,
    nav: LibraryNav,
    onNav: ((LibraryNav) -> LibraryNav) -> Unit,
) {
    // Worked out once per list and hour: coming back to the tab shows the same cards at once.
    val cacheKey = remember(catalog, movies) { NewArrivalsKey(System.identityHashCode(catalog), movies, System.currentTimeMillis() / 3_600_000L) }
    val items by produceState(arrivalsCache[cacheKey], cacheKey) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { arrivalsFor(catalog, movies) }.also { arrivalsCache[cacheKey] = it }
        }
    }
    val focus = remember { FocusRequester() }
    val gridFirst = remember { FocusRequester() }
    val rowFirst = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val progressVersion = WatchProgress.version
    // The films left half way, the latest first (series keep their own "resume" inside the show page).
    val resumes by produceState(emptyList<Resume>(), catalog, movies, progressVersion) {
        value = if (!movies) emptyList() else withContext(Dispatchers.IO) { resumesFor(catalog) }
    }
    val list = items
    LaunchedEffect(nav.resume, list) {
        if (nav.resume && !list.isNullOrEmpty()) {
            focus.requestWhenReady()
            onNav { it.copy(resume = false) }
        }
    }
    when {
        list == null -> PrismText(text = stringResource(R.string.favorites_loading), size = 20.sp, color = PrismColors.TextSecondary)
        list.isEmpty() -> PrismText(
            text = stringResource(if (movies) R.string.new_none_movies else R.string.new_none_series),
            size = 20.sp,
            color = PrismColors.TextSecondary,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        else -> {
            val resumeKeys = resumes.map { it.arrival.key }.toSet()
            // a film already in "Continue watching" is not repeated in the grid
            val rest = list.drop(1).filter { it.key !in resumeKeys }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ArrivalHero(
                    arrival = list.first(),
                    focusRequester = focus,
                    // Down always lands on the FIRST card below, never on the nearest one.
                    onDown = {
                        when {
                            resumes.isNotEmpty() -> {
                                scope.launch {
                                    rowState.scrollToItem(0)
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    runCatching { rowFirst.requestFocus() }
                                }
                                true
                            }
                            rest.isNotEmpty() -> runCatching { gridFirst.requestFocus() }.isSuccess
                            else -> false
                        }
                    },
                    onOpen = { onNav(list.first().open) },
                )
                if (resumes.isNotEmpty()) {
                    PrismText(text = stringResource(R.string.continue_watching), size = 20.sp, weight = FontWeight.SemiBold)
                    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                        itemsIndexed(resumes, key = { _, r -> r.arrival.key }) { index, r ->
                            GlassSurface(
                                onClick = { onNav(r.arrival.open) },
                                modifier = Modifier.width(CardWidth).height(ResumeHeight),
                                focusRequester = if (index == 0) rowFirst else null,
                                focusScale = 1.04f,
                            ) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Poster(r.arrival.logo, Modifier.width(48.dp).height(70.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PrismText(text = r.arrival.title, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 2)
                                        ProgressLine(r.fraction, Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
                if (rest.isNotEmpty()) {
                    PrismText(text = stringResource(R.string.new_title, list.size), size = 20.sp, weight = FontWeight.SemiBold)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(CardWidth),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(rest, key = { _, a -> a.key }) { index, a ->
                            GlassSurface(
                                onClick = { onNav(a.open) },
                                modifier = Modifier.height(CardHeight),
                                focusRequester = if (index == 0) gridFirst else null,
                                focusScale = 1.04f,
                            ) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Poster(a.logo, Modifier.width(72.dp).height(104.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        PrismText(text = a.title, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 3)
                                        PrismText(text = a.group, size = 15.sp, color = PrismColors.TextSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ResumeHeight = 96.dp

private class Resume(val arrival: Arrival, val fraction: Float)

/** The large card at the top of the Films and Series pages: the first arrival, with its poster glowing behind it. */
@Composable
private fun ArrivalHero(arrival: Arrival, focusRequester: FocusRequester, onDown: () -> Boolean, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(172.dp).clip(RoundedCornerShape(24.dp)).background(PrismColors.Bg1.copy(alpha = 0.6f))) {
        AmbientBackdrop(arrival.logo)
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(arrival.logo, Modifier.fillMaxHeight().aspectRatio(2f / 3f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismText(text = arrival.group, size = 16.sp, color = PrismColors.Accent, maxLines = 1)
                PrismText(text = arrival.title, size = 32.sp, weight = FontWeight.Bold, maxLines = 2)
                GlassButton(
                    text = stringResource(R.string.new_open),
                    onClick = onOpen,
                    focusRequester = focusRequester,
                    emphasized = true,
                    modifier = Modifier.onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown() },
                )
            }
        }
    }
}

/** Films with a saved resume point, the most recently watched first. */
private fun resumesFor(catalog: Catalog): List<Resume> {
    val keys = WatchProgress.keys()
    if (keys.isEmpty()) return emptyList()
    return catalog.favorites(keys).filter { it.type == ContentType.MOVIE && it.url.isNotEmpty() }
        .mapNotNull { film -> WatchProgress.get(film.url)?.let { film to it } }
        .sortedByDescending { it.second.savedAt }
        .take(RESUME_LIMIT)
        .map { (film, point) -> Resume(filmArrival(film), point.fraction) }
}

private fun filmArrival(film: Channel): Arrival =
    Arrival(VariantTags.of(film.name).title, film.group.orEmpty(), film.logo, film.url) { nav ->
        nav.copy(tab = LibraryTab.MOVIES, movieUrl = film.url, itemIndex = 0, resume = true, origin = nav.copy(resume = true, origin = null))
    }

private const val RESUME_LIMIT = 12

private fun arrivalsFor(catalog: Catalog, movies: Boolean): List<Arrival> {
    val since = System.currentTimeMillis() - Novelty.WINDOW_MS
    return if (movies) {
        catalog.recentMovies(since, ARRIVALS_LIMIT).filter { it.url.isNotEmpty() }.distinctBy { it.url }.sortedBy { VodPlatforms.rank(it.group) }.map(::filmArrival)
    } else {
        catalog.recentShows(since, ARRIVALS_LIMIT).distinctBy { FavoriteKey.ofShow(it) }.sortedBy { VodPlatforms.rank(it.group) }.map { show ->
            Arrival(VariantTags.of(show.title).title, show.group, show.logo, FavoriteKey.ofShow(show)) { nav ->
                nav.copy(tab = LibraryTab.SERIES, showTitle = show.title, showGroup = show.group, season = -1, query = "", origin = nav.copy(resume = true, origin = null))
            }
        }
    }
}

private const val ARRIVALS_LIMIT = 60

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
internal fun FollowScreen(prefs: SportPrefs, onDone: () -> Unit) {
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
private fun EventCard(
    event: HighlightEvent, live: Boolean, liked: Boolean, reminder: Boolean?, focusRequester: FocusRequester?,
    onFocusChange: (Boolean) -> Unit, onClick: () -> Unit,
) {
    GlassSurface(
        onClick = onClick,
        modifier = Modifier.width(CardWidth).height(CardHeight).onFocusChanged { onFocusChange(it.hasFocus) },
        focusRequester = focusRequester,
        tint = if (live) PrismColors.Danger else PrismColors.Accent,
        focusScale = 1.06f,
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            EventHeader(event, live, liked, compact = true)
            PrismText(text = event.title, size = titleSp(event.title.length, large = false).sp, weight = FontWeight.Bold, maxLines = 2)
            EventAction(event, reminder, 14.sp)
        }
    }
}

/**
 * The big card at the top of the page, like the featured banner of a streaming app. For an event on air the right side
 * is kept for the channel's preview, which starts after the cursor has rested here for a moment ([previewing]); the
 * space is there from the start, so nothing moves when the picture arrives. The title adapts to its length.
 */
@Composable
private fun HeroCard(
    event: HighlightEvent, live: Boolean, liked: Boolean, reminder: Boolean?, focusRequester: FocusRequester?,
    previewing: Boolean, onFocusChange: (Boolean) -> Unit, onDown: () -> Boolean, onClick: () -> Unit,
) {
    GlassSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(HeroHeight).testTag("hero")
            .onFocusChanged { onFocusChange(it.hasFocus) }
            // "Down" from the large card always goes to the first card of the row below, not to whichever card is nearest.
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown() },
        focusRequester = focusRequester,
        tint = PrismColors.Accent,
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        focusScale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                EventHeader(event, live, liked)
                PrismText(
                    text = event.title,
                    size = titleSp(event.title.length, large = true, narrow = live).sp,
                    weight = FontWeight.Bold,
                    maxLines = 3,
                )
                EventAction(event, reminder, 18.sp, strong = true)
            }
            if (live) {
                HeroPreview(event.channels.first(), active = previewing, Modifier.fillMaxHeight().testTag("heroPreview"))
            }
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
        HighlightTab.Following -> R.string.highlights_following
        HighlightTab.Movies -> R.string.new_movies
        HighlightTab.Series -> R.string.new_series
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
