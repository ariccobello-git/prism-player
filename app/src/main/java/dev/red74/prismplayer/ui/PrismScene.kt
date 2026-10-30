package dev.red74.prismplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val Spectrum = listOf(
    Color(0xFFFF3B4E), // red
    Color(0xFFFF9A3D), // orange
    Color(0xFFFFE14D), // yellow
    Color(0xFF4DFF88), // green
    Color(0xFF3FE6FF), // cyan
    Color(0xFF5F7BFF), // blue
    Color(0xFFB765FF), // violet
)

/** Which parts of the scene to draw: the closing animation takes the sky and the prism with its light apart. */
internal enum class SceneLayers { ALL, SKY, PRISM }

/** How far the colours travel (prism units) before they leave the screen. */
private const val RAY_REACH = 5.0

/** How much the colours are pulled towards the viewer and bent down along the way (view units). */
private const val RAY_PULL = 5.2
private const val RAY_DROP = 2.4

/** The share of a colour's path drawn when the fan is fully open: the rest is already beyond the bottom edge. */
private const val RAY_LENGTH = 0.62

private fun fraction(value: Float, from: Float, length: Float): Float = ((value - from) / length).coerceIn(0f, 1f)

private fun lerp(a: Offset, b: Offset, t: Float): Offset = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The opening scene at [tm] seconds of the opening music's own timeline (10.6 s, see [PrismMotion]): a real
 * photograph of the Milky Way ([sky]; ESO/S. Brunier, CC BY 4.0, see the README), a solid glass prism turning in 3D
 * ([PrismView], [drawGlassPrism3D]), a white beam from the lower left and the spectrum, traced with real refraction
 * ([PrismOptics]). Once out of the glass the colours fly out of the screen towards the viewer and down past its
 * bottom edge. [glassView] is a small soft copy of the sky for what the glass shows.
 */
