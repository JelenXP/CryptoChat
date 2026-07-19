package com.jelenxp.cryptochat.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Ukládání a mazání profilových fotek kontaktů. Fotky leží v soukromém úložišti
 * appky (`files/avatars/`), takže je nevidí jiné aplikace; díky
 * `allowBackup="false"` se ani nezálohují do cloudu.
 *
 * Vybraná fotka se před uložením zmenší (max hrana [MAX_DIMENSION] px) a uloží
 * jako JPEG - drží to soubory malé a rychlé na vykreslení. Název souboru nese
 * časové razítko, aby se cesta při každé změně lišila a Compose fotku znovu
 * načetl (nezůstal starý obrázek z cache).
 *
 * Všechny operace jsou odolné proti výjimkám (vrací null / tiše selžou), ať
 * poškozený nebo nečitelný obrázek nikdy neshodí appku.
 */
object AvatarStore {

    private const val TAG = "AvatarStore"
    private const val DIR = "avatars"
    private const val MAX_DIMENSION = 512
    private const val JPEG_QUALITY = 85

    /**
     * Načte obrázek z [source], zmenší, uloží jako fotku kontaktu [contactId] a
     * vrátí absolutní cestu k souboru (nebo null při selhání). Předchozí fotky
     * téhož kontaktu smaže.
     */
    fun saveAvatar(context: Context, contactId: String, source: Uri): String? {
        return try {
            val bitmap = decodeScaled(context, source) ?: return null
            val rotated = applyExifRotation(context, source, bitmap)
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            deleteAvatars(context, contactId)
            val file = File(dir, "${safeId(contactId)}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            rotated.recycle()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Uložení fotky se nepovedlo", e)
            null
        }
    }

    /**
     * Vytvoří dočasný soubor v cache a vrátí jeho `content://` Uri přes
     * FileProvider - sem fotoaparát zapíše vyfocenou fotku. Vrátí null při chybě.
     */
    fun newCameraOutputUri(context: Context): Uri? {
        return try {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se vytvořit cíl pro foťák", e)
            null
        }
    }

    /** Smaže všechny fotky daného kontaktu. */
    fun deleteAvatars(context: Context, contactId: String) {
        try {
            val dir = File(context.filesDir, DIR)
            if (!dir.isDirectory) return
            val prefix = "${safeId(contactId)}_"
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix)) f.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mazání fotek se nepovedlo", e)
        }
    }

    /** Dekóduje bitmapu ze zdroje rovnou podvzorkovanou zhruba na [MAX_DIMENSION]. */
    private fun decodeScaled(context: Context, source: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // Po podvzorkování může být hrana pořád větší - dorovnáme přesně.
        val longEdge = maxOf(decoded.width, decoded.height)
        if (longEdge <= MAX_DIMENSION) return decoded
        val scale = MAX_DIMENSION.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    /** Otočí bitmapu podle EXIF orientace (fotky z foťáku bývají „na boku"). */
    private fun applyExifRotation(context: Context, source: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val orientation = context.contentResolver.openInputStream(source)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Ať název souboru neobsahuje nic, co by v cestě dělalo problém. */
    private fun safeId(id: String): String =
        id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}
