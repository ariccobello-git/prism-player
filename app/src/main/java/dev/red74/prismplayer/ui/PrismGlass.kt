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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Thick glass looks green-blue where you look through it edge-on. */
private val GlassEdge = Color(0xFF4FA59B)
private val GlassBody = Color(0xFFCFE6F2)
private val GlassShadow = Color(0xFF020711)

private fun polygon(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (k in 1 until points.size) lineTo(points[k].x, points[k].y)
    close()
}

private fun Offset.unit(): Offset {
    val d = hypot(x, y).coerceAtLeast(1e-4f)
    return Offset(x / d, y / d)
}

/** Convex hull (monotone chain): the outline of the whole glass block on screen. */
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

/** One flat face of the glass: its corners on screen, how it faces the viewer and the light, how far it is. */
private class Face(
    val corners: IntArray,
    val visible: Boolean,
    val lit: Float,
    val depth: Double,
    val cap: Boolean,
)

/**
 * A solid glass prism in real 3D ([PrismView]): two triangular ends and three long side faces, turned by [theta]
 * around its own length while the view swings by [yaw]. Faces turned to the viewer are shaded by how much they face
 * the light, the hidden ones show faintly through the glass, the sky behind is seen blurred and turned over through
 * it, the face most turned to the light mirrors the sky, the edges catch the light with a faint rainbow fringe, and
 * the corner nearest the light sparkles with the music.
 *
 * [project] maps a point of the prism's frame (x, y across the section, z along the length) on screen.
 */
