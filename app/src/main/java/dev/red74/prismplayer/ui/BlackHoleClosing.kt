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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.red74.prismplayer.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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

    /** Core radius of the black hole as a share of the screen height; with its glowing disk it spans about a third. */
    const val CORE = 0.065f
    const val DISK = 2.5f

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

/** One piece of the picture being swallowed. */
private class Piece(val layer: Int, val col: Int, val row: Int, val start: Float, val duration: Float, val spin: Float)

private const val COLS = 16
private const val ROWS = 9

/** The two layers of the last opening picture (sky; prism with its light) and the pieces they break into. */
private class Swallowed(val layers: List<ImageBitmap>, val pieces: List<Piece>, val width: Int, val height: Int)

/** Draws the opening's last picture into two separate layers and cuts them into pieces with random timings. */
private fun DrawScope.prepare(images: SplashImages?): Swallowed {
    val w = size.width.roundToInt().coerceAtLeast(1)
    val h = size.height.roundToInt().coerceAtLeast(1)
    val end = PrismMotion.MUSIC_END.toFloat()
    val layers = listOf(SceneLayers.SKY, SceneLayers.PRISM).map { which ->
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            if (which == SceneLayers.SKY) drawRect(Color.Black)
            drawPrismScene(end, images?.sky, images?.glass, which)
        }
        bitmap
    }
    val random = java.util.Random(74)
    val pieces = ArrayList<Piece>()
    val light = layers[1].asAndroidBitmap()
    for (layer in 0..1) for (row in 0 until ROWS) for (col in 0 until COLS) {
        if (layer == 1) {
            // the light layer is empty almost everywhere: only pieces with something in them are kept
            var any = false
            for (sy in 0..4) for (sx in 0..4) {
                val x = ((col + sx / 4f) * w / COLS).toInt().coerceIn(0, w - 1)
                val y = ((row + sy / 4f) * h / ROWS).toInt().coerceIn(0, h - 1)
                if ((light.getPixel(x, y) ushr 24) > 10) any = true
            }
            if (!any) continue
        }
        val start = BlackHoleTimeline.SWALLOW_START + random.nextFloat() * BlackHoleTimeline.SWALLOW_SPREAD
        val duration = BlackHoleTimeline.PIECE_MIN + random.nextFloat() * (BlackHoleTimeline.PIECE_MAX - BlackHoleTimeline.PIECE_MIN)
        pieces += Piece(layer, col, row, start, duration, (random.nextFloat() - 0.5f) * 240f)
    }
    return Swallowed(layers, pieces, w, h)
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
    val pw = s.width / COLS
    val ph = s.height / ROWS

    // --- the picture, piece by piece: still ones in place, falling ones spiralling into the hole --------------------
    for (layer in 0..1) for (p in s.pieces) {
        if (p.layer != layer) continue
        val f = BlackHoleTimeline.fall(t, p.start, p.duration)
        if (f >= 1f) continue
        val srcOffset = IntOffset(p.col * pw, p.row * ph)
        val srcSize = IntSize(if (p.col == COLS - 1) s.width - p.col * pw else pw, if (p.row == ROWS - 1) s.height - p.row * ph else ph)
        if (f <= 0f) {
            drawImage(s.layers[layer], srcOffset, srcSize, srcOffset, srcSize)
            continue
        }
        val home = Offset(srcOffset.x + srcSize.width / 2f, srcOffset.y + srcSize.height / 2f)
        val r0 = hypot(home.x - hole.x, home.y - hole.y)
        val a0 = atan2(home.y - hole.y, home.x - hole.x)
        val r = r0 * (1f - f)
        val a = a0 + 2.4f * f.pow(1.3f)
        val at = Offset(hole.x + cos(a) * r, hole.y + sin(a) * r)
        val shrink = (1f - f).pow(0.8f)
        val stretch = 1f + 1.6f * f
        val turn = Math.toDegrees((a - a0).toDouble()).toFloat() + p.spin * f
        withTransform({
            translate(at.x - home.x, at.y - home.y)
            rotate(turn, home)
            scale(shrink * stretch, shrink / stretch, home)
        }) {
            drawImage(s.layers[layer], srcOffset, srcSize, srcOffset, srcSize, alpha = (1f - f).pow(0.35f))
        }
    }

    // the closing starts from black and the opening's last picture appears quickly, then is swallowed
    if (t < 0.35f) drawRect(Color.Black.copy(alpha = 1f - t / 0.35f))

    // --- the black hole: a dark core inside a turning, glowing disk of matter ------------------------------------------
    if (k > 0f) {
        val disk = core * BlackHoleTimeline.DISK
        val glowUp = if (t > BlackHoleTimeline.SHRINK_START) 1f + 1.5f * ((t - BlackHoleTimeline.SHRINK_START) / BlackHoleTimeline.SHRINK).coerceIn(0f, 1f) else 1f
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFF9A3D).copy(alpha = 0.22f * glowUp.coerceAtMost(2f)), Color(0xFF7A3CFF).copy(alpha = 0.10f), Color.Transparent), center = hole, radius = disk * 1.5f),
            radius = disk * 1.5f, center = hole, blendMode = BlendMode.Plus,
        )
        rotate(t * 140f, hole) {
            drawCircle(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFFFB25A).copy(alpha = 0.85f), Color(0xFFFF5A3C).copy(alpha = 0.25f), Color(0xFFFFE6B0).copy(alpha = 0.9f),
                        Color(0xFF8A4BFF).copy(alpha = 0.30f), Color(0xFFFFB25A).copy(alpha = 0.85f),
                    ),
                    center = hole,
                ),
                radius = (core + disk) / 2f, center = hole, style = Stroke(width = (disk - core) * 0.9f), blendMode = BlendMode.Plus,
            )
        }
        rotate(-t * 230f, hole) {
            drawCircle(
                Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent, Color(0xFFFFD08A).copy(alpha = 0.45f), Color.Transparent), center = hole),
                radius = core * 1.6f, center = hole, style = Stroke(width = core * 0.35f), blendMode = BlendMode.Plus,
            )
        }
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
