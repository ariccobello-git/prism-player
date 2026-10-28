package dev.red74.prismplayer.ui

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.first

private const val SPLASH_MS = 10_000
/** Animation length in animation-seconds (the 10 s intro plays the 7 s timeline slowed down, so the hit lands at 5.3 s). */
private const val TOTAL_SECONDS = 7f
private val Spectrum = listOf(
    Color(0xFFFF4D5E), // red
    Color(0xFFFF9F43), // orange
    Color(0xFFFFE14D), // yellow
    Color(0xFF4DFF88), // green
    Color(0xFF3FE6FF), // cyan
    Color(0xFF6F86FF), // blue
    Color(0xFFC36BFF), // violet
)

private fun fraction(value: Float, from: Float, length: Float): Float =
    ((value - from) / length).coerceIn(0f, 1f)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun lerp(a: Offset, b: Offset, t: Float): Offset = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

// --- a small 3D engine: one triangular glass prism, rotated and drawn with perspective ------------------------

private class Vec3(val x: Float, val y: Float, val z: Float)

private class Face(val corners: IntArray, val isSpectrum: Boolean = false)

private const val DEPTH = 0.62f
private val PrismCorners = listOf(
    Vec3(0f, -1f, DEPTH), Vec3(-0.9f, 0.8f, DEPTH), Vec3(0.9f, 0.8f, DEPTH), // front triangle
    Vec3(0f, -1f, -DEPTH), Vec3(-0.9f, 0.8f, -DEPTH), Vec3(0.9f, 0.8f, -DEPTH), // back triangle
)
private val PrismFaces = listOf(
    Face(intArrayOf(0, 1, 2)),
    Face(intArrayOf(3, 4, 5)),
    Face(intArrayOf(0, 1, 4, 3)),
    Face(intArrayOf(0, 2, 5, 3), isSpectrum = true), // the face the light leaves through
    Face(intArrayOf(1, 2, 5, 4)),
)
private val PrismEdges = listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 5 to 3, 0 to 3, 1 to 4, 2 to 5)

private class Projected(val x: Float, val y: Float, val depth: Float)

private fun project(point: Vec3, yaw: Float, pitch: Float, center: Offset, scale: Float): Projected {
    val x1 = point.x * cos(yaw) + point.z * sin(yaw)
    val z1 = -point.x * sin(yaw) + point.z * cos(yaw)
    val y2 = point.y * cos(pitch) - z1 * sin(pitch)
    val z2 = point.y * sin(pitch) + z1 * cos(pitch)
    val perspective = CAMERA_DISTANCE / (CAMERA_DISTANCE - z2)
    return Projected(center.x + x1 * scale * perspective, center.y + y2 * scale * perspective, z2)
}

private const val CAMERA_DISTANCE = 5f

/** Spins in with a long ease-out, settles at a three-quarter view and then sways a little. */
private fun yawAt(seconds: Float): Float {
    val rest = Math.toRadians(-32.0).toFloat()
    val spin = easeOut(fraction(seconds, 0f, 2.6f))
    val sway = Math.toRadians(3.0).toFloat() * sin(seconds * 1.7f) * fraction(seconds, 2.6f, 0.5f)
    return rest + Math.toRadians(420.0).toFloat() * (1f - spin) + sway
}

/**
 * Opening screen: deep space, a white beam reaches a glass prism and leaves it split into a wide spectrum,
 * while a progress bar fills. It ends when the animation has finished and the app data is [ready].
 */
