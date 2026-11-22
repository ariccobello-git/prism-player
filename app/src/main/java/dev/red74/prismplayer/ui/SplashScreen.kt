package dev.red74.prismplayer.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.red74.prismplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The opening lasts exactly as long as its music. */
private const val SPLASH_SECONDS = 12.0f

/**
 * The music was stretched from 10.6 s to [SPLASH_SECONDS] without changing its sound, so the scene, timed on the
 * original music ([PrismMotion]), runs this much slower: every pulse and the hit still fall on the same frame.
 */
private const val TIME_SCALE = SPLASH_SECONDS / 10.6f

/** The app fades in over the opening this long before the music ends, so picture and sound finish together. */
const val SPLASH_EXIT_MS = 450

/** Gap between picture and music that is left alone, and the most the picture is moved per frame to close one. */
private const val SYNC_TOLERANCE_S = 0.04f
private const val SYNC_STEP_S = 0.002f

/** Longest wait for the photo and the music to be ready before the opening starts anyway. */
private const val PREPARE_TIMEOUT_MS = 1_500L

private fun fraction(value: Float, from: Float, length: Float): Float =
    ((value - from) / length).coerceIn(0f, 1f)

/**
 * Opening screen: deep space, a fine white thread of light touches a crystal moon turning in 3D and leaves it split
 * into a soft spectrum that flies out of the screen, while a progress bar fills (the scene is [drawPrismScene]). It ends when the animation has finished and the app data is [ready].
 */
@Composable
fun SplashScreen(ready: Boolean, onFinished: () -> Unit) {
    // Seconds into the opening. The music is the clock: picture and sound start on the same frame and stay together.
    val clock = remember { mutableFloatStateOf(0f) }
    val readyNow by rememberUpdatedState(ready)
    val finish by rememberUpdatedState(onFinished)
    val context = LocalContext.current
    val images by produceState<SplashImages?>(null) {
        value = withContext(Dispatchers.Default) { loadSplashImages(context) }
    }
    val music = remember { arrayOfNulls<MediaPlayer>(1) }
    /** "Salta" was pressed: the opening ends as soon as the app data is ready. */
    val skipped = remember { mutableStateOf(false) }
    val skipFocus = remember { FocusRequester() }

    // Back is ignored; the opening is skipped only with its own button.
    BackHandler(enabled = true) { }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { skipFocus.requestFocus() }
    }

    LaunchedEffect(Unit) {
        // Original synthesized intro (12 s): the prism turns with it, hits at 6 s, rests at 9.2 s, the music fades
        // out with the last frames. It is prepared away from the screen thread so the first frames never stutter.
        val player = withContext(Dispatchers.IO) {
            runCatching {
                val attributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                MediaPlayer.create(context, R.raw.prism_intro, attributes, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
            }.getOrNull()
        }
        music[0] = player
        withTimeoutOrNull(PREPARE_TIMEOUT_MS) { snapshotFlow { images }.first { it != null } }
        player?.let {
            // The opening music always plays: the Home switch only silences the interface sounds.
            runCatching { it.setVolume(UiSounds.MASTER, UiSounds.MASTER); it.start() }
        }
        val startedAt = withFrameNanos { it }
        var correction = 0f
        var drift = 0f
        var frame = 0
        var finished = false
        while (true) {
            val now = withFrameNanos { it }
            // A few times a second the music says where it really is. Its position is coarse (it moves in steps of
            // tens of milliseconds), so small gaps are ignored and a real gap is closed by at most 2 ms per frame:
            // the picture speeds up or slows down by an unseen amount instead of jumping, which looked like stutter.
            if (++frame % 10 == 0 && (now - startedAt) / 1e9f < SPLASH_SECONDS - 0.3f) {
                val audio = runCatching { player?.takeIf { it.isPlaying }?.currentPosition?.div(1000f) }.getOrNull()
                if (audio != null && audio > 0.1f) drift = audio - ((now - startedAt) / 1e9f + correction)
            }
            if (kotlin.math.abs(drift) > SYNC_TOLERANCE_S) {
                val step = drift.coerceIn(-SYNC_STEP_S, SYNC_STEP_S)
                correction += step
                drift -= step
            }
            val t = (now - startedAt) / 1e9f + correction
            clock.floatValue = t
            if (!finished && readyNow && (skipped.value || t >= SPLASH_SECONDS - SPLASH_EXIT_MS / 1000f)) {
                finished = true
                finish()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // The app has already faded in; let the last notes of the music fade out on their own.
            val player = music[0] ?: return@onDispose
            music[0] = null
            runCatching {
                if (player.isPlaying) player.setOnCompletionListener { it.release() } else player.release()
            }.onFailure { runCatching { player.release() } }
        }
    }
    // Leaving the app during the opening silences it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        music[0]?.let { runCatching { it.stop(); it.release() } }
        music[0] = null
    }

    val t = { clock.floatValue }
    val progress = { (clock.floatValue / SPLASH_SECONDS).coerceIn(0f, 1f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF02030A)),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawPrismScene(t() / TIME_SCALE, images?.sky, moon = images?.moon) }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.graphicsLayer { alpha = fraction(t() / TIME_SCALE, 6.2f, 1.4f) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PrismText(
                    text = stringResource(R.string.app_name).uppercase(),
                    size = 48.sp,
                    weight = FontWeight.Bold,
                )
                PrismText(
                    text = stringResource(R.string.built_by),
                    size = 20.sp,
                    color = PrismColors.Accent,
                )
                PrismText(
                    text = stringResource(R.string.version_label, dev.red74.prismplayer.BuildConfig.BUILD_LABEL),
                    size = 16.sp,
                    color = PrismColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .size(width = 420.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .drawBehind {
                        drawRect(
                            brush = Brush.horizontalGradient(listOf(PrismColors.Accent, PrismColors.AccentAlt)),
                            size = Size(size.width * progress(), size.height),
                        )
                        // Still loading after the music: a soft light runs along the full bar until the lists are ready.
                        val over = t() - SPLASH_SECONDS
                        if (over > 0f) {
                            val x = ((over / 1.4f) % 1f) * (size.width * 1.4f) - size.width * 0.2f
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                    startX = x - size.width * 0.2f, endX = x + size.width * 0.2f,
                                ),
                                blendMode = BlendMode.Plus,
                            )
                        }
                    },
            )
        }
        GlassButton(
            text = stringResource(R.string.action_skip_intro),
            onClick = {
                if (!skipped.value) {
                    skipped.value = true
                    // The music stops with the opening instead of playing on over the app.
                    music[0]?.let { runCatching { it.stop(); it.release() } }
                    music[0] = null
                }
            },
            focusRequester = skipFocus,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 40.dp, bottom = 36.dp),
        )
    }
}

/** The sky photo and the moon's map. */
internal class SplashImages(val sky: ImageBitmap, val moon: ImageBitmap? = null)

/** Loads the sky photo and the moon's map away from the screen thread. */
internal fun loadSplashImages(context: android.content.Context): SplashImages? = runCatching {
    val sky = BitmapFactory.decodeResource(context.resources, R.drawable.galaxy_bg) ?: return@runCatching null
    val moon = BitmapFactory.decodeResource(context.resources, R.drawable.crystal_moon_map)?.asImageBitmap()
    SplashImages(sky.asImageBitmap(), moon)
}.getOrNull()
