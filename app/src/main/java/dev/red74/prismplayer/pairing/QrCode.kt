package dev.red74.prismplayer.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A square QR module grid (true = dark). Drawn by the UI on a Canvas, so no bitmap is involved. */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

object QrCode {
    fun encode(content: String): QrMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 0,
        )
        // Width/height 0 asks for the smallest matrix: exactly one entry per QR module.
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bits.width
        val modules = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                modules[y * size + x] = bits.get(x, y)
            }
        }
        return QrMatrix(size, modules)
    }
}
