package dev.red74.prismplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The mood colour of a poster, as Apple TV tints the page behind a film: the average of the pixels, with the vivid ones
 * counting more than grey or dark ones, then lifted or lowered to a soft level so the text on top stays readable.
 * Pure arithmetic on ARGB pixels, so it is tested without Android.
 */
object AmbientColor {
    /** The colour, or null when there is nothing to take it from (no pixels, transparent, or almost black). */
    fun of(pixels: IntArray): Color? {
        var weight = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val max = maxOf(pr, pg, pb)
            val min = minOf(pr, pg, pb)
            val saturation = if (max == 0) 0.0 else (max - min).toDouble() / max
            val w = 0.1 + saturation * (max / 255.0)
            weight += w
            r += pr * w
            g += pg * w
            b += pb * w
        }
        if (weight <= 0.0) return null
        r /= weight; g /= weight; b /= weight
        val peak = maxOf(r, g, b)
        if (peak < 24.0) return null
        // Brightest channel brought to a fixed level: a dark poster still gives a visible tint, a bright one a gentle one.
        val k = LEVEL / peak
        return Color((r * k).toInt().coerceIn(0, 255), (g * k).toInt().coerceIn(0, 255), (b * k).toInt().coerceIn(0, 255))
    }

    private const val LEVEL = 190.0
}

private val ambientCache = object : LinkedHashMap<String, Color?>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color?>?): Boolean = size > 48
}

private suspend fun loadAmbient(context: android.content.Context, url: String): Color? = withContext(Dispatchers.IO) {
    runCatching {
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(SAMPLE, SAMPLE).allowHardware(false).build())
        val drawable = (result as? SuccessResult)?.drawable ?: return@runCatching null
        val bitmap = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SAMPLE, SAMPLE)
        drawable.draw(Canvas(bitmap))
        val pixels = IntArray(SAMPLE * SAMPLE)
        bitmap.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        bitmap.recycle()
        AmbientColor.of(pixels)
    }.getOrNull()
}

private const val SAMPLE = 24

/**
 * A soft glow of the poster's colour in the upper right of the page, fading in slowly once the colour is known.
 * One 24x24 sample per poster (kept for the run), one gradient drawn behind the page: nothing that weighs on the TV stick.
 */
@Composable
fun AmbientBackdrop(logo: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tint by produceState<Color?>(logo?.let { ambientCache[it] }, logo) {
        value = when {
            logo.isNullOrBlank() -> null
            ambientCache.containsKey(logo) -> ambientCache[logo]
            else -> loadAmbient(context, logo).also { ambientCache[logo] = it }
        }
    }
    val glow by animateColorAsState((tint ?: Color.Transparent).copy(alpha = if (tint == null) 0f else 0.42f), tween(700), label = "ambient")
    Box(
        modifier.fillMaxSize().drawBehind {
            if (glow.alpha > 0f) {
                drawRect(
                    Brush.radialGradient(
                        listOf(glow, glow.copy(alpha = glow.alpha * 0.35f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.1f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            }
        },
    )
}
