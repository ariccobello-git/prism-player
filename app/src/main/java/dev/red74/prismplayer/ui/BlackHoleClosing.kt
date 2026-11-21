package dev.red74.prismplayer.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.red74.prismplayer.R
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The closing animation, timed on its music (stretched to 9 s without changing its sound):
 * - it starts from the last picture of the opening (sky, prism at rest, spectrum);
 * - a black hole comes from the depth of the sky and grows to about a third of the screen ([HOLE_IN]);
 * - it swallows the picture piece by piece, sky, glass and light each torn off on their own and in random order,
 *   spiralling in and stretching as they fall ([SWALLOW]);
 * - it shrinks to the centre of the screen ([SHRINK]) and leaves a bright point with a soft halo for 2 s ([POINT]);
 * - the point goes out, the screen stays black and empty for 1.5 s ([BLACK]) and the app closes.
 */
internal object BlackHoleTimeline {
    const val HOLE_IN_START = 0.3f
    const val HOLE_IN = 1.6f
    const val SWALLOW_START = 1.4f
    const val SWALLOW_SPREAD = 3.0f
    const val PIECE_MIN = 0.9f
    const val PIECE_MAX = 1.5f
    const val SHRINK_START = 5.9f
    const val SHRINK = 1.1f
    const val POINT_START = SHRINK_START + SHRINK
    const val POINT = 2.0f
    const val BLACK = 1.5f
    const val TOTAL = POINT_START + POINT + BLACK

    /** Core radius of the black hole as a share of the screen height; with its glow it spans about a third. */
    const val CORE = 0.065f

    /** Inner edge of the glowing ring, sharp and thin, in core radii. */
    const val RING_IN = 1.12f

    /** Outer edge where the ring has faded away, in core radii (about the size of the moon in the scene). */
    const val RING_OUT = 3.4f

    /** Size of the black hole from 0 to 1 at [t] seconds. */
    fun holeScale(t: Float): Float = when {
        t < HOLE_IN_START -> 0f
        t < HOLE_IN_START + HOLE_IN -> easeOut((t - HOLE_IN_START) / HOLE_IN)
        t < SHRINK_START -> 1f
        t < POINT_START -> 1f - easeIn((t - SHRINK_START) / SHRINK)
        else -> 0f
    }

    /** How far (0..1) a piece that starts at [start] and lasts [duration] has fallen at [t]; it speeds up as it falls. */
    fun fall(t: Float, start: Float, duration: Float): Float = ((t - start) / duration).coerceIn(0f, 1f).pow(1.7f)

    /** Brightness of the last point of light (0..1): it rises as the hole closes, glows, then goes out. */
    fun point(t: Float): Float = when {
        t < SHRINK_START + SHRINK * 0.6f -> 0f
        t < POINT_START -> (t - SHRINK_START - SHRINK * 0.6f) / (SHRINK * 0.4f)
        t < POINT_START + POINT - 0.45f -> 1f
        t < POINT_START + POINT -> 1f - (t - (POINT_START + POINT - 0.45f)) / 0.45f
        else -> 0f
    }.coerceIn(0f, 1f)

    private fun easeOut(x: Float): Float = 1f - (1f - x).pow(3)
    private fun easeIn(x: Float): Float = x.pow(2.2f)
}

private const val COLS = 80
private const val ROWS = 45

