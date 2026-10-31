package dev.red74.prismplayer.player

import dev.red74.prismplayer.data.StreamHold
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/**
 * How much of the stream is kept ahead (and behind) the playing point.
 *
 * Media3's defaults (50 s ahead, 2.5 s before starting, 5 s after a stall, nothing behind, up to ~140 MB) suit a
 * phone; on a 1-2 GB TV stick with IPTV panels they start slowly and use a lot of memory. The full-screen player
 * starts after 1.5 s, keeps 30-60 s ahead within 64 MB, and keeps 20 s behind so a 10-second skip back does not
 * download again. The preview starts like the full-screen player (1.5 s) and keeps 15-30 s ahead within 24 MB:
 * with only 1 s at the start it ran dry at every hiccup of the line (VPN, busy panel) and played in jerks.
 */
object BufferPlan {
    data class Durations(
        val minMs: Int,
        val maxMs: Int,
        val startMs: Int,
        val afterStallMs: Int,
        val backMs: Int,
        val maxBytes: Int,
    )

    val FULL = Durations(minMs = 30_000, maxMs = 60_000, startMs = 1_500, afterStallMs = 3_500, backMs = 20_000, maxBytes = 64 shl 20)
    val PREVIEW = Durations(minMs = 15_000, maxMs = 30_000, startMs = 1_500, afterStallMs = 3_500, backMs = 0, maxBytes = 24 shl 20)

    /** Audio kept in the sound card: a longer queue does not run dry when the stick is busy drawing the menus. */
    const val AUDIO_MIN_BUFFER_US = 400_000
    const val AUDIO_MAX_BUFFER_US = 1_000_000
    const val AUDIO_BUFFER_FACTOR = 6
}

/** The preview playing next to the lists, so the full-screen player can close its connection before opening its own. */
object PreviewPlayers {
    private val active = mutableMapOf<ExoPlayer, StreamHold>()

    fun register(player: ExoPlayer, hold: StreamHold) { active[player] = hold }

    fun unregister(player: ExoPlayer) { active.remove(player) }

    /** True while a preview is showing a content (it counts as the app being in use). Main thread only. */
    fun anyPlaying(): Boolean = active.keys.any { it.isPlaying || it.playbackState == androidx.media3.common.Player.STATE_BUFFERING }

    /** Panels often allow one connection per account: a preview still open would make the channel answer 403. */
    fun stopAll() {
        for ((player, hold) in active) {
            player.stop()
            player.clearMediaItems()
            hold.release()
        }
    }
}

/**
 * Stream connections open right now, from every player of the app (preview and full screen). A player that stops
 * closes its connection on its own thread a moment later; a new stream waits here until that has happened, so the
 * provider never sees two connections of the same list at once, not even for an instant (zapping, preview to full
 * screen).
 */
object StreamConnections {
    private val open = MutableStateFlow(0)

    val count: Int get() = open.value

