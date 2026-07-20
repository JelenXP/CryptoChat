package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Odkladiště blobů, které se nepodařilo otevřít.
 *
 * Relay je dead-drop: GET zprávu vyzvedne a ZÁROVEŇ ji na serveru smaže. Když ji
 * tedy klient nedokáže rozšifrovat a jen ji zahodí, je nenávratně pryč - i když
 * je za tím jen dočasná příčina, třeba že protějšek běží na jiné verzi formátu.
 * Přesně tohle se jednou stalo (změna vnitřní hlavičky bez zvýšení
 * [WireCompat.WIRE_MAJOR]) a zprávy zmizely beze stopy.
 *
 * Takový blob se proto uloží sem a při každém pollu se zkusí otevřít znovu.
 * Jakmile si obě strany sednou (typicky po aktualizaci appky), zprávy se samy
 * doberou a objeví se v konverzaci. Bloby jsou pořád zašifrované - leží tu tedy
 * ve stejné podobě, v jaké přišly ze sítě.
 */
object BlobQuarantine {

    private const val TAG = "BlobQuarantine"
    private const val DIR = "blob_quarantine"

    /** Kolik blobů nejvýš držet na kontakt (nejstarší se zahazují). */
    private const val MAX_PER_CONTACT = 30

    /**
     * Strop v bajtech na kontakt. Bez něj by nedůvěryhodný relay (zná ID schránek,
     * sám je ukládá) nasypal do příchozí schránky odpad a zaplnil telefon -
     * 30 blobů po 2 MB je 60 MB, což je pořád moc.
     */
    private const val MAX_BYTES_PER_CONTACT = 24L * 1024 * 1024

    /** Kolik blobů nejvýš vrátit v jednom kole (aby se do paměti nenačetlo všechno). */
    private const val MAX_RETRY_BATCH = 5

    /** Jak dlouho blob zkoušet, než ho vzdáme. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Uloží blob, který se nepodařilo otevřít. [firstSeenAt] je čas prvního
     * odložení - při opakovaném ukládání téhož blobu se MUSÍ zachovat původní,
     * jinak by se stáří pořád resetovalo a blob by nevypršel nikdy.
     *
     * Jméno souboru má tři pole: `sortKey_firstSeenAt_otisk.bin`.
     *  - `sortKey` = čas TOHOTO uložení. [takeAll] podle něj řadí, takže znovu
     *    odložený blob jde na KONEC fronty - i bloby za prvními pěti se tak
     *    dostanou na řadu (jinak by pětice trvale nezpracovatelných blobů
     *    zablokovala opakování všech ostatních až do jejich vypršení).
     *  - `firstSeenAt` = pro expiraci a ořezávání.
     *  - `otisk` = aby se dva různé bloby nepřepsaly a tentýž se neuložil dvakrát.
     */
    fun save(
        context: Context,
        contactId: String,
        blob: ByteArray,
        firstSeenAt: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            val d = dir(context, contactId).apply { mkdirs() }
            val fp = fingerprint(blob)
            // Duplicitu poznáme podle OTISKU OBSAHU (poslední pole názvu), ne podle
            // celého jména - `sortKey` se totiž při každém uložení mění, takže by
            // se tentýž blob jinak hromadil v kopiích.
            val already = d.listFiles()?.any { it.name.endsWith("_$fp.bin") } == true
            if (already) return true
            val sortKey = System.currentTimeMillis()
            File(d, "${sortKey}_${firstSeenAt}_$fp.bin").writeBytes(blob)
            trim(d)
            true
        } catch (e: Throwable) {
            // Volající MUSÍ poznat neúspěch: kdyby odložení selhalo (plný disk)
            // a on přesto potvrdil příjem, server by blob smazal a zpráva by byla
            // nenávratně pryč - přesně to, čemu má karanténa bránit.
            Log.w(TAG, "Uložení blobu do karantény selhalo (${e.javaClass.simpleName})")
            false
        }
    }

    /** Krátký otisk obsahu pro jméno souboru. */
    private fun fingerprint(blob: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            .take(8).joinToString("") { "%02x".format(it) }

    /**
     * Vybere odložené bloby a z karantény je ODSTRANÍ. Volající je pustí znovu
     * normálním zpracováním; když zase selžou, prostě se uloží zpátky přes
     * [save]. Příliš staré se zahodí.
     */
    fun takeAll(context: Context, contactId: String): List<Pending> {
        val out = ArrayList<Pending>()
        try {
            val files = dir(context, contactId).listFiles() ?: return out
            // Řazení podle jména = podle `sortKey` (prvního pole): nejdřív ty, co
            // od posledního pokusu čekají nejdéle. Round-robin, žádné hladovění.
            for (f in files.sortedBy { it.name }) {
                try {
                    val firstSeen = firstSeenOf(f)
                    if (System.currentTimeMillis() - firstSeen > MAX_AGE_MS) {
                        f.delete()   // příliš staré, už to nemá smysl zkoušet
                        continue
                    }
                    // Po dávkách: načíst všechno najednou by u velkých blobů
                    // znamenalo OutOfMemoryError ve foreground service.
                    if (out.size >= MAX_RETRY_BATCH) break
                    out.add(Pending(f.readBytes(), firstSeen))
                    f.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Načtení odloženého blobu selhalo (${e.javaClass.simpleName})")
                }
            }
            if (out.isNotEmpty()) Log.i(TAG, "Zkouším znovu ${out.size} odložených zpráv")
        } catch (e: Exception) {
            Log.w(TAG, "Průchod karanténou selhal (${e.javaClass.simpleName})")
        }
        return out
    }

    /** Odložený blob i s časem, kdy byl odložen poprvé. */
    data class Pending(val blob: ByteArray, val firstSeenAt: Long)

    /**
     * Čas prvního odložení z názvu souboru (fallback na čas úpravy). Nový formát
     * má tři pole (`sortKey_firstSeenAt_otisk`), starší dvě (`firstSeenAt_otisk`)
     * - podle počtu polí vezmeme to správné.
     */
    private fun firstSeenOf(f: File): Long {
        val parts = f.name.split('_')
        val field = if (parts.size >= 3) parts[1] else parts.getOrNull(0)
        return field?.toLongOrNull() ?: f.lastModified()
    }

    /** Zahodí karanténu kontaktu (při jeho smazání). */
    fun clear(context: Context, contactId: String) {
        try {
            dir(context, contactId).deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Úklid karantény selhal (${e.javaClass.simpleName})")
        }
    }

    /**
     * Ořízne karanténu na počet i na celkovou velikost. Zahazuje podle času
     * PRVNÍHO odložení (ne podle `sortKey`) - tedy nejdřív ty, co tu vězí nejdéle
     * a jsou nejspíš navždy nezpracovatelné; čerstvě přijaté zprávy zůstanou.
     */
    private fun trim(d: File) {
        val files = (d.listFiles() ?: return).sortedBy { firstSeenOf(it) }
        var count = files.size
        var bytes = files.sumOf { it.length() }
        for (f in files) {
            if (count <= MAX_PER_CONTACT && bytes <= MAX_BYTES_PER_CONTACT) break
            val len = f.length()
            if (f.delete()) {
                count--
                bytes -= len
            }
        }
    }

    /** Adresář kontaktu. Jméno je očištěné, ať se z id nedá vyrobit cesta. */
    private fun dir(context: Context, contactId: String): File {
        val safe = contactId.filter { it.isLetterOrDigit() || it == '-' }.take(64)
        return File(File(context.filesDir, DIR), safe.ifEmpty { "unknown" })
    }
}
