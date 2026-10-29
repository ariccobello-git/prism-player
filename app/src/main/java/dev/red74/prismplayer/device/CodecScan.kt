package dev.red74.prismplayer.device

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** What this device can decode by itself, read from the system's own codec list (not from the web). */
object CodecScan {
    class Row(val label: String, val mime: String, val supported: Boolean, val hardware: Boolean)

    private val wanted = listOf(
        "H.264 / AVC" to "video/avc",
        "H.265 / HEVC" to "video/hevc",
        "AV1" to "video/av01",
        "VP9" to "video/x-vnd.on2.vp9",
        "Dolby Vision" to "video/dolby-vision",
        "AAC" to "audio/mp4a-latm",
        "Opus" to "audio/opus",
        "AC-3 (Dolby Digital)" to "audio/ac3",
        "E-AC-3 (Dolby Digital Plus)" to "audio/eac3",
        "Dolby TrueHD" to "audio/true-hd",
        "DTS" to "audio/vnd.dts",
    )

    fun scan(): List<Row> {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        }.getOrDefault(emptyList())
        return wanted.map { (label, mime) ->
            val matching = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            Row(label, mime, matching.isNotEmpty(), matching.any(::isHardware))
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !(info.name.startsWith("OMX.google.", true) || info.name.startsWith("c2.android.", true))
}
