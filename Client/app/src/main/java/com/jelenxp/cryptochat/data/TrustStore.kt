package com.jelenxp.cryptochat.data

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import org.json.JSONObject

/**
 * Trvalé „ověřeno" pro kontakty (trust pinning). Po ověření otisku sdíleného
 * klíče ([com.jelenxp.cryptochat.crypto.CryptoManager.fingerprint]) se jeho
 * hodnota uloží; když se pak otisk změní (podvržený klíč, obnova klíče, obnova
 * ze zálohy), appka to pozná a upozorní znovu ověřit.
 *
 * **Pinuje se otisk STATICKÉHO klíče M** (`Contact.keyBase64`), NE ratchetové
 * bezpečnostní číslo - to se re-keyem (PCS) legitimně mění, takže by hlásilo
 * planý poplach po každé rotaci. Otisk M se změní jen při skutečné výměně klíče.
 *
 * Ukládá se šifrovaně at rest přes [StorageCrypto] (jeden blob, mapa
 * `contactId -> otisk`, aby seznam zjistil stav jedním dešifrováním). Odolnost
 * proti pádům: chyba čtení = prázdno (bereme jako „neověřeno"), chyba zápisu se
 * spolkne (ověření se prostě neuloží).
 */
class TrustStore(
    context: Context,
    private val crypto: StorageCrypto = KeystoreStorageCrypto
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Mapa `contactId -> ověřený otisk`. Prázdná při chybě/absenci. */
    fun all(): Map<String, String> = synchronized(lock) {
        val stored = prefs.getString(KEY, null) ?: return emptyMap()
        val json = crypto.decrypt(stored) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = HashMap<String, String>(obj.length())
            for (k in obj.keys()) obj.optString(k).takeIf { it.isNotEmpty() }?.let { out[k] = it }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Nečitelný trust blob (${e.javaClass.simpleName})")
            emptyMap()
        }
    }

    /** Naposledy ověřený otisk kontaktu, nebo `null` (neověřeno). */
    fun verifiedFingerprint(contactId: String): String? = all()[contactId]

    /** Zapamatuje otisk jako ověřený (uživatel potvrdil shodu). Vrací úspěch. */
    fun setVerified(contactId: String, fingerprint: String): Boolean = synchronized(lock) {
        val map = HashMap(all())
        map[contactId] = fingerprint
        save(map)
    }

    /** Zapomene ověření kontaktu (mazání kontaktu / obnova klíče). */
    fun clear(contactId: String): Boolean = synchronized(lock) {
        val map = HashMap(all())
        if (map.remove(contactId) == null) return true
        save(map)
    }

    private fun save(map: Map<String, String>): Boolean {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        val encrypted = try {
            crypto.encrypt(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Šifrování trust blobu selhalo (${e.javaClass.simpleName})")
            return false
        }
        return try {
            prefs.edit().putString(KEY, encrypted).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Zápis trust blobu selhal (${e.javaClass.simpleName})")
            false
        }
    }

    companion object {
        private const val TAG = "TrustStore"
        private const val PREFS_NAME = "crypto_chat_trust"
        private const val KEY = "trust"
        private val lock = Any()
    }
}

/**
 * Čistá logika stavu důvěry (mimo store i UI, ať jde otestovat) - projektové
 * pravidlo 2. Porovnává naposledy ověřený otisk s aktuálním.
 */
object TrustState {
    enum class Level {
        /** Nikdy neověřeno. */
        UNVERIFIED,

        /** Ověřeno a otisk pořád sedí. */
        VERIFIED,

        /** Ověřeno DŘÍV, ale otisk se od té doby ZMĚNIL - podezřelé, znovu ověřit. */
        CHANGED
    }

    fun evaluate(storedFingerprint: String?, currentFingerprint: String): Level = when {
        storedFingerprint == null -> Level.UNVERIFIED
        storedFingerprint == currentFingerprint -> Level.VERIFIED
        else -> Level.CHANGED
    }
}
