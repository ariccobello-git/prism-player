package dev.red74.prismplayer.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.EpgHit
import dev.red74.prismplayer.data.SearchAllResult
import dev.red74.prismplayer.data.Show
import dev.red74.prismplayer.data.VariantTags
import dev.red74.prismplayer.reminders.ReminderStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * What the search page shows, kept while the viewer plays something and comes back: the words typed, what was found
 * and for which guide and list it was found (so coming back does not search again).
 */
class SearchPage {
    var query by mutableStateOf("")
    var result by mutableStateOf(SearchAllResult.Empty)
    internal var searched: Pair<String, Any>? = null

    fun reset() {
        query = ""
        result = SearchAllResult.Empty
        searched = null
    }
}

/**
 * One search over everything the list has: live channels, films, series and programmes of the guide. The word index
 * of the list answers for the first three and the guide index (built in the background the first time this page is
 * opened, never at start-up) for the programmes. OK on a channel or a programme on air plays it; on a programme that
 * has not started it sets a reminder; on a film or a series it opens its page in the library.
 */
@Composable
fun SearchAllScreen(
    page: SearchPage,
    search: suspend (String) -> SearchAllResult,
    /** Changes when the guide or the list does, so a search already shown is run again with the new data. */
    dataKey: Any,
    onPlay: (List<Channel>, Int) -> Unit,
    onOpenMovie: (Channel) -> Unit,
    onOpenShow: (Show) -> Unit,
    onBack: () -> Unit,
) {
    val query = page.query
    val result = page.result
    val toggleReminder = rememberReminderToggle()
    LaunchedEffect(query, dataKey) {
        val key = query to dataKey
        if (page.searched == key) return@LaunchedEffect
        if (query.isBlank()) {
            page.result = SearchAllResult.Empty
        } else {
            // A pause after the last letter, so typing does not run a search for every key.
            delay(DEBOUNCE_MS)
            page.result = search(query)
        }
        page.searched = key
    }
    val first = remember { FocusRequester() }
    RequestFocusOnEnter(first)
    var notice by remember { mutableStateOf<String?>(null) }
    val on = stringResource(R.string.reminder_toast_on)
    val off = stringResource(R.string.reminder_toast_off)
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }
    val nowMs = System.currentTimeMillis()
    // The reminder set on a programme shows as a bell on its row at once.
    val reminders = ReminderStore.current

    PrismBackground {
        GlassPanel(Modifier.fillMaxSize().padding(32.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack)
                    GlassTextField(
                        value = query,
                        onValueChange = { page.query = it.take(60) },
                        label = stringResource(R.string.search_all_hint),
                        modifier = Modifier.weight(1f),
                        focusRequester = first,
                        imeAction = ImeAction.Search,
                    )
                }
                Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).confineFocus(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (query.isNotBlank() && result.isEmpty) PrismText(text = stringResource(R.string.search_none), size = 20.sp, color = PrismColors.TextSecondary)
                    Section(R.string.search_all_channels, result.channels.isNotEmpty()) {
                        result.channels.forEachIndexed { i, channel ->
                            ResultRow(
                                id = "c$i", logo = channel.logo, title = channel.name, subtitle = channel.group,
                                onClick = { onPlay(result.channels, i) },
                            )
                        }
                    }
                    Section(R.string.search_all_programmes, result.programmes.isNotEmpty()) {
                        result.programmes.forEachIndexed { i, hit ->
                            val live = hit.isLive(nowMs)
                            val channel = hit.channels.first()
                            val reminder = reminderFor(hit.programme.title, hit.programme.startMs, channel)
                            val set = !live && reminders.any { it.id == reminder.id }
                            ResultRow(
                                id = "p$i", logo = channel.logo, title = hit.programme.title,
                                subtitle = programmeLine(hit, live, set, channel),
                                onClick = {
                                    if (live) onPlay(hit.channels, 0)
                                    else notice = if (toggleReminder(reminder)) on else off
                                },
                            )
                        }
                    }
                    Section(R.string.search_all_movies, result.movies.isNotEmpty()) {
                        result.movies.forEachIndexed { i, movie ->
                            ResultRow(id = "m$i", logo = movie.logo, title = VariantTags.of(movie.name).title, subtitle = movie.group, onClick = { onOpenMovie(movie) })
                        }
                    }
                    Section(R.string.search_all_series, result.shows.isNotEmpty()) {
                        result.shows.forEachIndexed { i, show ->
                            ResultRow(id = "s$i", logo = show.logo, title = VariantTags.of(show.title).title, subtitle = show.group, onClick = { onOpenShow(show) })
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
        }
    }
}

@Composable
private fun Section(title: Int, visible: Boolean, content: @Composable () -> Unit) {
    if (!visible) return
    PrismText(text = stringResource(title), size = 20.sp, weight = FontWeight.SemiBold, color = PrismColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
    content()
}

@Composable
private fun ResultRow(id: String, logo: String?, title: String, subtitle: String?, onClick: () -> Unit) {
    GlassSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().focusMemory(id), focusScale = 1.02f) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(logo, Modifier.size(52.dp), fit = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PrismText(text = title, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 1)
                if (!subtitle.isNullOrBlank()) PrismText(text = subtitle, size = 16.sp, color = PrismColors.TextSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun programmeLine(hit: EpgHit, live: Boolean, reminderSet: Boolean, channel: Channel): String {
    val more = hit.channels.size - 1
    val where = channel.name + if (more > 0) "  +$more" else ""
    if (live) return stringResource(R.string.highlights_on_air) + "  ·  " + where
    val context = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
    val time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(hit.programme.startMs))
    val bell = stringResource(if (reminderSet) R.string.reminder_active else R.string.reminder_remind)
    return "$time  ·  $where  ·  $bell"
}

private const val DEBOUNCE_MS = 280L
