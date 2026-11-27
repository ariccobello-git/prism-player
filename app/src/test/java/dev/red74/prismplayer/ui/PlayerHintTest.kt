package dev.red74.prismplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The hint line under the player's controls names only the keys that work on what is playing. */
class PlayerHintTest {
    private fun hint(seek: Boolean, zap: Boolean) = playerHint(seek, zap, "SEEK", "ZAP", "BASE")

    @Test
    fun aLiveChannelWithoutSeekingShowsNoSeekKeys() = assertEquals("ZAP · BASE", hint(seek = false, zap = true))

    @Test
    fun aFilmShowsNoChannelKeys() = assertEquals("SEEK · BASE", hint(seek = true, zap = false))

    @Test
    fun aLiveChannelThatCanSeekShowsBoth() = assertEquals("SEEK · ZAP · BASE", hint(seek = true, zap = true))

    @Test
    fun aSingleStreamShowsOnlyTheBase() = assertEquals("BASE", hint(seek = false, zap = false))
}
