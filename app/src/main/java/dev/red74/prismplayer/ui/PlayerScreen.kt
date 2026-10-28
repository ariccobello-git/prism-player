package dev.red74.prismplayer.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.player.AspectMode
import kotlinx.coroutines.delay

private enum class PlayStatus { Loading, Ready, Ended, Error }

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

    val player = remember { createPlayer(context) }
    val rootFocus = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }

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
        status = PlayStatus.Loading
        player.setMediaItem(MediaItem.fromUri(channels[index].url))
        player.prepare()
        player.playWhenReady = true
    }

    // Keep D-pad focus somewhere sensible: on the menu when it is open, on the video surface otherwise.
    LaunchedEffect(overlayVisible) {
        withFrameNanos { }
        runCatching {
            if (overlayVisible) overlayFocus.requestFocus() else rootFocus.requestFocus()
        }
    }

    LaunchedEffect(overlayVisible, interaction, errorName) {
        if (overlayVisible && errorName == null) {
            delay(7_000)
            overlayVisible = false
        }
    }

    LaunchedEffect(formatToast) {
        if (formatToast != null) {
            delay(1_600)
            formatToast = null
        }
    }

    BackHandler {
        if (overlayVisible && errorName == null) overlayVisible = false else onExit()
    }

    fun changeChannel(delta: Int) {
        index = (index + delta + channels.size) % channels.size
    }

    fun changeAspect(next: AspectMode) {
        onAspectChange(next)
        formatToast = next
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) interaction++
                if (overlayVisible || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                        overlayVisible = true
                        true
                    }
                    Key.DirectionUp, Key.ChannelUp -> {
                        changeChannel(+1)
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        changeChannel(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        changeAspect(aspect.next())
                        true
                    }
                    Key.DirectionLeft -> {
                        changeAspect(aspect.previous())
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
                view.resizeMode = if (aspect.stretch) {
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
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
                    .padding(48.dp),
            )
        }

        AnimatedVisibility(visible = overlayVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.75f),
                            ),
                        ),
                    ),
            ) {
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(40.dp),
                ) {
                    PrismText(
                        text = channels[index].name,
                        size = 34.sp,
                        weight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    channels[index].group?.let {
                        PrismText(text = it, size = 20.sp, color = PrismColors.TextSecondary, maxLines = 1)
                    }
                }

                GlassPanel(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(40.dp),
                ) {
                    Column(
                        Modifier.padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        errorName?.let {
                            PrismText(
                                text = stringResource(R.string.player_error, it),
                                size = 20.sp,
                                color = PrismColors.Danger,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            GlassButton(
                                text = stringResource(R.string.player_format, aspectLabel(aspect)),
                                onClick = { changeAspect(aspect.next()) },
                                focusRequester = overlayFocus,
                                emphasized = true,
                            )
                            GlassButton(
                                text = stringResource(R.string.player_previous),
                                onClick = { changeChannel(-1) },
                            )
                            GlassButton(
                                text = stringResource(
                                    if (isPlaying) R.string.player_pause else R.string.player_play,
                                ),
                                onClick = { if (isPlaying) player.pause() else player.play() },
                            )
                            GlassButton(
                                text = stringResource(R.string.player_next),
                                onClick = { changeChannel(+1) },
                            )
                            GlassButton(
                                text = stringResource(R.string.action_back),
                                onClick = onExit,
                            )
                        }
                        PrismText(
                            text = stringResource(R.string.player_hint),
                            size = 16.sp,
                            color = PrismColors.TextSecondary,
                        )
                    }
                }
            }
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
fun aspectLabel(mode: AspectMode): String = stringResource(
    when (mode) {
        AspectMode.AUTO -> R.string.aspect_auto
        AspectMode.RATIO_16_9 -> R.string.aspect_16_9
        AspectMode.RATIO_4_3 -> R.string.aspect_4_3
        AspectMode.FIT_SCREEN -> R.string.aspect_fit_screen
    },
)

private fun createPlayer(context: Context): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("PrismPlayer/0.1 (Android TV)")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)
    // IPTV panels often serve raw MPEG-TS (output=mpegts). These standard Media3 flags make such streams start
    // and keep sync more reliably, and decoder fallback retries with another decoder when one fails.
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
        )
        .setConstantBitrateSeekingEnabled(true)
    val renderers = DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors).setDataSourceFactory(httpFactory))
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .build()
}
