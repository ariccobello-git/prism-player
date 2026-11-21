package dev.red74.prismplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The small symbols of the app (search, bell, star, play, check, close, more), drawn as thin rounded lines in the
 * style of Apple's SF Symbols, so they look the same on every TV and never depend on a font having an emoji.
 */
enum class LineIcon { SEARCH, BELL, BELL_ON, STAR, STAR_ON, PLAY, CHECK, CLOSE, MORE }

@Composable
fun LineIcon(icon: LineIcon, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Color.White) {
    Canvas(modifier.size(size)) { drawLineIcon(icon, color) }
}

private fun DrawScope.drawLineIcon(icon: LineIcon, color: Color) {
    val s = size.minDimension
    val line = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (icon) {
        LineIcon.SEARCH -> {
            drawCircle(color, radius = s * 0.30f, center = p(0.42f, 0.42f), style = line)
            drawLine(color, p(0.64f, 0.64f), p(0.88f, 0.88f), strokeWidth = line.width, cap = StrokeCap.Round)
        }
        LineIcon.BELL, LineIcon.BELL_ON -> {
            val body = Path().apply {
                moveTo(p(0.20f, 0.74f).x, p(0.20f, 0.74f).y)
                cubicTo(p(0.30f, 0.66f).x, p(0.30f, 0.66f).y, p(0.30f, 0.56f).x, p(0.30f, 0.56f).y, p(0.30f, 0.44f).x, p(0.30f, 0.44f).y)
                cubicTo(p(0.30f, 0.26f).x, p(0.30f, 0.26f).y, p(0.40f, 0.16f).x, p(0.40f, 0.16f).y, p(0.50f, 0.16f).x, p(0.50f, 0.16f).y)
                cubicTo(p(0.60f, 0.16f).x, p(0.60f, 0.16f).y, p(0.70f, 0.26f).x, p(0.70f, 0.26f).y, p(0.70f, 0.44f).x, p(0.70f, 0.44f).y)
                cubicTo(p(0.70f, 0.56f).x, p(0.70f, 0.56f).y, p(0.70f, 0.66f).x, p(0.70f, 0.66f).y, p(0.80f, 0.74f).x, p(0.80f, 0.74f).y)
                close()
            }
            if (icon == LineIcon.BELL_ON) drawPath(body, color, style = Fill)
            drawPath(body, color, style = line)
            // the clapper
            drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = p(0.42f, 0.78f), size = androidx.compose.ui.geometry.Size(s * 0.16f, s * 0.12f), style = line)
        }
        LineIcon.STAR, LineIcon.STAR_ON -> {
            val cx = s / 2
            val cy = s * 0.53f
            val outer = s * 0.40f
            val inner = s * 0.17f
            val path = Path()
            for (i in 0 until 10) {
                val radius = if (i % 2 == 0) outer else inner
                val angle = -PI / 2 + i * PI / 5
                val x = cx + (radius * cos(angle)).toFloat()
                val y = cy + (radius * sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            if (icon == LineIcon.STAR_ON) drawPath(path, color, style = Fill)
            drawPath(path, color, style = line)
        }
        LineIcon.PLAY -> {
            val path = Path().apply {
                moveTo(p(0.32f, 0.20f).x, p(0.32f, 0.20f).y)
                lineTo(p(0.80f, 0.50f).x, p(0.80f, 0.50f).y)
                lineTo(p(0.32f, 0.80f).x, p(0.32f, 0.80f).y)
                close()
            }
            drawPath(path, color, style = Fill)
            drawPath(path, color, style = Stroke(width = s * 0.06f, join = StrokeJoin.Round))
        }
        LineIcon.CHECK -> {
            val path = Path().apply {
                moveTo(p(0.20f, 0.54f).x, p(0.20f, 0.54f).y)
                lineTo(p(0.42f, 0.74f).x, p(0.42f, 0.74f).y)
                lineTo(p(0.80f, 0.28f).x, p(0.80f, 0.28f).y)
            }
            drawPath(path, color, style = line)
        }
        LineIcon.CLOSE -> {
            drawLine(color, p(0.28f, 0.28f), p(0.72f, 0.72f), strokeWidth = line.width, cap = StrokeCap.Round)
            drawLine(color, p(0.72f, 0.28f), p(0.28f, 0.72f), strokeWidth = line.width, cap = StrokeCap.Round)
        }
        LineIcon.MORE -> {
            for (x in listOf(0.22f, 0.5f, 0.78f)) drawCircle(color, radius = s * 0.075f, center = p(x, 0.5f))
        }
    }
}

/** A round button holding one symbol; [description] is what a screen reader (and the tests) call it. */
@Composable
fun GlassIconButton(
    icon: LineIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    size: Dp = 56.dp,
) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier.size(size).semantics { contentDescription = description },
        focusRequester = focusRequester,
        cornerRadius = size / 2,
        contentPadding = PaddingValues(0.dp),
        focusScale = 1.12f,
    ) {
        LineIcon(icon, Modifier.align(Alignment.Center), size = size * 0.5f)
    }
}

/** A symbol followed by a text, in one line (a card's "set a reminder", "followed", the channel to play). */
@Composable
fun IconLabel(
    icon: LineIcon,
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = PrismColors.TextSecondary,
    weight: FontWeight = FontWeight.Normal,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        LineIcon(icon, size = (size.value * 1.15f).dp, color = color)
        PrismText(text = text, size = size, color = color, weight = weight, maxLines = 1)
    }
}
