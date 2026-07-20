package com.jelenxp.cryptochat.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Obrázky v chatu: příprava k odeslání (zmenšení + komprese pod limit relaye),
 * uložení přijatých/odeslaných fotek do soukromého úložiště appky
 * (files/chat_media/) a jejich načtení k zobrazení. Fotky jsou tak jen na
 * zařízení, jiné aplikace je nevidí.
 */
object ChatMediaStore {

    private const val TAG = "ChatMediaStore"
    private const val DIR = "chat_media"
    private const val MAX_DIMENSION = 1920

    /** Strop velikosti (relay MAX_BLOB_SIZE 2 MB minus rezerva na GCM + hlavičku). */
    const val MAX_BYTES = 1_900_000

    /**
     * Načte obrázek z [source], zmenší (max hrana [MAX_DIMENSION]) a zkomprimuje
     * do JPEG pod [MAX_BYTES]. Vrací bajty, nebo null (nešlo dostatečně zmenšit /
     * chyba).
     */
    fun compress(context: Context, source: Uri): ByteArray? {
        return try {
            val bitmap = decodeScaled(context, source) ?: return null
            val rotated = applyExifRotation(context, source, bitmap)
            var quality = 85
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                quality -= 10
            } while (bytes.size > MAX_BYTES && quality >= 35)
            rotated.recycle()
            if (bytes.size > MAX_BYTES) null else bytes
        } catch (e: Throwable) {
            Log.e(TAG, "Komprese obrázku selhala (${e.javaClass.simpleName})")
            null
        }
    }

    /** Uloží JPEG bajty do nového souboru v úložišti chatu a vrátí cestu (nebo null). */
    fun save(context: Context, jpeg: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.writeBytes(jpeg)
            file.absolutePath
        } catch (e: Throwable) {
            Log.e(TAG, "Uložení obrázku selhalo (${e.javaClass.simpleName})")
            null
        }
    }

    /** Strop velikosti souboru/videa, který jde poslat (posílá se po kouscích). */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024

    /** Základní údaje o vybraném souboru. */
    data class FileInfo(val name: String, val mimeType: String, val size: Long)

    /** Zjistí název, typ a velikost vybraného souboru (nebo null). */
    fun fileInfo(context: Context, uri: Uri): FileInfo? {
        return try {
            var name = "soubor"
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            FileInfo(name, mime, size)
        } catch (e: Throwable) {
            Log.e(TAG, "Nepodařilo se přečíst údaje o souboru (${e.javaClass.simpleName})")
            null
        }
    }

    /** Zkopíruje vybraný soubor do úložiště chatu a vrátí cestu (nebo null). */
    fun copyIn(context: Context, uri: Uri, fileName: String): String? {
        return try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val safe = fileName.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
                .joinToString("").take(60).ifBlank { "soubor" }
            val out = File(dir, "${UUID.randomUUID().toString().take(8)}_$safe")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            out.absolutePath
        } catch (e: Throwable) {
            Log.e(TAG, "Kopírování souboru selhalo (${e.javaClass.simpleName})")
            null
        }
    }

    /** Otevře soubor v systémové aplikaci (přes FileProvider). */
    fun openFile(context: Context, path: String, mimeType: String?) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(path)
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Otevření souboru selhalo (${e.javaClass.simpleName})")
        }
    }

    /** Načte fotku ze souboru podvzorkovanou na [maxPx] (pro zobrazení v bublině). */
    fun decodeForDisplay(path: String, maxPx: Int = 1080): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Throwable) {
            Log.e(TAG, "Načtení obrázku selhalo (${e.javaClass.simpleName})")
            null
        }
    }

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
        } catch (e: Throwable) {
            bitmap
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target || h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
