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
        // Dedup klíč = (odesílatel, msgId), a JEN mezi PŘÍCHOZÍMI. `msgId` volí
        // odesílatel, takže dedup jen podle msgId by dovolil insiderovi kolizí
        // potlačit cizí zprávu (nález fáze 3). Různí odesílatelé se stejným msgId =
        // dvě různé zprávy. Odchozí (null odesílatel) se nededuplikují.
        if (current.any { !it.outgoing && it.senderMemberIdHex == message.senderMemberIdHex && it.msgIdHex == message.msgIdHex }) {
            return AppendResult.DUPLICATE
        }
        return if (saveLocked(groupId, current + message)) AppendResult.ADDED else AppendResult.FAILED
    }

    /**
     * Nastaví stav ODCHOZÍ zprávy (SENDING→SENT→DELIVERED/FAILED). Míří jen na
     * odchozí (moje) zprávy — příchozí se stejným `msgId` od jiného odesílatele se
     * nesmí splést. **DELIVERED se nedegraduje** (terminální stav, viz upgrade-only
     * logika 1:1 `ChatRepository`).
     */
    fun setStatus(groupId: String, msgIdHex: String, status: GroupChatMessage.Status): MutationResult =
        synchronized(lock) {
            val current = loadForWriteLocked(groupId) ?: return MutationResult.FAILED
            val idx = current.indexOfFirst { it.outgoing && it.msgIdHex == msgIdHex }
            if (idx < 0) return MutationResult.NOT_FOUND
            val cur = current[idx]
            if (cur.status == GroupChatMessage.Status.DELIVERED && status != GroupChatMessage.Status.DELIVERED) {
                return MutationResult.UPDATED // pozdější retry/souběh doručenek nesmí shodit DELIVERED
            }
            if (cur.status == status) return MutationResult.UPDATED
            val next = current.toMutableList().also { it[idx] = cur.copy(status = status) }
            return if (saveLocked(groupId, next)) MutationResult.UPDATED else MutationResult.FAILED
        }

    /**
     * Zaznamená doručenku: příjemce [recipientMemberIdHex] potvrdil moji odchozí
     * zprávu [msgIdHex]. Ubyde z `pendingRecipients`; když je pak prázdné (doručeno
     * všem), přepne na [GroupChatMessage.Status.DELIVERED]. Idempotentní (duplicitní
     * doručenka nic nezmění). Míří jen na ODCHOZÍ zprávy.
     */
    fun markDeliveredBy(groupId: String, msgIdHex: String, recipientMemberIdHex: String): MutationResult =
        synchronized(lock) {
            val current = loadForWriteLocked(groupId) ?: return MutationResult.FAILED
            val idx = current.indexOfFirst { it.outgoing && it.msgIdHex == msgIdHex }
            if (idx < 0) return MutationResult.NOT_FOUND
            val cur = current[idx]
            if (recipientMemberIdHex !in cur.pendingRecipients) return MutationResult.UPDATED // idempotent
            val newPending = cur.pendingRecipients - recipientMemberIdHex
            val newStatus = if (newPending.isEmpty()) GroupChatMessage.Status.DELIVERED else cur.status
            val next = current.toMutableList().also { it[idx] = cur.copy(pendingRecipients = newPending, status = newStatus) }
            return if (saveLocked(groupId, next)) MutationResult.UPDATED else MutationResult.FAILED
        }

    /**
     * Reakce [reactorMemberIdHex] emoji [emoji] na zprávu [targetMsgIdHex] (prázdné
     * emoji = ZRUŠENÍ). Míří na JAKOUKOLI zprávu podle msgId (moji i cizí). Idempotentní
     * (stejný stav → UPDATED bez zápisu). NOT_FOUND když cíl v historii není.
     */
    fun applyReaction(groupId: String, targetMsgIdHex: String, reactorMemberIdHex: String, emoji: String): MutationResult =
        synchronized(lock) {
            val current = loadForWriteLocked(groupId) ?: return MutationResult.FAILED
            val idx = current.indexOfFirst { it.msgIdHex == targetMsgIdHex }
            if (idx < 0) return MutationResult.NOT_FOUND
            val cur = current[idx]
            val next = if (emoji.isEmpty()) cur.reactions - reactorMemberIdHex else cur.reactions + (reactorMemberIdHex to emoji)
            if (next == cur.reactions) return MutationResult.UPDATED // idempotent
            val list = current.toMutableList().also { it[idx] = cur.copy(reactions = next) }
            return if (saveLocked(groupId, list)) MutationResult.UPDATED else MutationResult.FAILED
        }

    /** Smaže celou historii skupiny (po opuštění/smazání skupiny). Best-effort. */
    fun clear(groupId: String) = synchronized(lock) {
        try {
            prefs.edit().remove(key(groupId)).commit()
        } catch (_: Exception) {
        } finally {
            cache.remove(groupId)
        }
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
            // commit() a HLÍDÁME návratovku — false BEZ výjimky (plný disk) by jinak
            // vypadal jako úspěch → příchozí zpráva se ACKne relayi, ten ji smaže, a po
            // restartu je pryč (jen paměťová cache). Fáze 4 na tomhle staví.
            val committed = prefs.edit().putString(key(groupId), crypto.encrypt(array.toString())).commit()
            if (!committed) {
                cache.remove(groupId)
                return false
            }
            cache[groupId] = messages
            true
        } catch (_: Exception) {
            cache.remove(groupId)
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
        put("pending", JSONArray(m.pendingRecipients.toList()))
        if (m.reactions.isNotEmpty()) {
            put("react", JSONObject().also { r -> m.reactions.forEach { (k, v) -> r.put(k, v) } })
        }
    }

    private fun fromJson(o: JSONObject): GroupChatMessage? {
        val mid = o.optString("mid").takeIf { it.isNotEmpty() } ?: return null
        val status = runCatching { GroupChatMessage.Status.valueOf(o.optString("st")) }
            .getOrDefault(GroupChatMessage.Status.SENT)
        val kind = runCatching { GroupChatMessage.Kind.valueOf(o.optString("kind", "TEXT")) }
            .getOrDefault(GroupChatMessage.Kind.TEXT)
        val pending = HashSet<String>()
        o.optJSONArray("pending")?.let { for (i in 0 until it.length()) pending.add(it.optString(i)) }
        val reactions = HashMap<String, String>()
        o.optJSONObject("react")?.let { r -> val it2 = r.keys(); while (it2.hasNext()) { val k = it2.next(); reactions[k] = r.optString(k) } }
        return GroupChatMessage(
            msgIdHex = mid,
            senderMemberIdHex = if (o.isNull("from")) null else o.optString("from"),
            text = o.optString("txt"),
            timestamp = o.optLong("ts"),
            status = status,
            kind = kind,
            mediaPath = if (o.isNull("media")) null else o.optString("media"),
            pendingRecipients = pending,
            reactions = reactions,
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
