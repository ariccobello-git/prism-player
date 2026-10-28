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

private const val SPLASH_MS = 5_000
private const val TOTAL_SECONDS = SPLASH_MS / 1000f
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
    val spin = easeOut(fraction(seconds, 0f, 1.7f))
    val sway = Math.toRadians(3.0).toFloat() * sin(seconds * 1.7f) * fraction(seconds, 1.7f, 0.5f)
    return rest + Math.toRadians(420.0).toFloat() * (1f - spin) + sway
}

/**
 * Opening screen: a white beam reaches a glass prism and leaves it split into the spectrum,
 * while a progress bar fills. It ends when the animation has finished and the app data is [ready].
 */
@Composable
fun SplashScreen(ready: Boolean, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val readyNow by rememberUpdatedState(ready)

    // The beam animation is not interruptible; back is ignored for these few seconds.
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        // Original synthesized sting, 5 s, timed to the animation (impact when the light leaves the prism).
        val player = runCatching {
            MediaPlayer.create(context, R.raw.prism_intro)?.apply {
                setVolume(0.85f, 0.85f)
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

    PrismBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.size(width = 960.dp, height = 560.dp)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 40.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PrismScene(
                        progress = { progress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Column(
                        Modifier.graphicsLayer { alpha = fraction(progress.value * TOTAL_SECONDS, 3.0f, 1.2f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PrismText(
                            text = stringResource(R.string.app_name).uppercase(),
                            size = 44.sp,
                            weight = FontWeight.Bold,
                        )
                        PrismText(
                            text = stringResource(R.string.built_by),
                            size = 20.sp,
                            color = PrismColors.Accent,
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    Box(
                        Modifier
                            .size(width = 420.dp, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.15f))
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
    }
}

@Composable
private fun PrismScene(progress: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val seconds = progress() * TOTAL_SECONDS
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.42f, h * 0.5f)
        val scale = h * 0.40f
        val yaw = yawAt(seconds)
        val pitch = Math.toRadians(-14.0).toFloat()
        val appear = fraction(seconds, 0f, 0.4f)

        val pts = PrismCorners.map { project(it, yaw, pitch, center, scale) }

        // 1) faces, far to near, as translucent glass; the exit face turns into a spectrum after the impact
        val disperse = fraction(seconds, 2.3f, 0.6f)
        val ordered = PrismFaces.sortedBy { face -> face.corners.map { pts[it].depth }.average() }
        for (face in ordered) {
            val path = Path()
            face.corners.forEachIndexed { i, index ->
                if (i == 0) path.moveTo(pts[index].x, pts[index].y) else path.lineTo(pts[index].x, pts[index].y)
            }
            path.close()
            val facing = face.corners.map { pts[it].depth }.average() > 0.0
            drawPath(path, Color.White.copy(alpha = appear * if (facing) 0.20f else 0.10f))
            if (face.isSpectrum && disperse > 0f) {
                val left = face.corners.minOf { pts[it].x }
                val right = face.corners.maxOf { pts[it].x }
                drawPath(
                    path,
                    Brush.horizontalGradient(
                        colors = Spectrum.map { it.copy(alpha = 0.67f * disperse) },
                        startX = left,
                        endX = right,
                    ),
                )
            }
        }

        // 2) glass edges: near ones bright, far ones faint
        for ((from, to) in PrismEdges) {
            val near = (pts[from].depth + pts[to].depth) / 2f > 0f
            drawLine(
                color = Color.White.copy(alpha = appear * if (near) 0.95f else 0.35f),
                start = Offset(pts[from].x, pts[from].y),
                end = Offset(pts[to].x, pts[to].y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // where the white beam enters (left face) and where it leaves (right face), in 3D
        fun onEdge(a: Int, b: Int, u: Float): Projected {
            val p = PrismCorners[a]
            val q = PrismCorners[b]
            val pointOnEdge = Vec3(lerp(p.x, q.x, u), lerp(p.y, q.y, u), 0f)
            return project(pointOnEdge, yaw, pitch, center, scale)
        }
        val entry = onEdge(0, 1, 0.55f).let { Offset(it.x, it.y) }
        val exit = onEdge(0, 2, 0.68f).let { Offset(it.x, it.y) }

        // 3) the white beam travels in from the left edge
        val beam = fraction(seconds, 0.9f, 1.0f)
        if (beam > 0f) {
            val start = Offset(0f, entry.y)
            val end = lerp(start, Offset(entry.x, entry.y), beam)
            drawLine(Color.White.copy(alpha = 0.22f), start, end, strokeWidth = 18.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White, start, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        }

        // 4) inside the glass it bends towards the base
        val inner = fraction(seconds, 1.9f, 0.4f)
        if (inner > 0f) {
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = entry,
                end = lerp(entry, exit, inner),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // 5) it leaves split into the seven colours, fanning out
        val fan = fraction(seconds, 2.3f, 1.5f)
        if (fan > 0f) {
            val eased = 1f - (1f - fan) * (1f - fan)
            val reach = (w - exit.x) * 1.05f * eased
            Spectrum.forEachIndexed { index, color ->
                val radians = (4f + index * 4.6f * eased) * PI.toFloat() / 180f
                val end = Offset(exit.x + cos(radians) * reach, exit.y + sin(radians) * reach)
                drawLine(color.copy(alpha = 0.22f), exit, end, strokeWidth = 16.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, exit, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.85f * eased), Color.Transparent),
                    center = exit,
                    radius = 26.dp.toPx(),
                ),
                radius = 26.dp.toPx(),
                center = exit,
            )
        }
    }
}
