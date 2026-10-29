package dev.red74.prismplayer.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
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
import dev.red74.prismplayer.data.NowNext
import dev.red74.prismplayer.data.Show
import dev.red74.prismplayer.player.createPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

private const val SEARCH_LIMIT = 500
private const val PREVIEW_DELAY_MS = 700L

private typealias NavChange = ((LibraryNav) -> LibraryNav) -> Unit

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
) {
    val retryFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    RequestFocusOnEnter(retryFocus, key = state.error)
    RequestFocusOnEnter(tabFocus, key = state.loading)

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
            kotlinx.coroutines.delay(600)
            onNav { it.copy(resume = false) }
        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack)
                    PrismText(text = state.playlistName, size = 30.sp, weight = FontWeight.Bold, maxLines = 1)
                }

                when {
                    state.loading -> PrismText(
                        text = stringResource(R.string.channels_loading),
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
                        TopBar(nav, onNav, tabFocus)
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
        Modifier.fillMaxWidth().depthFocus(),
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
                        it.copy(
                            tab = tab, category = 0, itemIndex = 0, movieUrl = null,
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
            val results by produceState(emptyList<Channel>(), catalog, query) {
                value = if (query.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
                    catalog.live.asSequence().flatMap { it.items.asSequence() }
                        .filter { it.name.contains(query, ignoreCase = true) }.take(SEARCH_LIMIT).toList()
                }
            }
            val everything = remember(catalog) { catalog.live.flatMap { it.items } }
            if (catalog.live.isEmpty()) {
                EmptyNote(R.string.library_empty)
            } else if (query.isEmpty()) {
                LiveView(everything, true, nav, epg, favorites, onNav, onPlay, onToggleFavorite)
            } else if (results.isEmpty()) {
                EmptyNote(R.string.search_none)
            } else {
                LiveView(results, false, nav, epg, favorites, onNav, onPlay, onToggleFavorite)
            }
        }

        LibraryTab.MOVIES -> {
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
                else -> {
                    val flat = if (query.isEmpty()) null else results
                    CategoryAndGrid(
                        names = catalog.movies.map { it.name },
                        counts = catalog.movies.map { it.items.size },
                        showCategories = flat == null,
                        nav = nav,
                        onNav = onNav,
                        count = { c -> flat?.size ?: catalog.movies[c].items.size },
                        entry = { c, i ->
                            val item = flat?.get(i) ?: catalog.movies[c].items[i]
                            GridEntry(item.name, item.logo, FavoriteKey.of(item) in favorites) {
                                onNav { it.copy(movieUrl = item.url, itemIndex = if (flat == null) i else 0, resume = true) }
                            }
                        },
                    )
                }
            }
        }

        LibraryTab.SERIES -> {
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
                else -> {
                    val flat = if (query.isEmpty()) null else results
                    CategoryAndGrid(
                        names = catalog.series.map { it.name },
                        counts = catalog.series.map { it.shows.size },
                        showCategories = flat == null,
                        nav = nav,
                        onNav = onNav,
                        count = { c -> flat?.size ?: catalog.series[c].shows.size },
                        entry = { c, i ->
                            val item = flat?.get(i) ?: catalog.series[c].shows[i]
                            GridEntry(item.title, item.logo, FavoriteKey.ofShow(item) in favorites) {
                                onNav { it.copy(showTitle = item.title, showGroup = item.group, season = -1, itemIndex = if (flat == null) i else 0, resume = true) }
                            }
                        },
                    )
                }
            }
        }

        LibraryTab.FAVORITES -> FavoritesView(catalog, query, nav, favorites, onNav, onPlay, onToggleFavorite)
    }
}

@Composable
private fun EmptyNote(text: Int) {
    PrismText(text = stringResource(text), size = 20.sp, color = PrismColors.TextSecondary)
}

// --- shared pieces ---------------------------------------------------------------------------------------

/**
 * First level of Live, Movies and Series: the categories of the list. OK opens one; inside it the cursor stays
 * in that category until Back is pressed.
 */
