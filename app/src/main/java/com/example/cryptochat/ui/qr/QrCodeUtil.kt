package com.example.cryptochat.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Vygeneruje QR kód jako Bitmap z libovolného textu (zde: Base64 klíč).
 *
 * Pixely se nejdřív spočítají do pole a zapíšou naráz přes [Bitmap.setPixels]
 * - to je řádově rychlejší než volat [Bitmap.setPixel] pro každý z ~262 000
 * pixelů zvlášť (znatelně méně sekání při zobrazení QR).
 */
fun generateQrBitmap(text: String, sizePx: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val rowOffset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[rowOffset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    return bitmap
}
