package dev.red74.prismplayer.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.first

private const val SPLASH_MS = 3_400
private val Spectrum = listOf(
    Color(0xFFFF4D5E), // red
    Color(0xFFFF9F43), // orange
    Color(0xFFFFE14D), // yellow
    Color(0xFF4DFF88), // green
    Color(0xFF3FE6FF), // cyan
    Color(0xFF6F86FF), // blue
    Color(0xFFC36BFF), // violet
)

private fun fraction(progress: Float, from: Float, length: Float): Float =
    ((progress - from) / length).coerceIn(0f, 1f)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun lerp(a: Offset, b: Offset, t: Float): Offset = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

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
                        Modifier.graphicsLayer { alpha = fraction(progress.value, 0.55f, 0.25f) },
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
        val p = progress()
        val w = size.width
        val h = size.height
        val s = h * 0.9f
        val cx = w * 0.40f
        val cy = h * 0.5f

        val apex = Offset(cx, cy - s * 0.5f)
        val left = Offset(cx - s * 0.48f, cy + s * 0.4f)
        val right = Offset(cx + s * 0.48f, cy + s * 0.4f)

        // Where the white beam enters the left face and where the bent beam leaves the right face.
        val entryY = cy + s * 0.04f
        val entryT = (entryY - apex.y) / (left.y - apex.y)
        val entry = Offset(lerp(apex.x, left.x, entryT), entryY)
        val exitY = entryY + s * 0.11f
        val exitT = (exitY - apex.y) / (right.y - apex.y)
        val exit = Offset(lerp(apex.x, right.x, exitT), exitY)

        // 1) the prism fades in
        val prismAlpha = fraction(p, 0f, 0.1f)
        val body = Path().apply {
            moveTo(apex.x, apex.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        }
        drawPath(
            path = body,
            brush = Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.26f * prismAlpha), Color.White.copy(alpha = 0.05f * prismAlpha)),
                start = apex,
                end = right,
            ),
        )
        drawPath(
            path = body,
            color = Color.White.copy(alpha = 0.92f * prismAlpha),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // 2) the white beam travels from the left edge to the prism
        val beamP = fraction(p, 0.08f, 0.24f)
        if (beamP > 0f) {
            val start = Offset(0f, entryY)
            val end = lerp(start, entry, beamP)
            drawLine(Color.White.copy(alpha = 0.22f), start, end, strokeWidth = 18.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White, start, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        }

        // 3) inside the glass the beam bends towards the base
        val innerP = fraction(p, 0.32f, 0.14f)
        if (innerP > 0f) {
            val end = lerp(entry, exit, innerP)
            drawLine(Color.White.copy(alpha = 0.9f), entry, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }

        // 4) it leaves the prism split into the spectrum and fans out
        val fanP = fraction(p, 0.46f, 0.38f)
        if (fanP > 0f) {
            val eased = 1f - (1f - fanP) * (1f - fanP)
            val reach = (w - exit.x) * 1.05f * eased
            Spectrum.forEachIndexed { index, color ->
                val degrees = 4f + index * 4.6f * eased
                val radians = degrees * PI.toFloat() / 180f
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
