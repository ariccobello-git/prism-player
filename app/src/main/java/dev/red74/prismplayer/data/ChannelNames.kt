package dev.red74.prismplayer.data

/**
 * Lists name live channels with technical decoration: "[S+] SKY CINEMA UNO FULL HD", "IT: Rai 1 HD". The screens show
 * the plain name, and the guide is matched on it too: the guide knows the channel as "Sky Cinema Uno".
 */
object ChannelNames {
    /** A short bracket group at the start or the end ("[S+]", "(IT)", "[ S+ ]"): a provider mark, not part of the name. */
    private val MARK_START = Regex("""^\s*[\[({]\s*[^\])}\s]{1,5}\s*[\])}]\s*""")
    private val MARK_END = Regex("""\s*[\[({]\s*[^\])}\s]{1,5}\s*[\])}]\s*$""")

    /** The name without quality tags and provider marks; the name itself when nothing would be left. */
    fun clean(name: String): String {
        var title = VariantTags.of(name).title
        repeat(2) {
            title = title.replace(MARK_START, "").replace(MARK_END, "").trim()
        }
        return title.ifEmpty { name.trim() }
    }

    /** The plain name of a group, same rules ("SKY CINEMA [S+]" becomes "SKY CINEMA"). */
    fun cleanGroup(group: String?): String? = group?.takeIf { it.isNotBlank() }?.let { clean(it) }

    /** Key to match a channel with its guide entry: the cleaned name, letters and digits only. */
    fun key(name: String?): String? = name?.takeIf { it.isNotBlank() }?.let { EpgData.normalize(clean(it)) }
}
