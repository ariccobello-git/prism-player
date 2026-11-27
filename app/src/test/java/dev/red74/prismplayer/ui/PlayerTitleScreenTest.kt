package dev.red74.prismplayer.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.red74.prismplayer.player.AspectMode
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real player, controls open: the name of what plays is on screen, inside the picture, for live and for films. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "it-w1280dp-h720dp")
class PlayerTitleScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun check(channels: List<Channel>, title: String, line: String?) {
        rule.setContent {
            PlayerScreen(
                channels = channels, startIndex = 0, aspect = AspectMode.AUTO, onAspectChange = {}, onIndexChange = {}, onExit = {},
                nowTitle = { line },
            )
        }
        rule.waitForIdle()
        val height = rule.onRoot().fetchSemanticsNode().size.height
        val width = rule.onRoot().fetchSemanticsNode().size.width
        for (text in listOfNotNull(title, line)) {
            val node = rule.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
            val b = node.boundsInRoot
            assertTrue("'$text' not inside the screen: $b", b.top >= 0f && b.bottom <= height && b.left >= 0f && b.right <= width)
            assertTrue("'$text' has no size: $b", b.width > 20f && b.height > 10f)
        }
    }

    @Test
    fun liveShowsChannelAndProgramme() = check(
        listOf(Channel("Rai Movie", "http://x/1", "Film", type = ContentType.LIVE), Channel("Rai 2", "http://x/2", "Rai", type = ContentType.LIVE)),
        "Rai Movie", "Il Padrino",
    )

    @Test
    fun filmShowsItsName() = check(
        listOf(Channel("Heat", "http://x/f.mp4", "Film", type = ContentType.MOVIE)), "Heat", null,
    )
}
