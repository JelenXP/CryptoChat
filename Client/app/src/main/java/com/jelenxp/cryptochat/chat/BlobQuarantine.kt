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
    private const val MAX_PER_CONTACT = 100

    /** Jak dlouho blob zkoušet, než ho vzdáme. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** Uloží blob, který se nepodařilo otevřít. */
    fun save(context: Context, contactId: String, blob: ByteArray) {
        try {
            val d = dir(context, contactId).apply { mkdirs() }
            File(d, "${System.currentTimeMillis()}_${blob.size}.bin").writeBytes(blob)
            trim(d)
        } catch (e: Exception) {
            Log.w(TAG, "Uložení blobu do karantény selhalo", e)
        }
    }

    /**
     * Vybere odložené bloby a z karantény je ODSTRANÍ. Volající je pustí znovu
     * normálním zpracováním; když zase selžou, prostě se uloží zpátky přes
     * [save]. Příliš staré se zahodí.
     */
    fun takeAll(context: Context, contactId: String): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        try {
            val files = dir(context, contactId).listFiles() ?: return out
            for (f in files.sortedBy { it.name }) {
                try {
                    if (System.currentTimeMillis() - f.lastModified() <= MAX_AGE_MS) {
                        out.add(f.readBytes())
                    }
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

    /** Zahodí karanténu kontaktu (při jeho smazání). */
    fun clear(context: Context, contactId: String) {
        try {
            dir(context, contactId).deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Úklid karantény selhal", e)
        }
    }

    private fun trim(d: File) {
        val files = d.listFiles() ?: return
        if (files.size <= MAX_PER_CONTACT) return
        files.sortedBy { it.name }
            .take(files.size - MAX_PER_CONTACT)
            .forEach { it.delete() }
    }

    /** Adresář kontaktu. Jméno je očištěné, ať se z id nedá vyrobit cesta. */
    private fun dir(context: Context, contactId: String): File {
        val safe = contactId.filter { it.isLetterOrDigit() || it == '-' }.take(64)
        return File(File(context.filesDir, DIR), safe.ifEmpty { "unknown" })
    }
}
