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
 */
class ChatRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Zprávy konverzace seřazené od nejstarší po nejnovější. */
    fun getMessages(contactId: String): List<ChatMessage> {
        return try {
            val stored = prefs.getString(key(contactId), null) ?: return emptyList()
            val json = KeystoreCryptoHelper.decryptFromStorage(stored) ?: return emptyList()
            val array = JSONArray(json)
            val result = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val status = runCatching { ChatMessage.Status.valueOf(o.optString("st")) }
                    .getOrDefault(ChatMessage.Status.SENT)
                result.add(
                    ChatMessage(
                        id = o.optString("id"),
                        outgoing = o.optBoolean("out"),
                        text = o.optString("txt"),
                        timestamp = o.optLong("ts"),
                        status = status
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se načíst historii, vracím prázdnou", e)
            emptyList()
        }
    }

    /** Přidá zprávu na konec konverzace. Vrací true při úspěchu. */
    fun append(contactId: String, message: ChatMessage): Boolean {
        val current = getMessages(contactId).toMutableList()
        current.add(message)
        return save(contactId, current)
    }

    /** Nastaví stav existující zprávy (např. SENDING -> SENT/FAILED). */
    fun updateStatus(contactId: String, messageId: String, status: ChatMessage.Status): Boolean {
        val current = getMessages(contactId)
        val updated = current.map { if (it.id == messageId) it.copy(status = status) else it }
        return save(contactId, updated)
    }

    /** Smaže celou historii konverzace (např. při smazání kontaktu). */
    fun clear(contactId: String) {
        prefs.edit().remove(key(contactId)).apply()
    }

    private fun save(contactId: String, messages: List<ChatMessage>): Boolean {
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
                )
            }
            val encrypted = KeystoreCryptoHelper.encryptForStorage(array.toString())
            prefs.edit().putString(key(contactId), encrypted).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se uložit historii", e)
            false
        }
    }

    private fun key(contactId: String) = "msgs_$contactId"

    companion object {
        private const val PREFS_NAME = "crypto_chat_messages"
        private const val TAG = "ChatRepository"
    }
}
