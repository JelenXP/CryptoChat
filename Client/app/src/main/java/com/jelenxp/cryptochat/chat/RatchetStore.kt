package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import org.json.JSONObject

/**
 * Perzistence [RatchetState] per kontakt - šifrovaně **at rest** přes
 * [StorageCrypto] (výchozí Keystore, testy si dosadí průhledný [FakeStorageCrypto]).
 *
 * Postaveno stejně jako [ChatRepository]: stav je společný přes companion object
 * (paměťová cache + procesový zámek), protože ratchet mění zároveň UI i
 * background service a každá úprava je read-modify-write. Instance jsou levné.
 *
 * ## Proč sealed [Load] místo nullable
 *
 * Načtení MUSÍ rozlišit tři věci (jako `ChatRepository.loadForWrite` rozlišuje
 * prázdné od `null`):
 *  - [Load.Absent]      - žádný stav (kontakt ještě není na ratchetu) → legitimní,
 *  - [Load.Unreadable]  - stav je uložený, ale nejde dešifrovat/rozparsovat →
 *                         **nesmí se přepsat** (jinak nevratná desynchronizace),
 *  - [Load.Loaded]      - platný stav.
 *
 * Kdyby se „nečitelné" slévalo s „absent", zápis by přepsal poškozený-ale-živý
 * stav prázdným a ratchet by se rozešel navždy. Přesně proto to není `Boolean`
 * ani nullable (pravidlo 5: víc než dva stavy → enum/sealed).
 *
 * ## Invariant pro volající (vynucuje se ve Fázi 3, roura)
 *
 * Relay blob se po ACK MAŽE, takže: **[save] musí uspět DŘÍV, než volající pošle
 * ACK.** [save] proto vrací `Boolean` - `false` = zápis selhal → ACK se nesmí
 * poslat, blob dorazí znovu.
 */
class RatchetStore(
    context: Context,
    private val crypto: StorageCrypto = KeystoreStorageCrypto
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Výsledek načtení stavu. */
    sealed class Load {
        data class Loaded(val state: RatchetState) : Load()
        object Absent : Load()
        object Unreadable : Load()
    }

    /** Načte stav kontaktu (z cache, jinak z disku). Viz [Load]. */
    fun read(contactId: String): Load = synchronized(lock) {
        cache[contactId]?.let { return Load.Loaded(it) }
        val stored = prefs.getString(key(contactId), null) ?: return Load.Absent
        // Nečitelné (null) NEcachujeme - jinak by si appka zapamatovala špatný
        // stav a zápis by ten skutečný na disku přepsal.
        val json = crypto.decrypt(stored) ?: return Load.Unreadable
        val state = try {
            RatchetState.fromJson(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se rozparsovat ratchet stav (${e.javaClass.simpleName})")
            null
        } ?: return Load.Unreadable
        cache[contactId] = state
        return Load.Loaded(state)
    }

    /** Pohodlný přístup: platný stav, nebo `null` (absent i nečitelné). */
    fun load(contactId: String): RatchetState? =
        (read(contactId) as? Load.Loaded)?.state

    /**
     * Uloží stav. Vrací `false`, když šifrování/zápis selhal - viz invariant
     * „save před ACK" v docstringu třídy. Při selhání se cache ani disk nemění.
     */
    fun save(contactId: String, state: RatchetState): Boolean = synchronized(lock) {
        val encrypted = try {
            crypto.encrypt(state.toJson().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Uložení ratchet stavu selhalo (${e.javaClass.simpleName})")
            return false
        }
        prefs.edit().putString(key(contactId), encrypted).apply()
        cache[contactId] = state
        return true
    }

    /**
     * Uloží ODESÍLACÍ půlku stavu a NECHÁ přijímací, jak je právě na disku.
     * Atomicky (read-merge-write pod companion zámkem), aby souběžný posun příjmu
     * nepřepsal odeslání a naopak (viz [RatchetState.withSendFrom]). Vrací false =
     * zápis selhal.
     */
    fun saveSend(contactId: String, sendState: RatchetState): Boolean = synchronized(lock) {
        val cur = load(contactId)   // reentrantní zámek
        save(contactId, if (cur != null) cur.withSendFrom(sendState) else sendState)
    }

    /** Uloží PŘIJÍMACÍ půlku stavu a nechá odesílací. Viz [saveSend]. */
    fun saveRecv(contactId: String, recvState: RatchetState): Boolean = synchronized(lock) {
        val cur = load(contactId)
        save(contactId, if (cur != null) cur.withRecvFrom(recvState) else recvState)
    }

    /**
     * Posune JEN `pointerMarker` (poslední epocha zapsaná do beaconu). Sahá pouze
     * na tohle jedno pole - NESMÍ přepsat `sendMsgNo` (to by mohlo vrátit odesílací
     * řetěz a zopakovat GCM klíč). Monotónní: nikdy neregreduje. Best-effort.
     */
    fun updatePointerMarker(contactId: String, marker: Long): Boolean = synchronized(lock) {
        val cur = load(contactId) ?: return false
        if (cur.pointerMarker >= marker) return true
        save(contactId, cur.copy(pointerMarker = marker))
    }

    /** Zapomene stav kontaktu (při jeho smazání). */
    fun clear(contactId: String) = synchronized(lock) {
        cache.remove(contactId)
        try {
            prefs.edit().remove(key(contactId)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Úklid ratchet stavu selhal", e)
        }
    }

    private fun key(contactId: String) = "ratchet_$contactId"

    companion object {
        private const val TAG = "RatchetStore"
        private const val PREFS_NAME = "crypto_chat_ratchet"

        private val lock = Any()
        private val cache = HashMap<String, RatchetState>()

        /** Testy: vynuluj sdílenou cache mezi běhy (stav je v companion object). */
        fun resetCacheForTests() = synchronized(lock) { cache.clear() }
    }
}
