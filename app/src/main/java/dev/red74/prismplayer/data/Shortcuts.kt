package dev.red74.prismplayer.data

/** The apps the user pinned on the Shortcuts page, kept in order as package names (never any list address). */
object Shortcuts {
    private val valid = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun decode(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { valid.matches(it) }.distinct()

    fun encode(packages: List<String>): String = packages.filter { valid.matches(it) }.distinct().joinToString(",")

    fun add(packages: List<String>, pkg: String): List<String> =
        if (!valid.matches(pkg) || pkg in packages) packages else packages + pkg

    fun remove(packages: List<String>, pkg: String): List<String> = packages.filter { it != pkg }
}