@Composable
fun SplashScreen(ready: Boolean, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val readyNow by rememberUpdatedState(ready)
    val sky = remember { StarSky() }

    // The animation is not interruptible; back is ignored for these few seconds.
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        // Original synthesized orchestral fanfare, 7 s, timed to the animation (the hit is when the light enters the glass).
        val player = runCatching {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            MediaPlayer.create(context, R.raw.prism_intro, attributes, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)?.apply {
                setVolume(1f, 1f)
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
        SpaceAndPrism(sky = sky, progress = { progress.value }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.graphicsLayer { alpha = fraction(progress.value * TOTAL_SECONDS, 4.6f, 1.4f) },
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

/** Fixed star field and Milky Way band, generated once (normalized 0..1 coordinates) so drawing stays cheap. */
private class StarSky {
    private val random = java.util.Random(74)
    private fun cloud(count: Int, band: Boolean): FloatArray {
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            var x = random.nextFloat()
            var y = random.nextFloat()
            if (band) {
                // a diagonal galactic band with a gaussian cross-section
                val along = random.nextFloat()
                val across = random.nextGaussian().toFloat() * 0.07f
                x = along * 1.2f - 0.1f - across * 0.55f
                y = 0.95f - along * 0.9f + across * 0.85f
            }
            out[i * 2] = x
            out[i * 2 + 1] = y
        }
        return out
    }
    val dust = cloud(1500, true)
    val faint = cloud(380, false)
    val medium = cloud(110, false)
    val bright = cloud(22, false)
    val tints = listOf(Color(0xFFFFF1DC), Color(0xFFDCE8FF), Color(0xFFFFE0C4), Color(0xFFFFFFFF))
}

private fun DrawScope.drawPoints(cloud: FloatArray, color: Color, widthPx: Float) {
    val w = size.width
    val h = size.height
    val points = ArrayList<Offset>(cloud.size / 2)
    var i = 0
    while (i < cloud.size) {
        points.add(Offset(cloud[i] * w, cloud[i + 1] * h))
        i += 2
    }
    drawPoints(points, PointMode.Points, color, widthPx, StrokeCap.Round)
}

@Composable
private fun SpaceAndPrism(sky: StarSky, progress: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val seconds = progress() * TOTAL_SECONDS
        val w = size.width
        val h = size.height
        val dp = density

        // --- deep space: soft nebulae and galactic glow -----------------------------------------------------------
        val reveal = fraction(seconds, 0f, 1.2f)
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF16204A).copy(alpha = 0.55f * reveal), Color.Transparent),
                center = Offset(w * 0.72f, h * 0.28f), radius = w * 0.55f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF3A1650).copy(alpha = 0.40f * reveal), Color.Transparent),
                center = Offset(w * 0.18f, h * 0.78f), radius = w * 0.45f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF0E3A52).copy(alpha = 0.30f * reveal), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.55f), radius = w * 0.6f,
            ),
        )
        // Milky Way band: dense faint dust
        drawPoints(sky.dust, Color(0xFFBFD0FF).copy(alpha = 0.28f * reveal), 1.2f * dp)
        drawPoints(sky.dust, Color(0xFFFFE9D0).copy(alpha = 0.10f * reveal), 2.6f * dp)
        // stars, three magnitudes; the bright ones twinkle slowly
        drawPoints(sky.faint, Color.White.copy(alpha = 0.55f * reveal), 1.5f * dp)
        val twinkle = 0.65f + 0.35f * sin(seconds * 2.3f)
        val twinkle2 = 0.65f + 0.35f * sin(seconds * 1.7f + 2f)
        drawPoints(sky.medium, Color(0xFFE8EEFF).copy(alpha = 0.85f * reveal * twinkle), 2.6f * dp)
        drawPoints(sky.bright, Color.White.copy(alpha = reveal * twinkle2), 4.2f * dp)
        // soft halos and 4-point glints on the brightest stars
        var k = 0
        while (k < sky.bright.size) {
            val c = Offset(sky.bright[k] * w, sky.bright[k + 1] * h)
            val tint = sky.tints[(k / 2) % sky.tints.size]
            drawCircle(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.35f * reveal * twinkle2), Color.Transparent), center = c, radius = 16.dp.toPx()),
                radius = 16.dp.toPx(), center = c, blendMode = BlendMode.Plus,
            )
            val arm = 9.dp.toPx() * twinkle2
            drawLine(Color.White.copy(alpha = 0.5f * reveal), Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f * reveal), Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 1.dp.toPx())
            k += 2
        }
        // vignette to push attention to the centre
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.75f,
            ),
        )

        // --- the prism ----------------------------------------------------------------------------------------------
        val center = Offset(w * 0.40f, h * 0.43f)
        val scale = h * 0.21f
        val yaw = yawAt(seconds)
        val pitch = Math.toRadians(-14.0).toFloat()
        val appear = fraction(seconds, 0.2f, 0.9f)
        val pts = PrismCorners.map { project(it, yaw, pitch, center, scale) }

        val beamIn = fraction(seconds, 1.6f, 1.4f)         // beam reaches the glass
        val inner = fraction(seconds, 3.0f, 0.7f)          // inside the glass
        val fan = fraction(seconds, 3.7f, 1.9f)            // spectrum opens
        val fanEase = easeOut(fan)
        val hit = fraction(seconds, 3.0f, 0.3f) * (1f - fraction(seconds, 3.3f, 1.8f) * 0.5f)

        // glass body: contact shadow + glow cast by the light
        drawOval(
            Brush.radialGradient(
                listOf(Color.Black.copy(alpha = 0.55f * appear), Color.Transparent),
                center = Offset(center.x, center.y + scale * 1.25f), radius = scale * 1.1f,
            ),
            topLeft = Offset(center.x - scale * 1.1f, center.y + scale * 1.05f), size = Size(scale * 2.2f, scale * 0.4f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF9FC4FF).copy(alpha = 0.16f * appear + 0.25f * hit), Color.Transparent), center = center, radius = scale * 2.1f),
            radius = scale * 2.1f, center = center, blendMode = BlendMode.Plus,
        )

        val disperse = fraction(seconds, 3.7f, 0.9f)
        val ordered = PrismFaces.sortedBy { face -> face.corners.map { pts[it].depth }.average() }
        for (face in ordered) {
            val path = Path()
            face.corners.forEachIndexed { i, index ->
                if (i == 0) path.moveTo(pts[index].x, pts[index].y) else path.lineTo(pts[index].x, pts[index].y)
            }
            path.close()
            val depthAvg = face.corners.map { pts[it].depth }.average().toFloat()
            val facing = depthAvg > 0f
            val top = face.corners.minOf { pts[it].y }
            val bottom = face.corners.maxOf { pts[it].y }
            val left = face.corners.minOf { pts[it].x }
            val right = face.corners.maxOf { pts[it].x }
            // glass: bluish-white body, lighter at one end, with a darker core in the thickness
            val base = if (facing) 0.30f else 0.12f
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(Color(0xFFE6F1FF).copy(alpha = appear * (base + 0.14f)), Color(0xFF6C9BE0).copy(alpha = appear * base * 0.7f), Color(0xFFBFD8FF).copy(alpha = appear * (base + 0.05f))),
                    start = Offset(left, top), end = Offset(right, bottom),
                ),
            )
            // internal caustic: the light travelling through the glass
            if (inner > 0f) {
                drawPath(path, Color.White.copy(alpha = 0.18f * hit.coerceAtLeast(0f)), blendMode = BlendMode.Plus)
            }
            if (face.isSpectrum && disperse > 0f) {
                drawPath(
                    path,
                    Brush.horizontalGradient(
                        colors = Spectrum.map { it.copy(alpha = 0.55f * disperse) },
                        startX = left, endX = right,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
            // specular streak on the faces that look at us
            if (facing) {
                val streak = Path().apply {
                    moveTo(left + (right - left) * 0.15f, top + (bottom - top) * 0.10f)
                    lineTo(left + (right - left) * 0.32f, top + (bottom - top) * 0.10f)
                    lineTo(left + (right - left) * 0.12f, bottom - (bottom - top) * 0.10f)
                    lineTo(left + (right - left) * 0.02f, bottom - (bottom - top) * 0.10f)
                    close()
                }
                drawPath(streak, Color.White.copy(alpha = 0.16f * appear), blendMode = BlendMode.Plus)
            }
        }

        // bevelled edges: soft halo + crisp core, near brighter than far
        for ((from, to) in PrismEdges) {
            val near = (pts[from].depth + pts[to].depth) / 2f > 0f
            val a = Offset(pts[from].x, pts[from].y)
            val b = Offset(pts[to].x, pts[to].y)
            drawLine(Color(0xFFBFD8FF).copy(alpha = appear * if (near) 0.28f else 0.10f), a, b, 9.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = appear * if (near) 0.95f else 0.35f), a, b, if (near) 2.4.dp.toPx() else 1.4.dp.toPx(), StrokeCap.Round)
        }

        fun onEdge(a: Int, b: Int, u: Float): Offset {
            val p = PrismCorners[a]
            val q = PrismCorners[b]
            val pr = project(Vec3(lerp(p.x, q.x, u), lerp(p.y, q.y, u), 0f), yaw, pitch, center, scale)
            return Offset(pr.x, pr.y)
        }
        val entry = onEdge(0, 1, 0.55f)
        val exit = onEdge(0, 2, 0.68f)

        // --- white beam: layered glow, with a faint lens flare where it touches the glass ------------------------
        if (beamIn > 0f) {
            val start = Offset(-20f, entry.y + (h * 0.04f) * (1f - beamIn))
            val end = lerp(start, entry, beamIn)
            drawLine(Color(0xFFBFD8FF).copy(alpha = 0.10f), start, end, 38.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color(0xFFDDEBFF).copy(alpha = 0.22f), start, end, 18.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = 0.55f), start, end, 8.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White, start, end, 3.5.dp.toPx(), StrokeCap.Round)
            // the leading tip glows
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), center = end, radius = 22.dp.toPx()),
                radius = 22.dp.toPx(), center = end, blendMode = BlendMode.Plus,
            )
        }
        if (hit > 0f) {
            val flare = 70.dp.toPx() * hit
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f * hit), Color(0xFF9FC4FF).copy(alpha = 0.25f * hit), Color.Transparent), center = entry, radius = flare),
                radius = flare, center = entry, blendMode = BlendMode.Plus,
            )
            drawLine(Color.White.copy(alpha = 0.5f * hit), Offset(entry.x - flare * 1.6f, entry.y), Offset(entry.x + flare * 1.6f, entry.y), 1.5.dp.toPx(), blendMode = BlendMode.Plus)
        }
        // inside the glass the beam bends towards the base
        if (inner > 0f) {
            val tip = lerp(entry, exit, inner)
            drawLine(Color.White.copy(alpha = 0.35f), entry, tip, 12.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = 0.95f), entry, tip, 3.dp.toPx(), StrokeCap.Round)
        }

        // --- the spectrum: seven wide luminous bands fanning out, plus bloom -------------------------------------
        if (fan > 0f) {
            val reach = (w - exit.x) * 1.35f * fanEase
            val spread = 5.0f * (PI.toFloat() / 180f)   // angular step between band centres, radians at full opening
            val first = 3f * (PI.toFloat() / 180f)
            val bandHalf = spread * 0.52f * fanEase
            Spectrum.forEachIndexed { index, color ->
                val centre = first + index * spread * fanEase
                val a1 = centre - bandHalf
                val a2 = centre + bandHalf
                val p1 = Offset(exit.x + cos(a1) * reach, exit.y + sin(a1) * reach)
                val p2 = Offset(exit.x + cos(a2) * reach, exit.y + sin(a2) * reach)
                val tip = Offset(exit.x + cos(centre) * reach, exit.y + sin(centre) * reach)
                val band = Path().apply { moveTo(exit.x, exit.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close() }
                // soft wide glow, then the saturated band, then a bright thin core
                drawPath(
                    band,
                    Brush.linearGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.30f), color.copy(alpha = 0.0f)), start = exit, end = tip),
                    blendMode = BlendMode.Plus,
                )
                drawLine(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), color.copy(alpha = 0.95f), color.copy(alpha = 0.15f)), start = exit, end = tip),
                    exit, tip, 4.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus,
                )
                drawLine(color.copy(alpha = 0.18f), exit, tip, 30.dp.toPx() * fanEase, StrokeCap.Round, blendMode = BlendMode.Plus)
            }
            // bloom where the light leaves the glass
            val bloom = 90.dp.toPx() * (0.6f + 0.4f * fanEase)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * fanEase), Color(0xFFFFF0D0).copy(alpha = 0.35f * fanEase), Color.Transparent), center = exit, radius = bloom),
                radius = bloom, center = exit, blendMode = BlendMode.Plus,
            )
        }
    }
}
