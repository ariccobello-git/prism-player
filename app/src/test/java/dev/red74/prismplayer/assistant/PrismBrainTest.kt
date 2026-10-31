package dev.red74.prismplayer.assistant

import dev.red74.prismplayer.assistant.PrismBrain.Intent
import dev.red74.prismplayer.assistant.PrismKnowledge.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismBrainTest {
    private val list = object : PrismKnowledge {
        val films = listOf("Matrix (1999)", "Matrix Reloaded", "Heat - La sfida")
        val shows = listOf("Dr. House", "La casa di carta")
        val channels = listOf("IT| Rai 1 HD", "IT| Rai 2", "Sky Cinema Uno")

        override suspend fun find(kind: Kind, query: String, limit: Int): List<String> {
            val all = when (kind) { Kind.FILM -> films; Kind.SERIES -> shows; Kind.CHANNEL -> channels }
            return all.filter { it.lowercase().contains(query.lowercase()) }.take(limit)
        }

        override suspend fun count(kind: Kind): Int = when (kind) { Kind.FILM -> 1200; Kind.SERIES -> 340; Kind.CHANNEL -> 950 }

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
        assertEquals(Intent.Unknown, PrismBrain.understand("blu verde giallo"))
        assertEquals(Intent.Unknown, PrismBrain.understand("   "))
    }

    @Test
    fun answersComeFromTheList() {
        assertEquals("Ecco cosa ho trovato per matrix. film: Matrix (1999), Matrix Reloaded.", ask("cerca il film matrix"))
        assertTrue(ask("c'è rai?").contains("canali: IT| Rai 1 HD, IT| Rai 2"))
        assertEquals("Non ho trovato niente per titanic in questa lista.", ask("cerca titanic"))
        assertEquals("Adesso su IT| Rai 1 HD c'è Tg1.", ask("cosa c'è su rai 1"))
        assertTrue(ask("cosa danno su rai 2").contains("la guida non dice"))
        assertTrue(ask("cosa c'è su canale inesistente").startsWith("Non ho trovato un canale"))
        assertEquals("Matrix (1999): Un hacker scopre la verità sul mondo.", ask("di cosa parla matrix"))
        assertTrue(ask("trama di dr house").contains("descrizione non è disponibile"))
        assertEquals("In questa lista ci sono 1200 film.", ask("quanti film ci sono"))
        assertEquals("In questa lista ci sono 950 canali live, 1200 film e 340 serie.", ask("quanti contenuti ci sono"))
        assertTrue(ask("non so").startsWith("Scusa, non ho capito"))
    }

    @Test
    fun yesAndNoAreHeard() {
        assertEquals(true, PrismBrain.yesOrNo("Sì"))
        assertEquals(true, PrismBrain.yesOrNo("si esatto"))
        assertEquals(true, PrismBrain.yesOrNo("Sì, va bene"))
        assertEquals(false, PrismBrain.yesOrNo("No"))
        assertEquals(false, PrismBrain.yesOrNo("Nò."))
        assertEquals(false, PrismBrain.yesOrNo("noo"))
        assertEquals(false, PrismBrain.yesOrNo("non è giusto"))
        assertEquals(false, PrismBrain.yesOrNo("no non va bene"))
        assertNull(PrismBrain.yesOrNo("forse"))
        assertNull(PrismBrain.yesOrNo(""))
    }
}
