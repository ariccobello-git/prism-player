package dev.red74.prismplayer.data

/**
 * The famous streaming platforms, recognised by the name of a group in the list ("NETFLIX", "Amazon Prime", "DISNEY+"...).
 * Their films and series come first among the new arrivals: the viewer knows those titles and the platforms' catalogues
 * are what the others are measured against. Only the group name is read: nothing is asked of the network.
 */
object VodPlatforms {
    private val NAMES = listOf(
        "netflix", "prime video", "amazon", "amazon prime", "prime", "disney", "apple tv", "appletv", "hbo", "max",
        "paramount", "sky original", "now tv", "discovery", "rai play", "raiplay", "mediaset infinity", "infinity",
    )

    /** 0 for a group of a famous platform, 1 for any other; sorting by it keeps the original order inside each. */
    fun rank(group: String?): Int = if (group != null && isPlatform(group)) 0 else 1

    fun isPlatform(group: String): Boolean {
        val padded = StringBuilder(group.length + 2).append(' ')
        var space = true
        for (c in group.lowercase()) {
            if (c.isLetterOrDigit()) { padded.append(c); space = false } else if (!space) { padded.append(' '); space = true }
        }
        padded.append(' ')
        val text = padded.toString()
        return NAMES.any { text.contains(" $it ") }
    }
}
