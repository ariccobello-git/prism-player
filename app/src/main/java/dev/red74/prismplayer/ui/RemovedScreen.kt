package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.RemovedBatch
import java.text.DateFormat
import java.util.Date

/**
 * What the list renewals of the last 7 days took away, newest first, grouped by list and time. It only shows: the
 * contents are gone from the lists, and each record deletes itself after 7 days.
 */
@Composable
fun RemovedScreen(load: suspend () -> List<RemovedBatch>, onBack: () -> Unit) {
    val batches by produceState<List<RemovedBatch>?>(null) { value = load() }
    val firstFocus = remember { FocusRequester() }
    RequestFocusOnEnter(firstFocus)
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val live = stringResource(R.string.tab_live)
    val movie = stringResource(R.string.tab_movies)
    val series = stringResource(R.string.tab_series)
    val ungrouped = stringResource(R.string.removed_ungrouped)

    PrismBackground {
        GlassPanel(Modifier.fillMaxSize().padding(32.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack, focusRequester = firstFocus)
                    PrismText(text = stringResource(R.string.removed_title), size = 30.sp, weight = FontWeight.Bold)
                }
                PrismText(text = stringResource(R.string.removed_hint), size = 18.sp, color = PrismColors.TextSecondary)
                val shown = batches
                when {
                    shown == null -> PrismText(text = stringResource(R.string.removed_loading), size = 20.sp, color = PrismColors.TextSecondary)
                    shown.isEmpty() -> PrismText(text = stringResource(R.string.removed_empty), size = 20.sp, color = PrismColors.TextSecondary)
                    else -> LazyColumn(
                        Modifier.fillMaxSize().depthFocus().confineFocus(),
                        // Room around the rows so the focus lift never pushes one past the panel's edge.
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        shown.forEachIndexed { index, batch ->
                            item(key = "head$index") {
                                PrismText(
                                    text = batch.listName + " · " + format.format(Date(batch.at)) + " · " +
                                        stringResource(R.string.removed_batch_count, batch.total),
                                    size = 22.sp,
                                    weight = FontWeight.SemiBold,
                                    color = PrismColors.Accent,
                                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 14.dp),
                                )
                            }
                            items(batch.items) { item ->
                                val type = when (item.type) {
                                    ContentType.LIVE -> live
                                    ContentType.MOVIE -> movie
                                    ContentType.SERIES -> series
                                }
                                GlassButton(
                                    text = item.name,
                                    subtitle = type + " · " + (item.group?.takeIf { it.isNotBlank() } ?: ungrouped),
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            val more = batch.total - batch.items.size
                            if (more > 0) {
                                item(key = "more$index") {
                                    PrismText(text = stringResource(R.string.removed_more, more), size = 18.sp, color = PrismColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
