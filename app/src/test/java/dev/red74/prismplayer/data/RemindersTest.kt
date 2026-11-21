package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val now = 1_000_000_000L
    private fun r(title: String = "Roma - Lazio", start: Long = now + 3_600_000L, channel: String = "k1") =
        Reminder(Reminders.idOf(title, start, channel), title, start, "Sky Sport", channel)

    @Test fun idIsStableAndDistinguishes() {
        assertEquals(r().id, r().id)
        assertTrue(r().id != r(start = now + 7_200_000L).id)
        assertTrue(r().id != r(channel = "k2").id)
        assertTrue(r().id != r(title = "Inter - Milan").id)
        // case and punctuation do not matter
        assertEquals(r("Roma - Lazio").id, r("ROMA: Lazio").id)
    }

    @Test fun onlyFutureProgrammesCanBeSet() {
        assertTrue(Reminders.canSet(now + 1, now))
        assertFalse(Reminders.canSet(now, now))
        assertFalse(Reminders.canSet(now - 1, now))
    }

    @Test fun triggerIsFiveMinutesBeforeOrAtOnce() {
        assertEquals(now + 3_600_000L - Reminders.LEAD_MS, Reminders.triggerAt(now + 3_600_000L, now))
        assertEquals(now + 1_000L, Reminders.triggerAt(now + 60_000L, now))
    }

    @Test fun toggleAddsAndRemoves() {
        val a = r(); val b = r("Inter - Milan", start = now + 1_800_000L)
        var list = Reminders.toggled(emptyList(), a)
        list = Reminders.toggled(list, b)
        assertEquals(listOf(b, a), list) // sorted by start
        list = Reminders.toggled(list, a)
        assertEquals(listOf(b), list)
    }

    @Test fun neverMoreThanMax() {
        var list = emptyList<Reminder>()
        for (i in 0 until 60) list = Reminders.toggled(list, r("T$i", start = now + i * 1000L))
        assertEquals(Reminders.MAX, list.size)
        assertEquals("T59", list.last().title)
    }

    @Test fun dueWindow() {
        val a = r(start = now + 3 * 60_000L) // 3 min away: inside the lead
        val b = r("B", start = now + 30 * 60_000L)
        val c = r("C", start = now - 11 * 60_000L) // over
        val list = listOf(c, a, b)
        assertEquals(listOf(a), Reminders.due(list, now, emptySet()))
        assertTrue(Reminders.due(list, now, setOf(a.id)).isEmpty())
        assertEquals(listOf(b), Reminders.due(list, now + 26 * 60_000L, emptySet()))
        assertEquals(listOf(a, b), Reminders.pruned(list, now))
    }

    @Test fun minutesLeftRoundsUp() {
        assertEquals(5, Reminders.minutesLeft(now + 5 * 60_000L, now))
        assertEquals(1, Reminders.minutesLeft(now + 1, now))
        assertEquals(0, Reminders.minutesLeft(now - 5, now))
    }

    @Test fun encodeDecodeRoundTrip() {
        val list = listOf(r(), r("Un titolo\tcon tab\ne a capo", start = now + 99_000L))
        val back = Reminders.decode(Reminders.encode(list))
        assertEquals(2, back.size)
        assertEquals(list[0], back[0])
        assertEquals("Un titolo con tab e a capo", back[1].title)
        assertEquals(list[1].id, back[1].id)
    }

    @Test fun decodeIgnoresGarbage() {
        assertTrue(Reminders.decode(null).isEmpty())
        assertTrue(Reminders.decode("").isEmpty())
        assertTrue(Reminders.decode("nonsense").isEmpty())
        assertTrue(Reminders.decode("id\tnotanumber\tk\tn\tt").isEmpty())
        assertEquals(1, Reminders.decode("id\t5\tk\tn\tt\nbad line").size)
        // the same reminder twice is one
        assertEquals(1, Reminders.decode("id\t5\tk\tn\tt\nid\t5\tk\tn\tt").size)
    }
}
