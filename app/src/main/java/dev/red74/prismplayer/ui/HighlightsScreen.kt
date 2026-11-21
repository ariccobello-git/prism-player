package dev.red74.prismplayer.ui

import android.text.format.DateFormat
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.HighlightEvent
import dev.red74.prismplayer.data.HighlightKind
import dev.red74.prismplayer.data.HighlightRow
import dev.red74.prismplayer.data.Highlights
import dev.red74.prismplayer.data.HighlightsData
import dev.red74.prismplayer.data.SportTopic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** The featured events found in the guide; filled in the background by the view model. */
val LocalHighlights = compositionLocalOf { HighlightsData.Empty }

private val CardWidth = 290.dp
private val CardHeight = 132.dp

/**
 * "In evidenza": the most awaited events of the next hours, by kind (Sport, Film, Entertainment), each with its time
 * and the channel of the list that carries it. It only reads what the view model already worked out: no work here
 * beyond drawing a few cards, no preview player and no extra connection.
 */
@Composable
fun HighlightsView(
    nav: LibraryNav,
    onNav: ((LibraryNav) -> LibraryNav) -> Unit,
    onPlay: (List<Channel>, Int, Boolean) -> Unit,
) {
    val data = LocalHighlights.current
    val kinds = remember(data) { data.kinds() }
    if (kinds.isEmpty()) {
        PrismText(text = stringResource(R.string.highlights_empty), size = 20.sp, color = PrismColors.TextSecondary)
        return
    }
    val kindIndex = nav.highlightKind.coerceIn(0, kinds.size - 1)
    val kind = kinds[kindIndex]
    val nowMs = rememberMinuteNow()
    val minute = nowMs / 60_000L
    val query = nav.query.trim().lowercase()
    val events = remember(data, kind, query) {
        data.events(kind).filter { event ->
            query.isEmpty() || event.title.lowercase().contains(query) || event.channels.any { it.name.lowercase().contains(query) }
        }
    }
    val rows = remember(events, minute) { Highlights.rows(events, nowMs) }
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            kinds.forEachIndexed { index, item ->
                GlassButton(
                    text = kindLabel(item),
                    onClick = { onNav { it.copy(highlightKind = index) } },
                    emphasized = index == kindIndex,
                )
            }
        }
        if (rows.isEmpty()) {
            PrismText(text = stringResource(R.string.search_none), size = 20.sp, color = PrismColors.TextSecondary)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> "${row.live}-${row.day}" }) { rowIndex, row ->
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
                                    live = row.live,
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
private fun EventCard(event: HighlightEvent, live: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
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
                event.topic?.let { PrismText(text = topicLabel(it), size = 16.sp, color = PrismColors.TextSecondary, maxLines = 1) }
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
    row.live -> stringResource(R.string.highlights_now)
    row.day == 0 -> stringResource(R.string.highlights_today)
    row.day == 1 -> stringResource(R.string.highlights_tomorrow)
    else -> remember(row) {
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(row.events.first().startMs))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

@Composable
private fun kindLabel(kind: HighlightKind): String = stringResource(
    when (kind) {
        HighlightKind.SPORT -> R.string.highlights_sport
        HighlightKind.FILM -> R.string.highlights_film
        HighlightKind.ENTERTAINMENT -> R.string.highlights_entertainment
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
