package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WithoutMarkedTest {
    private val films = listOf("a", "b", "c", "d", "e")

    @Test
    fun nothingMarkedKeepsTheVerySameList() {
        assertSame(films, WithoutMarked.of(films, emptySet()) { it })
        assertSame(films, WithoutMarked.of(films, setOf("zzz")) { it })
    }

    @Test
    fun markedOnesAreLeftOutInOrder() {
        val shown = WithoutMarked.of(films, setOf("b", "e")) { it }
        assertEquals(listOf("a", "c", "d"), shown)
        assertEquals(3, shown.size)
        assertEquals("d", shown[2])
    }

    @Test
    fun allMarkedGivesAnEmptyList() {
        assertEquals(emptyList<String>(), WithoutMarked.of(films, films.toSet()) { it })
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun outsideTheListThrows() {
        WithoutMarked.of(films, setOf("a")) { it }[4]
    }
}
