package dev.red74.prismplayer.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryState
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.Category
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.FavoriteKey
import dev.red74.prismplayer.data.GroupTree
import dev.red74.prismplayer.data.SortMode
import dev.red74.prismplayer.data.sortedBy
import dev.red74.prismplayer.data.NowNext
import dev.red74.prismplayer.data.Show
import dev.red74.prismplayer.player.PreviewPlayers
import dev.red74.prismplayer.player.createPlayer
import dev.red74.prismplayer.player.previewItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

private const val SEARCH_LIMIT = 500
private const val PREVIEW_DELAY_MS = 700L

private typealias NavChange = ((LibraryNav) -> LibraryNav) -> Unit

/** Contents marked unavailable (see [dev.red74.prismplayer.data.Availability]) and the preview's report of a good start. */
private class Reachability(val unavailable: Set<String>, val onPreviewWorked: (Channel) -> Unit)

private val LocalReachability = androidx.compose.runtime.compositionLocalOf { Reachability(emptySet()) {} }

private const val UNAVAILABLE_SIGN = "⊘"
private const val UNAVAILABLE_ALPHA = 0.45f

@Composable
fun LibraryScreen(
    state: LibraryState,
    nav: LibraryNav,
    epg: EpgData,
    favorites: Set<String>,
    onNav: NavChange,
    onPlay: (items: List<Channel>, index: Int, follow: Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    unavailable: Set<String> = emptySet(),
    onPreviewWorked: (Channel) -> Unit = {},
) {
    val reachability = remember(unavailable, onPreviewWorked) { Reachability(unavailable, onPreviewWorked) }
    androidx.compose.runtime.CompositionLocalProvider(LocalReachability provides reachability) {
        LibraryContent(state, nav, epg, favorites, onNav, onPlay, onToggleFavorite, onRetry, onBack)
    }
}

@Composable
private fun LibraryContent(
    state: LibraryState,
    nav: LibraryNav,
    epg: EpgData,
    favorites: Set<String>,
    onNav: NavChange,
    onPlay: (items: List<Channel>, index: Int, follow: Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    RequestFocusOnEnter(retryFocus, key = state.error)
    // The tab row takes the cursor on entry, except when coming back to a content (the item played, a detail page):
    // both asking at once left the cursor on the tabs instead of the channel just watched.
    LaunchedEffect(state.loading) {
        if (nav.resume || nav.movieUrl != null || nav.showTitle != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { tabFocus.requestFocus() }
    }

    // A search never survives leaving the screen or opening an item: coming back finds the field empty.
    LaunchedEffect(Unit) {
        if (nav.query.isNotEmpty()) onNav { it.copy(query = "", itemIndex = 0, resume = false) }
    }
    LaunchedEffect(nav.movieUrl, nav.showTitle) {
        if ((nav.movieUrl != null || nav.showTitle != null) && nav.query.isNotEmpty()) onNav { it.copy(query = "") }
    }

    // Back always goes up exactly one level: detail, then the opened category, then a search, then the list screen.
    // Levels: tab row (tabs + search) above, content below. Back from the content goes up to the tab row; Back from
    // the tab row clears a search first and then leaves the screen. OK on a tab goes down into the content.
    var contentFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = nav.query.isNotEmpty() && !contentFocused) {
        UiSounds.play(UiSounds.Kind.BACK)
        onNav { it.copy(query = "", itemIndex = 0, resume = false) }
    }
    BackHandler(enabled = contentFocused) {
        UiSounds.play(UiSounds.Kind.BACK)
        runCatching { tabFocus.requestFocus() }
    }
    // A request to focus the content that found nothing to focus (empty tab) must not linger.
    LaunchedEffect(nav.resume) {
        if (nav.resume) {
            kotlinx.coroutines.delay(1_500)
            onNav { it.copy(resume = false) }
        }
    }
    // Levels: tab row > categories > items > detail. Back from the items returns to the category list.
    val groupNames = remember(state.catalog, nav.tab) {
        when (nav.tab) {
            LibraryTab.LIVE -> state.catalog.live.map { it.name }
            LibraryTab.MOVIES -> state.catalog.movies.map { it.name }
            LibraryTab.SERIES -> state.catalog.series.map { it.name }
            LibraryTab.FAVORITES -> emptyList()
        }
    }
    val groupTree = remember(groupNames) { GroupTree.build(groupNames) }
    val currentMain = groupTree.getOrNull(nav.category)
    val levelUp = GroupTree.levelUp(nav.level, currentMain?.hasSubLevel == true)
    BackHandler(enabled = contentFocused && levelUp >= 0 && nav.query.isEmpty() && nav.tab != LibraryTab.FAVORITES &&
        nav.movieUrl == null && nav.showTitle == null) {
        UiSounds.play(UiSounds.Kind.BACK)
        onNav { it.copy(level = levelUp, resume = true) }
    }
    BackHandler(enabled = nav.movieUrl != null || nav.showTitle != null) {
        UiSounds.play(UiSounds.Kind.BACK)
        onNav { it.copy(movieUrl = null, showTitle = null, showGroup = null, season = -1, resume = true) }
    }

    PrismBackground {
        GlassPanel(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().then(if (state.loading || state.error != null) Modifier else Modifier.depthFocus().confineFocus()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack)
                    val crumb = if (nav.level >= 1 && nav.query.isEmpty() && currentMain != null) {
                        val mainName = currentMain.name.ifBlank { stringResource(R.string.category_other) }
                        if (nav.level >= 2 && currentMain.hasSubLevel && nav.sub > 0) {
                            mainName + "  ›  " + currentMain.subs.getOrNull(nav.sub - 1)?.name.orEmpty()
                        } else mainName
                    } else null
                    PrismText(
                        text = if (crumb.isNullOrBlank()) state.playlistName else state.playlistName + "  ›  " + crumb,
                        size = 30.sp, weight = FontWeight.Bold, maxLines = 1,
                    )
                }
                if (!state.loading && state.error == null) TopBar(nav, onNav, tabFocus)
                }

                when {
                    state.loading -> PrismText(
                        text = if (state.loadedCount > 0) stringResource(R.string.channels_loading_count, state.loadedCount) else stringResource(R.string.channels_loading),
                        size = 22.sp,
                        color = PrismColors.TextSecondary,
                    )

                    state.error != null -> {
                        PrismText(
                            text = loadErrorText(state.error, state.httpCode),
                            size = 22.sp,
                            color = PrismColors.Danger,
                        )
                        GlassButton(
                            text = stringResource(R.string.channels_retry),
                            onClick = onRetry,
                            focusRequester = retryFocus,
                            emphasized = true,
                        )
                    }

                    else -> {
                        if (state.catalog.truncated) {
                            PrismText(
                                text = stringResource(R.string.channels_truncated, state.catalog.totalEntries),
                                size = 16.sp,
                                color = PrismColors.Warn,
                            )
                        }
                        Box(Modifier.fillMaxSize().onFocusChanged { contentFocused = it.hasFocus }.depthFocus()) {
                            LibraryBody(state.catalog, nav, epg, favorites, onNav, onPlay, onToggleFavorite)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(nav: LibraryNav, onNav: NavChange, tabFocus: FocusRequester) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val tabs = listOf(
            LibraryTab.LIVE to R.string.tab_live,
            LibraryTab.MOVIES to R.string.tab_movies,
            LibraryTab.SERIES to R.string.tab_series,
            LibraryTab.FAVORITES to R.string.tab_favorites,
        )
        for ((tab, label) in tabs) {
            GlassButton(
                text = stringResource(label),
                onClick = {
                    UiSounds.play(UiSounds.Kind.FORWARD)
                    onNav {
                        if (it.tab == tab) it.copy(resume = true)
                        else it.copy(
                            tab = tab, category = 0, level = 0, sub = 0, itemIndex = 0, movieUrl = null,
                            showTitle = null, showGroup = null, season = -1, resume = true,
                        )
                    }
                },
                emphasized = nav.tab == tab,
                focusRequester = if (nav.tab == tab) tabFocus else null,
            )
        }
        GlassTextField(
            value = nav.query,
            onValueChange = { text -> onNav { it.copy(query = text.take(60), itemIndex = 0) } },
            label = stringResource(R.string.search_label),
            modifier = Modifier.weight(1f),
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        )
    }
}

private data class Entry(val channel: Channel?, val show: Show?) {
    val title: String get() = channel?.name ?: show?.title.orEmpty()
    val logo: String? get() = channel?.logo ?: show?.logo
}

@Composable
private fun LibraryBody(
    catalog: Catalog,
    nav: LibraryNav,
    epg: EpgData,
    favorites: Set<String>,
    onNav: NavChange,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val query = nav.query.trim()
    when (nav.tab) {
        LibraryTab.LIVE -> {
            val names = remember(catalog) { catalog.live.map { it.name } }
            val counts = remember(catalog) { catalog.live.map { it.items.size } }
            val results by produceState(emptyList<Channel>(), catalog, query) {
                value = if (query.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
                    catalog.live.asSequence().flatMap { it.items.asSequence() }
                        .filter { it.name.contains(query, ignoreCase = true) }.take(SEARCH_LIMIT).toList()
                }
            }
            if (catalog.live.isEmpty()) {
                EmptyNote(R.string.library_empty)
            } else if (query.isEmpty()) {
                GroupBrowser(names, counts, nav, onNav) { cats ->
                    val all = remember(cats, catalog) { if (cats.size == 1) catalog.live[cats[0]].items else cats.flatMap { catalog.live[it].items } }
                    val items = sortedAsync(all, nav.sort) { it.name }
                    LiveView(items, true, nav, epg, favorites, onNav, onPlay, onToggleFavorite)
                }
            } else if (results.isEmpty()) {
                EmptyNote(R.string.search_none)
            } else {
                val sorted = sortedAsync(results, nav.sort) { it.name }
                LiveView(sorted, false, nav, epg, favorites, onNav, onPlay, onToggleFavorite)
            }
        }

        LibraryTab.MOVIES -> {
            val names = remember(catalog) { catalog.movies.map { it.name } }
            val counts = remember(catalog) { catalog.movies.map { it.items.size } }
            val selected = nav.movieUrl
            val movie = remember(catalog, selected) {
                selected?.let { url -> catalog.movies.firstNotNullOfOrNull { c -> c.items.firstOrNull { it.url == url } } }
            }
            val results by produceState(emptyList<Channel>(), catalog, query) {
                value = if (query.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
                    catalog.movies.asSequence().flatMap { it.items.asSequence() }
                        .filter { it.name.contains(query, ignoreCase = true) }.take(SEARCH_LIMIT).toList()
                }
            }
            when {
                movie != null -> MovieDetail(movie, favorites, onNav, onPlay, onToggleFavorite)
                catalog.movies.isEmpty() -> EmptyNote(R.string.library_empty)
                query.isNotEmpty() && results.isEmpty() -> EmptyNote(R.string.search_none)
                query.isEmpty() -> GroupBrowser(names, counts, nav, onNav) { cats ->
                    val all = remember(cats, catalog) { if (cats.size == 1) catalog.movies[cats[0]].items else cats.flatMap { catalog.movies[it].items } }
                    val items = sortedAsync(all, nav.sort) { it.name }
                    MovieGrid(items, true, favorites, nav, onNav)
                }
                else -> {
                    val sorted = sortedAsync(results, nav.sort) { it.name }
                    MovieGrid(sorted, false, favorites, nav, onNav)
                }
            }
        }

        LibraryTab.SERIES -> {
            val names = remember(catalog) { catalog.series.map { it.name } }
            val counts = remember(catalog) { catalog.series.map { it.shows.size } }
            val group = nav.showGroup
            val title = nav.showTitle
            val show = remember(catalog, group, title) {
                if (title == null) null else catalog.series.asSequence()
                    .filter { group == null || it.name == group }
                    .flatMap { it.shows.asSequence() }.firstOrNull { it.title == title }
            }
            val results by produceState(emptyList<Show>(), catalog, query) {
                value = if (query.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
                    catalog.series.asSequence().flatMap { it.shows.asSequence() }
                        .filter { it.title.contains(query, ignoreCase = true) }.take(SEARCH_LIMIT).toList()
                }
            }
            when {
                show != null -> ShowDetail(show, nav, favorites, onNav, onPlay, onToggleFavorite)
                catalog.series.isEmpty() -> EmptyNote(R.string.library_empty)
                query.isNotEmpty() && results.isEmpty() -> EmptyNote(R.string.search_none)
                query.isEmpty() -> GroupBrowser(names, counts, nav, onNav) { cats ->
                    val all = remember(cats, catalog) { if (cats.size == 1) catalog.series[cats[0]].shows else cats.flatMap { catalog.series[it].shows } }
                    val items = sortedAsync(all, nav.sort) { it.title }
                    ShowGrid(items, true, favorites, nav, onNav)
                }
                else -> {
                    val sorted = sortedAsync(results, nav.sort) { it.title }
                    ShowGrid(sorted, false, favorites, nav, onNav)
                }
            }
        }

        LibraryTab.FAVORITES -> FavoritesView(catalog, query, nav, favorites, onNav, onPlay, onToggleFavorite)
    }
}

@Composable
private fun MovieGrid(items: List<Channel>, restorable: Boolean, favorites: Set<String>, nav: LibraryNav, onNav: NavChange) {
    val unavailable = LocalReachability.current.unavailable
    ItemGrid(items.size, nav, onNav) { i ->
        val item = items[i]
        val key = FavoriteKey.of(item)
        GridEntry(item.name, item.logo, key in favorites, unavailable = key in unavailable) {
            onNav { it.copy(movieUrl = item.url, itemIndex = if (restorable) i else 0, resume = true) }
        }
    }
}

@Composable
private fun ShowGrid(items: List<Show>, restorable: Boolean, favorites: Set<String>, nav: LibraryNav, onNav: NavChange) {
    ItemGrid(items.size, nav, onNav) { i ->
        val item = items[i]
        GridEntry(item.title, item.logo, FavoriteKey.ofShow(item) in favorites) {
            onNav { it.copy(showTitle = item.title, showGroup = item.group, season = -1, itemIndex = if (restorable) i else 0, resume = true) }
        }
    }
}

/** The ⊘ next to a content the provider could not deliver lately (see [dev.red74.prismplayer.data.Availability]). */
@Composable
private fun UnavailableSign(modifier: Modifier = Modifier, size: Int = 20) {
    PrismText(text = UNAVAILABLE_SIGN, size = size.sp, weight = FontWeight.Bold, color = PrismColors.Danger, modifier = modifier)
}

@Composable
private fun EmptyNote(text: Int) {
    PrismText(text = stringResource(text), size = 20.sp, color = PrismColors.TextSecondary)
}

// --- shared pieces ---------------------------------------------------------------------------------------

private class GroupRow(val label: String, val count: Int)

/**
 * The group levels of Live, Movies and Series on one page: main groups, sub-groups, then the contents. Each level
 * is a column; going down (OK) keeps the earlier columns on the left, smaller and dimmer, and Back goes up one
 * level at a time. The arrows stay inside the column that holds the cursor.
 */
@Composable
private fun GroupBrowser(
    names: List<String>,
    counts: List<Int>,
    nav: LibraryNav,
    onNav: NavChange,
    content: @Composable (cats: List<Int>) -> Unit,
) {
    val tree = remember(names) { GroupTree.build(names) }
    if (tree.isEmpty()) return
    val other = stringResource(R.string.category_other)
    val all = stringResource(R.string.group_all)
    val mainIndex = nav.category.coerceIn(0, tree.lastIndex)
    val main = tree[mainIndex]
    val level = when {
        nav.level <= 0 -> 0
        nav.level == 1 && main.hasSubLevel -> 1
        else -> 2
    }
    val subIndex = nav.sub.coerceIn(0, if (main.hasSubLevel) main.subs.size else 0)
    val cats = remember(main, subIndex) {
        if (!main.hasSubLevel || subIndex == 0) main.categories else listOf(main.subs[subIndex - 1].category)
    }
    val mainRows = remember(tree, counts, other) {
        tree.map { GroupRow(it.name.ifBlank { other }, it.categories.sumOf { c -> counts[c] }) }
    }
    val subRows = remember(main, counts, all) {
        if (!main.hasSubLevel) emptyList()
        else listOf(GroupRow(all, main.categories.sumOf { c -> counts[c] })) +
            main.subs.map { GroupRow(it.name.ifBlank { other }, counts[it.category]) }
    }
    val mainWeight by animateFloatAsState(
        targetValue = when (level) { 0 -> 1f; 1 -> 0.32f; else -> if (main.hasSubLevel) 0.2f else 0.27f },
        animationSpec = tween(320), label = "mainWeight",
    )
    val subWeight by animateFloatAsState(
        targetValue = if (level == 1) 0.68f else 0.2f,
        animationSpec = tween(320), label = "subWeight",
    )
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupColumn(
            rows = mainRows,
            selected = mainIndex,
            active = level == 0,
            resume = nav.resume,
            onResumed = { onNav { it.copy(resume = false) } },
            modifier = Modifier.weight(mainWeight).fillMaxHeight(),
            onLeft = null,
            onPick = { i ->
                onNav {
                    it.copy(category = i, sub = 0, itemIndex = 0, level = if (tree[i].hasSubLevel) 1 else 2, resume = true)
                }
            },
        )
        if (level >= 1 && main.hasSubLevel) {
            GroupColumn(
                rows = subRows,
                selected = subIndex,
                active = level == 1,
                resume = nav.resume,
                onResumed = { onNav { it.copy(resume = false) } },
                modifier = Modifier.weight(subWeight).fillMaxHeight(),
                onLeft = { onNav { it.copy(level = 0, resume = true) } },
                onPick = { i -> onNav { it.copy(sub = i, itemIndex = 0, level = 2, resume = true) } },
            )
        }
        if (level >= 2) {
            // Left from the left edge of the contents goes back up one level (the arrow never gets stuck).
            val up = GroupTree.levelUp(2, main.hasSubLevel)
            Box(
                Modifier
                    .weight(if (main.hasSubLevel) 0.6f else 0.73f)
                    .fillMaxHeight()
                    .onExitFocus(androidx.compose.ui.focus.FocusDirection.Left) {
                        onNav { it.copy(level = up, resume = true) }
                    },
            ) { content(cats) }
        }
    }
}

@Composable
private fun GroupColumn(
    rows: List<GroupRow>,
    selected: Int,
    active: Boolean,
    resume: Boolean,
    onResumed: () -> Unit,
    modifier: Modifier,
    onLeft: (() -> Unit)?,
    onPick: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(selected) }
    val current = selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current)
    LaunchedEffect(resume, active) {
        if (resume && active) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            onResumed()
        }
    }
    // Right opens the highlighted group (like OK); Left goes back up a level.
    LazyColumn(
        modifier
            .depthFocus()
            .confineFocus()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> { UiSounds.play(UiSounds.Kind.FORWARD); onPick(focusedIndex); true }
                    Key.DirectionLeft -> if (onLeft != null) { UiSounds.play(UiSounds.Kind.BACK); onLeft(); true } else false
                    else -> false
                }
            },
        state = state,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(rows) { index, row ->
            GlassSurface(
                onClick = {
                    UiSounds.play(UiSounds.Kind.FORWARD)
                    onPick(index)
                },
                tint = if (index == current) PrismColors.Accent else null,
                focusRequester = if (index == current) focus else null,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) focusedIndex = index },
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PrismText(
                        text = row.label,
                        size = 17.sp,
                        weight = FontWeight.SemiBold,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    PrismText(
                        text = row.count.toString(),
                        size = 14.sp,
                        color = PrismColors.TextSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** The ordering of the final list: standard (as the list declares), A-Z, Z-A, latest added first. */
@Composable
private fun SortBar(nav: LibraryNav, onNav: NavChange) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrismText(text = stringResource(R.string.sort_label), size = 15.sp, color = PrismColors.TextSecondary)
        for ((mode, label) in listOf(
            SortMode.STANDARD to R.string.sort_standard,
            SortMode.AZ to R.string.sort_az,
            SortMode.ZA to R.string.sort_za,
            SortMode.RECENT to R.string.sort_recent,
        )) {
            GlassButton(
                text = stringResource(label),
                onClick = { onNav { it.copy(sort = mode, itemIndex = 0) } },
                emphasized = nav.sort == mode,
            )
        }
    }
}

