package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perzistence skupin ([Group]) — JSON v SharedPreferences, celé pole zašifrované
 * přes [StorageCrypto] (na disku nikdy čitelně). Analogie
 * [com.jelenxp.cryptochat.data.ContactRepository], stejná odolnost proti výjimkám:
 * poškozený záznam se přeskočí, selhání se nikdy nepromítne do pádu.
 *
 * **Selhání čtení NESMÍ přepsat data** ([loadForWriteLocked] vrací null) — jinak by
 * jeden nedostupný Keystore smazal všechny skupiny. Souběh (služba na pozadí × UI)
 * řeší procesový zámek [lock] + paměťová [cache].
 */
class GroupStore(
    context: Context,
    private val crypto: StorageCrypto = KeystoreStorageCrypto,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGroups(): List<Group> = synchronized(lock) { loadLocked() }

    fun getGroup(groupId: String): Group? = synchronized(lock) {
        loadLocked().firstOrNull { it.groupId == groupId }
    }

    /**
     * Vloží nebo nahradí skupinu podle [Group.groupId]. Vrací false, když se
     * historii nepodařilo přečíst (pak se NEZAPISUJE, ať se nic neztratí) nebo
     * když selhal zápis.
     */
    fun upsert(group: Group): Boolean = synchronized(lock) {
        val current = loadForWriteLocked() ?: return false
        val next = current.filter { it.groupId != group.groupId } + group
        saveLocked(next)
    }

    /** Smaže skupinu. Vrací false při selhání čtení/zápisu. */
    fun delete(groupId: String): Boolean = synchronized(lock) {
        val current = loadForWriteLocked() ?: return false
        if (current.none { it.groupId == groupId }) return true
        saveLocked(current.filter { it.groupId != groupId })
    }

    // --- interní ---

    private fun loadLocked(): List<Group> = loadForWriteLocked() ?: emptyList()

    private fun loadForWriteLocked(): List<Group>? {
        cache?.let { return it }
        val loaded = readFromDisk() ?: return null
        cache = loaded
        return loaded
    }

    private fun readFromDisk(): List<Group>? {
        return try {
            val stored = prefs.getString(KEY, null) ?: return emptyList()
            val json = crypto.decrypt(stored) ?: return null
            val array = JSONArray(json)
            val result = ArrayList<Group>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                fromJson(o)?.let { result.add(it) } // poškozený záznam se přeskočí
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLocked(groups: List<Group>): Boolean {
        return try {
            val array = JSONArray()
            for (g in groups) array.put(toJson(g))
            val encrypted = crypto.encrypt(array.toString())
            // commit() (durabilní, ne apply()) — a HLÍDÁME jeho návratovku: může vrátit
            // false BEZ výjimky (plný disk / poškozené prefs). Bez téhle kontroly by se
            // neúspěch tvářil jako úspěch a po restartu by skupina/GS byla nenávratně pryč
            // (v paměti jen cache). Fáze 4 na tomhle staví ACK-až-po-durabilním-zápisu.
            val committed = prefs.edit().putString(KEY, encrypted).commit()
            if (!committed) {
                cache = null
                return false
            }
            cache = groups
            true
        } catch (_: Exception) {
            // Zápis selhal (plný disk / Keystore) — zahoď cache, ať nemaskuje disk.
            cache = null
            false
        }
    }

    private fun toJson(g: Group): JSONObject = JSONObject().apply {
        put("id", g.groupId)
        put("name", g.name)
        put("avatar", g.avatarPath)
        put("epoch", g.groupEpoch)
        put("gs", g.gsBase64)
        put("roster", g.rosterBytesBase64)
        put("rosterSig", g.rosterSigBase64)
        put("adminPub", g.adminPublicKeyBase64)
        put("myId", g.myMemberId)
        put("adminId", g.adminMemberId)
        put("amAdmin", g.amIAdmin)
        put("signPriv", g.mySignPrivateKeyBase64)
        put("signPub", g.mySignPublicKeyBase64)
        put("sealPriv", g.mySealPrivateKeyBase64)
        put("sealPub", g.mySealPublicKeyBase64)
        put("used", JSONArray(g.usedMemberIds.toList()))
    }

    private fun fromJson(o: JSONObject): Group? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val used = HashSet<String>()
        o.optJSONArray("used")?.let { for (i in 0 until it.length()) used.add(it.optString(i)) }
        return Group(
            groupId = id,
            name = o.optString("name"),
            avatarPath = if (o.isNull("avatar")) null else o.optString("avatar"),
            groupEpoch = o.optLong("epoch"),
            gsBase64 = o.optString("gs"),
            rosterBytesBase64 = o.optString("roster"),
            rosterSigBase64 = o.optString("rosterSig"),
            adminPublicKeyBase64 = o.optString("adminPub"),
            myMemberId = o.optString("myId"),
            adminMemberId = o.optString("adminId"),
            amIAdmin = o.optBoolean("amAdmin"),
            mySignPrivateKeyBase64 = o.optString("signPriv"),
            mySignPublicKeyBase64 = o.optString("signPub"),
            mySealPrivateKeyBase64 = o.optString("sealPriv"),
            mySealPublicKeyBase64 = o.optString("sealPub"),
            usedMemberIds = used,
        )
    }

    companion object {
        private const val PREFS_NAME = "crypto_chat_prefs"
        private const val KEY = "groups_json"

        // Stav je společný přes instance (jsou levné, zahoditelné) — jako ChatRepository.
        private val lock = Any()

        @Volatile
        private var cache: List<Group>? = null

        /** Jen pro testy: zahodí paměťovou cache, ať další čtení jde z disku. */
        internal fun clearCacheForTest() {
            synchronized(lock) { cache = null }
        }
    }
}