/** The two layers of the last opening picture (sky; prism with its light) and the meshes of cells they break into. */
private class Swallowed(val layers: List<ImageBitmap>, val meshes: List<SwallowMesh>, val width: Int, val height: Int) {
    private val max = meshes.maxOf { it.count }
    val verts = FloatArray(max * SwallowMesh.FLOATS_PER_QUAD)
    val texs = FloatArray(max * SwallowMesh.FLOATS_PER_QUAD)
    val colors = IntArray(max * 4)
    val indices = SwallowMesh.indices(max)
    val paints: List<android.graphics.Paint> = layers.map { layer ->
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(layer.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
    }
}

/** Draws the opening's last picture into two separate layers and cuts them into fine cells with random timings. */
private fun DrawScope.prepare(images: SplashImages?): Swallowed {
    val w = size.width.roundToInt().coerceAtLeast(1)
    val h = size.height.roundToInt().coerceAtLeast(1)
    val end = PrismMotion.MUSIC_END.toFloat()
    val layers = listOf(SceneLayers.SKY, SceneLayers.PRISM).map { which ->
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            if (which == SceneLayers.SKY) drawRect(Color.Black)
            drawPrismScene(end, images?.sky, images?.glass, which, images?.moon)
        }
        bitmap
    }
    val light = layers[1].asAndroidBitmap()
    val meshes = layers.indices.map { layer ->
        val cells = ArrayList<Int>()
        for (row in 0 until ROWS) for (col in 0 until COLS) {
            if (layer == 1) {
                // the light layer is empty almost everywhere: only cells with something in them are kept
                var any = false
                for (sy in 0..2) for (sx in 0..2) {
                    val x = ((col + sx / 2f) * w / COLS).toInt().coerceIn(0, w - 1)
                    val y = ((row + sy / 2f) * h / ROWS).toInt().coerceIn(0, h - 1)
                    if ((light.getPixel(x, y) ushr 24) > 10) any = true
                }
                if (!any) continue
            }
            cells += row * COLS + col
        }
        SwallowMesh(COLS, ROWS, w, h, cells.toIntArray(), 74L + layer)
    }
    return Swallowed(layers, meshes, w, h)
}

/** The closing animation with its music; [onFinished] is called once the screen has stayed black for 1.5 s. */
@Composable
fun BlackHoleClosing(onFinished: () -> Unit) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onFinished)
    val clock = remember { mutableFloatStateOf(0f) }
    val images by produceState<SplashImages?>(null) { value = withContext(Dispatchers.Default) { loadSplashImages(context) } }
    val loaded by produceState(false) {
        withTimeoutOrNull(1_200L) { snapshotFlow { images }.first { it != null } }
        value = true
    }
    val music = remember { arrayOfNulls<MediaPlayer>(1) }
    val state = remember { arrayOfNulls<Swallowed>(1) }

    DisposableEffect(Unit) {
        UiSounds.enabled = false
        onDispose {
            music[0]?.let { runCatching { it.stop() }; runCatching { it.release() } }
            music[0] = null
        }
    }
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        val player = withContext(Dispatchers.IO) {
            runCatching {
                val attributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                MediaPlayer.create(context, R.raw.prism_outro, attributes, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
            }.getOrNull()
        }
        music[0] = player
        // The closing music always plays: the Home switch only silences the interface sounds.
        player?.let { runCatching { it.setVolume(UiSounds.MASTER, UiSounds.MASTER); it.start() } }
        val startedAt = withFrameNanos { it }
        while (true) {
            val t = (withFrameNanos { it } - startedAt) / 1e9f
            clock.floatValue = t
            if (t >= BlackHoleTimeline.TOTAL) {
                done()
                return@LaunchedEffect
            }
        }
    }

    Canvas(Modifier.fillMaxSize().background(Color.Black)) {
        if (!loaded) return@Canvas
        val swallowed = state[0]?.takeIf { it.width == size.width.roundToInt() && it.height == size.height.roundToInt() }
            ?: prepare(images).also { state[0] = it }
        drawBlackHole(clock.floatValue, swallowed)
    }
}

