package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.crypto.KeystoreCryptoHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lokální historie zpráv jednotlivých konverzací. Ukládá se jako JSON do
 * SharedPreferences, celé pole je před uložením zašifrované klíčem z Android
 * Keystore ([KeystoreCryptoHelper]) - na disku tedy nikdy neleží čitelné.
 *
 * Stejně jako [com.jelenxp.cryptochat.data.ContactRepository] je odolné proti
 * výjimkám: poškozená data vrátí prázdný seznam, zápis vrací Boolean, appka
 * kvůli němu nikdy nespadne.
 *
 * **Souběh:** historii mění zároveň poll smyčka služby na pozadí i UI. Každá
 * úprava je read-modify-write nad celým polem, takže bez zámku by si zápisy
 * navzájem přepisovaly - a protože relay zprávu po vyzvednutí MAŽE, ztracená
 * příchozí zpráva by byla ztracená nenávratně. Všechny operace proto běží pod
 * jedním procesovým zámkem [lock].
 *
 * **Cache:** dešifrování Keystorem + parsování JSONu je drahé a náhledy v
 * seznamu kontaktů se čtou často. Rozparsovaná historie se proto drží v paměti
 * ([cache]); zápisy ji rovnou aktualizují. Instance téhle třídy jsou levné a
 * zahoditelné - stav je společný přes companion object.
 */
class ChatRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Zprávy konverzace seřazené od nejstarší po nejnovější. */
    fun getMessages(contactId: String): List<ChatMessage> = synchronized(lock) {
        loadLocked(contactId)
    }

    /** Načte historii (z cache, jinak z disku). Volej jen pod [lock]. */
    private fun loadLocked(contactId: String): List<ChatMessage> {
        cache[contactId]?.let { return it }
        // Neúspěšné čtení (null) NEcachujeme - jinak by si appka zapamatovala
        // prázdnou historii a první zápis by tu skutečnou na disku přepsal.
        val loaded = readFromDisk(contactId) ?: return emptyList()
        cache[contactId] = loaded
        return loaded
    }

    /** Přečte historii z disku. Vrací null, když se to nepovedlo (na rozdíl od prázdné). */
    private fun readFromDisk(contactId: String): List<ChatMessage>? {
        return try {
            val stored = prefs.getString(key(contactId), null) ?: return emptyList()
            val json = KeystoreCryptoHelper.decryptFromStorage(stored) ?: return null
            val array = JSONArray(json)
            val result = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val status = runCatching { ChatMessage.Status.valueOf(o.optString("st")) }
                    .getOrDefault(ChatMessage.Status.SENT)
                val kind = runCatching { ChatMessage.Kind.valueOf(o.optString("kind", "TEXT")) }
                    .getOrDefault(ChatMessage.Kind.TEXT)
                result.add(
                    ChatMessage(
                        id = o.optString("id"),
                        outgoing = o.optBoolean("out"),
                        text = o.optString("txt"),
                        timestamp = o.optLong("ts"),
                        status = status,
                        kind = kind,
                        mediaPath = if (o.has("media")) o.optString("media") else null,
                        mimeType = if (o.has("mime")) o.optString("mime") else null
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se načíst historii", e)
            null
        }
    }

    /** Přidá zprávu na konec konverzace. Vrací true při úspěchu. */
    fun append(contactId: String, message: ChatMessage): Boolean = synchronized(lock) {
        val current = loadLocked(contactId).toMutableList()
        current.add(message)
        saveLocked(contactId, current)
    }

    /**
     * Přidá zprávu jen tehdy, když v konverzaci ještě není zpráva se stejným id.
     * Používá se u příjmu souborů, kde id = hex `fileId`: kdyby odesílatel poslal
     * stejný soubor znovu, vznikly by dvě zprávy se shodným id a `LazyColumn`
     * (klíčovaný právě id) by shodil obrazovku chatu. Vrací true, když se přidala.
     */
    fun appendIfAbsent(contactId: String, message: ChatMessage): Boolean = synchronized(lock) {
        val current = loadLocked(contactId)
        if (current.any { it.id == message.id }) return false
        saveLocked(contactId, current + message)
        true
    }

    /** Nastaví stav existující zprávy (např. SENDING -> SENT/FAILED). */
    fun updateStatus(contactId: String, messageId: String, status: ChatMessage.Status): Boolean =
        synchronized(lock) {
            val updated = loadLocked(contactId)
                .map { if (it.id == messageId) it.copy(status = status) else it }
            saveLocked(contactId, updated)
        }

    /** Doplní cestu k souboru a stav (po složení všech kousků přijatého souboru). */
    fun updateMedia(
        contactId: String,
        messageId: String,
        mediaPath: String?,
        status: ChatMessage.Status
    ): Boolean = synchronized(lock) {
        val updated = loadLocked(contactId).map {
            if (it.id == messageId) it.copy(mediaPath = mediaPath, status = status) else it
        }
        saveLocked(contactId, updated)
    }

    /** Poslední zpráva konverzace, nebo null když žádná není. */
    fun getLastMessage(contactId: String): ChatMessage? = getMessages(contactId).lastOrNull()

    /** Počet nepřečtených (příchozích) zpráv - hlídá se jen lokálně v appce. */
    fun getUnreadCount(contactId: String): Int = prefs.getInt(unreadKey(contactId), 0)

    /** Zvýší počítadlo nepřečtených o jednu (volá se při příjmu mimo otevřený chat). */
    fun incrementUnread(contactId: String) = synchronized(lock) {
        // Čtení a zápis musí být atomické, jinak se souběžné inkrementy překryjí
        // a odznak ukáže míň nepřečtených, než kolik jich doopravdy přišlo.
        prefs.edit().putInt(unreadKey(contactId), getUnreadCount(contactId) + 1).apply()
    }

    /** Označí konverzaci za přečtenou (volá se při otevření chatu). */
    fun markRead(contactId: String) {
        prefs.edit().putInt(unreadKey(contactId), 0).apply()
    }

    /** Nastaví počítadlo nepřečtených (použije se při obnově ze zálohy). */
    fun setUnread(contactId: String, count: Int) {
        prefs.edit().putInt(unreadKey(contactId), count.coerceAtLeast(0)).apply()
    }

    /** Přepíše celou historii konverzace (použije se při obnově ze zálohy). */
    fun restore(contactId: String, messages: List<ChatMessage>): Boolean = synchronized(lock) {
        saveLocked(contactId, messages)
    }

    /** Smaže celou historii konverzace (např. při smazání kontaktu). */
    fun clear(contactId: String) = synchronized(lock) {
        cache.remove(contactId)
        prefs.edit().remove(key(contactId)).remove(unreadKey(contactId)).apply()
    }

    private fun unreadKey(contactId: String) = "unread_$contactId"

    /** Uloží historii a zaktualizuje cache. Volej jen pod [lock]. */
    private fun saveLocked(contactId: String, messages: List<ChatMessage>): Boolean {
        return try {
            val array = JSONArray()
            messages.forEach { m ->
                array.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("out", m.outgoing)
                        .put("txt", m.text)
                        .put("ts", m.timestamp)
                        .put("st", m.status.name)
                        .put("kind", m.kind.name)
                        .apply {
                            m.mediaPath?.let { put("media", it) }
                            m.mimeType?.let { put("mime", it) }
                        }
                )
            }
            val encrypted = KeystoreCryptoHelper.encryptForStorage(array.toString())
            prefs.edit().putString(key(contactId), encrypted).apply()
            cache[contactId] = messages
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se uložit historii", e)
            // Cache po neúspěšném zápisu zahoď, ať v paměti nezůstane stav, který
            // na disku není - příště se načte znovu z disku.
            cache.remove(contactId)
            false
        }
    }

    private fun key(contactId: String) = "msgs_$contactId"

    companion object {
        private const val PREFS_NAME = "crypto_chat_messages"
        private const val TAG = "ChatRepository"

        /** Procesový zámek nad celou historií (viz poznámka o souběhu v třídě). */
        private val lock = Any()

        /** Rozparsovaná historie v paměti, klíčovaná id kontaktu. */
        private val cache = HashMap<String, List<ChatMessage>>()
    }
}
