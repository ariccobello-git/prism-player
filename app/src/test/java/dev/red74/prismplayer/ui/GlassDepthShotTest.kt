package dev.red74.prismplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cards rendered for real: a content card has no glass at rest (the page shows through), a control keeps its glass,
 * and the card that holds the cursor gets a lit fill and a light reflection on its lower half. The pictures are saved
 * with the test reports.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class GlassDepthShotTest {
    @get:Rule
    val rule = createComposeRule()

    private val page = Color(0xFF0B0F1A)

    private fun shot(tag: String): android.graphics.Bitmap {
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        runCatching {
            val dir = File("build/test-results/testDebugUnitTest/glassdepth").apply { mkdirs() }
            File(dir, "$tag.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

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
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val plain = shot("plain")
        val glass = shot("glass")
        val focused = shot("focused")
        // a point well inside each card, away from the edge and the top sheen
        val inside = { b: android.graphics.Bitmap -> b.getPixel(b.width / 2, b.height / 2) }
        val low = { b: android.graphics.Bitmap -> b.getPixel(b.width / 2, b.height * 9 / 10) }
        val pageColor = page.toArgb()
        assertEquals("a content card shows the page through", lum(pageColor), lum(inside(plain)))
        assertTrue("a glass control is lighter than the page: ${inside(glass)}", lum(inside(glass)) > lum(inside(plain)))
        assertTrue("the focused card is lit: ${inside(focused)}", lum(inside(focused)) > lum(inside(plain)) + 20)
        assertTrue("the reflection makes the lower half lighter than the middle", lum(low(focused)) > lum(inside(focused)))
        assertNotEquals(inside(plain), inside(focused))
    }
}
