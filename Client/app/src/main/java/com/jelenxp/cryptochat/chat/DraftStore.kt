package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import org.json.JSONObject

/**
 * Rozepsané (neodeslané) zprávy per kontakt - přežijí odchod z konverzace i
 * restart appky. Text zprávy je citlivý, takže se ukládá **šifrovaně at rest**
 * přes [StorageCrypto] (ostrá impl. Keystore, testy si dosadí průhledný
 * [com.jelenxp.cryptochat.chat.FakeStorageCrypto]).
 *
 * Všechny drafty leží v JEDNOM šifrovaném blobu (JSON mapa `contactId -> text`),
 * aby seznam konverzací ([com.jelenxp.cryptochat.ui.screens.MainScreen]) zjistil
 * „rozepsáno" jedním dešifrováním, ne N Keystore operacemi.
 *
 * Odolnost proti pádům (projektový cíl): čtení při chybě vrací prázdnou mapu,
 * zápis chybu spolkne - rozepsaná zpráva je pohodlí, ne data, o která nesmíme
 * přijít za cenu pádu.
 */
class DraftStore(
    context: Context,
    private val crypto: StorageCrypto = KeystoreStorageCrypto
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Všechny drafty (dešifrované). Prázdná mapa při chybě/absenci. */
    fun all(): Map<String, String> = readAll() ?: emptyMap()

    /**
     * Jako [all], ale vrací `null`, když se úložiště nepodařilo DEŠIFROVAT (přechodná
     * nedostupnost Keystore). To se MUSÍ odlišit od „nic uloženo" (prázdná mapa),
     * jinak by [set] při selhání přepsal a zahodil drafty ostatních kontaktů. Poškozený
     * JSON (trvale nečitelný) se naopak bere jako prázdno - přepis je legitimní obnova.
     */
    private fun readAll(): Map<String, String>? = synchronized(lock) {
        val stored = prefs.getString(KEY, null)
        if (stored == null) {
            plaintextCache = emptyMap()   // definitivně žádné drafty → cache je platná
            return emptyMap()
        }
        // Přechodné selhání dešifrování → „nevím" (null), NEcachuj prázdno: synchronní
        // náhled i set se pak zachovají bezpečně (náhled: null, set: nepřepíše).
        val json = crypto.decrypt(stored) ?: return null
        return try {
            val obj = JSONObject(json)
            val out = HashMap<String, String>(obj.length())
            for (k in obj.keys()) obj.optString(k).takeIf { it.isNotEmpty() }?.let { out[k] = it }
            // Cache drží SAMOSTATNOU kopii (`out.toMap()`), NE tutéž instanci, kterou
            // vracíme volajícímu: kdyby si volající vrácenou mapu zmutoval, lock-free
            // čtenář [cachedDraft] by jinak nad sdílenou instancí viděl roztržený stav.
            // Kopii nikdo jiný nedrží, takže se nikdy nemění na místě = bezpečné čtení.
            plaintextCache = out.toMap()
            out
        } catch (e: Exception) {
            Log.w(TAG, "Nečitelné drafty (${e.javaClass.simpleName})")
            plaintextCache = emptyMap()   // trvale poškozeno = prázdno je platný stav
            emptyMap()
        }
    }

    /** Draft daného kontaktu, nebo prázdný řetězec. */
    fun get(contactId: String): String = all()[contactId] ?: ""

    /**
     * Nastaví/aktualizuje draft. Prázdný (nebo jen mezery) text draft SMAŽE - ať
     * po odeslání nebo smazání textu nezůstane viset „rozepsáno". Vrací false, když
     * se zápis nepovedl (best-effort).
     */
    fun set(contactId: String, text: String): Boolean = synchronized(lock) {
        // Když teď nejde bezpečně přečíst (přechodné selhání dešifrování), NEPŘEPISUJ:
        // jinak by se uložil jen tenhle draft a o drafty ostatních kontaktů bys přišel.
        val current = readAll() ?: return false
        val map = HashMap(current)
        if (text.isBlank()) {
            if (map.remove(contactId) == null) return true   // nic k mazání
        } else {
            if (map[contactId] == text) return true           // beze změny
            map[contactId] = text
        }
        return save(map)
    }

    /** Smaže draft kontaktu (po odeslání / smazání konverzace). */
    fun clear(contactId: String): Boolean = set(contactId, "")

    private fun save(map: Map<String, String>): Boolean {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        val encrypted = try {
            crypto.encrypt(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Šifrování draftů selhalo (${e.javaClass.simpleName})")
            return false
        }
        return try {
            prefs.edit().putString(KEY, encrypted).apply()
            plaintextCache = map.toMap()   // drž cache v kroku s diskem (v procesu jsme jediný zapisovatel)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Zápis draftů selhal (${e.javaClass.simpleName})")
            false
        }
    }

    companion object {
        private const val TAG = "DraftStore"
        private const val PREFS_NAME = "crypto_chat_drafts"
        private const val KEY = "drafts"
        private val lock = Any()

        /**
         * Procesová cache dešifrovaných draftů (poslední výsledek [all]/[save]).
         * `null` = ještě se v tomto procesu nenačetly. Aplikace je jediný zapisovatel,
         * takže cache == disk; mění se jen pod [lock], čte se přes volatile referenci
         * na NEMĚNNOU mapu (nikdy se nemutuje na místě), takže je čtení bez zámku bezpečné.
         */
        @Volatile
        private var plaintextCache: Map<String, String>? = null

        /**
         * Synchronní náhled draftu z paměťové cache (BEZ Keystore, volatelné z hlavního
         * vlákna). Umožní ChatScreenu vykreslit vstupní pole rovnou ve správné výšce, aby
         * při otevření konverzace s víceřádkovým draftem nezablikal (pole by jinak narostlo
         * až po async dešifrování). `MainScreen` cache naplní voláním [all], takže při
         * běžném toku (seznam → klepnutí na kontakt) je teplá.
         *
         * @return text draftu; `""` když je cache teplá, ale kontakt draft nemá (jistota
         *   „žádný draft"); `null` když cache ještě není naplněná - volající pak načte async.
         */
        fun cachedDraft(contactId: String): String? = plaintextCache?.let { it[contactId] ?: "" }

        /** Jen pro testy: shodí procesovou cache, ať jde ověřit „studený" stav ([cachedDraft] == null). */
        internal fun clearCacheForTest() { plaintextCache = null }
    }
}
