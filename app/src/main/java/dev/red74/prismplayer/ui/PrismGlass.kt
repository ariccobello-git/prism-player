package dev.red74.prismplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The key light: the bright core of the galaxy, up and to the left of the prism (unit vector, y down). */
private val Light = Offset(-0.6f, -0.8f)

/** Thick glass looks green-blue where you look through it edge-on. */
private val GlassEdge = Color(0xFF4FA59B)
private val GlassBody = Color(0xFFCFE6F2)
private val GlassShadow = Color(0xFF020711)

private fun Offset.dot(o: Offset): Float = x * o.x + y * o.y

private fun Offset.unit(): Offset {
    val d = hypot(x, y).coerceAtLeast(1e-4f)
    return Offset(x / d, y / d)
}

private fun polygon(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (k in 1 until points.size) lineTo(points[k].x, points[k].y)
    close()
}

/** Convex hull (monotone chain) of the six corners: the outline of the whole glass block on screen. */
private fun hull(points: List<Offset>): List<Offset> {
    val sorted = points.sortedWith(compareBy<Offset> { it.x }.thenBy { it.y })
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (p in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
        lower += p
    }
    val upper = ArrayList<Offset>()
    for (p in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
        upper += p
    }
    return lower.dropLast(1) + upper.dropLast(1)
}

/**
 * A solid glass prism, drawn the way a real one looks against the sky:
 * - seen slightly from above and to the left, with perspective (the far triangle is smaller);
 * - the sky behind it shows through, blurred, magnified and turned over as real glass does, and swims as it turns;
 * - each face is lit by how much it faces the light, the faces turned away fall into shadow, and the block shades
 *   the stars just behind it;
 * - one side face mirrors the sky (total internal reflection), the thick edges go green-blue, the lit edges carry a
 *   crisp highlight with a faint rainbow fringe, and the corner nearest the light sparkles with the music.
 *
 * [corners] are the prism's cross-section (circumradius 1) at angle [theta], [screen] maps them on screen.
 * [glassView] is a small, soft copy of the sky used for everything seen through or reflected by the glass.
 */
