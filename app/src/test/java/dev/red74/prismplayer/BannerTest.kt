package dev.red74.prismplayer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The TV home screen banner points at the moon artwork and that file exists (a new name makes the launcher drop its cached old banner). */
class BannerTest {
    private fun file(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    @Test
    fun manifestUsesTheMoonBanner() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:banner=\"@drawable/banner_moon\""))
        assertTrue(file("src/main/res/drawable-xhdpi/banner_moon.png").length() > 10_000)
    }
}
