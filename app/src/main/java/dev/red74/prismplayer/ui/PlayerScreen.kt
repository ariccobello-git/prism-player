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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Availability
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.device.DeviceProfile
import dev.red74.prismplayer.data.TextCleaner
import dev.red74.prismplayer.data.WatchProgress
import dev.red74.prismplayer.player.AspectMode
import dev.red74.prismplayer.player.FastScan
import dev.red74.prismplayer.player.PreviewPlayers
import dev.red74.prismplayer.player.StreamConnections
import dev.red74.prismplayer.player.StreamIdentity
import dev.red74.prismplayer.player.TrackPolicy
import dev.red74.prismplayer.player.createPlayer
import dev.red74.prismplayer.data.ProviderHosts
import dev.red74.prismplayer.data.StreamHold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/**
 * Pause after a change of channel before the new one connects: holding Up or Down runs through the channels and
 * only the one the viewer stops on opens a connection.
 */
private const val ZAP_SETTLE_MS = 300L
private const val LIVE_EDGE_MS = 6_000L
private const val CHANNEL_BANNER_MS = 4_000L

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
    onPlaybackWorked: (Channel) -> Unit = {},
    onPlaybackFailed: (Channel, Availability.Trouble, Boolean) -> Unit = { _, _, _ -> },
    /** The programme on air on a live channel (from the guide), shown in the banner after a change of channel. */
    nowTitle: (Channel) -> String? = { null },
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var overlayVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(PlayStatus.Loading) }
    var errorName by remember { mutableStateOf<String?>(null) }
    var trouble by remember { mutableStateOf<Availability.Trouble?>(null) }
    var troubleHttp by remember { mutableStateOf<Int?>(null) }
    /** Tries of the current channel (see [StreamIdentity.plan]) and a request for the next one. */
    var attempt by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf(StreamIdentity.plan()) }
    var retryRequest by remember { mutableIntStateOf(0) }
    var startedOk by remember { mutableStateOf(false) }
    /** Audio and subtitles follow [TrackPolicy] until the viewer picks them in the panel. */
    var autoTracks by remember { mutableStateOf(true) }
    val appliedTracks = remember { arrayOfNulls<String>(1) }
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
    /** Fast scan like other players: 0 normal, +2 up to +128 faster forward, -2 up to -128 backwards (see FastScan.SPEEDS). */
    var scan by remember { mutableIntStateOf(0) }
    /** Where the scan clock is while scanning by pictures (see [FastScan]), -1 otherwise. */
    var scanPosMs by remember { mutableLongStateOf(-1L) }
    /** True when 2x could not keep up with real playback and moved to the scan clock. */
    var scanBySeeking by remember { mutableStateOf(false) }
    /** "Buffering" appears only when loading lasts, not for every short seek. */
    var showBuffering by remember { mutableStateOf(false) }
    /** Pictures put on screen after a seek; a counter outside Compose state so it never redraws the screen. */
    val framesShown = remember { intArrayOf(0) }

    val player = remember { createPlayer(context) }
    val hold = remember { StreamHold() }
    val remote = remember { DeviceProfile.get() }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    /** [0]: the first content has been set up (later runs are channel changes). [1]: the app is in the background. */
    val flags = remember { booleanArrayOf(false, false) }
    /** True when the stream was closed because the app went to the background; it reconnects when it comes back. */
    var parked by remember { mutableStateOf(false) }
    /** Bumped at each change of live channel: the banner with the channel's name shows for a few seconds. */
    var bannerRequest by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(false) }

    val current = channels[index]
    val isSeries = current.type == ContentType.SERIES
    val hasNext = isSeries && index < channels.lastIndex
    val isLiveStream = liveFlag || current.type == ContentType.LIVE
    val currentChannels by rememberUpdatedState(channels)
    val worked by rememberUpdatedState(onPlaybackWorked)
    /** Films and episodes remember where they were left (see [WatchProgress]); live channels do not. */
    val keepsProgress = current.type != ContentType.LIVE
    val failed by rememberUpdatedState(onPlaybackFailed)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> status = PlayStatus.Loading
                    Player.STATE_READY -> {
                        status = PlayStatus.Ready
                        errorName = null
                        if (!startedOk) {
                            startedOk = true
                            StreamIdentity.preferred = StreamIdentity.current
                            currentChannels.getOrNull(index)?.let(worked)
                        }
                    }
                    Player.STATE_ENDED -> {
                        status = PlayStatus.Ended
                        currentChannels.getOrNull(index)?.let { WatchProgress.clear(it.url) }
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onRenderedFirstFrame() {
                framesShown[0]++
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
                if (autoTracks) applyTrackPolicy(player, newTracks, appliedTracks)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                liveFlag = player.isCurrentMediaItemLive
                canSeek = player.isCurrentMediaItemSeekable
            }

            override fun onPlayerError(error: PlaybackException) {
                // A live channel paused for long falls out of the provider's window: it simply goes back to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    if (flags[1]) parked = true else player.prepare()
                    return
                }
                val http = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                val kind = Availability.classify(error.errorCode, http)
                // HTTP errors are tried again silently: a moment later, then with the other identities.
                if (!startedOk && Availability.retries(kind) && attempt < plan.lastIndex) {
                    attempt++
                    retryRequest++
                    return
                }
                currentChannels.getOrNull(index)?.let { failed(it, kind, attempt >= plan.lastIndex) }
                trouble = kind
                troubleHttp = http
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
            hold.release()
        }
    }

    // Declared after the listener effect, so on leaving it saves before the player is released.
    DisposableEffect(index) {
        val url = channels[index].url
        onDispose { if (keepsProgress) saveProgress(player, url) }
    }

    // In the background (Home key, TV switched to standby) the stream is closed, not just paused: the list's one
    // connection is never left open while nobody watches. Coming back reconnects at the same point, paused.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        flags[1] = true
        player.pause()
        if (keepsProgress) saveProgress(player, current.url)
        if (player.playbackState != Player.STATE_IDLE) {
            player.stop()
            parked = true
        }
        hold.release()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        flags[1] = false
        val resume = parked
        parked = false
        if (resume) overlayVisible = true
        scope.launch {
            // Back in front, the player holds the line again (no list or guide download while it is open).
            hold.acquire()
            if (!resume) return@launch
            StreamConnections.awaitNone()
            if (flags[1]) { parked = true; hold.release(); return@launch }
            if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
            player.prepare()
        }
    }

    // While playing, the point is kept every few seconds, in case the TV is switched off.
    LaunchedEffect(index, isPlaying) {
        if (!keepsProgress || !isPlaying) return@LaunchedEffect
        while (true) {
            delay(PROGRESS_SAVE_MS)
            saveProgress(player, channels[index].url)
        }
    }

    LaunchedEffect(index) {
        val zapping = flags[0]
        flags[0] = true
        // Changing channel: the old one is closed at once (its connection ends on the player's own thread).
        if (zapping) player.stop()
        onIndexChange(index)
        errorName = null
        trouble = null
        attempt = 0
        startedOk = false
        plan = StreamIdentity.plan()
        StreamIdentity.current = plan.first()
        // Panels often allow one connection per account: the preview's must be closed before this one opens.
        PreviewPlayers.stopAll()
        // Any list, guide or plot request still talking to the panel is closed before the stream opens.
        hold.acquire()
        ProviderHosts.noteUrl(channels[index].url)
        // Every content starts in Italian without subtitles (see TrackPolicy); the tracks decide the rest.
        autoTracks = true
        appliedTracks[0] = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(TrackPolicy.ITALIAN)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        countdown = -1
        status = PlayStatus.Loading
        scanPosMs = -1L
        scan = 0
        player.setPlaybackSpeed(1f)
        liveFlag = false
        canSeek = false
        positionMs = 0L
        durationMs = C.TIME_UNSET
        if (zapping) delay(ZAP_SETTLE_MS)
        // The preview's or the previous channel's connection must be really closed before this one opens.
        StreamConnections.awaitNone()
        val resumeMs = if (keepsProgress) WatchProgress.get(channels[index].url)?.let(WatchProgress::resumeFrom) else null
        if (resumeMs != null && resumeMs > 0) {
            player.setMediaItem(MediaItem.fromUri(channels[index].url), resumeMs)
            skipToast = context.getString(R.string.resume_toast, formatTime(resumeMs))
        } else {
            player.setMediaItem(MediaItem.fromUri(channels[index].url))
        }
        player.playWhenReady = true
        // Gone to the background meanwhile: it connects when the app comes back.
        if (flags[1]) {
            parked = true
            hold.release()
            return@LaunchedEffect
        }
        player.prepare()
    }

    LaunchedEffect(retryRequest) {
        if (retryRequest == 0) return@LaunchedEffect
        val tryIndex = index
        delay(if (attempt == 1) Availability.RETRY_DELAY_MS else Availability.NEXT_IDENTITY_DELAY_MS)
        if (tryIndex != index) return@LaunchedEffect
        StreamIdentity.current = plan[attempt.coerceAtMost(plan.lastIndex)]
        // In the background: the errored player keeps its item and tries again when the app comes back.
        if (flags[1]) { parked = true; return@LaunchedEffect }
        hold.acquire()
        StreamConnections.awaitNone()
        ProviderHosts.noteUrl(channels[index].url)
        val resumeMs = if (keepsProgress) WatchProgress.get(channels[index].url)?.let(WatchProgress::resumeFrom) else null
        if (resumeMs != null && resumeMs > 0) {
            player.setMediaItem(MediaItem.fromUri(channels[index].url), resumeMs)
        } else {
            player.setMediaItem(MediaItem.fromUri(channels[index].url))
        }
        player.prepare()
        player.playWhenReady = true
    }

    // Progress ticker while the controls are visible.
    LaunchedEffect(overlayVisible, index) {
        while (overlayVisible) {
            positionMs = if (scanPosMs >= 0L) scanPosMs else player.currentPosition.coerceAtLeast(0L)
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
    LaunchedEffect(status, scan) {
        showBuffering = false
        if (status == PlayStatus.Loading && scan == 0) {
            delay(700)
            showBuffering = true
        }
    }

    // Fast scan (see FastScan): 2x plays for real while the stream keeps up; 4x, 8x and every rewind move a clock at
    // exactly the chosen speed and show a key frame near it each time the previous picture is on screen.
    LaunchedEffect(scan, scanBySeeking) {
        if (scan == 0) {
            if (scanBySeeking) scanBySeeking = false
            player.setSeekParameters(SeekParameters.DEFAULT)
            player.setPlaybackSpeed(1f)
            player.volume = 1f
            if (scanPosMs >= 0L) {
                player.seekTo(scanPosMs)
                positionMs = scanPosMs
                scanPosMs = -1L
                player.play()
            }
            return@LaunchedEffect
        }
        if (FastScan.playsForReal(scan) && !scanBySeeking) {
            player.setSeekParameters(SeekParameters.DEFAULT)
            if (scanPosMs >= 0L) {
                player.seekTo(scanPosMs)
                scanPosMs = -1L
            }
            // No sound while scanning: sped-up audio is only noise.
            player.volume = 0f
            player.setPlaybackSpeed(scan.toFloat())
            player.play()
            var wasReady = false
            var stalledMs = 0L
            while (true) {
                delay(100)
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration
                when (player.playbackState) {
                    Player.STATE_READY -> { wasReady = true; stalledMs = 0L }
                    Player.STATE_BUFFERING -> stalledMs += 100
                    Player.STATE_ENDED -> { scan = 0; return@LaunchedEffect }
                    else -> Unit
                }
                val offset = if (player.isCurrentMediaItemLive) player.currentLiveOffset else C.TIME_UNSET
                if (offset != C.TIME_UNSET && offset < LIVE_EDGE_MS) { scan = 0; return@LaunchedEffect }
                // Before the first picture a longer wait is normal (the stream is still starting).
                val allowed = if (wasReady) FastScan.STALL_FALLBACK_MS else FastScan.STALL_FALLBACK_MS * 4
                if (stalledMs >= allowed) { scanBySeeking = true; return@LaunchedEffect }
            }
        }
        player.setPlaybackSpeed(1f)
        player.pause()
        // Key frames only: no decoding from the key frame up to the exact position, which is what makes seeks slow.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        var pos = if (scanPosMs >= 0L) scanPosMs else player.currentPosition.coerceAtLeast(0L)
        scanPosMs = pos
        var last = withFrameMillis { it }
        var lastSeekAt = last - FastScan.MAX_SEEK_WAIT_MS
        var framesAtSeek = framesShown[0]
        var lastShownAt = 0L
        var lastTarget = -1L
        while (true) {
            val now = withFrameMillis { it }
            pos = FastScan.advance(pos, scan, now - last)
            last = now
            val limit = FastScan.forwardLimit(player.duration.takeIf { it != C.TIME_UNSET && it > 0L })
            val reachedEnd = pos <= 0L || (limit != null && pos >= limit)
            pos = pos.coerceAtLeast(0L).let { if (limit != null) it.coerceAtMost(limit) else it }
            if (reachedEnd) {
                scanPosMs = pos
                scan = 0
                return@LaunchedEffect
            }
            val pictureShown = framesShown[0] != framesAtSeek || player.playbackState == Player.STATE_READY
            // Jumps by whole blocks (see FastScan.blockMs): a slow answer skips blocks, the speed never drops.
            val target = FastScan.blockTarget(pos, scan).let { if (limit != null) it.coerceAtMost(limit) else it }
            if (target != lastTarget && FastScan.shouldSeek(now - lastSeekAt, pictureShown)) {
                framesAtSeek = framesShown[0]
                player.seekTo(target)
                lastTarget = target
                lastSeekAt = now
            }
            if (now - lastShownAt >= 100L) {
                scanPosMs = pos
                positionMs = pos
                durationMs = player.duration
                lastShownAt = now
            }
        }
    }

    // Each new press keeps the banner up: it goes a few seconds after the last change of channel.
    LaunchedEffect(bannerRequest) {
        if (bannerRequest == 0) return@LaunchedEffect
        bannerVisible = true
        delay(CHANNEL_BANNER_MS)
        bannerVisible = false
    }

    LaunchedEffect(skipToast) {
        val toast = skipToast
        if (toast != null) {
            // "+10"/"-10" from skips flash by; the resume note stays long enough to be read.
            delay(if (toast.startsWith("+") || toast.startsWith("-")) 900 else 2_500)
            skipToast = null
        }
    }

    BackHandler {
        when {
            scan != 0 -> scan = 0
            countdown >= 0 -> countdown = -1
            panel != Panel.None -> panel = Panel.None
            overlayVisible && errorName == null -> overlayVisible = false
            else -> onExit()
        }
    }

    fun changeChannel(delta: Int) {
        index = (index + delta + channels.size) % channels.size
        if (channels[index].type == ContentType.LIVE) bannerRequest++
    }

    fun changeAspect(next: AspectMode) {
        onAspectChange(next)
        formatToast = next
    }

    fun skip(deltaMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val from = if (scanPosMs >= 0L) scanPosMs else player.currentPosition
        scanPosMs = -1L
        scan = 0
        val duration = player.duration
        var target = from + deltaMs
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        player.seekTo(target.coerceAtLeast(0L))
        positionMs = target.coerceAtLeast(0L)
        skipToast = (if (deltaMs > 0) "+" else "-") + (kotlin.math.abs(deltaMs) / 1000) + " s"
    }

    /** Each press goes one step further (2x up to 128x, see FastScan.SPEEDS), then back to normal speed. */
    fun cycleScan(direction: Int) {
        if (!player.isCurrentMediaItemSeekable) return
        scan = FastScan.next(scan, direction)
    }

    fun goLive() {
        scanPosMs = -1L
        scan = 0
        player.seekToDefaultPosition()
        player.play()
    }

    /**
     * After an error (the line dropped, the provider refused) Play tries again: a film or an episode goes on from
     * where it stopped, a live channel from its live point.
     */
    fun retryAfterError() {
        errorName = null
        trouble = null
        troubleHttp = null
        status = PlayStatus.Loading
        attempt = 0
        startedOk = false
        plan = StreamIdentity.plan()
        StreamIdentity.current = plan.first()
        if (isLiveStream) player.seekToDefaultPosition()
        player.playWhenReady = true
        scope.launch {
            hold.acquire()
            StreamConnections.awaitNone()
            if (flags[1]) { parked = true; hold.release(); return@launch }
            player.prepare()
        }
    }

    fun play() {
        if (status == PlayStatus.Error && player.playbackState == Player.STATE_IDLE) retryAfterError() else player.play()
    }

    fun togglePlay() {
        // Ending a scan resumes normal playback from where the scan arrived.
        if (scan != 0) { scan = 0; player.play(); return }
        if (player.isPlaying) player.pause() else play()
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
                    Key.MediaPlay -> { play(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { player.pause(); return@onPreviewKeyEvent true }
                    Key.MediaFastForward -> { cycleScan(+1); return@onPreviewKeyEvent true }
                    Key.MediaStepForward -> { skip(SKIP_MS); return@onPreviewKeyEvent true }
                    Key.MediaRewind -> { cycleScan(-1); return@onPreviewKeyEvent true }
                    Key.MediaStepBackward -> { skip(-SKIP_MS); return@onPreviewKeyEvent true }
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

        if (showBuffering && errorName == null) {
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
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (scan != 0) {
            ScanBadge(
                scan = scan,
                time = durationMs.takeIf { !isLiveStream && it != C.TIME_UNSET && it > 0L }
                    ?.let { formatTime(positionMs) + " / " + formatTime(it) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        skipToast?.let {
            StatusChip(text = it, modifier = Modifier.align(Alignment.Center))
        }

        // The controls show the name themselves, so the banner stays out of their way.
        AnimatedVisibility(
            visible = bannerVisible && !overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            ChannelBanner(number = index + 1, channel = current, programme = remember(current) { nowTitle(current) })
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
                            text = troubleText(trouble, troubleHttp) ?: stringResource(R.string.player_error, it),
                            size = 20.sp,
                            color = PrismColors.Danger,
                        )
                        PrismText(text = stringResource(R.string.player_retry_hint), size = 16.sp, color = PrismColors.TextSecondary)
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
                            TransportButton(icon = TransportIcon.REWIND, onClick = { cycleScan(-1) })
                        }
                        TransportButton(
                            icon = if (isPlaying) TransportIcon.PAUSE else TransportIcon.PLAY,
                            onClick = { togglePlay() },
                            focusRequester = playFocus,
                            selected = true,
                        )
                        if (canSeek) {
                            TransportButton(icon = TransportIcon.FAST_FORWARD, onClick = { cycleScan(+1) })
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
                            Panel.Tracks -> TracksPanel(tracks, player, panelFocus, onPicked = { autoTracks = false }) { panel = Panel.None }
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

private const val PROGRESS_SAVE_MS = 10_000L

/** Only a real position is kept: before the content starts the player reports 0 and no duration. */
private fun saveProgress(player: Player, url: String) {
    if (player.playbackState == Player.STATE_IDLE || player.isCurrentMediaItemLive) return
    val duration = player.duration
    WatchProgress.save(url, player.currentPosition, if (duration == C.TIME_UNSET) 0L else duration)
}

internal fun formatTime(ms: Long): String {
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

/**
 * Applies [TrackPolicy] to the tracks a content offers: Italian audio when there is one, and the subtitles it
 * asks for (Italian, else English, else none). Tracks can appear in steps (subtitles found later in a stream),
 * so this runs on each change; [applied] keeps the last choice so nothing is set twice.
 */
private fun applyTrackPolicy(player: Player, tracks: Tracks, applied: Array<String?>) {
    var italianAudio: Tracks.Group? = null
    var italianAudioIndex = 0
    val audioLanguages = ArrayList<String?>()
    val textLanguages = ArrayList<String?>()
    for (group in tracks.groups) {
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val language = group.getTrackFormat(i).language
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    audioLanguages += language
                    if (italianAudio == null && TrackPolicy.normalize(language) == TrackPolicy.ITALIAN) {
                        italianAudio = group
                        italianAudioIndex = i
                    }
                }
                C.TRACK_TYPE_TEXT -> textLanguages += language
            }
        }
    }
    if (audioLanguages.isEmpty() && textLanguages.isEmpty()) return
    val subtitles = TrackPolicy.subtitles(audioLanguages, textLanguages)
    val decision = "${italianAudio != null}|$subtitles|${audioLanguages.size}|${textLanguages.size}"
    if (applied[0] == decision) return
    applied[0] = decision
    val builder = player.trackSelectionParameters.buildUpon().clearOverrides()
    italianAudio?.let { builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, italianAudioIndex)) }
    if (subtitles == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(subtitles)
            .setSelectUndeterminedTextLanguage(false)
        val group = tracks.groups.firstOrNull { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSupported(it) && TrackPolicy.normalize(g.getTrackFormat(it).language) == subtitles }
        }
        if (group != null) {
            val index = (0 until group.length).first { group.isTrackSupported(it) && TrackPolicy.normalize(group.getTrackFormat(it).language) == subtitles }
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        }
    }
    player.trackSelectionParameters = builder.build()
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TracksPanel(
    tracks: Tracks,
    player: Player,
    focus: FocusRequester,
    onPicked: () -> Unit,
    onClose: () -> Unit,
) {
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
                onPicked()
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(group.type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
            if (group.type == C.TRACK_TYPE_AUDIO) audio += choice else subs += choice
        }
    }
    val off = TrackChoice(stringResource(R.string.np_subtitles_off), !anyTextSelected) {
        onPicked()
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

/** A plain explanation of what went wrong on the provider's side, or null for the other errors. */
@Composable
private fun troubleText(trouble: Availability.Trouble?, http: Int?): String? {
    val code = http?.toString() ?: "?"
    return when (trouble) {
        Availability.Trouble.REFUSED -> stringResource(R.string.player_trouble_refused, code)
        Availability.Trouble.MISSING -> stringResource(R.string.player_trouble_missing, code)
        Availability.Trouble.SERVER -> stringResource(R.string.player_trouble_server, code)
        Availability.Trouble.NO_ANSWER -> stringResource(R.string.player_trouble_no_answer)
        Availability.Trouble.BAD_STREAM -> stringResource(R.string.player_trouble_bad_stream)
        Availability.Trouble.OTHER, null -> null
    }
}

/** Number, logo, name and programme of the channel just tuned to, at the top left of the picture. */
@Composable
private fun ChannelBanner(number: Int, channel: Channel, programme: String?) {
    GlassPanel(Modifier.padding(40.dp).width(560.dp), cornerRadius = 22.dp) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismText(text = number.toString(), size = 30.sp, weight = FontWeight.Bold, color = PrismColors.Accent)
            Poster(channel.logo, channel.name, Modifier.width(64.dp).height(64.dp), fit = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrismText(text = channel.name, size = 26.sp, weight = FontWeight.Bold, maxLines = 1)
                val second = programme ?: channel.group
                if (!second.isNullOrBlank()) {
                    PrismText(text = second, size = 18.sp, color = PrismColors.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** The 2x...128x sign in the middle of the picture, with the time it has reached. */
@Composable
private fun ScanBadge(scan: Int, time: String?, modifier: Modifier = Modifier) {
    GlassPanel(modifier, cornerRadius = 28.dp) {
        Column(
            Modifier.padding(horizontal = 40.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrismText(
                text = (if (scan > 0) "▶▶  " else "◀◀  ") + kotlin.math.abs(scan) + "×",
                size = 44.sp,
                weight = FontWeight.Bold,
            )
            time?.let { PrismText(text = it, size = 20.sp, color = PrismColors.TextSecondary) }
        }
    }
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