private fun DrawScope.drawBlackHole(t: Float, s: Swallowed) {
    val w = size.width
    val h = size.height
    val hole = Offset(w / 2f, h / 2f)
    val k = BlackHoleTimeline.holeScale(t)
    val core = h * BlackHoleTimeline.CORE * k

    // --- the picture, cell by cell: still ones in place, falling ones streaking along a spiral into the hole -----
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        for (layer in s.meshes.indices) {
            val quads = s.meshes[layer].fill(t, hole.x, hole.y, s.verts, s.texs, s.colors)
            if (quads == 0) continue
            // drawVertices counts FLOATS (2 per point), not points
            native.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES,
                quads * SwallowMesh.FLOATS_PER_QUAD, s.verts, 0, s.texs, 0, s.colors, 0, s.indices, 0, quads * 6, s.paints[layer],
            )
        }
    }

    // the closing starts from black and the opening's last picture appears quickly, then is swallowed
    if (t < 0.35f) drawRect(Color.Black.copy(alpha = 1f - t / 0.35f))

    // --- the black hole: a dark core inside a turning, glowing disk of matter ------------------------------------------
    if (k > 0f) {
        val glowUp = if (t > BlackHoleTimeline.SHRINK_START) 1f + 1.5f * ((t - BlackHoleTimeline.SHRINK_START) / BlackHoleTimeline.SHRINK).coerceIn(0f, 1f) else 1f
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFF9A3D).copy(alpha = 0.16f * glowUp.coerceAtMost(2f)), Color(0xFF7A3CFF).copy(alpha = 0.07f), Color.Transparent), center = hole, radius = core * BlackHoleTimeline.RING_OUT),
            radius = core * BlackHoleTimeline.RING_OUT, center = hole, blendMode = BlendMode.Plus,
        )
        drawAccretionRing(hole, core, t)
        // the photon ring, bright and thin, right at the edge of the dark
        drawCircle(Color(0xFFFFE9C4).copy(alpha = (0.75f * glowUp).coerceAtMost(1f)), radius = core * 1.08f, center = hole, style = Stroke(width = 2.dp.toPx()), blendMode = BlendMode.Plus)
        drawCircle(
            Brush.radialGradient(0f to Color.Black, 0.86f to Color.Black, 1f to Color.Black.copy(alpha = 0f), center = hole, radius = core * 1.05f),
            radius = core * 1.05f, center = hole,
        )
    }

    // --- the last point of light, with a soft halo that breathes, before it goes out ----------------------------------
    val light = BlackHoleTimeline.point(t)
    if (light > 0f) {
        val breathe = 1f + 0.10f * sin((t - BlackHoleTimeline.POINT_START) * 4.2f)
        val halo = 70.dp.toPx() * breathe
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFBFD8FF).copy(alpha = 0.30f * light), Color(0xFF9FB8FF).copy(alpha = 0.10f * light), Color.Transparent), center = hole, radius = halo * 1.8f),
            radius = halo * 1.8f, center = hole, blendMode = BlendMode.Plus,
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.65f * light), Color(0xFFDDE8FF).copy(alpha = 0.25f * light), Color.Transparent), center = hole, radius = halo * 0.7f),
            radius = halo * 0.7f, center = hole, blendMode = BlendMode.Plus,
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = light), Color.White.copy(alpha = 0.6f * light), Color.Transparent), center = hole, radius = 9.dp.toPx()),
            radius = 9.dp.toPx(), center = hole,
        )
    }
}

/**
 * The turning ring of matter: a thin, sharp, white-hot edge right at the black core that widens outwards into a soft
 * fade, reddening (red shift) until it vanishes at [BlackHoleTimeline.RING_OUT]. One side is brighter, as matter
 * moving towards the viewer is, and that bright side turns with the ring.
 */
internal fun DrawScope.drawAccretionRing(hole: Offset, core: Float, t: Float) {
    val outer = core * BlackHoleTimeline.RING_OUT
    val edge = BlackHoleTimeline.RING_IN / BlackHoleTimeline.RING_OUT
    fun at(share: Float) = edge + (1f - edge) * share
    drawIntoCanvas { canvas ->
        val bounds = androidx.compose.ui.geometry.Rect(hole.x - outer, hole.y - outer, hole.x + outer, hole.y + outer)
        canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
        // colour and strength by distance: sharp white-hot inner edge, then orange, then deep red, then nothing
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent,
                (edge * 0.985f) to Color.Transparent,
                edge to Color(0xFFFFF4DC),
                at(0.04f) to Color(0xFFFFD08A).copy(alpha = 0.92f),
                at(0.14f) to Color(0xFFFF9440).copy(alpha = 0.62f),
                at(0.32f) to Color(0xFFE2501F).copy(alpha = 0.34f),
                at(0.58f) to Color(0xFFA02A18).copy(alpha = 0.14f),
                at(0.85f) to Color(0xFF5A1410).copy(alpha = 0.04f),
                1f to Color.Transparent,
                center = hole, radius = outer,
            ),
            radius = outer, center = hole,
        )
        // the bright side that turns with the ring (multiplies what was drawn, softly)
        rotate(t * 140f, hole) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.White,
                    0.30f to Color.White.copy(alpha = 0.40f),
                    0.55f to Color.White.copy(alpha = 0.70f),
                    0.80f to Color.White.copy(alpha = 0.32f),
                    1f to Color.White,
                    center = hole,
                ),
                radius = outer, center = hole, blendMode = BlendMode.DstIn,
            )
        }
        canvas.restore()
    }
}
