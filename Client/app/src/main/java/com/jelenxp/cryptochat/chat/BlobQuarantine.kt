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
    private const val MAX_BYTES_PER_CONTACT = 4L * 1024 * 1024

    /** Kolik blobů nejvýš vrátit v jednom kole (aby se do paměti nenačetlo všechno). */
    private const val MAX_RETRY_BATCH = 5

    /** Jak dlouho blob zkoušet, než ho vzdáme. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Uloží blob, který se nepodařilo otevřít. [firstSeenAt] je čas prvního
     * odložení - při opakovaném ukládání téhož blobu se MUSÍ zachovat původní,
     * jinak by se stáří pořád resetovalo a blob by nevypršel nikdy.
     */
    fun save(
        context: Context,
        contactId: String,
        blob: ByteArray,
        firstSeenAt: Long = System.currentTimeMillis()
    ) {
        try {
            val d = dir(context, contactId).apply { mkdirs() }
            // Jméno nese čas prvního odložení a otisk obsahu. Otisk zabrání tomu,
            // aby se dva různé bloby přijaté ve stejné milisekundě přepsaly, a
            // zároveň zajistí, že se tentýž blob neuloží dvakrát.
            val file = File(d, "${firstSeenAt}_${fingerprint(blob)}.bin")
            if (file.exists()) return
            file.writeBytes(blob)
            trim(d)
        } catch (e: Exception) {
            Log.w(TAG, "Uložení blobu do karantény selhalo", e)
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
                    Log.w(TAG, "Načtení odloženého blobu selhalo", e)
                }
            }
            if (out.isNotEmpty()) Log.i(TAG, "Zkouším znovu ${out.size} odložených zpráv")
        } catch (e: Exception) {
            Log.w(TAG, "Průchod karanténou selhal", e)
        }
        return out
    }

    /** Odložený blob i s časem, kdy byl odložen poprvé. */
    data class Pending(val blob: ByteArray, val firstSeenAt: Long)

    /** Čas prvního odložení z názvu souboru (fallback na čas úpravy). */
    private fun firstSeenOf(f: File): Long =
        f.name.substringBefore('_').toLongOrNull() ?: f.lastModified()

    /** Zahodí karanténu kontaktu (při jeho smazání). */
    fun clear(context: Context, contactId: String) {
        try {
            dir(context, contactId).deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Úklid karantény selhal", e)
        }
    }

    /** Ořízne karanténu na počet i na celkovou velikost (nejstarší jde první). */
    private fun trim(d: File) {
        val files = (d.listFiles() ?: return).sortedBy { it.name }
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
