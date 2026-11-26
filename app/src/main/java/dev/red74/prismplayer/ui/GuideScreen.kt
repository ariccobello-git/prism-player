package dev.red74.prismplayer.ui

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.Programme
import dev.red74.prismplayer.reminders.ReminderStore
import java.util.Date
import kotlinx.coroutines.delay

/** How the programme guide is laid out: a time axis from the last half hour, 3.5 dp per minute, six hours. */
internal object GuideLayout {
    const val STEP_MS = 1_800_000L
    const val WINDOW_MS = 6 * 3_600_000L
    const val DP_PER_MINUTE = 3.5f
    const val MIN_CELL_DP = 56

    /** The left edge of the guide: the half hour that has just begun. */
    fun windowStart(nowMs: Long): Long = nowMs - nowMs % STEP_MS

    /** One block of a row: a programme, or (programme null) a gap the guide knows nothing about. */
    class Cell(val programme: Programme?, val startMs: Long, val endMs: Long, val widthDp: Int)

    private fun widthOf(startMs: Long, endMs: Long): Int =
        ((endMs - startMs) / 60_000f * DP_PER_MINUTE).toInt().coerceAtLeast(1)

    /**
     * The blocks of one channel from [startMs] to [endMs], in order: a programme already running at the left edge is
     * cut there, a programme running past the right edge is cut there, holes are filled with gaps, so that the widths
     * add up to the whole window and every row lines up with the time axis.
     */
    fun cells(programmes: List<Programme>, startMs: Long, endMs: Long): List<Cell> {
        val out = ArrayList<Cell>()
        var cursor = startMs
        for (p in programmes.sortedBy { it.startMs }) {
            if (p.endMs <= cursor || p.startMs >= endMs || p.endMs <= p.startMs) continue
            val from = maxOf(p.startMs, cursor)
            if (from > cursor) out += Cell(null, cursor, from, widthOf(cursor, from))
            val to = minOf(p.endMs, endMs)
            out += Cell(p, from, to, maxOf(widthOf(from, to), MIN_CELL_DP))
            cursor = to
        }
        if (cursor < endMs) out += Cell(null, cursor, endMs, widthOf(cursor, endMs))
        return out
    }

    /** The half-hour marks of the time axis. */
    fun marks(startMs: Long, endMs: Long): List<Long> = generateSequence(startMs) { it + STEP_MS }.takeWhile { it < endMs }.toList()
}

/**
 * The programme guide as a grid: channels down, time across. All rows slide together (one shared scroll position);
 * OK on what is on air plays that channel, OK on a later programme sets or removes its reminder.
 */
@Composable
internal fun GuideGrid(
    channels: List<Channel>,
    epg: EpgData,
    onPlay: (index: Int) -> Unit,
    onReminder: (Channel, Programme) -> Boolean,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    // The axis does not move while the viewer reads: it starts at the half hour the guide was opened in.
    val start = remember { GuideLayout.windowStart(System.currentTimeMillis()) }
    val end = start + GuideLayout.WINDOW_MS
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val reminders = ReminderStore.current
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val labelWidth = 170.dp

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(labelWidth + 8.dp))
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                for (mark in GuideLayout.marks(start, end)) {
                    Box(Modifier.width((GuideLayout.STEP_MS / 60_000f * GuideLayout.DP_PER_MINUTE).dp)) {
                        PrismText(text = timeFormat.format(Date(mark)), size = 15.sp, weight = FontWeight.SemiBold, color = PrismColors.TextSecondary, maxLines = 1)
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("guide"), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(channels, key = { index, _ -> index }) { rowIndex, channel ->
                val cells = remember(channel, start, epg) { GuideLayout.cells(epg.programmes(channel), start, end) }
                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(labelWidth), contentAlignment = Alignment.CenterStart) {
                        PrismText(text = channel.name, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 2)
                    }
                    Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                        var firstCell = true
                        for (cell in cells) {
                            val p = cell.programme
                            if (p == null) {
                                Spacer(Modifier.width(cell.widthDp.dp).height(58.dp))
                                continue
                            }
                            val live = p.startMs <= nowMs && nowMs < p.endMs
                            val reminderId = remember(channel, p) { reminderFor(p.title, p.startMs, channel).id }
                            val reminderOn = !live && reminders.any { it.id == reminderId }
                            val requester = if (rowIndex == 0 && firstCell) firstFocus else null
                            firstCell = false
                            GlassSurface(
                                onClick = { if (live) onPlay(rowIndex) else onReminder(channel, p) },
                                modifier = Modifier.width(cell.widthDp.dp).height(58.dp).padding(end = 3.dp),
                                focusRequester = requester,
                                tint = when {
                                    live -> PrismColors.Danger
                                    reminderOn -> PrismColors.Accent
                                    else -> null
                                },
                                cornerRadius = 12.dp,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                focusScale = 1.02f,
                            ) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                                    PrismText(text = p.title, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                                    PrismText(
                                        text = timeFormat.format(Date(p.startMs)) + " – " + timeFormat.format(Date(p.endMs)),
                                        size = 12.sp, color = PrismColors.TextSecondary, maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
