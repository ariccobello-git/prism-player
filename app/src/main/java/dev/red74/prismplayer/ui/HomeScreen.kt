package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.Notice
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.data.SavedPlaylist
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    playlists: List<SavedPlaylist>,
    notice: Notice?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddUrl: () -> Unit,
    onPair: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    RequestFocusOnEnter(firstFocus)

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_000)
            onNoticeShown()
        }
    }

    PrismBackground {
        Row(
            Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            GlassPanel(
                Modifier
                    .weight(0.38f)
                    .fillMaxHeight(),
            ) {
                Column(
                    Modifier.padding(36.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PrismText(
                        text = stringResource(R.string.app_name),
                        size = 46.sp,
                        weight = FontWeight.Bold,
                    )
                    PrismText(
                        text = stringResource(R.string.built_by),
                        size = 20.sp,
                        color = PrismColors.Accent,
                    )
                    PrismText(
                        text = stringResource(R.string.home_tagline),
                        size = 18.sp,
                        color = PrismColors.TextSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    GlassButton(
                        text = stringResource(R.string.action_add_url),
                        onClick = onAddUrl,
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = firstFocus,
                        emphasized = true,
                    )
                    GlassButton(
                        text = stringResource(R.string.action_pair),
                        onClick = onPair,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            GlassPanel(
                Modifier
                    .weight(0.62f)
                    .fillMaxHeight(),
            ) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        PrismText(
                            text = stringResource(R.string.playlists_title),
                            size = 28.sp,
                            weight = FontWeight.SemiBold,
                        )
                    }
                    if (playlists.isEmpty()) {
                        item {
                            PrismText(
                                text = stringResource(R.string.playlists_empty),
                                size = 20.sp,
                                color = PrismColors.TextSecondary,
                            )
                        }
                    } else {
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                onOpen = { onOpen(playlist.id) },
                                onDelete = { onDelete(playlist.id) },
                            )
                        }
                    }
                }
            }
        }

        if (notice != null) {
            GlassPanel(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
                cornerRadius = 22.dp,
            ) {
                PrismText(
                    text = noticeText(notice),
                    size = 20.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun noticeText(notice: Notice): String = when (notice) {
    Notice.ADDED -> stringResource(R.string.notice_added)
    Notice.REMOVED -> stringResource(R.string.notice_removed)
    Notice.PAIRED -> stringResource(R.string.notice_paired)
    Notice.SAVE_FAILED -> stringResource(R.string.notice_save_failed)
}

@Composable
private fun PlaylistRow(
    playlist: SavedPlaylist,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(3_000)
            confirming = false
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(
            text = playlist.name,
            subtitle = PlaylistUrl.displayLabel(playlist.url),
            onClick = onOpen,
            modifier = Modifier.weight(1f),
        )
        Box {
            GlassButton(
                text = stringResource(
                    if (confirming) R.string.action_delete_confirm else R.string.action_delete,
                ),
                onClick = {
                    if (confirming) onDelete() else confirming = true
                },
                danger = confirming,
            )
        }
    }
}