    /** Waits until no stream connection is open, at most [timeoutMs]; true when the line is free. */
    suspend fun awaitNone(timeoutMs: Long = CLOSE_WAIT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { open.first { it == 0 } } != null

    /** Wraps a source so its connection is counted from the moment it starts opening until it is closed. */
    fun counted(source: DataSource): DataSource = Counted(source)

    internal fun opening() = open.update { it + 1 }

    internal fun closed() = open.update { (it - 1).coerceAtLeast(0) }

    private class Counted(private val source: DataSource) : DataSource {
        private var counted = false

        override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            if (!counted) {
                counted = true
                opening()
            }
            try {
                return source.open(dataSpec)
            } catch (e: Exception) {
                // A connection that failed to open is already closed by the source itself.
                release()
                throw e
            }
        }

        private fun release() {
            if (counted) {
                counted = false
                closed()
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

        override fun getUri(): Uri? = source.uri

        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders

        override fun close() {
            try {
                source.close()
            } finally {
                release()
            }
        }
    }

    /** Longest wait for an old connection to close; past it the new stream starts anyway (it never hangs). */
    const val CLOSE_WAIT_MS = 2_000L
}

/**
 * The identity (User-Agent) streams are asked with. Some panels serve part of their channels (often the HD and
 * FHD ones, from other servers) only to identities they know, and answer 403 or 404 to the others. The full-screen
 * player tries them in turn when a channel answers with an HTTP error, and the one that works is kept for the
 * rest of the session (previews use it too).
 */
object StreamIdentity {
    /** null is the platform's own identity, which worked for most channels so far. */
    val CANDIDATES: List<String?> = listOf(null, "VLC/3.0.20 LibVLC/3.0.20", "okhttp/4.12.0", "Lavf/60.3.100")

    @Volatile var preferred: String? = null

    /** What the full-screen player is using right now. */
    @Volatile var current: String? = null

    /**
     * Identity of each try of one channel: the usual one twice (the second time a moment later, when a connection
     * still open from the preview has closed), then the others.
     */
    fun plan(): List<String?> = listOf(preferred, preferred) + CANDIDATES.filter { it != preferred }
}

/** Upper limit for the preview's video when the stream offers a choice (HLS/DASH variants). */
private const val PREVIEW_MAX_BITRATE = 2_500_000

/** One player setup for the full-screen view and (with [preview]) the small preview next to the channel list. */
fun createPlayer(context: Context, preview: Boolean = false): ExoPlayer {
    // A fresh source for every connection, so a change of identity applies to the next try (and to each HLS segment).
    // Every connection is counted (see StreamConnections), so the next stream can wait for this one to close.
    val httpFactory = DataSource.Factory {
        StreamConnections.counted(
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(if (preview) StreamIdentity.preferred else StreamIdentity.current)
                .createDataSource(),
        )
    }
    // Standard extractors and the library's own identity, as in the older player that handled live lists well.
    // More load retries (8) keep a live stream alive through the short hiccups panels often have.
    val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(BufferPlan.AUDIO_MIN_BUFFER_US)
                        .setMaxPcmBufferDurationUs(BufferPlan.AUDIO_MAX_BUFFER_US)
                        .setPcmBufferMultiplicationFactor(BufferPlan.AUDIO_BUFFER_FACTOR)
                        .build(),
                )
                .build()
    }.setEnableDecoderFallback(true)
    val sources = DefaultMediaSourceFactory(httpFactory)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
    val plan = if (preview) BufferPlan.PREVIEW else BufferPlan.FULL
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(plan.minMs, plan.maxMs, plan.startMs, plan.afterStallMs)
        .setBackBuffer(plan.backMs, /* retainBackBufferFromKeyframe = */ true)
        .setTargetBufferBytes(plan.maxBytes)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val builder = ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(sources)
        .setLoadControl(loadControl)
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
    if (preview) {
        // The preview never changes speed to catch up with a live edge (that is what made it run fast with
        // warbling sound) and, where the stream offers several qualities, takes the best one within SD: the window
        // is a third of the screen wide. The very lowest variant is not forced any more: on some panels it is the
        // one with fewer frames per second, which makes the preview look jerky.
        builder.setLivePlaybackSpeedControl(
            DefaultLivePlaybackSpeedControl.Builder()
                .setFallbackMinPlaybackSpeed(1f)
                .setFallbackMaxPlaybackSpeed(1f)
                .build(),
        )
        val selector = DefaultTrackSelector(context)
        selector.setParameters(
            selector.buildUponParameters()
                .setMaxVideoSizeSd()
                .setMaxVideoBitrate(PREVIEW_MAX_BITRATE),
        )
        builder.setTrackSelector(selector)
    }
    return builder.build()
}

/** A preview item: a live stream's own speed range is overridden too, so nothing but 1x is ever used. */
fun previewItem(url: String): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
    .build()
