package dev.red74.prismplayer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The standard media-control symbols, drawn here so they never depend on a font having the glyph. */
enum class TransportIcon { PLAY, PAUSE, FAST_FORWARD, REWIND }

@Composable
fun TransportButton(
    icon: TransportIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        selected = selected,
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
        focusScale = 1.08f,
    ) {
        Canvas(Modifier.size(32.dp)) {
            val w = size.width
            val h = size.height
            val white = Color.White
            fun triangle(left: Float, right: Float, pointRight: Boolean) {
                val path = Path().apply {
                    if (pointRight) {
                        moveTo(left, 0f); lineTo(right, h / 2); lineTo(left, h)
                    } else {
                        moveTo(right, 0f); lineTo(left, h / 2); lineTo(right, h)
                    }
                    close()
                }
                drawPath(path, white)
            }
            when (icon) {
                TransportIcon.PLAY -> triangle(w * 0.18f, w * 0.95f, true)
                TransportIcon.PAUSE -> {
                    drawRect(white, Offset(w * 0.15f, 0f), Size(w * 0.27f, h))
                    drawRect(white, Offset(w * 0.58f, 0f), Size(w * 0.27f, h))
                }
                TransportIcon.FAST_FORWARD -> {
                    triangle(0f, w * 0.52f, true)
                    triangle(w * 0.48f, w, true)
                }
                TransportIcon.REWIND -> {
                    triangle(0f, w * 0.52f, false)
                    triangle(w * 0.48f, w, false)
                }
            }
        }
    }
}

/** Round "i" button, the usual symbol for information. */
@Composable
fun InfoButton(onClick: () -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        focusRequester = focusRequester,
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(0.dp),
        focusScale = 1.1f,
    ) {
        Row(Modifier.align(Alignment.Center)) {
            PrismText(text = "i", size = 28.sp, weight = FontWeight.Bold)
        }
    }
}

/**
 * Favourite star at the end of a content row: faint when the item is not a favourite, lit in the favourite orange
 * when it is. It lifts when focused and pops with a spring when switched on.
 */
@Composable
fun StarButton(favorite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = if (favorite) PrismColors.Favorite else Color.White.copy(alpha = 0.20f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "starColor",
    )
    val pop by animateFloatAsState(
        targetValue = if (favorite) 1.12f else 0.92f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow),
        label = "starPop",
    )
    GlassSurface(
        onClick = onToggle,
        modifier = modifier.size(52.dp),
        cornerRadius = 26.dp,
        contentPadding = PaddingValues(0.dp),
        focusScale = 1.12f,
    ) {
        Canvas(
            Modifier
                .align(Alignment.Center)
                .size(30.dp)
                .graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                },
        ) {
            val cx = size.width / 2
            val cy = size.height / 2 + size.height * 0.03f
            val outer = size.width / 2
            val inner = outer * 0.42f
            val path = Path()
            for (i in 0 until 10) {
                val radius = if (i % 2 == 0) outer else inner
                val angle = -PI / 2 + i * PI / 5
                val x = cx + (radius * cos(angle)).toFloat()
                val y = cy + (radius * sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color)
        }
    }
}
