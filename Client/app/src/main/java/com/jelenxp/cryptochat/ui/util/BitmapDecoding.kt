package com.jelenxp.cryptochat.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Bezpečné dekódování obrázků z disku pro UI (avatary).
 *
 * Bez podvzorkování by se velká fotka načetla v plné velikosti - zbytečný jank a
 * hlavně riziko [OutOfMemoryError] u fotoaparátových snímků (desítky MB v paměti).
 * Navíc `catch (Exception)` by `OutOfMemoryError` (je to `Error`, ne `Exception`)
 * NECHYTIL a poškozený/obří soubor by shodil celou appku.
 */

/**
 * Spočítá `inSampleSize` (mocnina 2), aby dekódovaný obrázek nebyl výrazně větší
 * než [reqPx] na kratší i delší hraně. Standardní postup z dokumentace Androidu.
 */
fun sampleSizeFor(srcWidth: Int, srcHeight: Int, reqPx: Int): Int {
    if (reqPx <= 0 || srcWidth <= 0 || srcHeight <= 0) return 1
    var inSampleSize = 1
    if (srcHeight > reqPx || srcWidth > reqPx) {
        val halfHeight = srcHeight / 2
        val halfWidth = srcWidth / 2
        while (halfHeight / inSampleSize >= reqPx && halfWidth / inSampleSize >= reqPx) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Načte obrázek ze souboru podvzorkovaný tak, aby nebyl větší než ~[reqPx] px.
 * Vrací `null` při jakémkoli problému (neexistuje, poškozený, málo paměti) -
 * chytá [Throwable], takže ani [OutOfMemoryError] neshodí appku.
 */
fun decodeSampledFile(path: String, reqPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqPx)
        }
        BitmapFactory.decodeFile(path, opts)
    } catch (e: Throwable) {
        null
    }
}