@Composable
private fun rememberNowMs(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun clock(ms: Long): String {
    val context = LocalContext.current
    return remember(ms) { DateFormat.getTimeFormat(context).format(Date(ms)) }
}

@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassButton(
        text = stringResource(if (isFavorite) R.string.fav_remove else R.string.fav_add),
        onClick = onClick,
        modifier = modifier,
        tint = if (isFavorite) PrismColors.Favorite else null,
    )
}

// --- Live ------------------------------------------------------------------------------------------------

@Composable
private fun LiveView(
    list: List<Channel>,
    restorable: Boolean,
    nav: LibraryNav,
    epg: EpgData,
    favorites: Set<String>,
    onNav: NavChange,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    // The channel watched last (also after zapping in the player) wins over the remembered row.
    val playedIndex = remember(list, nav.played) { nav.played?.let { url -> list.indexOfFirst { it.url == url } } ?: -1 }
    val target = (if (playedIndex >= 0) playedIndex else nav.itemIndex).coerceIn(0, (list.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = target)
    val sortAtStart = remember { nav.sort }
    LaunchedEffect(nav.sort) { if (nav.sort != sortAtStart) listState.scrollToItem(0) }
    val nowMs = rememberNowMs()
    var preview by remember(list) { mutableStateOf<Channel?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val unavailable = LocalReachability.current.unavailable

    LaunchedEffect(nav.resume) {
        if (nav.resume) {
            if (listState.firstVisibleItemIndex > target || listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                listState.scrollToItem(target)
            }
            rowFocus.requestWhenReady()
            onNav { it.copy(resume = false) }
        }
    }

    Row(Modifier.fillMaxSize().confineFocus(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SortBar(nav, onNav)
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(list, key = { index, _ -> index }) { index, channel ->
                ChannelRow(
                    channel = channel,
                    nowNext = epg.nowNext(channel, nowMs),
                    nowMs = nowMs,
                    isFavorite = FavoriteKey.of(channel) in favorites,
                    unavailable = FavoriteKey.of(channel) in unavailable,
                    onToggleFavorite = { onToggleFavorite(FavoriteKey.of(channel)) },
                    focusRequester = if (index == target) rowFocus else null,
                    onFocused = { preview = channel },
                    onClick = {
                        fullscreen = true
                        onNav { it.copy(itemIndex = if (restorable) index else 0, resume = restorable) }
                        onPlay(list, index, restorable)
                    },
                )
            }
        }
        }
        PreviewPane(
            channel = preview,
            nowNext = preview?.let { epg.nowNext(it, nowMs) },
            nowMs = nowMs,
            hasGuide = !epg.isEmpty,
            active = !fullscreen,
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    nowNext: NowNext,
    nowMs: Long,
    isFavorite: Boolean,
    unavailable: Boolean,
    onToggleFavorite: () -> Unit,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    GlassSurface(
        onClick = onClick,
        onFocused = onFocused,
        focusRequester = focusRequester,
        modifier = Modifier.weight(1f),
        focusScale = 1.02f,
    ) {
        Row(
            Modifier.fillMaxWidth().alpha(if (unavailable) UNAVAILABLE_ALPHA else 1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(channel.logo, channel.name, Modifier.size(56.dp), fit = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (unavailable) UnavailableSign()
                    PrismText(text = channel.name, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 1)
                }
                nowNext.now?.let {
                    PrismText(text = it.title, size = 15.sp, color = PrismColors.TextSecondary, maxLines = 1)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.15f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(nowNext.progress(nowMs))
                                .fillMaxHeight()
                                .background(PrismColors.Accent),
                        )
                    }
                }
            }
        }
    }
    StarButton(isFavorite, onToggleFavorite)
    }
}

