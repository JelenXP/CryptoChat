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
     *
     * **`commit()`, NE `apply()`** (jako [ChatRepository.saveLocked]): odesílací
     * cesta posune `sendMsgNo` a hned pak odešle (advance-immediately). `apply()`
     * je asynchronní - kdyby systém zabil proces (u FGS běžné, START_STICKY,
     * OOM-kill) mezi úspěšným odesláním a doflushnutím, `sendMsgNo` by po restartu
     * REGREDOVAL, a protože odeslaná zpráva už je durabilně SENT (commit v
     * historii), `flushOutbox` ji nezopakuje → příští odeslání znovu použije týž
     * `msgNo` → týž pár (AES-GCM klíč, IV) na JINÝ obsah = katastrofa nonce reuse.
     * Ratchet leží ve VLASTNÍM prefs souboru, takže commit historie tenhle zápis
     * neflushne - durabilní musí být sám. Běží na IO vlákně, takže synchronní
     * zápis nikoho neblokuje.
     */
    fun save(contactId: String, state: RatchetState): Boolean = synchronized(lock) {
        val encrypted = try {
            crypto.encrypt(state.toJson().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Uložení ratchet stavu selhalo (${e.javaClass.simpleName})")
            return false
        }
        val ok = try {
            prefs.edit().putString(key(contactId), encrypted).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Durabilní zápis ratchet stavu selhal (${e.javaClass.simpleName})")
            false
        }
        // Cache měň JEN po úspěšném durabilním zápisu - jinak by v paměti zůstal
        // stav, který na disku není, a po restartu by se řetěz rozešel.
        if (!ok) return false
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
        when (val cur = read(contactId)) {   // reentrantní zámek
            is Load.Loaded -> save(contactId, cur.state.withSendFrom(sendState))
            Load.Absent -> save(contactId, sendState)
            // Unreadable: poškozený-ale-živý stav NEPŘEPISOVAT prázdnou půlkou (jinak
            // nevratná desynchronizace - proto vůbec existuje sealed Load).
            Load.Unreadable -> false
        }
    }

    /** Uloží PŘIJÍMACÍ půlku stavu a nechá odesílací. Viz [saveSend]. */
    fun saveRecv(contactId: String, recvState: RatchetState): Boolean = synchronized(lock) {
        when (val cur = read(contactId)) {
            is Load.Loaded -> save(contactId, cur.state.withRecvFrom(recvState))
            Load.Absent -> save(contactId, recvState)
            Load.Unreadable -> false
        }
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

    /**
     * Posune JEN [RatchetState.backfillFloor] (podlaha backfillu, nález v2.0-27
     * reziduum). Sahá pouze na tohle jedno pole - `copy` zachová obě půlky stavu,
     * takže souběžný posun odesílání/příjmu nepřepíše. **Monotónní**: nikdy
     * neregreduje (podlaha se smí jen zvedat, nebo poprvé inicializovat z `-1`).
     * `commit()` jako [save] - musí přežít restart, jinak by se ztratil interval
     * přeskočených epoch. Best-effort (false = zápis selhal, zkusí se příště).
     */
    fun updateBackfillFloor(contactId: String, floor: Int): Boolean = synchronized(lock) {
        val cur = load(contactId) ?: return false
        // Inicializovaná (>=0) a stejně vysoko/výš → no-op (nikdy neregreduj).
        // Neinicializovaná (-1) se nastaví vždy (i na 0).
        if (cur.backfillFloor >= 0 && cur.backfillFloor >= floor) return true
        save(contactId, cur.copy(backfillFloor = floor))
    }

    /**
     * Atomický read-modify-write pod companion zámkem: načte stav, [transform] ho
     * změní, uloží. Pro operace, které se dotýkají OBOU půlek naráz (KEM re-key,
     * handshake stav) - slučovací saveSend/saveRecv by nestačily. Vrací false, když
     * stav nejde načíst/zapsat; `null` z [transform] = neukládat (no-op → true).
     */
    fun updateLocked(contactId: String, transform: (RatchetState) -> RatchetState?): Boolean =
        synchronized(lock) {
            val cur = load(contactId) ?: return false
            val next = transform(cur) ?: return true
            save(contactId, next)
        }

    /** Zapomene stav kontaktu (při jeho smazání). `commit()` jako [save]. */
    fun clear(contactId: String) = synchronized(lock) {
        cache.remove(contactId)
        try {
            prefs.edit().remove(key(contactId)).commit()
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
