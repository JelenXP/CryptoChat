package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import java.security.MessageDigest

/**
 * Ochrana proti opakovanému doručení téže zprávy (replay).
 *
 * Relay je nedůvěryhodný: schránku sice po vyzvednutí maže, ale nic ho k tomu
 * nenutí. Zlomyslný (nebo zkompromitovaný) server může tentýž blob nabídnout
 * znovu - i za týden, i v jiné epoše - a příjemci by se zpráva zobrazila
 * podruhé, jako by ji protějšek opravdu poslal znovu.
 *
 * Řešení: pamatujeme si otisky už zpracovaných blobů a duplicity zahazujeme.
 * Pamatuje se posledních [MAX_REMEMBERED] otisků na kontakt (FIFO) - starší
 * blob už stejně dávno vypršel na serveru, takže hlubší paměť nemá smysl.
 *
 * Pozn.: legitimní opakované odeslání téže zprávy tohle NEblokuje - každé
 * zašifrování používá čerstvý IV, takže blob vyjde pokaždé jiný.
 */
object ReplayGuard {

    private const val PREFS_NAME = "crypto_chat_seen"
    private const val TAG = "ReplayGuard"

    /** Kolik otisků na kontakt si držet. */
    private const val MAX_REMEMBERED = 500

    /** Kolik bajtů otisku ukládat (96 bitů je proti náhodné kolizi bohatě dost). */
    private const val DIGEST_BYTES = 12

    private val lock = Any()

    /**
     * Vrací true, když blob ještě nebyl zpracovaný (a rovnou si ho poznamená).
     * Při jakémkoli problému raději propustí (true) - ztratit zprávu je horší
     * než ji ve výjimečném případě zobrazit dvakrát.
     */
    fun isNew(context: Context, contactId: String, blob: ByteArray): Boolean = synchronized(lock) {
        return try {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "seen_$contactId"
            val digest = fingerprint(blob)
            val stored = prefs.getString(key, "").orEmpty()
            val seen = if (stored.isEmpty()) ArrayList() else ArrayList(stored.split(','))
            if (seen.contains(digest)) return false

            seen.add(digest)
            // Ořízni na posledních MAX_REMEMBERED (nejstarší zahoď).
            while (seen.size > MAX_REMEMBERED) seen.removeAt(0)
            prefs.edit().putString(key, seen.joinToString(",")).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Kontrola duplicity selhala, zprávu propouštím", e)
            true
        }
    }

    /** Zapomene otisky daného kontaktu (při jeho smazání). */
    fun clear(context: Context, contactId: String) = synchronized(lock) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("seen_$contactId").apply()
        } catch (e: Exception) {
            Log.w(TAG, "Úklid otisků selhal", e)
        }
    }

    private fun fingerprint(blob: ByteArray): String {
        val full = MessageDigest.getInstance("SHA-256").digest(blob)
        return full.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
    }
}
