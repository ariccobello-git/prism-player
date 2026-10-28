package dev.red74.prismplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.device.DeviceProfile
import dev.red74.prismplayer.data.TextCleaner
import dev.red74.prismplayer.player.AspectMode
import dev.red74.prismplayer.player.createPlayer
import kotlinx.coroutines.delay
import java.util.Locale

private enum class PlayStatus { Loading, Ready, Ended, Error }
private enum class Panel { None, Tracks, Episodes, Info }

private const val SKIP_MS = 10_000L

/** Holding Left or Right skips further and further: remotes without rewind and fast-forward keys rely on it. */
private fun holdStep(repeatCount: Int): Long = when {
    repeatCount >= 12 -> 60_000L
    repeatCount >= 4 -> 30_000L
    else -> SKIP_MS
}
private const val NEXT_COUNTDOWN_S = 8
private const val LIVE_EDGE_MS = 6_000L

/** A selectable audio or subtitle track. */
private class TrackChoice(val label: String, val selected: Boolean, val select: () -> Unit)

/**
 * Full-screen player with Netflix-style controls: play/pause, 10-second skips, a timeline with elapsed and
 * remaining time, episodes, audio and subtitles, next episode, and for live streams a LIVE marker that jumps back
 * to the live edge. Left/Right skip, Up/Down zap between channels, OK shows the controls.
 */
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    startIndex: Int,
    aspect: AspectMode,
    onAspectChange: (AspectMode) -> Unit,
    onIndexChange: (Int) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var overlayVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(PlayStatus.Loading) }
    var errorName by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var formatToast by remember { mutableStateOf<AspectMode?>(null) }
    var skipToast by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf(Panel.None) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(C.TIME_UNSET) }
    var canSeek by remember { mutableStateOf(false) }
    var liveFlag by remember { mutableStateOf(false) }
    var behindLiveMs by remember { mutableLongStateOf(0L) }
    var countdown by remember { mutableIntStateOf(-1) }

    val player = remember { createPlayer(context) }
    val remote = remember { DeviceProfile.get(context) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    val current = channels[index]
    val isSeries = current.type == ContentType.SERIES
    val hasNext = isSeries && index < channels.lastIndex
    val isLiveStream = liveFlag || current.type == ContentType.LIVE

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> status = PlayStatus.Loading
                    Player.STATE_READY -> {
                        status = PlayStatus.Ready
                        errorName = null
                    }
                    Player.STATE_ENDED -> status = PlayStatus.Ended
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                liveFlag = player.isCurrentMediaItemLive
                canSeek = player.isCurrentMediaItemSeekable
            }

            override fun onPlayerError(error: PlaybackException) {
                // Only the error code name is shown: exception messages can contain the stream URL.
                errorName = error.errorCodeName
                status = PlayStatus.Error
                overlayVisible = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    LaunchedEffect(index) {
        onIndexChange(index)
        errorName = null
        countdown = -1
        status = PlayStatus.Loading
        liveFlag = false
        canSeek = false
        positionMs = 0L
        durationMs = C.TIME_UNSET
        player.setMediaItem(MediaItem.fromUri(channels[index].url))
        player.prepare()
        player.playWhenReady = true
    }

    // Progress ticker while the controls are visible.
    LaunchedEffect(overlayVisible, index) {
        while (overlayVisible) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration
            canSeek = player.isCurrentMediaItemSeekable
            behindLiveMs = if (player.isCurrentMediaItemLive) {
                player.currentLiveOffset.takeIf { it != C.TIME_UNSET } ?: 0L
            } else {
                0L
            }
            delay(500)
        }
    }

    // After the end of an episode the next one starts by itself unless Back is pressed.
    LaunchedEffect(status, index) {
        if (status == PlayStatus.Ended && hasNext) {
            overlayVisible = true
            for (left in NEXT_COUNTDOWN_S downTo 1) {
                countdown = left
                delay(1_000)
                if (countdown < 0) return@LaunchedEffect // cancelled with Back
            }
            countdown = -1
            index += 1
        }
    }

    LaunchedEffect(overlayVisible, panel) {
        withFrameNanos { }
        runCatching {
            when {
                panel != Panel.None -> panelFocus.requestFocus()
                overlayVisible -> playFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    LaunchedEffect(overlayVisible, interaction, errorName, panel, isPlaying) {
        if (overlayVisible && errorName == null && panel == Panel.None && isPlaying && countdown < 0) {
            delay(5_000)
            overlayVisible = false
        }
    }

    LaunchedEffect(formatToast) {
        if (formatToast != null) {
            delay(1_600)
            formatToast = null
        }
    }
    LaunchedEffect(skipToast) {
        if (skipToast != null) {
            delay(900)
            skipToast = null
        }
    }

    BackHandler {
        when {
            countdown >= 0 -> countdown = -1
            panel != Panel.None -> panel = Panel.None
            overlayVisible && errorName == null -> overlayVisible = false
            else -> onExit()
        }
    }

    fun changeChannel(delta: Int) {
        index = (index + delta + channels.size) % channels.size
    }

    fun changeAspect(next: AspectMode) {
        onAspectChange(next)
        formatToast = next
    }

    fun skip(deltaMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val duration = player.duration
        var target = player.currentPosition + deltaMs
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        player.seekTo(target.coerceAtLeast(0L))
        positionMs = target.coerceAtLeast(0L)
        skipToast = (if (deltaMs > 0) "+" else "-") + (kotlin.math.abs(deltaMs) / 1000)
    }

    fun goLive() {
        player.seekToDefaultPosition()
        player.play()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) interaction++
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Media keys of the remote work whatever is on screen.
                when (event.key) {
                    Key.MediaPlayPause -> { togglePlay(); return@onPreviewKeyEvent true }
                    Key.MediaPlay -> { player.play(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { player.pause(); return@onPreviewKeyEvent true }
                    Key.MediaFastForward, Key.MediaStepForward -> { skip(SKIP_MS); return@onPreviewKeyEvent true }
                    Key.MediaRewind, Key.MediaStepBackward -> { skip(-SKIP_MS); return@onPreviewKeyEvent true }
                    Key.MediaNext, Key.ChannelUp -> {
                        if (channels.size > 1) changeChannel(+1)
                        return@onPreviewKeyEvent true
                    }
                    Key.MediaPrevious, Key.ChannelDown -> {
                        if (channels.size > 1) changeChannel(-1)
                        return@onPreviewKeyEvent true
                    }
                    Key.Info -> {
                        overlayVisible = true
                        panel = if (panel == Panel.Info) Panel.None else Panel.Info
                        return@onPreviewKeyEvent true
                    }
                    else -> Unit
                }
                if (overlayVisible) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                        overlayVisible = true
                        true
                    }
                    Key.DirectionLeft -> {
                        if (canSeek) skip(-holdStep(event.nativeKeyEvent.repeatCount)) else overlayVisible = true
                        true
                    }
                    Key.DirectionRight -> {
                        if (canSeek) skip(holdStep(event.nativeKeyEvent.repeatCount)) else overlayVisible = true
                        true
                    }
                    Key.DirectionUp -> {
                        if (isLiveStream && channels.size > 1) changeChannel(+1) else overlayVisible = true
                        true
                    }
                    Key.DirectionDown -> {
                        if (isLiveStream && channels.size > 1) changeChannel(-1) else overlayVisible = true
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val videoModifier = aspect.ratio
            ?.let { Modifier.aspectRatio(it) }
            ?: Modifier.fillMaxSize()

        val exoPlayer = player
        AndroidView(
            modifier = videoModifier,
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    setShutterBackgroundColor(Color.Black.toArgb())
                    setPlayer(exoPlayer)
                }
            },
            update = { view ->
                view.resizeMode = when {
                    aspect.zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    aspect.stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
        )

        if (status == PlayStatus.Loading && errorName == null) {
            StatusChip(
                text = stringResource(R.string.player_buffering),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp),
            )
        }

        formatToast?.let { mode ->
            StatusChip(
                text = stringResource(R.string.player_format, aspectLabel(mode)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp),
            )
        }

        skipToast?.let {
            StatusChip(text = it + " s", modifier = Modifier.align(Alignment.Center))
        }

        if (countdown >= 0) {
            GlassPanel(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 190.dp),
                cornerRadius = 20.dp,
            ) {
                Column(Modifier.padding(horizontal = 26.dp, vertical = 16.dp)) {
                    PrismText(text = stringResource(R.string.np_next_episode), size = 20.sp, weight = FontWeight.Bold)
                    PrismText(text = countdown.toString() + " s", size = 18.sp, color = PrismColors.TextSecondary)
                }
            }
        }

        AnimatedVisibility(visible = overlayVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.70f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                    ),
            ) {
                // Top: what is playing on the left; audio and subtitles, format and info on one line on the right.
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(40.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        PrismText(
                            text = if (isSeries) (current.seriesTitle ?: current.name) else current.name,
                            size = 34.sp,
                            weight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        if (isSeries && current.season > 0) {
                            PrismText(
                                text = stringResource(R.string.season_n, current.season) + " · " + current.episode,
                                size = 20.sp,
                                color = PrismColors.TextSecondary,
                                maxLines = 1,
                            )
                        } else {
                            current.group?.let {
                                PrismText(text = it, size = 20.sp, color = PrismColors.TextSecondary, maxLines = 1)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassButton(
                            text = stringResource(R.string.np_audio_subs),
                            onClick = { panel = Panel.Tracks },
                        )
                        GlassButton(
                            text = stringResource(R.string.np_format),
                            onClick = { changeAspect(aspect.next()) },
                        )
                        InfoButton(onClick = { panel = Panel.Info })
                    }
                }

                // Bottom: the timeline and then every transport control on a single line.
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    errorName?.let {
                        PrismText(
                            text = stringResource(R.string.player_error, it),
                            size = 20.sp,
                            color = PrismColors.Danger,
                        )
                    }
                    Timeline(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        canSeek = canSeek,
                        live = isLiveStream,
                        behindLiveMs = behindLiveMs,
                        onSkip = ::skip,
                        onGoLive = ::goLive,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canSeek) {
                            TransportButton(icon = TransportIcon.REWIND, onClick = { skip(-SKIP_MS) })
                        }
                        TransportButton(
                            icon = if (isPlaying) TransportIcon.PAUSE else TransportIcon.PLAY,
                            onClick = { if (isPlaying) player.pause() else player.play() },
                            focusRequester = playFocus,
                            selected = true,
                        )
                        if (canSeek) {
                            TransportButton(icon = TransportIcon.FAST_FORWARD, onClick = { skip(SKIP_MS) })
                        }
                        if (isSeries) {
                            GlassButton(
                                text = stringResource(R.string.np_episodes),
                                onClick = { panel = Panel.Episodes },
                            )
                        }
                        if (hasNext) {
                            GlassButton(
                                text = stringResource(R.string.np_next_episode),
                                onClick = { changeChannel(+1) },
                            )
                        } else if (!isSeries && channels.size > 1) {
                            GlassButton(text = stringResource(R.string.player_previous), onClick = { changeChannel(-1) })
                            GlassButton(text = stringResource(R.string.player_next), onClick = { changeChannel(+1) })
                        }
                    }
                    PrismText(
                        text = stringResource(if (remote.minimalRemote) R.string.player_hint_hold else R.string.player_hint),
                        size = 15.sp,
                        color = PrismColors.TextSecondary,
                    )
                }

                if (panel != Panel.None) {
                    SidePanel(
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        when (panel) {
                            Panel.Tracks -> TracksPanel(tracks, player, panelFocus) { panel = Panel.None }
                            Panel.Episodes -> EpisodesPanel(channels, index, panelFocus) {
                                index = it
                                panel = Panel.None
                            }
                            Panel.Info -> InfoPanel(player, panelFocus) { panel = Panel.None }
                            Panel.None -> Unit
                        }
                    }
                }
            }
        }
    }
}

