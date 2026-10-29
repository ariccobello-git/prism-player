package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutsTest {
    @Test
    fun encodeDecodeKeepsOrderAndDropsDuplicates() {
        val raw = Shortcuts.encode(listOf("com.netflix.ninja", "org.xbmc.kodi", "com.netflix.ninja"))
        assertEquals(listOf("com.netflix.ninja", "org.xbmc.kodi"), Shortcuts.decode(raw))
    }

    @Test
    fun invalidNamesAreIgnored() {
        assertEquals(listOf("a.b"), Shortcuts.decode("a.b, ,nodot,../x,a b.c"))
        assertEquals(emptyList<String>(), Shortcuts.decode(null))
    }

    @Test
    fun addAndRemove() {
        var list = emptyList<String>()
        list = Shortcuts.add(list, "com.a.b")
        list = Shortcuts.add(list, "com.a.b")
        list = Shortcuts.add(list, "bad")
        list = Shortcuts.add(list, "com.c.d")
        assertEquals(listOf("com.a.b", "com.c.d"), list)
        assertEquals(listOf("com.c.d"), Shortcuts.remove(list, "com.a.b"))
    }
}
