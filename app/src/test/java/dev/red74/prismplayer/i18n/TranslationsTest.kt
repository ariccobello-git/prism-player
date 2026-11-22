package dev.red74.prismplayer.i18n

import dev.red74.prismplayer.pairing.PairPage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Italian, English and French must offer the same texts with the same placeholders, so no page falls back or crashes. */
class TranslationsTest {
    private fun strings(folder: String): Map<String, String> {
        val file = listOf(File("src/main/res/$folder/strings.xml"), File("app/src/main/res/$folder/strings.xml")).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun placeholders(text: String): List<String> = Regex("%\\d\\$[sd]|%%").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageHasEveryTextWithTheSamePlaceholders() {
        val english = strings("values")
        for (folder in listOf("values-it", "values-fr")) {
            val other = strings(folder)
            assertEquals("$folder: missing or extra texts", english.keys, other.keys)
            for ((key, text) in english) assertEquals("$folder/$key", placeholders(text), placeholders(other.getValue(key)))
        }
    }

    @Test
    fun frenchHasNoUnescapedApostrophes() {
        // A plain ' breaks Android resources: French uses the typographic ’ instead.
        for ((key, text) in strings("values-fr")) assertTrue("values-fr/$key", !text.contains('\''))
    }

    @Test
    fun phonePageFollowsTheBrowserLanguage() {
        assertEquals(PairPage.Lang.IT, PairPage.langFrom("it-IT,it;q=0.9"))
        assertEquals(PairPage.Lang.FR, PairPage.langFrom("fr-FR,fr;q=0.9,en;q=0.8"))
        assertEquals(PairPage.Lang.EN, PairPage.langFrom("de-DE"))
        assertEquals(PairPage.Lang.EN, PairPage.langFrom(null))
        assertTrue(PairPage.codeEntry(PairPage.Lang.FR).contains("Saisissez le code"))
        assertTrue(PairPage.codeEntry(PairPage.Lang.FR).contains("<html lang=\"fr\">"))
    }
}