// --- timeline -----------------------------------------------------------------------------------------------

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun Timeline(
    positionMs: Long,
    durationMs: Long,
    canSeek: Boolean,
    live: Boolean,
    behindLiveMs: Long,
    onSkip: (Long) -> Unit,
    onGoLive: () -> Unit,
) {
    val hasDuration = durationMs != C.TIME_UNSET && durationMs > 0
    if (!hasDuration && !live) return
    var focused by remember { mutableStateOf(false) }
    val atEdge = live && behindLiveMs < LIVE_EDGE_MS

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (hasDuration && canSeek) {
            PrismText(text = formatTime(positionMs), size = 16.sp, color = PrismColors.TextSecondary)
            Box(
                Modifier
                    .weight(1f)
                    .height(if (focused) 12.dp else 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.25f))
                    .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(6.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { onSkip(-SKIP_MS); true }
                            Key.DirectionRight -> { onSkip(SKIP_MS); true }
                            else -> false
                        }
                    }
                    .focusable(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(PrismColors.Danger),
                )
            }
            PrismText(
                text = if (live) formatTime(durationMs) else "-" + formatTime(durationMs - positionMs),
                size = 16.sp,
                color = PrismColors.TextSecondary,
            )
        } else {
            Box(Modifier.weight(1f))
        }
        if (live) {
            GlassButton(
                text = "● " + stringResource(R.string.np_live),
                onClick = onGoLive,
                tint = if (atEdge) PrismColors.Danger else null,
            )
        }
    }
}

