package dev.red74.prismplayer.ui

import android.graphics.Bitmap
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.red74.prismplayer.R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The opening lasts exactly as long as its music. */
private const val SPLASH_SECONDS = 10.6f

/** The app fades in over the opening this long before the music ends, so picture and sound finish together. */
const val SPLASH_EXIT_MS = 450

/** Longest wait for the photo and the music to be ready before the opening starts anyway. */
private const val PREPARE_TIMEOUT_MS = 1_500L

private val Spectrum = listOf(
    Color(0xFFFF3B4E), // red
    Color(0xFFFF9A3D), // orange
    Color(0xFFFFE14D), // yellow
    Color(0xFF4DFF88), // green
    Color(0xFF3FE6FF), // cyan
    Color(0xFF5F7BFF), // blue
    Color(0xFFB765FF), // violet
)

private fun fraction(value: Float, from: Float, length: Float): Float =
    ((value - from) / length).coerceIn(0f, 1f)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun lerp(a: Offset, b: Offset, t: Float): Offset = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Opening screen: deep space, a white beam reaches a glass prism and leaves it split into a wide spectrum,
 * while a progress bar fills. It ends when the animation has finished and the app data is [ready].
 */
@Composable
fun SplashScreen(ready: Boolean, onFinished: () -> Unit) {
    // Seconds into the opening. The music is the clock: picture and sound start on the same frame and stay together.
    val clock = remember { mutableFloatStateOf(0f) }
    val readyNow by rememberUpdatedState(ready)
    val finish by rememberUpdatedState(onFinished)
    val context = LocalContext.current
    val images by produceState<SplashImages?>(null) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val sky = BitmapFactory.decodeResource(context.resources, R.drawable.galaxy_bg) ?: return@runCatching null
                // A small copy of the sky, soft once stretched back to full size: what the glass shows through and reflects.
                val small = Bitmap.createScaledBitmap(sky, 240, (240f * sky.height / sky.width).roundToInt().coerceAtLeast(1), true)
                SplashImages(sky.asImageBitmap(), small.asImageBitmap())
            }.getOrNull()
        }
    }
    val music = remember { arrayOfNulls<MediaPlayer>(1) }

    // The animation is not interruptible; back is ignored for these few seconds.
    BackHandler(enabled = true) { }

    LaunchedEffect(Unit) {
        // Original synthesized intro (10.6 s): the prism turns with it, hits at 5.3 s, rests at 8.1 s, the music fades
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
            val v = if (UiSounds.muted) 0f else UiSounds.MASTER
            runCatching { it.setVolume(v, v); it.start() }
        }
        val startedAt = withFrameNanos { it }
        var correction = 0f
        var frame = 0
        var finished = false
        while (true) {
            val now = withFrameNanos { it }
            var t = (now - startedAt) / 1e9f + correction
            // Every few frames the picture is pulled gently towards where the music really is, so a slow start of
            // the audio on the stick or a skipped frame never leaves them apart, without visible jumps.
            if (++frame % 6 == 0 && t < SPLASH_SECONDS - 0.3f) {
                val audio = runCatching { player?.takeIf { it.isPlaying }?.currentPosition?.div(1000f) }.getOrNull()
                if (audio != null && audio > 0.1f) {
                    val gap = audio - t
                    if (kotlin.math.abs(gap) > 0.03f) {
                        val step = gap.coerceIn(-0.05f, 0.05f)
                        correction += step
                        t += step
                    }
                }
            }
            clock.floatValue = t
            if (!finished && readyNow && t >= SPLASH_SECONDS - SPLASH_EXIT_MS / 1000f) {
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
        SpaceAndPrism(images = images, time = t, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.graphicsLayer { alpha = fraction(t(), 6.2f, 1.4f) },
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
    }
}

/** The sky photo and a small soft copy of it for what the glass shows. */
private class SplashImages(val sky: ImageBitmap, val glass: ImageBitmap)

/**
 * The scene: a real photograph of the Milky Way (ESO/S. Brunier, CC BY 4.0, cropped and turned; see the README),
 * a glass prism that turns with the music, a white beam from the lower left and the spectrum, traced with real
 * refraction (see [PrismOptics]) so the colours follow every move of the glass. The turn itself is [PrismMotion].
 */
@Composable
private fun SpaceAndPrism(images: SplashImages?, time: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = time()
        val galaxy = images?.sky
        val glassView = images?.glass
        val w = size.width
        val h = size.height

        // --- the sky: slowly drifting closer, fading in from black ------------------------------------------------
        val reveal = fraction(t, 0f, 1.8f)
        if (galaxy != null) {
            val zoom = 1f + 0.07f * (t / SPLASH_SECONDS).coerceAtMost(1.4f)
            withTransform({ scale(zoom, zoom, Offset(w * 0.45f, h * 0.5f)) }) {
                drawImage(
                    galaxy,
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    alpha = reveal,
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.72f,
            ),
        )

        // --- the prism ---------------------------------------------------------------------------------------------
        val center = Offset(w * 0.40f, h * 0.44f)
        val radius = h * 0.22f
        val theta = PrismMotion.angle(t.toDouble())
        val appear = fraction(t, 0.2f, 1.2f)
        fun screen(v: PrismOptics.Vec) = Offset(center.x + (v.x * radius).toFloat(), center.y + (v.y * radius).toFloat())

        val paths = Array(PrismOptics.INDICES.size) { PrismOptics.trace(theta, PrismOptics.INDICES[it]) }
        val mid = paths[3]
        val relative = Math.toDegrees(PrismOptics.relativeToClassic(theta)).toFloat()
        val gate = smooth(-15f, -12f, relative) * (1f - smooth(3.5f, 5.5f, relative))
        val release = fraction(t, 5.25f, 0.3f)
        val power = gate * release

        val beamIn = fraction(t, 2.4f, 1.9f)
        val hit = fraction(t, 4.2f, 0.3f) * (1f - 0.45f * fraction(t, 4.6f, 1.6f)) +
            exp(-(((t - 5.35f) / 0.2f) * ((t - 5.35f) / 0.2f)))
        val flash = hit.coerceIn(0f, 1.4f)

        // glow cast by the light, behind the glass
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF9FC4FF).copy(alpha = 0.14f * appear + 0.22f * flash.coerceAtMost(1f)), Color.Transparent),
                center = center, radius = radius * 2.3f,
            ),
            radius = radius * 2.3f, center = center, blendMode = BlendMode.Plus,
        )

        drawGlassPrism(
            corners = PrismOptics.triangle(theta),
            screen = { screen(it) },
            center = center,
            radius = radius,
            theta = theta.toFloat(),
            appear = appear,
            flash = flash,
            power = power,
            sparkle = PrismMotion.sparkle(t.toDouble()).toFloat(),
            glassView = glassView,
        )

        // --- the white beam: layered glow, climbing from the lower left to wherever it meets the glass --------------
        val entry = mid?.let { screen(it.entry) }
        if (entry != null && beamIn > 0f) {
            val dirX = cos(PrismOptics.BEAM_ANGLE).toFloat()
            val dirY = sin(PrismOptics.BEAM_ANGLE).toFloat()
            val startLength = (center.x + 20f) / dirX
            val start = Offset(center.x - dirX * startLength, center.y - dirY * startLength)
            val end = lerp(start, entry, beamIn)
            drawLine(Color(0xFFBFD8FF).copy(alpha = 0.10f), start, end, 38.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color(0xFFDDEBFF).copy(alpha = 0.22f), start, end, 18.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = 0.55f), start, end, 8.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White, start, end, 3.5.dp.toPx(), StrokeCap.Round)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), center = end, radius = 22.dp.toPx()),
                radius = 22.dp.toPx(), center = end, blendMode = BlendMode.Plus,
            )
        }
        if (entry != null && flash > 0f) {
            val flare = 70.dp.toPx() * flash
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f * flash.coerceAtMost(1f)), Color(0xFF9FC4FF).copy(alpha = 0.25f * flash.coerceAtMost(1f)), Color.Transparent), center = entry, radius = flare),
                radius = flare, center = entry, blendMode = BlendMode.Plus,
            )
            drawLine(Color.White.copy(alpha = 0.5f * flash.coerceAtMost(1f)), Offset(entry.x - flare * 1.6f, entry.y), Offset(entry.x + flare * 1.6f, entry.y), 1.5.dp.toPx(), blendMode = BlendMode.Plus)
        }

        // inside the glass: the bent white beam, and after the hit each colour on its own way
        val innerGrow = fraction(t, 4.25f, 0.9f)
        if (mid != null && entry != null && innerGrow > 0f) {
            val route = listOf(entry) + mid.inside.map { screen(it) }
            var total = 0f
            for (k in 0 until route.size - 1) total += hypot(route[k + 1].x - route[k].x, route[k + 1].y - route[k].y)
            var left = total * innerGrow
            for (k in 0 until route.size - 1) {
                val seg = hypot(route[k + 1].x - route[k].x, route[k + 1].y - route[k].y)
                if (left <= 0f) break
                val part = (left / seg.coerceAtLeast(1e-3f)).coerceAtMost(1f)
                val a = route[k]
                val b = lerp(route[k], route[k + 1], part)
                drawLine(Color.White.copy(alpha = 0.30f), a, b, 12.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
                drawLine(Color.White.copy(alpha = 0.90f), a, b, 3.dp.toPx(), StrokeCap.Round)
                left -= seg
            }
        }
        if (power > 0f) {
            paths.forEachIndexed { i, p ->
                if (p != null && p.exits) {
                    val route = listOf(screen(p.entry)) + p.inside.map { screen(it) }
                    for (k in 0 until route.size - 1) {
                        drawLine(Spectrum[i].copy(alpha = 0.30f * power * p.transmission.toFloat()), route[k], route[k + 1], 2.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
                    }
                }
            }
        }

        // --- the spectrum: bands fanning out from where each colour leaves the glass ---------------------------------
        if (power > 0.002f) {
            // Only colours that leave in the same general direction count (a ray that bounced around inside is dropped).
            val reference = paths.firstOrNull { it?.exits == true }?.exitAngle ?: 0.0
            val available = paths.indices.filter { paths[it]?.exits == true && kotlin.math.abs(paths[it]!!.exitAngle - reference) < 0.35 }
            if (available.isNotEmpty()) {
                val grow = easeOut(fraction(t, 5.3f, 2.0f))
                val spread = 1f + 4.0f * grow            // the colours open up from a narrow beam to a wide fan
                val reach = hypot(w, h) * 1.1f * (0.25f + 0.75f * grow)
                val firstAngle = paths[available.first()]!!.exitAngle
                val angles = available.map { i ->
                    val raw = (paths[i]!!.exitAngle - firstAngle).coerceIn(-0.2, 0.2)
                    (firstAngle + raw * spread).toFloat()
                }
                val origins = available.map { screen(paths[it]!!.inside.last()) }
                available.forEachIndexed { n, i ->
                    val origin = origins[n]
                    val centre = angles[n]
                    val prev = if (n > 0) (centre - angles[n - 1]) / 2f else null
                    val next = if (n < available.size - 1) (angles[n + 1] - centre) / 2f else null
                    val lo = prev ?: next ?: 0.02f
                    val hi = next ?: prev ?: 0.02f
                    val strength = power * (paths[i]!!.transmission.toFloat() / 0.85f).coerceIn(0.2f, 1.1f)
                    val color = Spectrum[i]
                    val tip = Offset(origin.x + cos(centre) * reach, origin.y + sin(centre) * reach)
                    val p1 = Offset(origin.x + cos(centre - lo) * reach, origin.y + sin(centre - lo) * reach)
                    val p2 = Offset(origin.x + cos(centre + hi) * reach, origin.y + sin(centre + hi) * reach)
                    val band = Path().apply { moveTo(origin.x, origin.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close() }
                    drawPath(
                        band,
                        Brush.linearGradient(listOf(color.copy(alpha = 0.58f * strength), color.copy(alpha = 0.30f * strength), color.copy(alpha = 0f)), start = origin, end = tip),
                        blendMode = BlendMode.Plus,
                    )
                    drawLine(
                        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f * strength), color.copy(alpha = 0.95f * strength), color.copy(alpha = 0.15f * strength)), start = origin, end = tip),
                        origin, tip, 4.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus,
                    )
                    drawLine(color.copy(alpha = 0.18f * strength), origin, tip, 30.dp.toPx() * grow, StrokeCap.Round, blendMode = BlendMode.Plus)
                }
                // bloom where the light leaves the glass; it breathes a little once the prism is at rest
                val avg = Offset(origins.map { it.x }.average().toFloat(), origins.map { it.y }.average().toFloat())
                val breathe = if (t > PrismMotion.STOP_AT) 0.94f + 0.06f * sin((t - PrismMotion.STOP_AT.toFloat()) * 2.6f) else 1f
                val bloom = 90.dp.toPx() * (0.6f + 0.4f * grow) * breathe
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * power), Color(0xFFFFF0D0).copy(alpha = 0.35f * power), Color.Transparent), center = avg, radius = bloom),
                    radius = bloom, center = avg, blendMode = BlendMode.Plus,
                )
            }
        }
    }
}