@Composable
private fun CategoryColumn(names: List<String>, counts: List<Int>, nav: LibraryNav, onNav: NavChange, modifier: Modifier) {
    val other = stringResource(R.string.category_other)
    val selected = nav.category.coerceIn(0, (names.size - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(names) { index, name ->
            GlassSurface(
                onClick = { onNav { it.copy(category = index, itemIndex = 0, resume = true) } },
                tint = if (index == selected) PrismColors.Accent else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PrismText(
                        text = name.ifBlank { other },
                        size = 17.sp,
                        weight = FontWeight.SemiBold,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    PrismText(
                        text = counts[index].toString(),
                        size = 14.sp,
                        color = PrismColors.TextSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
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
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = nav.itemIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
    )
    val nowMs = rememberNowMs()
    var preview by remember(list) { mutableStateOf<Channel?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }

    LaunchedEffect(nav.resume) {
        if (nav.resume) {
            withFrameNanos { }
            runCatching { rowFocus.requestFocus() }
            onNav { it.copy(resume = false) }
        }
    }

    Row(Modifier.fillMaxSize().confineFocus(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val target = nav.itemIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        LazyColumn(
            Modifier
                .weight(0.58f)
                .fillMaxHeight(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(list, key = { index, _ -> index }) { index, channel ->
                ChannelRow(
                    channel = channel,
                    nowNext = epg.nowNext(channel, nowMs),
                    nowMs = nowMs,
                    isFavorite = FavoriteKey.of(channel) in favorites,
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(channel.logo, channel.name, Modifier.size(56.dp), fit = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PrismText(text = channel.name, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 1)
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
    val exo = remember { createPlayer(context) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        exo.addListener(listener)
        onDispose {
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
        delay(PREVIEW_DELAY_MS)
        exo.setMediaItem(MediaItem.fromUri(channel.url))
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

private class GridEntry(val title: String, val logo: String?, val favorite: Boolean, val onClick: () -> Unit)

@Composable
private fun CategoryAndGrid(
    names: List<String>,
    counts: List<Int>,
    showCategories: Boolean,
    nav: LibraryNav,
    onNav: NavChange,
    count: (Int) -> Int,
    entry: (Int, Int) -> GridEntry,
) {
    val category = nav.category.coerceIn(0, (names.size - 1).coerceAtLeast(0))
    val total = count(category)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = nav.itemIndex.coerceIn(0, (total - 1).coerceAtLeast(0)))
    val itemFocus = remember { FocusRequester() }
    val target = nav.itemIndex.coerceIn(0, (total - 1).coerceAtLeast(0))

    LaunchedEffect(nav.resume) {
        if (nav.resume) {
            withFrameNanos { }
            runCatching { itemFocus.requestFocus() }
            onNav { it.copy(resume = false) }
        }
    }

    Row(Modifier.fillMaxSize().confineFocus(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showCategories) {
            CategoryColumn(names, counts, nav, onNav, Modifier.weight(0.27f).fillMaxHeight().depthFocus())
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier
                .weight(if (showCategories) 0.73f else 1f)
                .fillMaxHeight()
                .depthFocus(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(total, key = { it }) { index ->
                val item = entry(category, index)
                GlassSurface(
                    onClick = item.onClick,
                    focusRequester = if (index == target) itemFocus else null,
                    tint = if (item.favorite) PrismColors.Favorite else null,
                    contentPadding = PaddingValues(6.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Poster(item.logo, item.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
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
    RequestFocusOnEnter(seasonFocus)
    val key = FavoriteKey.ofShow(show)

    Row(Modifier.fillMaxSize().confineFocus(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(240.dp).depthFocus(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Poster(show.logo, show.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            FavoriteButton(key in favorites, { onToggleFavorite(key) }, Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).depthFocus(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrismText(text = show.title, size = 30.sp, weight = FontWeight.Bold, maxLines = 2)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(seasons) { index, number ->
                    GlassButton(
                        text = if (number == 0) stringResource(R.string.season_none) else stringResource(R.string.season_n, number),
                        onClick = { onNav { it.copy(season = number) } },
                        onFocused = { if (number != season) onNav { it.copy(season = number) } },
                        emphasized = number == season,
                        focusRequester = if (index == 0) seasonFocus else null,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(episodes, key = { index, _ -> index }) { index, episode ->
                    GlassSurface(
                        onClick = {
                            onNav { it.copy(resume = false) }
                            onPlay(episodes, index, false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusScale = 1.02f,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
    RequestFocusOnEnter(firstFocus, key = entries.size)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                focusRequester = if (index == 0) firstFocus else null,
                focusScale = 1.02f,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
