package dev.red74.prismplayer.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import dev.red74.prismplayer.R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The opening lasts exactly as long as its music. */
private const val SPLASH_MS = 10_600
private const val SPLASH_SECONDS = 10.6f
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
    val progress = remember { Animatable(0f) }
    val readyNow by rememberUpdatedState(ready)
    val context0 = LocalContext.current
    val galaxy by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeResource(context0.resources, R.drawable.galaxy_bg)?.asImageBitmap() }.getOrNull()
        }
    }

    // The animation is not interruptible; back is ignored for these few seconds.
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        // Original synthesized intro (10.6 s): the prism turns with it, hits at 5.3 s, rests at 8.1 s, the music ends with the animation.
        val player = runCatching {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            MediaPlayer.create(context, R.raw.prism_intro, attributes, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)?.apply {
                val v = if (UiSounds.muted) 0f else UiSounds.MASTER
                setVolume(v, v)
                start()
            }
        }.getOrNull()
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = SPLASH_MS, easing = LinearEasing))
        snapshotFlow { readyNow }.first { it }
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF02030A)),
    ) {
        SpaceAndPrism(galaxy = galaxy, progress = { progress.value }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.graphicsLayer { alpha = fraction(progress.value * SPLASH_SECONDS, 6.2f, 1.4f) },
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
                            size = Size(size.width * progress.value, size.height),
                        )
                    },
            )
        }
    }
}

/**
 * The scene: a real photograph of the Milky Way (ESO/S. Brunier, CC BY 4.0, cropped and turned; see the README),
 * a glass prism that turns with the music, a white beam from the lower left and the spectrum, traced with real
 * refraction (see [PrismOptics]) so the colours follow every move of the glass. The turn itself is [PrismMotion].
 */
@Composable
private fun SpaceAndPrism(galaxy: ImageBitmap?, progress: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = progress() * SPLASH_SECONDS
        val w = size.width
        val h = size.height

        // --- the sky: slowly drifting closer, fading in from black ------------------------------------------------
        val reveal = fraction(t, 0f, 1.8f)
        if (galaxy != null) {
            val zoom = 1f + 0.07f * (t / SPLASH_SECONDS)
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

        val corners = PrismOptics.triangle(theta)
        val depth = Offset(-radius * 0.14f, -radius * 0.20f)
        val front = corners.map { screen(it) + Offset(depth.x * 0.5f, depth.y * 0.5f) }
        val back = corners.map { screen(it) - Offset(depth.x * 0.5f, depth.y * 0.5f) }

        // back face and the three sides, lit from the upper left
        val backPath = Path().apply { moveTo(back[0].x, back[0].y); lineTo(back[1].x, back[1].y); lineTo(back[2].x, back[2].y); close() }
        drawPath(backPath, Color(0xFF9DBEFF).copy(alpha = 0.10f * appear))
        for (i in 0 until 3) {
            val j = (i + 1) % 3
            val side = Path().apply {
                moveTo(front[i].x, front[i].y); lineTo(front[j].x, front[j].y)
                lineTo(back[j].x, back[j].y); lineTo(back[i].x, back[i].y); close()
            }
            val mx = ((corners[i].x + corners[j].x) / 2).toFloat()
            val my = ((corners[i].y + corners[j].y) / 2).toFloat()
            val norm = hypot(mx, my).coerceAtLeast(1e-3f)
            val lit = (0.5f + 0.5f * ((mx / norm) * -0.6f + (my / norm) * -0.8f)).coerceIn(0f, 1f)
            drawPath(side, Color(0xFFCFE2FF).copy(alpha = appear * (0.08f + 0.20f * lit)))
            drawPath(side, Color.White.copy(alpha = 0.12f * appear), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
        }

        // front face: glass tint plus a bright streak that slides across as the glass turns
        val frontPath = Path().apply { moveTo(front[0].x, front[0].y); lineTo(front[1].x, front[1].y); lineTo(front[2].x, front[2].y); close() }
        val axis = Offset(cos(theta.toFloat() + 0.8f), sin(theta.toFloat() + 0.8f))
        drawPath(
            frontPath,
            Brush.linearGradient(
                listOf(Color(0xFFEAF3FF).copy(alpha = appear * 0.34f), Color(0xFF6C9BE0).copy(alpha = appear * 0.12f), Color(0xFFBFD8FF).copy(alpha = appear * 0.26f)),
                start = center - axis * radius, end = center + axis * radius,
            ),
        )
        val streakAt = 0.5f + 0.30f * sin(theta.toFloat() * 2.3f)
        clipPath(frontPath) {
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    (streakAt - 0.12f) to Color.Transparent,
                    streakAt to Color.White.copy(alpha = 0.34f * appear),
                    (streakAt + 0.12f) to Color.Transparent,
                    1f to Color.Transparent,
                    start = center - axis * radius, end = center + axis * radius,
                ),
                blendMode = BlendMode.Plus,
            )
            if (power > 0f) {
                // the light leaving through the glass tints the face it passes
                drawPath(frontPath, Color.White.copy(alpha = 0.10f * power), blendMode = BlendMode.Plus)
            }
        }
        // bevelled edges: soft halo and crisp core, back edges fainter
        for (i in 0 until 3) {
            val j = (i + 1) % 3
            drawLine(Color(0xFFBFD8FF).copy(alpha = appear * 0.26f), front[i], front[j], 9.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = appear * 0.95f), front[i], front[j], 2.4.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White.copy(alpha = appear * 0.30f), back[i], back[j], 1.4.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White.copy(alpha = appear * 0.40f), front[i], back[i], 1.4.dp.toPx(), StrokeCap.Round)
        }

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
