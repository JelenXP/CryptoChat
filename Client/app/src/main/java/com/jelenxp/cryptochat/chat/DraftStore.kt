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
    fun all(): Map<String, String> = synchronized(lock) {
        val stored = prefs.getString(KEY, null) ?: return emptyMap()
        val json = crypto.decrypt(stored) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = HashMap<String, String>(obj.length())
            for (k in obj.keys()) obj.optString(k).takeIf { it.isNotEmpty() }?.let { out[k] = it }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Nečitelné drafty (${e.javaClass.simpleName})")
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
        val map = HashMap(all())
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
    }
}
