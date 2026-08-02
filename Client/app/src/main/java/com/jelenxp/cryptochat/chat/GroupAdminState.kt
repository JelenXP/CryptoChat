package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import org.json.JSONObject

/**
 * ADMINOVA lokální mapa mezi skupinovými členy a 1:1 kontakty (jen na zařízení
 * admina, TAJNÉ → šifrováno [StorageCrypto]). Roster zná jen `memberId`, ale
 * doručení řídicích balíků ([GroupBundle]) jde přes párový 1:1 kanál, takže admin
 * potřebuje vědět, KTERÝ kontakt = který člen. Drží dvě mapy per skupina:
 *
 *  - **pending** (`contactId → přidělený memberId`): pozvaní, kteří ještě neposlali
 *    své pubkeys. Slouží ke třem věcem najednou: (1) AUTORIZACE příchozích PUBKEYS
 *    (přijmu je jen od pozvaného kontaktu — groupId je sice tajné, ale tohle je
 *    obrana do hloubky), (2) REZERVACE memberId (přidělí se už při pozvánce, ne až
 *    při joinu), (3) IDEMPOTENCE joinu — druhé doručení týchž PUBKEYS použije týž
 *    memberId, takže se nepřidá duplicitní člen (viz [GroupActions.onPubkeysReceived]).
 *  - **members** (`memberId → contactId`): členové, kteří se už připojili. Routing
 *    balíků při add/remove + zpětné dohledání „je tenhle kontakt už člen?".
 *
 * Neadminovo zařízení tenhle store nepoužívá (mapu nezná a nepotřebuje).
 */
object GroupAdminState {
    private const val PREFS = "crypto_chat_prefs"
    private val lock = Any()
    private fun key(groupId: String) = "gadm_$groupId"

    /** Snímek stavu jedné skupiny. Prázdný, když ještě nic není. */
    data class State(
        val pending: Map<String, String>, // contactId → memberId
        val members: Map<String, String>, // memberId → contactId
    )

    private fun load(context: Context, groupId: String, crypto: StorageCrypto): State {
        val enc = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(groupId), null) ?: return State(emptyMap(), emptyMap())
        return try {
            val o = JSONObject(crypto.decrypt(enc) ?: return State(emptyMap(), emptyMap()))
            State(toMap(o.optJSONObject("pending")), toMap(o.optJSONObject("members")))
        } catch (_: Exception) {
            State(emptyMap(), emptyMap())
        }
    }

    private fun save(context: Context, groupId: String, state: State, crypto: StorageCrypto): Boolean = try {
        val o = JSONObject().put("pending", fromMap(state.pending)).put("members", fromMap(state.members))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(groupId), crypto.encrypt(o.toString())).commit()
    } catch (_: Exception) {
        false
    }

    private fun fromMap(m: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    private fun toMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = LinkedHashMap<String, String>()
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); m[k] = o.optString(k) }
        return m
    }

    /**
     * Přidělí (nebo znovu vrátí) memberId pro pozvaný [contactId]. Když kontakt už
     * pending JE, vrátí týž memberId (opětovná pozvánka = re-send, ne nový člen).
     * Jinak vygeneruje čerstvý (mimo [usedIds] i všechny už rozdané v této skupině)
     * a uloží ho do pending. Vrací memberId, nebo null při selhání zápisu.
     */
    fun reserveInvite(context: Context, groupId: String, contactId: String, usedIds: Set<String>, crypto: StorageCrypto = KeystoreStorageCrypto): String? = synchronized(lock) {
        val state = load(context, groupId, crypto)
        state.pending[contactId]?.let { return it } // už pozván → týž id (idempotence pozvánky)
        val taken = usedIds + state.pending.values + state.members.keys
        var id = GroupCrypto.randomMemberId()
        while (id in taken) id = GroupCrypto.randomMemberId()
        val next = state.copy(pending = state.pending + (contactId to id))
        if (!save(context, groupId, next, crypto)) return null
        id
    }

    /** memberId přidělený pozvanému kontaktu (null = nepozván). Autorizace PUBKEYS. */
    fun pendingMemberId(context: Context, groupId: String, contactId: String, crypto: StorageCrypto = KeystoreStorageCrypto): String? = synchronized(lock) {
        load(context, groupId, crypto).pending[contactId]
    }

    /** memberId kontaktu, který se UŽ připojil (null = ještě není člen). */
    fun memberIdForContact(context: Context, groupId: String, contactId: String, crypto: StorageCrypto = KeystoreStorageCrypto): String? = synchronized(lock) {
        val members = load(context, groupId, crypto).members
        members.entries.firstOrNull { it.value == contactId }?.key
    }

    /** contactId člena (pro routing balíku). null = admin sám / neznámý člen. */
    fun contactForMember(context: Context, groupId: String, memberIdHex: String, crypto: StorageCrypto = KeystoreStorageCrypto): String? = synchronized(lock) {
        load(context, groupId, crypto).members[memberIdHex]
    }

    /**
     * Přesune kontakt z pending do members (join dokončen). Idempotentní: druhé
     * volání jen potvrdí stejný stav. Vrací false při selhání zápisu.
     */
    fun promote(context: Context, groupId: String, contactId: String, memberIdHex: String, crypto: StorageCrypto = KeystoreStorageCrypto): Boolean = synchronized(lock) {
        val state = load(context, groupId, crypto)
        val next = state.copy(
            pending = state.pending - contactId,
            members = state.members + (memberIdHex to contactId),
        )
        save(context, groupId, next, crypto)
    }

    /** Odebere člena z mapy (po [GroupAdmin.removeMember]). Vrací false při selhání zápisu. */
    fun unbindMember(context: Context, groupId: String, memberIdHex: String, crypto: StorageCrypto = KeystoreStorageCrypto): Boolean = synchronized(lock) {
        val state = load(context, groupId, crypto)
        if (memberIdHex !in state.members) return true
        save(context, groupId, state.copy(members = state.members - memberIdHex), crypto)
    }

    /** Smaže celý admin stav skupiny (po smazání skupiny). */
    fun clear(context: Context, groupId: String) = synchronized(lock) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(key(groupId)).commit()
        } catch (_: Exception) {}
    }
}
