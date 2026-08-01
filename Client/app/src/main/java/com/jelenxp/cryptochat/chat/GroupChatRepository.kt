package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lokální historie SKUPINOVÝCH konverzací (thread per `groupId`, klíč
 * `gmsgs_<groupId>`), šifrovaná přes [StorageCrypto]. Samostatná od 1:1
 * [ChatRepository], aby jeho invarianty zůstaly čisté (viz `GROUP_CHAT_PLAN.md` D).
 *
 * Stejné bezpečnostní zásady jako u 1:1:
 *  - **dedup přes `msgId`** ([appendIfAbsent]) — resend/replay se nezobrazí dvakrát,
 *  - **selhání čtení NEpřepíše historii** ([loadForWriteLocked] vrací null → volající
 *    zápis nepotvrdí a relay zprávu podrží),
 *  - [AppendResult] je enum (ne Boolean), aby volající odlišil „už tam byla" od
 *    „nepovedlo se" — obojí by jako Boolean vedlo k tiché ztrátě.
 *
 * Souběh (služba × UI) řeší procesový zámek [lock] + paměťová [cache].
 */
class GroupChatRepository(
    context: Context,
    private val crypto: StorageCrypto = KeystoreStorageCrypto,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class AppendResult { ADDED, DUPLICATE, FAILED }
    enum class MutationResult { UPDATED, NOT_FOUND, FAILED }

    fun getMessages(groupId: String): List<GroupChatMessage> = synchronized(lock) { loadLocked(groupId) }

    /**
     * Přidá zprávu, pokud v historii ještě není zpráva s týmž `msgId`. Vrací
     * [AppendResult.DUPLICATE] když už tam je (resend/replay), [AppendResult.FAILED]
     * když se historii nepodařilo přečíst (pak se NEZAPISUJE).
     */
    fun appendIfAbsent(groupId: String, message: GroupChatMessage): AppendResult = synchronized(lock) {
        val current = loadForWriteLocked(groupId) ?: return AppendResult.FAILED
        if (current.any { it.msgIdHex == message.msgIdHex }) return AppendResult.DUPLICATE
        return if (saveLocked(groupId, current + message)) AppendResult.ADDED else AppendResult.FAILED
    }

    /** Nastaví stav odchozí zprávy (SENDING→SENT→DELIVERED/FAILED). */
    fun setStatus(groupId: String, msgIdHex: String, status: GroupChatMessage.Status): MutationResult =
        synchronized(lock) {
            val current = loadForWriteLocked(groupId) ?: return MutationResult.FAILED
            val idx = current.indexOfFirst { it.msgIdHex == msgIdHex }
            if (idx < 0) return MutationResult.NOT_FOUND
            val next = current.toMutableList().also { it[idx] = it[idx].copy(status = status) }
            return if (saveLocked(groupId, next)) MutationResult.UPDATED else MutationResult.FAILED
        }

    // --- interní ---

    private fun loadLocked(groupId: String): List<GroupChatMessage> = loadForWriteLocked(groupId) ?: emptyList()

    private fun loadForWriteLocked(groupId: String): List<GroupChatMessage>? {
        cache[groupId]?.let { return it }
        val loaded = readFromDisk(groupId) ?: return null
        cache[groupId] = loaded
        return loaded
    }

    private fun readFromDisk(groupId: String): List<GroupChatMessage>? {
        return try {
            val stored = prefs.getString(key(groupId), null) ?: return emptyList()
            val json = crypto.decrypt(stored) ?: return null
            val array = JSONArray(json)
            val result = ArrayList<GroupChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                fromJson(o)?.let { result.add(it) }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLocked(groupId: String, messages: List<GroupChatMessage>): Boolean {
        return try {
            val array = JSONArray()
            for (m in messages) array.put(toJson(m))
            prefs.edit().putString(key(groupId), crypto.encrypt(array.toString())).commit()
            cache[groupId] = messages
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun toJson(m: GroupChatMessage): JSONObject = JSONObject().apply {
        put("mid", m.msgIdHex)
        put("from", m.senderMemberIdHex) // null = odchozí
        put("txt", m.text)
        put("ts", m.timestamp)
        put("st", m.status.name)
        put("kind", m.kind.name)
        put("media", m.mediaPath)
    }

    private fun fromJson(o: JSONObject): GroupChatMessage? {
        val mid = o.optString("mid").takeIf { it.isNotEmpty() } ?: return null
        val status = runCatching { GroupChatMessage.Status.valueOf(o.optString("st")) }
            .getOrDefault(GroupChatMessage.Status.SENT)
        val kind = runCatching { GroupChatMessage.Kind.valueOf(o.optString("kind", "TEXT")) }
            .getOrDefault(GroupChatMessage.Kind.TEXT)
        return GroupChatMessage(
            msgIdHex = mid,
            senderMemberIdHex = if (o.isNull("from")) null else o.optString("from"),
            text = o.optString("txt"),
            timestamp = o.optLong("ts"),
            status = status,
            kind = kind,
            mediaPath = if (o.isNull("media")) null else o.optString("media"),
        )
    }

    companion object {
        private const val PREFS_NAME = "crypto_chat_prefs"
        private fun key(groupId: String) = "gmsgs_$groupId"

        private val lock = Any()
        private val cache = HashMap<String, List<GroupChatMessage>>()

        /** Jen pro testy: zahodí paměťovou cache. */
        internal fun clearCacheForTest() {
            synchronized(lock) { cache.clear() }
        }
    }
}