@Composable
private fun PreviewPane(
    channel: Channel?,
    nowNext: NowNext?,
    nowMs: Long,
    hasGuide: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier, cornerRadius = 22.dp) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreviewPlayer(channel, active, Modifier.fillMaxWidth())
            if (channel != null) {
                PrismText(text = channel.name, size = 22.sp, weight = FontWeight.Bold, maxLines = 1)
                if (FavoriteKey.of(channel) in LocalReachability.current.unavailable) {
                    PrismText(text = stringResource(R.string.unavailable_note), size = 15.sp, color = PrismColors.Danger, maxLines = 2)
                }
                channel.group?.takeIf { it.isNotBlank() }?.let {
                    PrismText(text = it, size = 15.sp, color = PrismColors.Accent, maxLines = 1)
                }
                val now = nowNext?.now
                val next = nowNext?.next
                if (now != null) {
                    PrismText(
                        text = stringResource(R.string.now_label) + " " + clock(now.startMs) + "-" + clock(now.endMs),
                        size = 15.sp,
                        color = PrismColors.TextSecondary,
                    )
                    PrismText(text = now.title, size = 18.sp, weight = FontWeight.SemiBold, maxLines = 2)
                    now.description?.let {
                        PrismText(text = it, size = 15.sp, color = PrismColors.TextSecondary, maxLines = 3)
                    }
                } else if (!hasGuide || next == null) {
                    PrismText(text = stringResource(R.string.no_guide), size = 15.sp, color = PrismColors.TextSecondary)
                }
                if (next != null) {
                    PrismText(
                        text = stringResource(R.string.next_label) + " " + clock(next.startMs) + "  " + next.title,
                        size = 15.sp,
                        color = PrismColors.TextSecondary,
                        maxLines = 1,
                    )
                }
                PrismText(text = stringResource(R.string.preview_hint), size = 14.sp, color = PrismColors.TextSecondary)
            }
        }
    }
}

