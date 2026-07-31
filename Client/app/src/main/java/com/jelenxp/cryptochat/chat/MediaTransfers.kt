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

    /**
     * Tvrdý strop počtu kousků jednoho přenosu. Reálný soubor má nejvýš
     * `ceil(ChatMediaStore.MAX_FILE_BYTES / RelaySync.CHUNK_SIZE)` ≈ 15 kousků
     * (25 MB / 1,8 MB), takže 64 je pohodlná rezerva. Slouží jako strop i BEZ
     * manifestu: bez něj by nepřátelský protějšek (zná `fileId`) mohl
     * předmanifestovými kousky s obřím indexem nafouknout `receivedCount` a
     * vyrobit spoustu souborů. `MediaTransfersTest.strop_pokryvaSkutecnouMez` hlídá,
     * že strop skutečnou mez (`MAX_FILE_BYTES/CHUNK_SIZE`) pokrývá. `internal` kvůli testu.
     */
    internal const val MAX_CHUNKS = 64

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

    /**
     * Hex → bajty. Odolné vůči smetí (lichá délka / ne-hex znak): vrátí PRÁZDNÉ pole
     * místo výjimky (projektový cíl - nepadat). Dnes ho krmí jen autentizovaný 32-znakový
     * fileId ([hex] z 16 bajtů), takže „smetí" větev je pojistka pro budoucí volající,
     * ne dosažitelný stav - proto ji hlídá test, ne runtime cesta.
     */
    fun fromHex(value: String): ByteArray {
        if (value.length % 2 != 0) return ByteArray(0)
        return try {
            ByteArray(value.length / 2) {
                ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte()
            }
        } catch (e: IllegalArgumentException) {
            ByteArray(0)   // digitToInt(16) hodí IAE na ne-hex znaku
        }
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
            Log.e(TAG, "Založení příjmu souboru selhalo (${e.javaClass.simpleName})")
            false
        }
    }

    /** Uloží jeden kousek. Vrací true, když už jsou všechny (lze skládat). */
    /** Výsledek uložení kousku: zapsal se vůbec, a je už soubor kompletní? */
    data class ChunkResult(val written: Boolean, val complete: Boolean)

    /**
     * Uloží jeden kousek a rozliší CHYBU ZÁPISU od „ještě nejsou všechny".
     *
     * Dřív obojí vracelo `false`, takže volající neměl jak poznat, že se kousek
     * vůbec neuložil - potvrdil ho serveru, ten ho smazal a soubor už nešlo nikdy
     * složit (bublina zůstala navždy ve stavu „přijímá se").
     */
    fun storeChunk(context: Context, fileIdHex: String, index: Int, bytes: ByteArray): ChunkResult {
        return try {
            // Adresář zakládáme i BEZ manifestu. Dřív se kousek bez něj zahodil -
            // jenže server ho po vyzvednutí smazal, takže ztracený manifest
            // znamenal tiše a nenávratně ztracený soubor. Radši kousky zaparkuj;
            // až manifest dorazí, jen se k nim doplní metadata.
            val d = dir(context, fileIdHex).apply { mkdirs() }
            val total = meta(context, fileIdHex)?.optInt("chunks", -1) ?: -1
            // Index mimo rozsah (poškozený nebo podvržený kousek) by nafoukl
            // počet přijatých a spustil předčasné „hotovo" - přenos by pak
            // navždy uvázl ve stavu FAILED. Zahodit ho je v pořádku (je vadný),
            // proto `written = true` - není co odkládat.
            //
            // Tvrdý strop [MAX_CHUNKS] platí i BEZ manifestu (`total <= 0`): kdyby
            // kousky dorazily před manifestem, guard `index >= total` by neplatil
            // (total je -1), takže bez tohohle by šlo předmanifestovými kousky
            // s obřím indexem nafouknout receivedCount a vyrobit spoustu souborů.
            if (index < 0 || index >= MAX_CHUNKS) return ChunkResult(written = true, complete = false)
            if (total > 0 && index >= total) return ChunkResult(written = true, complete = false)
            File(d, index.toString()).writeBytes(bytes)
            ChunkResult(
                written = true,
                complete = total > 0 && receivedCount(context, fileIdHex) >= total
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Uložení kousku selhalo (${e.javaClass.simpleName})")
            ChunkResult(written = false, complete = false)
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
        } catch (e: Throwable) {
            Log.w(TAG, "Úklid rozpracovaných přenosů selhal (${e.javaClass.simpleName})")
        }
    }

    /** Kolik kousků už dorazilo. */
    fun receivedCount(context: Context, fileIdHex: String): Int =
        dir(context, fileIdHex).listFiles()?.count { it.name != META } ?: 0

    /**
     * Existuje ještě rozpracovaný přenos (dočasný adresář) tohoto souboru?
     * Používá se k rozpoznání natrvalo zaseklého příjmu podle LOKÁLNÍHO stavu:
     * když [purgeStale] tmp adresář smazal (24 h od posledního doteku), přenos
     * je definitivně mrtvý - bez ohledu na (klidně rozjetý) čas odesílatele.
     */
    fun hasPending(context: Context, fileIdHex: String): Boolean =
        dir(context, fileIdHex).isDirectory

    /** Celkový počet kousků z manifestu (0 když se neví). */
    fun totalChunks(context: Context, fileIdHex: String): Int =
        meta(context, fileIdHex)?.optInt("chunks", 0) ?: 0

    /** Název souboru z manifestu. */
    fun fileName(context: Context, fileIdHex: String): String? =
        meta(context, fileIdHex)?.optString("name")?.takeIf { it.isNotEmpty() }

    /**
     * Výsledek [assemble]. Tři stavy se NESMÍ slévat do `null` - volající se podle
     * nich chová jinak:
     *  - [Done] slož se povedl → zapiš RECEIVED a AŽ POTOM ukliď kousky,
     *  - [Corrupt] obsah je prokazatelně vadný (velikost nesedí manifestu),
     *    opakování nepomůže → FAILED a potvrdit,
     *  - [Retry] přechodná chyba (plný disk, chybějící kousek, nečitelná metadata)
     *    → NEPOTVRZOVAT, kousky nechat ležet a zkusit znovu.
     */
    sealed interface AssembleResult {
        data class Done(val path: String) : AssembleResult
        object Corrupt : AssembleResult
        object Retry : AssembleResult
    }

    /**
     * Složí kousky do výsledného souboru v `files/chat_media/` a vrátí výsledek.
     *
     * **Úklid dočasných kousků TAHLE funkce NEDĚLÁ** (dřív ho dělala hned po
     * složení). Volající je smaže [cleanup] AŽ po úspěšném zápisu stavu do
     * historie - jinak by selhání zápisu smazalo kousky dřív, než je dokončení
     * trvale zaznamenané, a přenos by navždy uvázl ve stavu „přijímá se".
     */
    fun assemble(context: Context, fileIdHex: String): AssembleResult {
        val d = dir(context, fileIdHex)
        val meta = meta(context, fileIdHex) ?: return AssembleResult.Retry
        val total = meta.optInt("chunks", 0)
        if (total <= 0 || receivedCount(context, fileIdHex) < total) return AssembleResult.Retry

        val outDir = File(context.filesDir, OUT_DIR).apply { mkdirs() }
        val safeName = safeFileName(meta.optString("name", "soubor"))
        val out = File(outDir, "${fileIdHex.take(8)}_$safeName")
        try {
            out.outputStream().use { sink ->
                for (i in 0 until total) {
                    val part = File(d, i.toString())
                    if (!part.isFile) {
                        // Kousek zmizel (nečekané) - neúplný výstup zahoď a zkus
                        // znovu, kousky nemaž.
                        out.delete()
                        return AssembleResult.Retry
                    }
                    part.inputStream().use { it.copyTo(sink) }
                }
            }
        } catch (e: Exception) {
            // Přechodná chyba zápisu (typicky plný disk). Neúplný výstup smaž,
            // kousky nech ležet - další pokus složí znovu. NEPOTVRZOVAT.
            Log.e(TAG, "Složení souboru selhalo (${e.javaClass.simpleName})")
            out.delete()
            return AssembleResult.Retry
        }
        // Kontrola velikosti proti manifestu: zkrácený/poškozený kousek by jinak
        // vyrobil soubor špatné délky. Kousky jsou GCM-ověřené, takže neshoda =
        // trvale vadný obsah (opakování nepomůže) → Corrupt.
        val expected = meta.optLong("size", -1L)
        if (expected >= 0 && out.length() != expected) {
            Log.e(TAG, "Složený soubor má jinou velikost, než hlásí manifest")
            out.delete()
            return AssembleResult.Corrupt
        }
        return AssembleResult.Done(out.absolutePath)
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