// --- panels ---------------------------------------------------------------------------------------------------

@Composable
private fun SidePanel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    GlassPanel(
        modifier
            .padding(end = 40.dp)
            .width(420.dp)
            .fillMaxHeight(0.7f),
        cornerRadius = 24.dp,
    ) {
        Column(Modifier.padding(22.dp).confineFocus(horizontal = true), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun trackName(group: Tracks.Group, i: Int, number: Int): String {
    val format = group.getTrackFormat(i)
    val language = format.language?.takeIf { it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ?.takeIf { it.isNotBlank() }
    val base = TextCleaner.cleanOrNull(format.label) ?: language?.replaceFirstChar { it.uppercase() }
        ?: stringResource(R.string.np_track_default, number)
    val channels = format.channelCount.takeIf { it > 0 && group.type == C.TRACK_TYPE_AUDIO }
    return if (channels != null) "$base · ${channelLabel(channels)}" else base
}

private fun channelLabel(count: Int): String = when (count) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    else -> count.toString()
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TracksPanel(tracks: Tracks, player: Player, focus: FocusRequester, onClose: () -> Unit) {
    val audio = ArrayList<TrackChoice>()
    val subs = ArrayList<TrackChoice>()
    var anyTextSelected = false
    var n = 0
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            n++
            val label = trackName(group, i, n)
            val selected = group.isTrackSelected(i)
            if (group.type == C.TRACK_TYPE_TEXT && selected) anyTextSelected = true
            val choice = TrackChoice(label, selected) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(group.type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
            if (group.type == C.TRACK_TYPE_AUDIO) audio += choice else subs += choice
        }
    }
    val off = TrackChoice(stringResource(R.string.np_subtitles_off), !anyTextSelected) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    PrismText(text = stringResource(R.string.np_audio_subs), size = 24.sp, weight = FontWeight.Bold)
    if (audio.isEmpty() && subs.isEmpty()) {
        PrismText(text = stringResource(R.string.np_no_tracks), size = 18.sp, color = PrismColors.TextSecondary)
        GlassButton(text = stringResource(R.string.np_close), onClick = onClose, focusRequester = focus)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
        item { PrismText(text = stringResource(R.string.np_audio), size = 18.sp, color = PrismColors.Accent) }
        itemsIndexed(audio) { i, choice -> TrackRow(choice, if (i == 0) focus else null, onClose) }
        item { PrismText(text = stringResource(R.string.np_subtitles), size = 18.sp, color = PrismColors.Accent) }
        itemsIndexed(listOf(off) + subs) { i, choice ->
            TrackRow(choice, if (audio.isEmpty() && i == 0) focus else null, onClose)
        }
    }
}

@Composable
private fun TrackRow(choice: TrackChoice, focus: FocusRequester?, onClose: () -> Unit) {
    GlassSurface(
        onClick = {
            choice.select()
            onClose()
        },
        modifier = Modifier.fillMaxWidth(),
        selected = choice.selected,
        focusRequester = focus,
        focusScale = 1.02f,
    ) {
        PrismText(
            text = (if (choice.selected) "✓ " else "") + choice.label,
            size = 18.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EpisodesPanel(channels: List<Channel>, current: Int, focus: FocusRequester, onPick: (Int) -> Unit) {
    PrismText(text = stringResource(R.string.np_episodes), size = 24.sp, weight = FontWeight.Bold)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
        itemsIndexed(channels) { i, episode ->
            GlassSurface(
                onClick = { onPick(i) },
                modifier = Modifier.fillMaxWidth(),
                selected = i == current,
                focusRequester = if (i == current) focus else null,
                focusScale = 1.02f,
            ) {
                PrismText(
                    text = (if (episode.episode > 0) "%02d  ".format(episode.episode) else "") + episode.name,
                    size = 18.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.InfoPanel(player: Player, focus: FocusRequester, onClose: () -> Unit) {
    PrismText(text = stringResource(R.string.np_info), size = 24.sp, weight = FontWeight.Bold)
    val exo = player as? androidx.media3.exoplayer.ExoPlayer
    val video = exo?.videoFormat
    val audio = exo?.audioFormat
    fun mime(value: String?) = value?.substringAfter('/')?.uppercase().orEmpty()
    val lines = buildList {
        if (video != null && video.width > 0) {
            add("${video.width}x${video.height}" + (if (video.frameRate > 0) " · %.0f fps".format(video.frameRate) else ""))
            add(mime(video.sampleMimeType) + (if (video.bitrate > 0) " · ${video.bitrate / 1000} kb/s" else ""))
        }
        if (audio != null) {
            add(mime(audio.sampleMimeType) + (if (audio.channelCount > 0) " · ${channelLabel(audio.channelCount)}" else ""))
        }
    }
    for (line in lines) PrismText(text = line, size = 18.sp, color = PrismColors.TextSecondary)
    GlassButton(text = stringResource(R.string.np_close), onClick = onClose, focusRequester = focus)
}

@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier) {
    GlassPanel(modifier, cornerRadius = 20.dp) {
        PrismText(
            text = text,
            size = 20.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
fun aspectLabel(mode: AspectMode): String = mode.label ?: stringResource(
    when (mode) {
        AspectMode.FIT_SCREEN -> R.string.aspect_fit_screen
        AspectMode.ZOOM -> R.string.aspect_zoom
        else -> R.string.aspect_auto
    },
)
