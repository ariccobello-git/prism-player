package dev.red74.prismplayer.player

/**
 * Video format choices: the usual screen formats plus the ratios used in cinema.
 *
 * @property ratio forced display ratio, or null to use the full screen box.
 * @property stretch true when the video is stretched to fill its box; false keeps the stream's own ratio (letterbox).
 * @property zoom true when the stream keeps its own ratio and is enlarged until the screen is full (edges cropped).
 * @property label fixed, language-neutral name; null for the modes whose name is translated in the app.
 */
enum class AspectMode(
    val ratio: Float?,
    val stretch: Boolean,
    val zoom: Boolean = false,
    val label: String? = null,
) {
    /** Keep the stream's native ratio, letterboxed on the screen. */
    AUTO(ratio = null, stretch = false),

    /** Stretch the video to the whole screen, whatever its native ratio. */
    FIT_SCREEN(ratio = null, stretch = true),

    /** Keep the native ratio and fill the screen, cropping what does not fit. */
    ZOOM(ratio = null, stretch = false, zoom = true),

    /** 4:3, the classic TV picture. */
    RATIO_4_3(ratio = 4f / 3f, stretch = true, label = "4:3"),

    /** 1.37:1, the Academy ratio of classic cinema. */
    RATIO_1_37(ratio = 1.37f, stretch = true, label = "1.37:1 Academy"),

    /** 1.66:1, European widescreen. */
    RATIO_1_66(ratio = 1.66f, stretch = true, label = "1.66:1 European"),

    /** 16:9, HD television. */
    RATIO_16_9(ratio = 16f / 9f, stretch = true, label = "16:9"),

    /** 1.85:1, cinema "flat". */
    RATIO_1_85(ratio = 1.85f, stretch = true, label = "1.85:1 Flat"),

    /** 2.00:1, Univisium (many streaming originals). */
    RATIO_2_00(ratio = 2.00f, stretch = true, label = "2.00:1 Univisium"),

    /** 2.20:1, 70 mm film. */
    RATIO_2_20(ratio = 2.20f, stretch = true, label = "2.20:1 70 mm"),

    /** 21:9, ultra-wide screens. */
    RATIO_21_9(ratio = 21f / 9f, stretch = true, label = "21:9 Ultra-wide"),

    /** 2.35:1, classic CinemaScope. */
    RATIO_2_35(ratio = 2.35f, stretch = true, label = "2.35:1 CinemaScope"),

    /** 2.39:1, modern anamorphic Scope. */
    RATIO_2_39(ratio = 2.39f, stretch = true, label = "2.39:1 Scope"),

    /** 2.76:1, Ultra Panavision 70. */
    RATIO_2_76(ratio = 2.76f, stretch = true, label = "2.76:1 Ultra Panavision");

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]

    fun previous(): AspectMode = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        fun fromName(name: String?): AspectMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
