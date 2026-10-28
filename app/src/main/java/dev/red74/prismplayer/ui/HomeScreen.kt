package dev.red74.prismplayer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.Notice
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.PlaylistStatus
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.data.SavedPlaylist
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    playlists: List<SavedPlaylist>,
    verifying: Set<String>,
    notice: Notice?,
    onOpen: (String) -> Unit,
    onVerify: (String) -> Unit,
    onVerifyAll: () -> Unit,
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
                    if (playlists.isNotEmpty()) {
                        GlassButton(
                            text = stringResource(R.string.action_verify_all),
                            onClick = onVerifyAll,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                                modifier = Modifier.animateItem(),
                                playlist = playlist,
                                checking = playlist.id in verifying,
                                onOpen = { onOpen(playlist.id) },
                                onVerify = { onVerify(playlist.id) },
                                onDelete = { onDelete(playlist.id) },
                            )
                        }
                    }
                }
            }
        }

        // The toast rises with a spring and sinks away, like an iOS banner.
        AnimatedContent(
            targetState = notice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            transitionSpec = {
                val spring = spring<IntOffset>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
                (slideInVertically(spring) { it } + fadeIn(tween(220)) + scaleIn(tween(300), initialScale = 0.9f)) togetherWith
                    (slideOutVertically(spring) { it } + fadeOut(tween(200)) + scaleOut(tween(250), targetScale = 0.95f))
            },
            label = "notice",
        ) { shown ->
            if (shown != null) {
                GlassPanel(cornerRadius = 22.dp) {
                    PrismText(
                        text = noticeText(shown),
                        size = 20.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                    )
                }
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
    Notice.DUPLICATE -> stringResource(R.string.notice_duplicate)
    Notice.VERIFY_OK -> stringResource(R.string.notice_verify_ok)
    Notice.VERIFY_FAILED -> stringResource(R.string.notice_verify_failed)
    Notice.VERIFY_ALL_OK -> stringResource(R.string.notice_verify_all_ok)
    Notice.VERIFY_SOME_FAILED -> stringResource(R.string.notice_verify_some_failed)
}

@Composable
private fun PlaylistRow(
    modifier: Modifier = Modifier,
    playlist: SavedPlaylist,
    checking: Boolean,
    onOpen: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    val failed = playlist.status == PlaylistStatus.FAILED && !checking
    val statusLabel: String
    val statusColor: androidx.compose.ui.graphics.Color
    when {
        checking -> {
            statusLabel = stringResource(R.string.status_checking)
            statusColor = PrismColors.Accent
        }
        playlist.status == PlaylistStatus.OK -> {
            statusLabel = stringResource(R.string.status_ok, playlist.channelCount)
            statusColor = PrismColors.Ok
        }
        playlist.status == PlaylistStatus.FAILED -> {
            statusLabel = loadErrorShort(playlist.lastError, playlist.lastHttpCode)
                ?: stringResource(R.string.status_failed)
            statusColor = PrismColors.Danger
        }
        else -> {
            statusLabel = stringResource(R.string.status_unverified)
            statusColor = PrismColors.Warn
        }
    }

    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(3_000)
            confirming = false
        }
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(
            text = playlist.name,
            subtitle = PlaylistUrl.displayLabel(playlist.url) + " · " + statusLabel,
            subtitleColor = statusColor,
            tint = if (failed) PrismColors.Danger else null,
            onClick = onOpen,
            modifier = Modifier.weight(1f),
        )
        if (playlist.status != PlaylistStatus.OK && !checking) {
            GlassButton(
                text = stringResource(
                    if (playlist.status == PlaylistStatus.FAILED) R.string.action_retry else R.string.action_verify,
                ),
                onClick = onVerify,
                emphasized = playlist.status == PlaylistStatus.UNVERIFIED,
            )
        }
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
