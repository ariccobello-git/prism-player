package dev.red74.prismplayer.assistant

import dev.red74.prismplayer.assistant.PrismBrain.Confirmation
import dev.red74.prismplayer.assistant.PrismBrain.Genre
import dev.red74.prismplayer.assistant.PrismBrain.Intent
import dev.red74.prismplayer.assistant.PrismKnowledge.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismBrainTest {
    private var opened: String? = null

    private val list = object : PrismKnowledge {
        val films = listOf("Matrix (1999)", "Matrix Reloaded", "Heat - La sfida")
        val shows = listOf("Dr. House", "La casa di carta")
        val channels = listOf("IT| Rai 1 HD", "IT| Rai 2", "Sky Cinema Uno")

        private fun all(kind: Kind) = when (kind) { Kind.FILM -> films; Kind.SERIES -> shows; Kind.CHANNEL -> channels }

        override suspend fun find(kind: Kind, query: String, limit: Int): List<String> =
            all(kind).filter { it.lowercase().contains(query.lowercase()) }.take(limit)

        override suspend fun best(kind: Kind?, query: String): PrismKnowledge.Hit? {
            for (k in kind?.let { listOf(it) } ?: listOf(Kind.CHANNEL, Kind.FILM, Kind.SERIES)) {
                val title = all(k).firstOrNull { it.lowercase().contains(query.lowercase()) } ?: continue
                return PrismKnowledge.Hit(title, k) { opened = title }
            }
            return null
        }

        override suspend fun count(kind: Kind): Int = when (kind) { Kind.FILM -> 1200; Kind.SERIES -> 340; Kind.CHANNEL -> 950 }

        override suspend fun group(kind: Kind?, stems: List<String>): PrismKnowledge.Group? =
            if ("horror" in stems && kind != Kind.CHANNEL) PrismKnowledge.Group("Film | Horror", Kind.FILM, 87, listOf("It", "Scream")) else null

        override suspend fun latest(kind: Kind, limit: Int): List<String> = all(kind).reversed().take(limit)

        override suspend fun onAir(query: String): Pair<String, String?>? =
            channels.firstOrNull { it.lowercase().contains(query.lowercase()) }?.let { it to if (it.contains("Rai 1")) "Tg1" else null }

        override suspend fun plot(query: String): Pair<String, String?>? = when {
            "matrix".contains(query.lowercase()) -> "Matrix (1999)" to "Un hacker scopre la verità sul mondo."
            "dr house".contains(query.lowercase()) -> "Dr. House" to null
            else -> null
        }
    }

    private fun ask(text: String) = runBlocking { PrismBrain.answer(text, list) }

    @Test
    fun questionsAreUnderstood() {
        assertEquals(Intent.Search(Kind.FILM, "matrix"), PrismBrain.understand("Cerca il film Matrix"))
        assertEquals(Intent.Search(null, "matrix"), PrismBrain.understand("Ciao Prism, c'è Matrix?"))
        assertEquals(Intent.Search(Kind.SERIES, "casa di carta"), PrismBrain.understand("trovami la serie la casa di carta per favore"))
        assertEquals(Intent.OnAir("rai 1"), PrismBrain.understand("Cosa c'è adesso su Rai 1?"))
        assertEquals(Intent.OnAir("rai 1"), PrismBrain.understand("che cosa danno su rai 1"))
        assertEquals(Intent.Plot("matrix"), PrismBrain.understand("Di cosa parla Matrix?"))
        assertEquals(Intent.Plot("dr house"), PrismBrain.understand("Qual è la trama di Dr. House"))
        assertEquals(Intent.Count(Kind.FILM), PrismBrain.understand("Quanti film ci sono?"))
        assertEquals(Intent.Count(null), PrismBrain.understand("quanti contenuti ha la lista"))
        assertEquals(Intent.Help(PrismBrain.Topic.FAVORITES), PrismBrain.understand("Come aggiungo un preferito?"))
        assertEquals(Intent.Help(PrismBrain.Topic.AUDIO), PrismBrain.understand("come metto i sottotitoli"))
        assertEquals(Intent.Help(PrismBrain.Topic.SPEED), PrismBrain.understand("Come si va avanti veloce?"))
        assertEquals(Intent.Help(PrismBrain.Topic.LISTS), PrismBrain.understand("come cambio lista"))
        assertEquals(Intent.WhoAreYou, PrismBrain.understand("Chi sei?"))
        assertEquals(Intent.Hello, PrismBrain.understand("Ciao"))
        assertEquals(Intent.Thanks, PrismBrain.understand("grazie mille"))
        assertEquals(Intent.Unknown, PrismBrain.understand("che tempo fa domani"))
        assertEquals(Intent.Unknown, PrismBrain.understand("   "))
    }

    @Test
    fun requestsToWatchSomethingAreUnderstood() {
        assertEquals(Intent.Play(Kind.CHANNEL, "rai 1"), PrismBrain.understand("Metti Rai 1"))
        assertEquals(Intent.Play(Kind.CHANNEL, "rai 1"), PrismBrain.understand("metti il canale Rai 1"))
        assertEquals(Intent.Play(Kind.FILM, "matrix"), PrismBrain.understand("Voglio guardare il film Matrix"))
        assertEquals(Intent.Play(null, "matrix"), PrismBrain.understand("Ehi Prism, puoi mettere Matrix?"))
        assertEquals(Intent.Play(Kind.SERIES, "dr house"), PrismBrain.understand("apri la serie Dr House"))
        assertEquals(Intent.Play(Kind.CHANNEL, "sky cinema uno"), PrismBrain.understand("vai su Sky Cinema Uno"))
    }

    @Test
    fun genresAndNewArrivalsAreUnderstood() {
        assertEquals(Intent.Genre(Kind.FILM, Genre.HORROR), PrismBrain.understand("Cerca un film horror"))
        assertEquals(Intent.Genre(null, Genre.COMEDY), PrismBrain.understand("hai qualche commedia?"))
        assertEquals(Intent.Genre(null, Genre.ANIMATION), PrismBrain.understand("cartoni animati"))
        assertEquals(Intent.Genre(Kind.FILM, Genre.ACTION), PrismBrain.understand("consigliami un film d'azione"))
        assertEquals(Intent.Latest(Kind.FILM), PrismBrain.understand("quali sono gli ultimi film aggiunti?"))
        assertEquals(Intent.Latest(Kind.SERIES), PrismBrain.understand("serie nuove"))
        // A title with a genre word in it is still a title.
        assertEquals(Intent.Search(null, "horror story"), PrismBrain.understand("cerca horror story"))
    }

    @Test
    fun answersComeFromTheList() {
        assertEquals("Per matrix ho trovato. film: Matrix (1999), Matrix Reloaded.", ask("cerca il film matrix").text)
        assertTrue(ask("c'è rai?").text.contains("canali: IT| Rai 1 HD, IT| Rai 2"))
        assertEquals("Non trovo niente per titanic in questa lista.", ask("cerca titanic").text)
        assertEquals("Adesso su IT| Rai 1 HD c'è Tg1.", ask("cosa c'è su rai 1").text)
        assertTrue(ask("cosa danno su rai 2").text.contains("la guida non dice"))
        assertEquals("Matrix (1999). Un hacker scopre la verità sul mondo.", ask("di cosa parla matrix").text)
        assertTrue(ask("trama di dr house").text.contains("descrizione non è disponibile"))
        assertEquals("In questa lista ci sono 1200 film.", ask("quanti film ci sono").text)
        assertEquals("Nella categoria Film | Horror ci sono 87 film, per esempio It, Scream.", ask("un film horror").text)
        assertTrue(ask("film western").text.contains("non trovo una categoria western"))
        assertEquals("Le ultime serie arrivate sono: La casa di carta, Dr. House.", ask("ultime serie aggiunte").text)
        assertTrue(ask("che tempo fa").text.startsWith("Scusa, posso aiutarti solo"))
    }

    @Test
    fun playingOpensWhatWasAskedFor() {
        val reply = ask("metti rai 2")
        assertEquals("Ecco IT| Rai 2.", reply.text)
        assertNotNull(reply.action)
        reply.action!!.invoke()
        assertEquals("IT| Rai 2", opened)
        assertEquals("Ti apro La casa di carta.", ask("apri la serie la casa di carta").text)
        val missing = ask("metti titanic")
        assertEquals("Non trovo titanic in questa lista.", missing.text)
        assertNull(missing.action)
    }

    @Test
    fun yesNoAndCorrectionsAreHeard() {
        assertEquals(true, PrismBrain.yesOrNo("Sì"))
        assertEquals(true, PrismBrain.yesOrNo("si esatto"))
        assertEquals(true, PrismBrain.yesOrNo("Sì, va bene"))
        assertEquals(true, PrismBrain.yesOrNo("sìì"))
        assertEquals(false, PrismBrain.yesOrNo("No"))
        assertEquals(false, PrismBrain.yesOrNo("Nò."))
        assertEquals(false, PrismBrain.yesOrNo("noo"))
        assertEquals(false, PrismBrain.yesOrNo("non è giusto"))
        assertEquals(false, PrismBrain.yesOrNo("no non va bene"))
        assertNull(PrismBrain.yesOrNo("forse"))
        assertNull(PrismBrain.yesOrNo(""))
        assertEquals(Confirmation.Yes, PrismBrain.confirmation("sì"))
        assertEquals(Confirmation.No, PrismBrain.confirmation("no"))
        assertEquals(Confirmation.Instead("metti Rai 2"), PrismBrain.confirmation("metti Rai 2"))
        assertEquals(Confirmation.Unclear, PrismBrain.confirmation("boh"))
    }

    @Test
    fun theWarmestVoiceIsChosen() {
        val options = listOf(
            VoicePick.Option("it-it-x-itb-local", 400, false),
            VoicePick.Option("it-it-x-itd-local", 400, false),
            VoicePick.Option("it-it-x-itd-network", 400, true),
            VoicePick.Option("it-IT-language", 300, false),
        )
        assertEquals("it-it-x-itd-local", VoicePick.best(options))
        assertEquals("it-it-x-itb-local", VoicePick.best(options.take(1) + options.last()))
        assertNull(VoicePick.best(emptyList()))
    }
}
