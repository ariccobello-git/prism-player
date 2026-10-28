package dev.red74.prismplayer.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/** One player setup for both the full-screen view and the small preview next to the channel list. */
fun createPlayer(context: Context): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
    // Standard extractors and the library's own identity, as in the older player that handled live lists well.
    // More load retries (8) keep a live stream alive through the short hiccups panels often have.
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    val sources = DefaultMediaSourceFactory(httpFactory)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(sources)
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .build()
}
