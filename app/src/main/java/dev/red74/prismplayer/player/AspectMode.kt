package dev.red74.prismplayer.player

/**
 * Video format choices.
 *
 * @property ratio forced display ratio, or null to use the full screen box.
 * @property stretch true when the video is stretched to fill its box; false keeps the stream's own ratio (letterbox).
 */
enum class AspectMode(val ratio: Float?, val stretch: Boolean) {
    /** Keep the stream's native ratio, letterboxed on the screen. */
    AUTO(ratio = null, stretch = false),

    /** Force 16:9: the video is stretched into a 16:9 box centred on the screen. */
    RATIO_16_9(ratio = 16f / 9f, stretch = true),

    /** Force 4:3: the video is stretched into a 4:3 box centred on the screen. */
    RATIO_4_3(ratio = 4f / 3f, stretch = true),

    /** Stretch the video to the whole screen, whatever its native ratio. */
    FIT_SCREEN(ratio = null, stretch = true);

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]

    fun previous(): AspectMode = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        fun fromName(name: String?): AspectMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
