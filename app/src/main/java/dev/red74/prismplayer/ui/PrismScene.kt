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

private val Spectrum = PrismColors.Spectrum

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
 * photograph of the Milky Way ([sky]; ESO/S. Brunier, CC BY 4.0, see the README), a crystal moon turning in 3D
 * ([PrismView], [drawCrystalMoon]), a fine white thread of light from the lower left that touches it in one bright
 * point, and the spectrum, traced with real refraction ([PrismOptics]). Once out of the crystal the soft colours fly
 * out of the screen towards the viewer and down past its bottom edge.
 */
internal fun DrawScope.drawPrismScene(
    tm: Float,
    sky: ImageBitmap?,
    layers: SceneLayers = SceneLayers.ALL,
    moon: ImageBitmap? = null,
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
            listOf(Color(0xFF9FC4FF).copy(alpha = 0.14f * appear + 0.06f * flash.coerceAtMost(1f)), Color.Transparent),
            center = center, radius = radius * 2.4f,
        ),
        radius = radius * 2.4f, center = center, blendMode = BlendMode.Plus,
    )

    drawCrystalMoon(
        theta = theta,
        center = center,
        radius = radius,
        appear = appear,
        power = power,
        sparkle = PrismMotion.sparkle(tm.toDouble()).toFloat(),
        moon = moon,
    )

    // --- the white beam: a fine thread of light from the lower left, just softened at its edges --------------------
    val entry = mid?.let { screen(it.entry) }
    if (mid != null && entry != null && beamIn > 0f) {
        val dx = cos(PrismOptics.BEAM_ANGLE)
        val dy = sin(PrismOptics.BEAM_ANGLE)
        var back = 1.0
        var start = entry
        while (back < 60.0) {
            start = project(mid.entry.x - dx * back, mid.entry.y - dy * back)
            if (start.x < -60f || start.y < -60f || start.y > h + 60f) break
            back += 0.5
        }
        val end = lerp(start, entry, beamIn)
        drawLine(Color(0xFFDDEBFF).copy(alpha = 0.07f), start, end, 6.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color.White.copy(alpha = 0.22f), start, end, 2.5.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Color.White.copy(alpha = 0.95f), start, end, 1.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        // the tip of the light while it travels
        if (beamIn < 1f) {
            val tip = 7.dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f), Color.Transparent), center = end, radius = tip),
                radius = tip, center = end, blendMode = BlendMode.Plus,
            )
        }
    }
    // the hit: one bright point where the thread touches the moon, with a soft glow around it
    if (entry != null && flash > 0f) {
        val f = flash.coerceAtMost(1f)
        val glow = 46.dp.toPx() * (0.55f + 0.45f * flash)
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.85f * f),
                0.12f to Color.White.copy(alpha = 0.55f * f),
                0.45f to Color(0xFFBFD8FF).copy(alpha = 0.16f * f),
                1f to Color.Transparent,
                center = entry, radius = glow,
            ),
            radius = glow, center = entry, blendMode = BlendMode.Plus,
        )
    }

    // inside the glass: the bent white thread, and after the hit each colour on its own way
    val innerGrow = fraction(tm, 4.25f, 0.9f)
    if (mid != null && entry != null && innerGrow > 0f) {
        var prev = entry
        var total = 0f
        for (v in mid.inside) { val p = screen(v); total += hypot(p.x - prev.x, p.y - prev.y); prev = p }
        var left = total * innerGrow
        prev = entry
        for (v in mid.inside) {
            if (left <= 0f) break
            val next = screen(v)
            val seg = hypot(next.x - prev.x, next.y - prev.y)
            val b = lerp(prev, next, (left / seg.coerceAtLeast(1e-3f)).coerceAtMost(1f))
            drawLine(Color.White.copy(alpha = 0.18f), prev, b, 3.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = 0.85f), prev, b, 1.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
            left -= seg
            prev = next
        }
    }
    if (power > 0f) {
        paths.forEachIndexed { i, p ->
            if (p != null && p.exits) {
                var prev = screen(p.entry)
                for (v in p.inside) {
                    val next = screen(v)
                    drawLine(Spectrum[i].copy(alpha = 0.26f * power * p.transmission.toFloat()), prev, next, 1.2.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
                    prev = next
                }
            }
        }
    }

    // --- the spectrum: soft bands that leave the glass, fly towards the viewer and fall past the bottom edge ---------
    if (power <= 0.002f) return
    // Only colours that leave in the same general direction count (a ray that bounced around inside is dropped).
    val reference = paths.firstOrNull { it?.exits == true }?.exitAngle ?: return
    var count = 0
    for (i in paths.indices) {
        val p = paths[i]
        if (p != null && p.exits && kotlin.math.abs(p.exitAngle - reference) < 0.35) Fan.colours[count++] = i
    }
    if (count == 0) return
    val grow = easeOut(fraction(tm, 5.3f, 2.0f))
    val spread = 1f + 4.0f * grow                       // the colours open up from a narrow beam to a wide fan
    val length = RAY_LENGTH * (0.2f + 0.8f * grow)
    val firstAngle = paths[Fan.colours[0]]!!.exitAngle
    for (n in 0 until count) Fan.angles[n] = firstAngle + (paths[Fan.colours[n]]!!.exitAngle - firstAngle).coerceIn(-0.2, 0.2) * spread

    /** A point of a colour's way: [s] from 0 (where it leaves the glass) to 1, at direction [angle]. */
    fun along(origin: PrismOptics.Vec, angle: Double, s: Double): Offset = project(
        origin.x + cos(angle) * RAY_REACH * s,
        origin.y + sin(angle) * RAY_REACH * s,
        extraY = RAY_DROP * s * s,
        extraZ = -RAY_PULL * s,
    )

    /** Fills [path] with the band between two directions, widening as it comes closer. No list is made per frame. */
    fun band(path: Path, origin: PrismOptics.Vec, from: Double, to: Double): Path {
        path.reset()
        var p = along(origin, from, 0.0)
        path.moveTo(p.x, p.y)
        for (k in 1..BAND_STEPS) { p = along(origin, from, length * k / BAND_STEPS); path.lineTo(p.x, p.y) }
        for (k in BAND_STEPS downTo 0) { p = along(origin, to, length * k / BAND_STEPS); path.lineTo(p.x, p.y) }
        path.close()
        return path
    }

    var sumX = 0f
    var sumY = 0f
    for (n in 0 until count) {
        val i = Fan.colours[n]
        val origin = paths[i]!!.inside.last()
        val centre = Fan.angles[n]
        // half the gap to each neighbour; the outer colours mirror their only neighbour
        val lo = if (n > 0) (centre - Fan.angles[n - 1]) / 2 else if (count > 1) (Fan.angles[1] - centre) / 2 else 0.02
        val hi = if (n < count - 1) (Fan.angles[n + 1] - centre) / 2 else lo
        val strength = power * (paths[i]!!.transmission.toFloat() / 0.85f).coerceIn(0.2f, 1.1f)
        val color = Spectrum[i]
        val start = screen(origin)
        sumX += start.x
        sumY += start.y
        val tip = along(origin, centre, length * 0.8)
        // wide and faint, overlapping the neighbours so the colours melt into each other with no hard edge
        drawPath(
            band(Fan.wide, origin, centre - lo * 2.2, centre + hi * 2.2),
            Brush.linearGradient(listOf(color.copy(alpha = 0.24f * strength), color.copy(alpha = 0.12f * strength), Color.Transparent), start = start, end = tip),
            blendMode = BlendMode.Plus,
        )
        // the body of the colour, still soft at its sides thanks to the layer above
        drawPath(
            band(Fan.body, origin, centre - lo * 0.8, centre + hi * 0.8),
            Brush.linearGradient(listOf(color.copy(alpha = 0.42f * strength), color.copy(alpha = 0.22f * strength), Color.Transparent), start = start, end = tip),
            blendMode = BlendMode.Plus,
        )
    }
    // bloom where the light leaves the glass; it breathes a little once the moon is at rest
    val avg = Offset(sumX / count, sumY / count)
    val breathe = if (tm > PrismMotion.STOP_AT) 0.94f + 0.06f * sin((tm - PrismMotion.STOP_AT.toFloat()) * 2.6f) else 1f
    val bloom = 70.dp.toPx() * (0.6f + 0.4f * grow) * breathe
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * power), Color(0xFFFFF0D0).copy(alpha = 0.28f * power), Color.Transparent), center = avg, radius = bloom),
        radius = bloom, center = avg, blendMode = BlendMode.Plus,
    )
}

/** Steps along each colour band: enough for its curve, few enough to stay light on the stick. */
private const val BAND_STEPS = 12

/** Reused every frame by the spectrum, so drawing it makes no garbage (garbage collections are what stutter). */
private object Fan {
    val colours = IntArray(PrismOptics.INDICES.size)
    val angles = DoubleArray(PrismOptics.INDICES.size)
    val wide = Path()
    val body = Path()
}
