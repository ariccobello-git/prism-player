package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.SavedPlaylist

/**
 * Choose which lists to verify: OK on a list ticks or unticks it, then "Verifica N" starts the check on the ticked
 * ones (one or several; they are checked one after the other, a single connection at a time).
 */
@Composable
fun VerifyListsScreen(playlists: List<SavedPlaylist>, onStart: (Set<String>) -> Unit, onBack: () -> Unit) {
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val firstFocus = remember { FocusRequester() }
    RequestFocusOnEnter(firstFocus)
    val rows = remember { ReturnFocus() }
    // Lists removed while this page is open drop out of the choice.
    val live = chosen.filter { id -> playlists.any { it.id == id } }.toSet()

    PrismBackground {
        GlassPanel(Modifier.fillMaxSize().padding(32.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.returnSection(FocusDirection.Down to rows),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack)
                    PrismText(text = stringResource(R.string.verify_pick_title), size = 30.sp, weight = FontWeight.Bold)
                }
                Row(Modifier.returnSection(FocusDirection.Down to rows), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassButton(
                        text = stringResource(R.string.verify_start, live.size),
                        onClick = { if (live.isNotEmpty()) onStart(live) },
                        emphasized = live.isNotEmpty(),
                        focusRequester = firstFocus,
                    )
                    GlassButton(
                        text = stringResource(R.string.verify_select_all),
                        onClick = { chosen = playlists.map { it.id }.toSet() },
                    )
                    GlassButton(text = stringResource(R.string.verify_select_none), onClick = { chosen = emptySet() })
                }
                PrismText(text = stringResource(R.string.verify_pick_hint), size = 18.sp, color = PrismColors.TextSecondary)
                LazyColumn(
                    Modifier.fillMaxSize().depthFocus().returnSection(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(playlists, key = { _, it -> it.id }) { index, playlist ->
                        val on = playlist.id in live
                        GlassSurface(
                            onClick = { chosen = if (on) live - playlist.id else live + playlist.id },
                            selected = on,
                            modifier = Modifier.fillMaxWidth().returnItem(rows, index),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                PrismText(
                                    text = if (on) "✓" else "○",
                                    size = 24.sp,
                                    weight = FontWeight.Bold,
                                    color = if (on) PrismColors.Accent else PrismColors.TextSecondary,
                                )
                                PrismText(text = playlist.name, size = 20.sp, weight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