internal fun DrawScope.drawPrismScene(
    tm: Float,
    sky: ImageBitmap?,
    glassView: ImageBitmap?,
    layers: SceneLayers = SceneLayers.ALL,
) {
    val w = size.width
    val h = size.height

    // --- the sky: slowly drifting closer, fading in from black ------------------------------------------------------
    if (layers != SceneLayers.PRISM) {
        val reveal = fraction(tm, 0f, 1.8f)
        if (sky != null) {
            val zoom = 1f + 0.07f * (tm / PrismMotion.MUSIC_END.toFloat()).coerceAtMost(1.4f)
            withTransform({ scale(zoom, zoom, Offset(w * 0.45f, h * 0.5f)) }) {
                drawImage(sky, dstSize = IntSize(w.roundToInt(), h.roundToInt()), alpha = reveal, filterQuality = FilterQuality.Medium)
            }
        }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.72f))
    }
    if (layers == SceneLayers.SKY) return

    // --- the prism -------------------------------------------------------------------------------------------------
    val center = Offset(w * 0.40f, h * 0.44f)
    val radius = h * 0.22f
    val theta = PrismMotion.angle(tm.toDouble())
    val yaw = PrismView.yaw(tm.toDouble())
    val appear = fraction(tm, 0.2f, 1.2f)
    fun project(x: Double, y: Double, z: Double = 0.0, extraY: Double = 0.0, extraZ: Double = 0.0): Offset {
        val v = PrismView.toView(x, y, z, yaw)
        val s = PrismView.scale(v.z + extraZ)
        return Offset(center.x + (v.x * s * radius).toFloat(), center.y + ((v.y + extraY) * s * radius).toFloat())
    }
    fun screen(v: PrismOptics.Vec) = project(v.x, v.y)

    val paths = Array(PrismOptics.INDICES.size) { PrismOptics.trace(theta, PrismOptics.INDICES[it]) }
    val mid = paths[3]
    val relative = Math.toDegrees(PrismOptics.relativeToClassic(theta)).toFloat()
    val gate = smooth(-15f, -12f, relative) * (1f - smooth(3.5f, 5.5f, relative))
    val power = gate * fraction(tm, 5.25f, 0.3f)

    val beamIn = fraction(tm, 2.4f, 1.9f)
    val hit = fraction(tm, 4.2f, 0.3f) * (1f - 0.45f * fraction(tm, 4.6f, 1.6f)) + exp(-(((tm - 5.35f) / 0.2f) * ((tm - 5.35f) / 0.2f)))
    val flash = hit.coerceIn(0f, 1.4f)

    // glow cast by the light, behind the glass
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF9FC4FF).copy(alpha = 0.14f * appear + 0.22f * flash.coerceAtMost(1f)), Color.Transparent),
            center = center, radius = radius * 2.4f,
        ),
        radius = radius * 2.4f, center = center, blendMode = BlendMode.Plus,
    )

    drawGlassPrism3D(
        theta = theta,
        yaw = yaw,
        project = { x, y, z -> project(x, y, z) },
        center = center,
        radius = radius,
        appear = appear,
        flash = flash,
        power = power,
        sparkle = PrismMotion.sparkle(tm.toDouble()).toFloat(),
        glassView = glassView,
    )

    // --- the white beam: it comes from far away on the lower left to wherever it meets the glass ---------------------
    val entry = mid?.let { screen(it.entry) }
    if (mid != null && entry != null && beamIn > 0f) {
        val dx = cos(PrismOptics.BEAM_ANGLE)
        val dy = sin(PrismOptics.BEAM_ANGLE)
        var back = 1.0
        var start = entry
        while (back < 60.0) {
            val x = mid.entry.x - dx * back
            val y = mid.entry.y - dy * back
            start = project(x, y)
            if (start.x < -60f || start.y < -60f || start.y > h + 60f) break
            back += 0.5
        }
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
    val innerGrow = fraction(tm, 4.25f, 0.9f)
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

    // --- the spectrum: bands that leave the glass, fly out of the screen towards the viewer and fall past its bottom ---
    if (power <= 0.002f) return
    // Only colours that leave in the same general direction count (a ray that bounced around inside is dropped).
    val reference = paths.firstOrNull { it?.exits == true }?.exitAngle ?: return
    val available = paths.indices.filter { paths[it]?.exits == true && kotlin.math.abs(paths[it]!!.exitAngle - reference) < 0.35 }
    if (available.isEmpty()) return
    val grow = easeOut(fraction(tm, 5.3f, 2.0f))
    val spread = 1f + 4.0f * grow                       // the colours open up from a narrow beam to a wide fan
    val length = RAY_LENGTH * (0.2f + 0.8f * grow)
    val firstAngle = paths[available.first()]!!.exitAngle
    val angles = available.map { i -> firstAngle + (paths[i]!!.exitAngle - firstAngle).coerceIn(-0.2, 0.2) * spread }
    val origins = available.map { paths[it]!!.inside.last() }

    /** A point of a colour's way: [s] from 0 (where it leaves the glass) to 1, at direction [angle]. */
    fun along(origin: PrismOptics.Vec, angle: Double, s: Double): Offset = project(
        origin.x + cos(angle) * RAY_REACH * s,
        origin.y + sin(angle) * RAY_REACH * s,
        extraY = RAY_DROP * s * s,
        extraZ = -RAY_PULL * s,
    )

    /** A band between two directions, which widens as it comes closer, as a strip of [steps] pieces. */
    fun band(origin: PrismOptics.Vec, from: Double, to: Double, steps: Int = 16): Path {
        val near = (0..steps).map { along(origin, from, length * it / steps.toDouble()) }
        val far = (0..steps).map { along(origin, to, length * it / steps.toDouble()) }
        return Path().apply {
            moveTo(near[0].x, near[0].y)
            for (p in near.drop(1)) lineTo(p.x, p.y)
            for (p in far.asReversed()) lineTo(p.x, p.y)
            close()
        }
    }

    available.forEachIndexed { n, i ->
        val origin = origins[n]
        val centre = angles[n]
        val prev = if (n > 0) (centre - angles[n - 1]) / 2 else null
        val next = if (n < available.size - 1) (angles[n + 1] - centre) / 2 else null
        val lo = prev ?: next ?: 0.02
        val hi = next ?: prev ?: 0.02
        val strength = power * (paths[i]!!.transmission.toFloat() / 0.85f).coerceIn(0.2f, 1.1f)
        val color = Spectrum[i]
        val start = screen(origin)
        val tip = along(origin, centre, length * 0.75)
        drawPath(
            band(origin, centre - lo, centre + hi),
            Brush.linearGradient(listOf(color.copy(alpha = 0.58f * strength), color.copy(alpha = 0.34f * strength), color.copy(alpha = 0.10f * strength)), start = start, end = tip),
            blendMode = BlendMode.Plus,
        )
        // a soft halo and a bright core along the middle of each colour, widening as they come closer too
        drawPath(
            band(origin, centre - lo * 0.45, centre + hi * 0.45),
            Brush.linearGradient(listOf(color.copy(alpha = 0.22f * strength * grow), color.copy(alpha = 0.06f * strength)), start = start, end = tip),
            blendMode = BlendMode.Plus,
        )
        drawPath(
            band(origin, centre - 0.004, centre + 0.004),
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f * strength), color.copy(alpha = 0.95f * strength), color.copy(alpha = 0.25f * strength)), start = start, end = tip),
            blendMode = BlendMode.Plus,
        )
    }
    // bloom where the light leaves the glass; it breathes a little once the prism is at rest
    val exits = origins.map { screen(it) }
    val avg = Offset(exits.map { it.x }.average().toFloat(), exits.map { it.y }.average().toFloat())
    val breathe = if (tm > PrismMotion.STOP_AT) 0.94f + 0.06f * sin((tm - PrismMotion.STOP_AT.toFloat()) * 2.6f) else 1f
    val bloom = 90.dp.toPx() * (0.6f + 0.4f * grow) * breathe
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * power), Color(0xFFFFF0D0).copy(alpha = 0.35f * power), Color.Transparent), center = avg, radius = bloom),
        radius = bloom, center = avg, blendMode = BlendMode.Plus,
    )
}
