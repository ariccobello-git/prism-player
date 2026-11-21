package dev.red74.prismplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The closing ring, rendered for real: thin and sharp (white-hot) at the black core, fading softly outwards and
 * reddening until it is gone. The picture is also saved with the test reports.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BlackHoleRingTest {
    private val px = 600
    private val core = 50f

    private fun render(t: Float): ImageBitmap {
        val bitmap = ImageBitmap(px, px)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
            drawRect(Color.Black)
            drawAccretionRing(Offset(px / 2f, px / 2f), core, t)
        }
        return bitmap
    }

    private fun pixel(image: ImageBitmap, radius: Float, angleDeg: Double = 0.0): Int {
        val a = Math.toRadians(angleDeg)
        val x = (px / 2f + kotlin.math.cos(a) * radius).toInt()
        val y = (px / 2f + kotlin.math.sin(a) * radius).toInt()
        return image.asAndroidBitmap().getPixel(x, y)
    }

    private fun lum(c: Int) = ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)

    @Test
    fun brightSharpEdgeThenSoftFadeToNothing() {
        val image = render(0f)
        runCatching {
            val dir = File("build/test-results/testDebugUnitTest/blackhole").apply { mkdirs() }
            File(dir, "ring.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val inside = lum(pixel(image, core * 1.0f))
        val edge = lum(pixel(image, core * 1.2f))
        val mid = lum(pixel(image, core * 2.0f))
        val far = lum(pixel(image, core * 3.0f))
        val beyond = lum(pixel(image, core * BlackHoleTimeline.RING_OUT + 12f))
        assertEquals("nothing inside the inner edge", 0, inside)
        assertTrue("sharp bright edge: $edge", edge > 500)
        assertTrue("fades: $edge > $mid", edge > mid && mid > far)
        assertTrue("still faintly there towards the end: $far", far > 0)
        assertEquals("nothing beyond the outer edge", 0, beyond)
        // sharp: the step in from the edge is much bigger than the step between two outer samples
        assertTrue(edge - lum(pixel(image, core * 1.05f)) > mid - far)
    }

    @Test
    fun reddensTowardsTheOutsideAndOneSideIsBrighter() {
        val image = render(0f)
        val outer = pixel(image, core * 2.6f)
        assertTrue("red beats blue in the outer ring", ((outer shr 16) and 255) > (outer and 255))
        val inner = pixel(image, core * 1.14f)
        assertTrue("the inner edge is nearly white", (inner and 255) > 150)
        // the sweep: angle 0 is the brightest side, 0.3 of a turn (108 deg) the dimmest
        assertTrue(lum(pixel(image, core * 1.6f, 0.0)) > lum(pixel(image, core * 1.6f, 108.0)))
    }

    @Test
    fun theBrightSideTurnsWithTime() {
        val first = render(0f)
        val later = render(0.75f) // 105 degrees on
        assertTrue(lum(pixel(first, core * 1.6f, 0.0)) > lum(pixel(later, core * 1.6f, 0.0)))
    }
}