/**
 * Small live preview. It starts shortly after the cursor settles on a channel, so zapping through the list does
 * not open a stream per row, and it is stopped before the full-screen player opens (panels often allow a single
 * connection per account).
 */
@Composable
private fun PreviewPlayer(channel: Channel?, active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember { createPlayer(context, preview = true) }
    var failed by remember { mutableStateOf(false) }
    val reachability = LocalReachability.current
    val currentChannel by androidx.compose.runtime.rememberUpdatedState(channel)
    val onWorked by androidx.compose.runtime.rememberUpdatedState(reachability.onPreviewWorked)
    /** The first preview after entering waits longer: the full-screen player's connection may still be counted. */
    var firstStart by remember { mutableStateOf(true) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) currentChannel?.let(onWorked)
            }
        }
        exo.addListener(listener)
        PreviewPlayers.register(exo)
        onDispose {
            PreviewPlayers.unregister(exo)
            exo.removeListener(listener)
            exo.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { exo.pause() }

    LaunchedEffect(channel?.url, active) {
        failed = false
        if (channel == null || !active) {
            exo.stop()
            exo.clearMediaItems()
            return@LaunchedEffect
        }
        delay(if (firstStart) PREVIEW_DELAY_MS * 2 else PREVIEW_DELAY_MS)
        firstStart = false
        exo.setPlaybackSpeed(1f)
        exo.setMediaItem(previewItem(channel.url))
        exo.prepare()
        exo.playWhenReady = true
    }

    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(Color.Black.toArgb())
                    setPlayer(exo)
                }
            },
        )
        if (failed) {
            PrismText(text = stringResource(R.string.preview_failed), size = 15.sp, color = PrismColors.TextSecondary)
        }
    }
}

