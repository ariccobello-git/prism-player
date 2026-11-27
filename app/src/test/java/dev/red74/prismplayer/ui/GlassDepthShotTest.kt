package dev.red74.prismplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cards rendered for real: a content card has no glass at rest (the page shows through), a control keeps its glass,
 * and the card that holds the cursor gets a lit fill and a light reflection on its lower half. The window is drawn into
 * a picture (as in the featured page test) and saved with the test reports.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class GlassDepthShotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val page = Color(0xFF0B0F1A)

    private fun lum(c: Int) = ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)

    @Test
    fun aContentCardIsClearAtRestAGlassOneIsNotAndFocusLightsTheCard() {
        val focus = FocusRequester()
        rule.setContent {
            PrismTheme {
                Row(Modifier.background(page).padding(40.dp)) {
                    GlassSurface(onClick = {}, modifier = Modifier.width(200.dp).height(120.dp).testTag("plain"), glass = false) {}
                    Spacer(Modifier.width(40.dp))
                    GlassSurface(onClick = {}, modifier = Modifier.width(200.dp).height(120.dp).testTag("glass")) {}
                    Spacer(Modifier.width(40.dp))
                    GlassSurface(onClick = {}, modifier = Modifier.width(200.dp).height(120.dp).testTag("focused"), glass = false, focusRequester = focus) {}
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        var bitmap: android.graphics.Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val w = view.width.coerceAtLeast(1)
            val h = view.height.coerceAtLeast(1)
            bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val picture = bitmap!!
        runCatching {
            val out = File("build/test-results/testDebugUnitTest/glassdepth/cards.png")
            out.parentFile?.mkdirs()
            out.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        // a point inside each card at the given height (0..1 of the card), away from the edge and the top sheen
        fun at(tag: String, fraction: Float): Int {
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            return picture.getPixel(b.center.x.toInt(), (b.top + b.height * fraction).toInt())
        }
        assertEquals("a content card shows the page through", lum(page.toArgb()), lum(at("plain", 0.5f)))
        assertTrue("a glass control is lighter than the page: ${at("glass", 0.5f)}", lum(at("glass", 0.5f)) > lum(at("plain", 0.5f)))
        assertTrue("the focused card is lit: ${at("focused", 0.5f)}", lum(at("focused", 0.5f)) > lum(at("plain", 0.5f)) + 20)
        assertTrue("the reflection makes the lower half lighter", lum(at("focused", 0.9f)) > lum(at("focused", 0.5f)))
    }
}
