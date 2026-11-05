package dev.red74.prismplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The body at the heart of the opening scene: a crystal moon (a small cut-out of the supplied picture, 512 px).
 * It turns with the same angle [theta] that steers the light inside it, so what the beam does and how the colours
 * leave follow the way the moon turns, and it comes to rest together with them. A fixed light from the upper left
 * (limb shading and a rim) keeps it round while the picture turns, the beam lights it from the inside, and a small
 * glint on the rim blinks with the music. One image draw plus a handful of gradients per frame.
 */
internal fun DrawScope.drawCrystalMoon(
    theta: Double,
    center: Offset,
    radius: Float,
    appear: Float,
    flash: Float,
    power: Float,
    sparkle: Float,
    moon: ImageBitmap?,
) {
    if (appear <= 0f) return
    val lightDir = Offset(PrismView.LIGHT.x.toFloat(), PrismView.LIGHT.y.toFloat()).let {
        val d = kotlin.math.hypot(it.x, it.y).coerceAtLeast(1e-4f)
        Offset(it.x / d, it.y / d)
    }

    // a soft halo around it, and the shadow it throws on the stars behind
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFBFD8FF).copy(alpha = 0.16f * appear), Color.Transparent), center = center, radius = radius * 1.5f),
        radius = radius * 1.5f, center = center, blendMode = BlendMode.Plus,
    )

    if (moon != null) {
        withTransform({ rotate(Math.toDegrees(theta).toFloat(), center) }) {
            drawImage(
                moon,
                dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
                dstSize = IntSize((radius * 2f).roundToInt(), (radius * 2f).roundToInt()),
                alpha = appear,
                filterQuality = FilterQuality.Medium,
            )
        }
    } else {
        drawCircle(Color(0xFF2A2D35).copy(alpha = appear), radius, center)
    }

    // fixed lighting over the turning picture: dark on the side away from the light, a faint limb darkening
    drawCircle(
        Brush.linearGradient(
            listOf(Color.Transparent, Color(0xFF020611).copy(alpha = 0.50f * appear)),
            start = center + lightDir * radius * 0.1f, end = center - lightDir * radius * 1.05f,
        ),
        radius = radius, center = center,
    )
    drawCircle(
        Brush.radialGradient(0.62f to Color.Transparent, 1f to Color(0xFF020611).copy(alpha = 0.40f * appear), center = center, radius = radius),
        radius = radius, center = center,
    )

    // the beam lights the crystal from the inside
    if (power > 0f) {
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFF0D0).copy(alpha = 0.26f * power), Color.Transparent), center = center, radius = radius * 0.95f),
            radius = radius, center = center, blendMode = BlendMode.Plus,
        )
    }
    if (flash > 0f) {
        drawCircle(Color(0xFFBFD8FF).copy(alpha = 0.10f * flash.coerceAtMost(1f)), radius, center, blendMode = BlendMode.Plus)
    }

    // a glint on the rim facing the light, brighter on every beat
    val corner = center + lightDir * radius * 0.93f
    val glow = appear * 0.8f * (0.30f + 0.70f * maxOf(sparkle, flash.coerceAtMost(1f)))
    val ray = 26.dp.toPx() * (0.6f + 0.8f * glow)
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * glow), Color(0xFFBFD8FF).copy(alpha = 0.25f * glow), Color.Transparent), center = corner, radius = ray * 0.6f),
        radius = ray * 0.6f, center = corner, blendMode = BlendMode.Plus,
    )
    for (k in 0 until 2) {
        val d = Offset(cos(0.35f + k * 1.5708f), sin(0.35f + k * 1.5708f)) * ray
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * glow), Color.Transparent), start = corner - d, end = corner + d),
            corner - d, corner + d, 1.3.dp.toPx(), blendMode = BlendMode.Plus,
        )
    }
}