// --- posters (movies, series) ------------------------------------------------------------------------------

private class GridEntry(
    val title: String,
    val logo: String?,
    val favorite: Boolean,
    val unavailable: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun ItemGrid(
    total: Int,
    nav: LibraryNav,
    onNav: NavChange,
    entry: (Int) -> GridEntry,
) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = nav.itemIndex.coerceIn(0, (total - 1).coerceAtLeast(0)))
    val itemFocus = remember { FocusRequester() }
    val target = nav.itemIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
    val sortAtStart = remember { nav.sort }
    LaunchedEffect(nav.sort) { if (nav.sort != sortAtStart) gridState.scrollToItem(0) }

    LaunchedEffect(nav.resume) {
        if (nav.resume) {
            withFrameNanos { }
            runCatching { itemFocus.requestFocus() }
            onNav { it.copy(resume = false) }
        }
    }

    Column(Modifier.fillMaxSize().depthFocus().confineFocus(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SortBar(nav, onNav)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(total, key = { it }) { index ->
                val item = entry(index)
                GlassSurface(
                    onClick = item.onClick,
                    focusRequester = if (index == target) itemFocus else null,
                    tint = if (item.favorite) PrismColors.Favorite else null,
                    contentPadding = PaddingValues(6.dp),
                ) {
                    Column(
                        Modifier.alpha(if (item.unavailable) UNAVAILABLE_ALPHA else 1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box {
                            Poster(item.logo, item.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            if (item.unavailable) UnavailableSign(Modifier.align(Alignment.TopEnd).padding(6.dp), size = 26)
                        }
                        PrismText(text = item.title, size = 15.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieDetail(
    movie: Channel,
    favorites: Set<String>,
    onNav: NavChange,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    RequestFocusOnEnter(playFocus)
    val key = FavoriteKey.of(movie)
    Row(Modifier.fillMaxSize().confineFocus(horizontal = true), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Poster(movie.logo, movie.name, Modifier.width(240.dp).aspectRatio(2f / 3f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PrismText(text = movie.name, size = 32.sp, weight = FontWeight.Bold, maxLines = 3)
            movie.group?.takeIf { it.isNotBlank() }?.let {
                PrismText(text = it, size = 18.sp, color = PrismColors.Accent, maxLines = 1)
            }
            if (key in LocalReachability.current.unavailable) {
                PrismText(text = stringResource(R.string.unavailable_note), size = 16.sp, color = PrismColors.Danger, maxLines = 2)
            }
            GlassButton(
                text = stringResource(R.string.action_play),
                onClick = {
                    onNav { it.copy(resume = false) }
                    onPlay(listOf(movie), 0, false)
                },
                focusRequester = playFocus,
                emphasized = true,
            )
            FavoriteButton(key in favorites, { onToggleFavorite(key) })
        }
    }
}

@Composable
private fun ShowDetail(
    show: Show,
    nav: LibraryNav,
    favorites: Set<String>,
    onNav: NavChange,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val seasons = show.seasons.ifEmpty { listOf(0) }
    val season = if (nav.season in seasons) nav.season else seasons.first()
    val episodes = remember(show, season) { show.episodesOf(season) }
    val seasonFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    // Coming back from an episode puts the cursor on it (the next one, if the player went on by itself).
    val playedIndex = remember(episodes, nav.played) { nav.played?.let { url -> episodes.indexOfFirst { it.url == url } } ?: -1 }
    val episodeTarget = playedIndex.coerceAtLeast(0)
    val episodeState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = episodeTarget)
    LaunchedEffect(Unit) {
        if (playedIndex >= 0) episodeFocus.requestWhenReady() else seasonFocus.requestWhenReady()
    }
    val unavailable = LocalReachability.current.unavailable
    val key = FavoriteKey.ofShow(show)
    var enterEpisodes by remember { mutableStateOf(false) }
    var episodesFocused by remember { mutableStateOf(false) }
    LaunchedEffect(enterEpisodes) {
        if (enterEpisodes) {
            withFrameNanos { }
            runCatching { episodeFocus.requestFocus() }
            enterEpisodes = false
        }
    }
    // Seasons (with the favourite star) above, episodes below: OK goes down into the episodes, Back comes up.
    BackHandler(enabled = episodesFocused) {
        UiSounds.play(UiSounds.Kind.BACK)
        runCatching { seasonFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Poster(show.logo, show.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrismText(text = show.title, size = 30.sp, weight = FontWeight.Bold, maxLines = 2)
            LazyRow(
                Modifier.depthFocus().confineFocus(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                item { FavoriteButton(key in favorites, { onToggleFavorite(key) }) }
                itemsIndexed(seasons) { index, number ->
                    GlassButton(
                        text = if (number == 0) stringResource(R.string.season_none) else stringResource(R.string.season_n, number),
                        onClick = { onNav { it.copy(season = number) }; enterEpisodes = true },
                        onFocused = { if (number != season) onNav { it.copy(season = number) } },
                        emphasized = number == season,
                        focusRequester = if (index == 0) seasonFocus else null,
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().onFocusChanged { episodesFocused = it.hasFocus }.depthFocus().confineFocus(),
                state = episodeState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(episodes, key = { index, _ -> index }) { index, episode ->
                    val missing = FavoriteKey.of(episode) in unavailable
                    GlassSurface(
                        focusRequester = if (index == episodeTarget) episodeFocus else null,
                        onClick = {
                            onNav { it.copy(resume = false) }
                            onPlay(episodes, index, false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusScale = 1.02f,
                    ) {
                        Row(
                            Modifier.alpha(if (missing) UNAVAILABLE_ALPHA else 1f),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (missing) UnavailableSign()
                            PrismText(
                                text = if (episode.episode > 0) "%02d".format(episode.episode) else "-",
                                size = 20.sp,
                                weight = FontWeight.Bold,
                                color = PrismColors.Accent,
                            )
                            PrismText(text = episode.name, size = 18.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// --- favourites -------------------------------------------------------------------------------------------------

@Composable
private fun FavoritesView(
    catalog: Catalog,
    query: String,
    nav: LibraryNav,
    favorites: Set<String>,
    onNav: NavChange,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    // The hashes are computed off the main thread: a big list has hundreds of thousands of entries.
    val entries by produceState(emptyList<Entry>(), catalog, favorites, query) {
        value = withContext(Dispatchers.Default) {
            if (favorites.isEmpty()) return@withContext emptyList()
            val found = ArrayList<Entry>()
            for (category in catalog.live) for (c in category.items) if (FavoriteKey.of(c) in favorites) found += Entry(c, null)
            for (category in catalog.movies) for (c in category.items) if (FavoriteKey.of(c) in favorites) found += Entry(c, null)
            for (category in catalog.series) for (s in category.shows) if (FavoriteKey.ofShow(s) in favorites) found += Entry(null, s)
            if (query.isEmpty()) found else found.filter { it.title.contains(query, ignoreCase = true) }
        }
    }
    if (entries.isEmpty()) {
        EmptyNote(if (favorites.isEmpty()) R.string.favorites_empty else R.string.search_none)
        return
    }
    val playable = remember(entries) { entries.mapNotNull { it.channel } }
    val firstFocus = remember { FocusRequester() }
    // The favourite watched last keeps the cursor when coming back from the player.
    val target = remember(entries, nav.played) { entries.indexOfFirst { it.channel != null && it.channel.url == nav.played }.coerceAtLeast(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = target)
    LaunchedEffect(entries.size) { firstFocus.requestWhenReady() }
    val unavailable = LocalReachability.current.unavailable
    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(entries) { index, entry ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(
                onClick = {
                    val channel = entry.channel
                    if (channel != null) {
                        onPlay(playable, playable.indexOf(channel).coerceAtLeast(0), false)
                    } else if (entry.show != null) {
                        onNav {
                            it.copy(tab = LibraryTab.SERIES, showTitle = entry.show.title, showGroup = entry.show.group, season = -1, query = "")
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                focusRequester = if (index == target) firstFocus else null,
                focusScale = 1.02f,
            ) {
                val missing = entry.channel?.let { FavoriteKey.of(it) in unavailable } == true
                Row(
                    Modifier.alpha(if (missing) UNAVAILABLE_ALPHA else 1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (missing) UnavailableSign()
                    Poster(entry.logo, entry.title, Modifier.size(56.dp), fit = entry.channel?.type == dev.red74.prismplayer.data.ContentType.LIVE)
                    PrismText(text = entry.title, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            StarButton(
                favorite = true,
                onToggle = {
                    val key = entry.channel?.let { FavoriteKey.of(it) } ?: entry.show?.let { FavoriteKey.ofShow(it) }
                    if (key != null) onToggleFavorite(key)
                },
            )
            }
        }
    }
}


/**
 * Orders a long list off the main thread: sorting a group of tens of thousands of entries on the TV stick would
 * freeze the screen, so the list is shown at once in its original order and swapped when the sort is ready.
 */
@Composable
private fun <T> sortedAsync(source: List<T>, mode: dev.red74.prismplayer.data.SortMode, title: (T) -> String): List<T> {
    val small = source.size < 2_000
    val first = remember(source, mode) { if (small) source.sortedBy(mode, title) else source }
    val result by produceState(initialValue = first, source, mode) {
        value = if (small) first else withContext(Dispatchers.Default) { source.sortedBy(mode, title) }
    }
    return result
}