internal fun DrawScope.drawGlassPrism(
    corners: Array<PrismOptics.Vec>,
    screen: (PrismOptics.Vec) -> Offset,
    center: Offset,
    radius: Float,
    theta: Float,
    appear: Float,
    flash: Float,
    power: Float,
    sparkle: Float,
    glassView: ImageBitmap?,
) {
    if (appear <= 0f) return
    val w = size.width
    val h = size.height
    // The far triangle sits down and to the right of the near one and is a little smaller.
    val shift = Offset(radius * 0.14f, radius * 0.20f)
    val front = corners.map { screen(it) - shift * 0.5f }
    val backRaw = corners.map { screen(it) + shift * 0.5f }
    val backCentre = Offset(backRaw.map { it.x }.average().toFloat(), backRaw.map { it.y }.average().toFloat())
    val back = backRaw.map { backCentre + (it - backCentre) * 0.9f }
    val outline = polygon(hull(front + back))
    val frontPath = polygon(front)
    val frontCentre = Offset(front.map { it.x }.average().toFloat(), front.map { it.y }.average().toFloat())

    // --- shadow: the block hides part of the glow and the stars just behind it -------------------------------------
    val shadowAt = center + Offset(radius * 0.22f, radius * 0.30f)
    drawCircle(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.55f * appear), Color.Black.copy(alpha = 0.22f * appear), Color.Transparent),
            center = shadowAt, radius = radius * 1.7f,
        ),
        radius = radius * 1.7f, center = shadowAt,
    )

    // --- what is seen through the glass: the sky, magnified, turned over and drifting as the prism turns ----------
    clipPath(outline) {
        drawPath(outline, GlassShadow.copy(alpha = 0.55f * appear))
        if (glassView != null) {
            val drift = Offset(cos(theta * 1.3f) * radius * 0.35f, sin(theta * 0.9f) * radius * 0.25f)
            withTransform({ scale(-1.25f, 1.25f, center + drift) }) {
                drawImage(
                    glassView,
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    alpha = 0.85f * appear,
                    colorFilter = ColorFilter.tint(Color(0xFFBFE0F0), BlendMode.Modulate),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        // the glass body: brighter towards the light, deeper towards the shadow side
        drawPath(
            outline,
            Brush.linearGradient(
                listOf(GlassBody.copy(alpha = 0.20f * appear), GlassBody.copy(alpha = 0.04f * appear), GlassShadow.copy(alpha = 0.35f * appear)),
                start = center + Light * radius, end = center - Light * radius,
            ),
        )
    }

    // --- far triangle and the sides behind the near face (seen through the glass, faint) --------------------------
    drawPath(polygon(back), GlassBody.copy(alpha = 0.05f * appear))
    val sides = (0 until 3).map { i ->
        val j = (i + 1) % 3
        val normal = ((front[i] + front[j]) * 0.5f - frontCentre).unit()
        Triple(i, j, normal)
    }
    for ((i, j, normal) in sides) {
        if (normal.dot(shift) > 0f) continue
        drawLine(Color.White.copy(alpha = 0.16f * appear), back[i], back[j], 1.2.dp.toPx(), StrokeCap.Round)
    }

    // --- the sides we see: lit, in shadow, or mirroring the sky ------------------------------------------------------
    val visible = sides.filter { it.third.dot(shift) > 0f }
    val mirror = visible.maxByOrNull { it.third.dot(Light) }
    for (side in visible) {
        val (i, j, normal) = side
        val quad = polygon(listOf(front[i], front[j], back[j], back[i]))
        val lit = (0.5f + 0.5f * normal.dot(Light)).coerceIn(0f, 1f)
        val nearEdge = (front[i] + front[j]) * 0.5f
        val farEdge = (back[i] + back[j]) * 0.5f
        clipPath(quad) {
            if (side == mirror && glassView != null) {
                val pivot = (nearEdge + farEdge) * 0.5f
                withTransform({ scale(1.4f, -1.4f, pivot) }) {
                    drawImage(
                        glassView,
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        alpha = (0.25f + 0.45f * lit) * appear,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            // light falls off from the near edge to the far one; faces turned away from the light stay dark
            drawPath(
                quad,
                Brush.linearGradient(
                    listOf(
                        GlassBody.copy(alpha = (0.06f + 0.34f * lit) * appear),
                        GlassBody.copy(alpha = (0.02f + 0.12f * lit) * appear),
                    ),
                    start = nearEdge, end = farEdge,
                ),
            )
            drawPath(quad, GlassShadow.copy(alpha = 0.45f * (1f - lit) * appear))
            // a reflection that slides along the face as the glass turns
            val along = (front[j] - front[i])
            val at = 0.5f + 0.42f * sin(theta * 1.7f + i)
            drawPath(
                quad,
                Brush.linearGradient(
                    0f to Color.Transparent,
                    (at - 0.08f).coerceIn(0f, 1f) to Color.Transparent,
                    at.coerceIn(0f, 1f) to Color.White.copy(alpha = (0.10f + 0.30f * lit) * appear),
                    (at + 0.08f).coerceIn(0f, 1f) to Color.Transparent,
                    1f to Color.Transparent,
                    start = front[i], end = front[i] + along,
                ),
                blendMode = BlendMode.Plus,
            )
        }
        drawPath(quad, Color.White.copy(alpha = (0.06f + 0.14f * lit) * appear), style = Stroke(1.dp.toPx()))
    }

    // --- the near face ------------------------------------------------------------------------------------------------
    val axis = Offset(cos(theta + 0.8f), sin(theta + 0.8f))
    drawPath(
        frontPath,
        Brush.linearGradient(
            listOf(
                Color(0xFFEAF3FF).copy(alpha = 0.26f * appear),
                Color(0xFF6C9BE0).copy(alpha = 0.06f * appear),
                Color(0xFFBFD8FF).copy(alpha = 0.16f * appear),
            ),
            start = frontCentre + Light * radius, end = frontCentre - Light * radius,
        ),
    )
    clipPath(frontPath) {
        // seen edge-on the glass is thick: a green-blue band inside every edge, darker on the shadow side
        for ((i, j, normal) in sides) {
            val lit = (0.5f + 0.5f * normal.dot(Light)).coerceIn(0f, 1f)
            drawLine(GlassEdge.copy(alpha = (0.18f + 0.18f * (1f - lit)) * appear), front[i], front[j], radius * 0.16f)
            drawLine(GlassShadow.copy(alpha = 0.30f * (1f - lit) * appear), front[i], front[j], radius * 0.07f)
        }
        val streakAt = 0.5f + 0.30f * sin(theta * 2.3f)
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                (streakAt - 0.10f) to Color.Transparent,
                streakAt to Color.White.copy(alpha = 0.30f * appear),
                (streakAt + 0.10f) to Color.Transparent,
                1f to Color.Transparent,
                start = center - axis * radius, end = center + axis * radius,
            ),
            blendMode = BlendMode.Plus,
        )
        if (power > 0f) {
            // the light crossing the glass lights it from the inside
            drawPath(frontPath, Color.White.copy(alpha = 0.10f * power), blendMode = BlendMode.Plus)
        }
        if (flash > 0f) drawPath(frontPath, Color(0xFFBFD8FF).copy(alpha = 0.12f * flash.coerceAtMost(1f)), blendMode = BlendMode.Plus)
    }

    // --- edges: crisp highlights where the light catches them, with a faint rainbow fringe; dim on the shadow side --
    val fringe = 1.6.dp.toPx()
    for ((i, j, normal) in sides) {
        val lit = (0.5f + 0.5f * normal.dot(Light)).coerceIn(0f, 1f)
        val a = front[i]
        val b = front[j]
        drawLine(Color(0xFFBFD8FF).copy(alpha = (0.08f + 0.26f * lit) * appear), a, b, 9.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color(0xFFFF5A6A).copy(alpha = 0.22f * lit * appear), a + normal * fringe, b + normal * fringe, 1.2.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color(0xFF5F8BFF).copy(alpha = 0.26f * lit * appear), a - normal * fringe, b - normal * fringe, 1.2.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color.White.copy(alpha = (0.35f + 0.65f * lit) * appear), a, b, (1.4f + 1.4f * lit).dp.toPx(), StrokeCap.Round)
        drawLine(Color.White.copy(alpha = (0.15f + 0.30f * lit) * appear), back[i], back[j], 1.2.dp.toPx(), StrokeCap.Round)
    }
    for (i in 0 until 3) {
        val lit = (0.5f + 0.5f * (front[i] - frontCentre).unit().dot(Light)).coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = (0.18f + 0.40f * lit) * appear), front[i], back[i], 1.3.dp.toPx(), StrokeCap.Round)
    }

    // --- the corner nearest the light sparkles, brighter on every beat ------------------------------------------------
    val corner = front.maxByOrNull { (it - frontCentre).dot(Light) } ?: return
    val glow = appear * (0.35f + 0.65f * maxOf(sparkle, flash.coerceAtMost(1f)))
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
