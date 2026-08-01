package com.jelenxp.cryptochat.chat

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Ukládání médií (fotek) skupinových zpráv na disk. Přijaté bajty se uloží jako
 * soubor a do historie jde jen cesta ([GroupChatMessage.mediaPath]). Komprese fotky
 * PŘED odesláním je věc volajícího (UI), stejně jako u 1:1 `ChatMediaStore` — tady
 * jde jen o bezztrátové uložení už hotových bajtů.
 */
object GroupMediaStore {

    private const val DIR = "group_media"

    /**
     * Uloží bajty fotky pro skupinu a vrátí absolutní cestu, nebo null při selhání.
     * Volající MUSÍ null brát jako selhání zápisu (jinak by se fotka po ACKnutí
     * ztratila — relay blob po vyzvednutí maže).
     */
    fun save(context: Context, groupId: String, bytes: ByteArray): String? {
        return try {
            val safe = groupId.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "unknown" }
            val dir = File(File(context.applicationContext.filesDir, DIR), safe).apply { mkdirs() }
            val name = ByteArray(12).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) } + ".bin"
            val file = File(dir, name)
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** Smaže všechna média skupiny (při jejím opuštění/smazání). */
    fun clear(context: Context, groupId: String) {
        try {
            val safe = groupId.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "unknown" }
            File(File(context.applicationContext.filesDir, DIR), safe).deleteRecursively()
        } catch (_: Throwable) {
        }
    }
}
