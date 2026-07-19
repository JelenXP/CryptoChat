package com.jelenxp.cryptochat.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * Pomocné funkce pro práci se soubory přes Storage Access Framework (bez
 * oprávnění k úložišti). Šifrování/dešifrování běží **streamově** (viz
 * [com.jelenxp.cryptochat.crypto.FileStreamCipher]) přes mezisoubor v cache -
 * proto pomocníci na dočasné soubory, kopii do cíle a sdílení.
 */
object FileTransfer {

    /** Výchozí strop velikosti souboru (256 MB), když je limit v nastavení zapnutý. */
    const val DEFAULT_LIMIT_BYTES = 256L * 1024 * 1024

    private const val TEMP_DIR = "share"

    /** Zobrazované jméno souboru (DISPLAY_NAME), nebo null. */
    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
    } catch (e: Exception) {
        null
    }

    /** Velikost souboru v bajtech (SIZE), nebo null když ji nejde zjistit. */
    fun fileSize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) c.getLong(i) else null
                } else null
            }
    } catch (e: Exception) {
        null
    }

    /** MIME typ souboru, nebo null. */
    fun mimeType(context: Context, uri: Uri): String? =
        try { context.contentResolver.getType(uri) } catch (e: Exception) { null }

    /** Nový (prázdný) dočasný soubor v cache pod daným jménem. */
    fun newTempFile(context: Context, fileName: String): File {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        return File(dir, sanitize(fileName))
    }

    /** Smaže staré dočasné soubory (volá se při vstupu na obrazovku). */
    fun clearTemp(context: Context) {
        try {
            File(context.cacheDir, TEMP_DIR).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // ignorovat
        }
    }

    /** Zkopíruje obsah [file] do [destUri] (vybraného přes SAF). Vrátí true při úspěchu. */
    fun copyToUri(context: Context, file: File, destUri: Uri): Boolean = try {
        context.contentResolver.openOutputStream(destUri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } != null
    } catch (e: Exception) {
        false
    }

    /** Nabídne [file] k odeslání přes systémové „Sdílet". Vrátí true, když se dialog otevřel. */
    fun shareFile(context: Context, file: File, mime: String, chooserTitle: String): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
        true
    } catch (e: Exception) {
        false
    }

    private fun sanitize(name: String): String =
        name.map { if (it == '/' || it == '\\' || it.code < 32) '_' else it }.joinToString("")
            .ifBlank { "soubor" }
}