internal fun DrawScope.drawGlassPrism3D(
    theta: Double,
    yaw: Double,
    project: (Double, Double, Double) -> Offset,
    center: Offset,
    radius: Float,
    appear: Float,
    flash: Float,
    power: Float,
    sparkle: Float,
    glassView: ImageBitmap?,
) {
    if (appear <= 0f) return
    val w = size.width
    val h = size.height
    val section = PrismOptics.triangle(theta)
    val half = PrismView.HALF_LENGTH
    // Corners 0..2 are the near end (z = -half), 3..5 the far end.
    val local = Array(6) { k -> Triple(section[k % 3].x, section[k % 3].y, if (k < 3) -half else half) }
    val screen = local.map { (x, y, z) -> project(x, y, z) }
    val view = local.map { (x, y, z) -> PrismView.toView(x, y, z, yaw) }

    fun face(corners: IntArray, nx: Double, ny: Double, nz: Double, cap: Boolean): Face {
        val n = PrismView.toView(nx, ny, nz, yaw).unit()
        val cx = corners.sumOf { view[it].x } / corners.size
        val cy = corners.sumOf { view[it].y } / corners.size
        val cz = corners.sumOf { view[it].z } / corners.size
        val toFace = PrismView.V(cx, cy, cz) - PrismView.EYE
        val lit = (0.15 + 0.85 * n.dot(PrismView.LIGHT)).coerceIn(0.0, 1.0).toFloat()
        return Face(corners, n.dot(toFace) < 0.0, lit, cz, cap)
    }

    val faces = ArrayList<Face>(5)
    for (i in 0 until 3) {
        val j = (i + 1) % 3
        // For a triangle centred on the origin the middle of each side points straight out of that side.
        val mx = (section[i].x + section[j].x) / 2
        val my = (section[i].y + section[j].y) / 2
        faces += face(intArrayOf(i, j, j + 3, i + 3), mx, my, 0.0, cap = false)
    }
    faces += face(intArrayOf(0, 1, 2), 0.0, 0.0, -1.0, cap = true)
    faces += face(intArrayOf(3, 4, 5), 0.0, 0.0, 1.0, cap = true)
    fun pathOf(f: Face) = polygon(f.corners.map { screen[it] })

    // --- shadow: the block hides part of the glow and the stars just behind it -------------------------------------
    val shadowAt = center + Offset(radius * 0.22f, radius * 0.30f)
    drawCircle(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.55f * appear), Color.Black.copy(alpha = 0.22f * appear), Color.Transparent),
            center = shadowAt, radius = radius * 1.9f,
        ),
        radius = radius * 1.9f, center = shadowAt,
    )

    // --- what is seen through the glass: the sky, magnified, turned over and drifting as the prism turns ----------
    val outline = polygon(hull(screen))
    val lightOnScreen = Offset(PrismView.LIGHT.x.toFloat(), PrismView.LIGHT.y.toFloat()).unit()
    clipPath(outline) {
        drawPath(outline, GlassShadow.copy(alpha = 0.55f * appear))
        if (glassView != null) {
            val drift = Offset(cos(theta.toFloat() * 1.3f) * radius * 0.35f, sin(theta.toFloat() * 0.9f + yaw.toFloat()) * radius * 0.25f)
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
        drawPath(
            outline,
            Brush.linearGradient(
                listOf(GlassBody.copy(alpha = 0.20f * appear), GlassBody.copy(alpha = 0.04f * appear), GlassShadow.copy(alpha = 0.35f * appear)),
                start = center + lightOnScreen * radius * 1.4f, end = center - lightOnScreen * radius * 1.4f,
            ),
        )
    }

    // --- the faces turned away, seen faintly through the glass -------------------------------------------------------
    for (f in faces.filter { !it.visible }) drawPath(pathOf(f), GlassBody.copy(alpha = 0.04f * appear))

    // --- the faces turned to the viewer, far to near --------------------------------------------------------------------
    val visible = faces.filter { it.visible }.sortedByDescending { it.depth }
    val mirror = visible.filter { !it.cap }.maxByOrNull { it.lit }
    for (f in visible) {
        val path = pathOf(f)
        val pts = f.corners.map { screen[it] }
        val mid = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
        clipPath(path) {
            if (f == mirror && glassView != null) {
                withTransform({ scale(1.4f, -1.4f, mid) }) {
                    drawImage(
                        glassView,
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        alpha = (0.20f + 0.45f * f.lit) * appear,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            // light falls off across the face, away from the light
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(GlassBody.copy(alpha = (0.06f + 0.34f * f.lit) * appear), GlassBody.copy(alpha = (0.02f + 0.10f * f.lit) * appear)),
                    start = mid + lightOnScreen * radius, end = mid - lightOnScreen * radius,
                ),
            )
            drawPath(path, GlassShadow.copy(alpha = 0.45f * (1f - f.lit) * appear))
            if (f.cap) {
                // seen end-on the glass is thick: a green-blue band inside every edge of the triangle
                for (k in pts.indices) {
                    val a = pts[k]
                    val b = pts[(k + 1) % pts.size]
                    drawLine(GlassEdge.copy(alpha = (0.22f + 0.14f * (1f - f.lit)) * appear), a, b, radius * 0.15f)
                }
            } else {
                // a reflection that slides along the long face as the glass turns
                val along = pts[1] - pts[0]
                val at = 0.5f + 0.42f * sin(theta.toFloat() * 1.7f + f.corners[0])
                drawPath(
                    path,
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        (at - 0.09f).coerceIn(0f, 1f) to Color.Transparent,
                        at.coerceIn(0f, 1f) to Color.White.copy(alpha = (0.10f + 0.32f * f.lit) * appear),
                        (at + 0.09f).coerceIn(0f, 1f) to Color.Transparent,
                        1f to Color.Transparent,
                        start = pts[0], end = pts[0] + along,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
            // the light crossing the glass lights it from the inside
            if (power > 0f) drawPath(path, Color.White.copy(alpha = 0.08f * power), blendMode = BlendMode.Plus)
            if (flash > 0f) drawPath(path, Color(0xFFBFD8FF).copy(alpha = 0.10f * flash.coerceAtMost(1f)), blendMode = BlendMode.Plus)
        }
    }

    // --- edges: crisp highlights on the outside of the block, with a faint rainbow fringe; faint where hidden -------
    val edges = listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 5 to 3, 0 to 3, 1 to 4, 2 to 5)
    val fringe = 1.6.dp.toPx()
    for ((a, b) in edges) {
        val owners = faces.filter { f -> a in f.corners && b in f.corners }
        val seen = owners.any { it.visible }
        val lit = owners.filter { it.visible }.maxOfOrNull { it.lit } ?: 0f
        val p = screen[a]
        val q = screen[b]
        if (!seen) {
            drawLine(Color.White.copy(alpha = 0.14f * appear), p, q, 1.1.dp.toPx(), StrokeCap.Round)
            continue
        }
        val normal = Offset(-(q.y - p.y), q.x - p.x).unit()
        drawLine(Color(0xFFBFD8FF).copy(alpha = (0.08f + 0.24f * lit) * appear), p, q, 8.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color(0xFFFF5A6A).copy(alpha = 0.20f * lit * appear), p + normal * fringe, q + normal * fringe, 1.2.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color(0xFF5F8BFF).copy(alpha = 0.24f * lit * appear), p - normal * fringe, q - normal * fringe, 1.2.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color.White.copy(alpha = (0.30f + 0.65f * lit) * appear), p, q, (1.3f + 1.4f * lit).dp.toPx(), StrokeCap.Round)
    }

    // --- the visible corner nearest the light sparkles, brighter on every beat ----------------------------------------
    val seenCorners = (0 until 6).filter { k -> faces.any { it.visible && k in it.corners } }
    val corner = seenCorners.maxByOrNull { view[it].dot(PrismView.LIGHT) }?.let { screen[it] } ?: return
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
