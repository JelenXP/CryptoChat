package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.io.File

/**
 * Přenos větších souborů (video, dokumenty) po kouscích. Relay bere blob do 2 MB,
 * takže se soubor rozdělí na kousky, pošle a na druhé straně zase složí.
 *
 * Přijaté kousky se ukládají **na disk** (`files/media_tmp/<fileId>/`), takže
 * rozdělaný přenos přežije i restart aplikace - kousky už vyzvednuté ze serveru
 * se tím pádem neztratí (server je po vyzvednutí maže).
 *
 * [progress] je pozorovatelný Compose stav (0..1) klíčovaný **id zprávy**, které
 * je u souborů shodné s hex podobou `fileId`.
 */
object MediaTransfers {

    private const val TAG = "MediaTransfers"
    private const val TMP_DIR = "media_tmp"
    private const val OUT_DIR = "chat_media"
    private const val META = "meta.json"

    private val _progress = mutableStateMapOf<String, Float>()
    val progress: Map<String, Float> get() = _progress

    fun setProgress(id: String, value: Float) {
        _progress[id] = value.coerceIn(0f, 1f)
    }

    fun clearProgress(id: String) {
        _progress.remove(id)
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun fromHex(value: String): ByteArray =
        ByteArray(value.length / 2) {
            ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte()
        }

    private fun dir(context: Context, fileIdHex: String) =
        File(File(context.filesDir, TMP_DIR), fileIdHex)

    /** Založí příjem souboru (uloží metadata). Vrací true při úspěchu. */
    fun startReceive(
        context: Context,
        fileIdHex: String,
        totalChunks: Int,
        totalSize: Long,
        mimeType: String,
        fileName: String
    ): Boolean {
        return try {
            val d = dir(context, fileIdHex).apply { mkdirs() }
            val meta = JSONObject()
                .put("chunks", totalChunks)
                .put("size", totalSize)
                .put("mime", mimeType)
                .put("name", fileName)
            File(d, META).writeText(meta.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Založení příjmu souboru selhalo", e)
            false
        }
    }

    /** Uloží jeden kousek. Vrací true, když už jsou všechny (lze skládat). */
    fun saveChunk(context: Context, fileIdHex: String, index: Int, bytes: ByteArray): Boolean {
        return try {
            // Adresář zakládáme i BEZ manifestu. Dřív se kousek bez něj zahodil -
            // jenže server ho po vyzvednutí smazal, takže ztracený manifest
            // znamenal tiše a nenávratně ztracený soubor. Radši kousky zaparkuj;
            // až manifest dorazí, jen se k nim doplní metadata.
            val d = dir(context, fileIdHex).apply { mkdirs() }
            val total = meta(context, fileIdHex)?.optInt("chunks", -1) ?: -1
            // Index mimo rozsah (poškozený nebo podvržený kousek) by nafoukl
            // počet přijatých a spustil předčasné „hotovo" - přenos by pak
            // navždy uvázl ve stavu FAILED.
            if (total > 0 && index >= total) return false
            File(d, index.toString()).writeBytes(bytes)
            total > 0 && receivedCount(context, fileIdHex) >= total
        } catch (e: Exception) {
            Log.e(TAG, "Uložení kousku selhalo", e)
            false
        }
    }

    /**
     * Uklidí rozpracované přenosy starší než [maxAgeMs]. Přerušený přenos by
     * jinak nechal v `files/media_tmp/` desítky MB navždy - to je hlavní důvod,
     * proč appka bez jediného kontaktu umí nabobtnat na stovky MB.
     */
    fun purgeStale(context: Context, maxAgeMs: Long) {
        try {
            val root = File(context.filesDir, TMP_DIR)
            val cutoff = System.currentTimeMillis() - maxAgeMs
            root.listFiles()?.forEach { d ->
                if (d.isDirectory && d.lastModified() < cutoff) d.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Úklid rozpracovaných přenosů selhal", e)
        }
    }

    /** Kolik kousků už dorazilo. */
    fun receivedCount(context: Context, fileIdHex: String): Int =
        dir(context, fileIdHex).listFiles()?.count { it.name != META } ?: 0

    /** Celkový počet kousků z manifestu (0 když se neví). */
    fun totalChunks(context: Context, fileIdHex: String): Int =
        meta(context, fileIdHex)?.optInt("chunks", 0) ?: 0

    /** Název souboru z manifestu. */
    fun fileName(context: Context, fileIdHex: String): String? =
        meta(context, fileIdHex)?.optString("name")?.takeIf { it.isNotEmpty() }

    /**
     * Složí kousky do výsledného souboru v `files/chat_media/`, uklidí dočasné
     * a vrátí cestu (nebo null při chybě / když ještě nejsou všechny).
     */
    fun assemble(context: Context, fileIdHex: String): String? {
        return try {
            val d = dir(context, fileIdHex)
            val meta = meta(context, fileIdHex) ?: return null
            val total = meta.optInt("chunks", 0)
            if (total <= 0 || receivedCount(context, fileIdHex) < total) return null

            val outDir = File(context.filesDir, OUT_DIR).apply { mkdirs() }
            val safeName = safeFileName(meta.optString("name", "soubor"))
            val out = File(outDir, "${fileIdHex.take(8)}_$safeName")
            out.outputStream().use { sink ->
                for (i in 0 until total) {
                    val part = File(d, i.toString())
                    if (!part.isFile) return null
                    part.inputStream().use { it.copyTo(sink) }
                }
            }
            cleanup(context, fileIdHex)
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Složení souboru selhalo", e)
            null
        }
    }

    /** Smaže dočasné kousky daného přenosu. */
    fun cleanup(context: Context, fileIdHex: String) {
        try {
            dir(context, fileIdHex).deleteRecursively()
        } catch (e: Exception) {
            // Úklid není kritický.
        }
    }

    private fun meta(context: Context, fileIdHex: String): JSONObject? {
        return try {
            val f = File(dir(context, fileIdHex), META)
            if (!f.isFile) null else JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return cleaned.takeIf { it.isNotBlank() }?.take(60) ?: "soubor"
    }
}
