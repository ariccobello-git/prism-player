package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented names in the styles IPTV lists use. */
class VariantTagsTest {
    private fun check(name: String, title: String, vararg badges: String) {
        val parsed = VariantTags.of(name)
        assertEquals("title of '$name'", title, parsed.title)
        assertEquals("badges of '$name'", badges.toList(), parsed.badges)
    }

    @Test
    fun clearTagsAreFoundAnywhere() {
        check("Avatar (2009) 4K", "Avatar (2009)", "4K")
        check("Avatar [SUB ITA]", "Avatar", "SUB ITA")
        check("Avatar - ITA - FHD", "Avatar", "ITA", "FHD")
        check("Avatar ENG (2009)", "Avatar (2009)", "ENG")
        check("Avatar 2009 1080p HEVC", "Avatar 2009", "FHD", "HEVC")
        check("Avatar H.265 HDR", "Avatar", "HDR", "HEVC")
        check("Avatar Full HD", "Avatar", "FHD")
        check("Avatar ITA SUB", "Avatar", "SUB ITA")
    }

    @Test
    fun shortTagsCountOnlyWhereListsPutThem() {
        check("IT - Avatar", "Avatar", "ITA")
        check("EN | Avatar (2009)", "Avatar (2009)", "ENG")
        check("[IT] Avatar", "Avatar", "ITA")
        check("Avatar HD", "Avatar", "HD")
        check("Avatar (FR)", "Avatar", "FRA")
        // Words of a title stay where they are.
        check("It Follows", "It Follows")
        check("IT", "IT")
        check("Just Do It", "Just Do It")
        check("JUST DO IT", "JUST DO IT")
        check("The English Patient", "The English Patient")
        check("Hd Hunters", "Hd Hunters")
        check("La vita è bella", "La vita è bella")
    }

    @Test
    fun decoratedLettersCountAsTheLettersTheyStandFor() {
        check("Avatar \uD835\uDFD2\uD835\uDC0A", "Avatar", "4K")          // bold 4K
        check("Avatar 4\uD83C\uDD3A", "Avatar", "4K")                       // 4 and squared K
        check("Avatar \u1DA0\u1D34\u1D30", "Avatar", "FHD")                // superscript FHD
        check("\u24BE\u24E3\u24D0 - Avatar", "Avatar", "ITA")              // circled Ita
    }

    @Test
    fun namesWithoutTagsAreLeftAlone() {
        check("Dr. House", "Dr. House")
        check("S.W.A.T.", "S.W.A.T.")
        check("Rai 1", "Rai 1")
        check("", "")
    }

    @Test
    fun tagsEveryGroupHasAreDropped() {
        val groups = VariantTags.distinctGroupBadges(listOf("FILM ITA 4K", "FILM ITA", "FILM 4K ITA"))
        assertEquals(listOf("4K"), groups["FILM ITA 4K"])
        assertEquals(null, groups["FILM ITA"])
        // Next to subtitled copies, "ITA" (dubbed) is what tells them apart, so it stays.
        val mixed = VariantTags.distinctGroupBadges(listOf("FILM ITA", "FILM | SUB ITA"))
        assertEquals(listOf("ITA"), mixed["FILM ITA"])
        assertEquals(listOf("SUB ITA"), mixed["FILM | SUB ITA"])
        assertTrue(VariantTags.distinctGroupBadges(listOf("FILM 4K", "FILM 4K")).isEmpty())
    }

    @Test
    fun measuredFactsWinOverTheName() {
        assertEquals(listOf("ITA", "FHD", "HEVC"), VariantTags.withFacts(listOf("FHD", "HEVC", "ITA"), listOf("4K")))
        assertEquals(listOf("SUB ITA", "4K"), VariantTags.withFacts(emptyList(), listOf("SUB ITA", "4K")))
        assertEquals(listOf("ENG", "SUB ITA", "FHD"), VariantTags.withFacts(listOf("ENG", "FHD"), listOf("SUB ITA")))
    }

    @Test
    fun languagesAreToldApartFromQuality() {
        assertTrue(VariantTags.isLanguage("ITA"))
        assertTrue(VariantTags.isLanguage("SUB ITA"))
        assertTrue(!VariantTags.isLanguage("4K"))
    }
}
