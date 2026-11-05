package dev.red74.prismplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.cos
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
        MoonMesh.draw(this, moon, theta, center, radius, appear * MOON_OPACITY)
    } else {
        drawCircle(Color(0xFF2A2D35).copy(alpha = appear * MOON_OPACITY), radius, center)
    }

    // fixed lighting over the turning picture: dark on the side away from the light, a faint limb darkening
    drawCircle(
        Brush.linearGradient(
            listOf(Color.Transparent, Color(0xFF020611).copy(alpha = 0.36f * appear)),
            start = center + lightDir * radius * 0.1f, end = center - lightDir * radius * 1.05f,
        ),
        radius = radius, center = center,
    )
    drawCircle(
        Brush.radialGradient(0.62f to Color.Transparent, 1f to Color(0xFF020611).copy(alpha = 0.30f * appear), center = center, radius = radius),
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

/** How solid the moon is: well see-through, so the stars and the light behind it show. */
private const val MOON_OPACITY = 0.58f

/**
 * The moon as a real ball turning on a tilted axis: a small sphere mesh (32 x 16 quads) textured with the moon map
 * (the supplied picture unrolled around a sphere, 1024 x 512). Every frame only the vertices are moved and the
 * triangles facing the viewer are drawn with one drawVertices call, so it stays light on the stick.
 */
private object MoonMesh {
    private const val N = 32
    private const val M = 16
    private const val TILT_IN_PLANE = -0.42      // the axis leans to the side
    private const val TILT_TO_VIEWER = 0.50      // and its top comes towards the viewer: a tilted orbital plane

    private val cu = FloatArray((N + 1) * (M + 1)); private val cv = FloatArray((N + 1) * (M + 1))
    private val bx = FloatArray((N + 1) * (M + 1)); private val by = FloatArray((N + 1) * (M + 1)); private val bz = FloatArray((N + 1) * (M + 1))
    private val verts = FloatArray((N + 1) * (M + 1) * 2)
    private val depth = FloatArray((N + 1) * (M + 1))
    private val indices = ShortArray(N * M * 6)
    private var built = false
    private var shaderFor: Any? = null
    private val paint = android.graphics.Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val texs = FloatArray((N + 1) * (M + 1) * 2)

    private fun build(w: Float, h: Float) {
        for (j in 0..M) {
            val lat = Math.PI / 2 - Math.PI * j / M
            for (i in 0..N) {
                val lon = -Math.PI + 2 * Math.PI * i / N
                val k = j * (N + 1) + i
                // body frame: longitude 0 faces the viewer at the start
                bx[k] = (cos(lat) * sin(lon)).toFloat()
                by[k] = (-sin(lat)).toFloat()
                bz[k] = (cos(lat) * cos(lon)).toFloat()
                texs[k * 2] = (i.toFloat() / N) * w
                texs[k * 2 + 1] = (j.toFloat() / M) * h
            }
        }
        built = true
    }

    fun draw(scope: DrawScope, moon: ImageBitmap, theta: Double, center: Offset, radius: Float, alpha: Float) {
        val bitmap = moon.asAndroidBitmap()
        if (!built) build(bitmap.width.toFloat(), bitmap.height.toFloat())
        if (shaderFor !== bitmap) {
            paint.shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            shaderFor = bitmap
        }
        val cs = cos(theta).toFloat(); val sn = sin(theta).toFloat()
        val cb = cos(TILT_TO_VIEWER).toFloat(); val sb = sin(TILT_TO_VIEWER).toFloat()
        val ca = cos(TILT_IN_PLANE).toFloat(); val sa = sin(TILT_IN_PLANE).toFloat()
        val count = (N + 1) * (M + 1)
        for (k in 0 until count) {
            // spin around the body's own axis, then lean the axis towards the viewer and to the side
            val x1 = bx[k] * cs + bz[k] * sn
            val z1 = -bx[k] * sn + bz[k] * cs
            val y2 = by[k] * cb - z1 * sb
            val z2 = by[k] * sb + z1 * cb
            val x3 = x1 * ca - y2 * sa
            val y3 = x1 * sa + y2 * ca
            verts[k * 2] = center.x + x3 * radius
            verts[k * 2 + 1] = center.y + y3 * radius
            depth[k] = z2
        }
        var n = 0
        for (j in 0 until M) for (i in 0 until N) {
            val a = j * (N + 1) + i
            val b = a + 1
            val c = a + N + 1
            val d = c + 1
            if (depth[a] + depth[b] + depth[c] > 0f) { indices[n++] = a.toShort(); indices[n++] = b.toShort(); indices[n++] = c.toShort() }
            if (depth[b] + depth[d] + depth[c] > 0f) { indices[n++] = b.toShort(); indices[n++] = d.toShort(); indices[n++] = c.toShort() }
        }
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        scope.drawIntoCanvas {
            it.nativeCanvas.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES, count, verts, 0, texs, 0, null, 0, indices, 0, n, paint,
            )
        }
    }
}
