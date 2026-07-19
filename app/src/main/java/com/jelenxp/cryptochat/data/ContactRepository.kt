package com.jelenxp.cryptochat.data

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.crypto.KeystoreCryptoHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jednoduché lokální úložiště seznamu uživatelů (kontaktů) a jejich klíčů.
 * Data se ukládají jako JSON do SharedPreferences - žádná síť, žádný server,
 * vše zůstává pouze na zařízení.
 *
 * Sdílený klíč i jméno každého kontaktu se před uložením zašifrují klíčem
 * z Android Keystore ([KeystoreCryptoHelper]) - na disku tedy nikdy neleží
 * v čitelné podobě. V paměti (třída [Contact]) zůstávají odšifrované, aby je
 * zbytek aplikace mohl normálně používat.
 *
 * Migrace: starší verze ukládaly jméno v plaintextu. Při čtení se proto jméno
 * nejdřív zkusí dešifrovat; když to selže (byl to plaintext), použije se
 * hodnota tak jak je - a při nejbližším uložení se už zašifruje.
 *
 * Všechny veřejné metody jsou odolné proti výjimkám (poškozená data na disku,
 * selhání Keystore na konkrétním zařízení apod.) - nikdy appku nespadnou,
 * jen selžou a dají o tom vědět voláním kódu přes návratovou hodnotu Boolean.
 */
class ContactRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Vrátí uložené kontakty. Pokud je uložený JSON poškozený, vrátí prázdný
     * seznam místo pádu appky. Pokud je poškozený jen jeden konkrétní záznam,
     * přeskočí se jen ten a ostatní se načtou normálně.
     */
    fun getContacts(): List<Contact> {
        return try {
            val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
            val array = JSONArray(json)
            val result = mutableListOf<Contact>()
            for (i in 0 until array.length()) {
                try {
                    val obj = array.getJSONObject(i)
                    val encryptedKey = if (obj.has("key")) obj.getString("key") else null
                    // Jméno: zkusíme dešifrovat; když to selže (starý plaintext
                    // záznam), použijeme surovou hodnotu (migrace za běhu).
                    val rawName = obj.optString("name", "")
                    val name = KeystoreCryptoHelper.decryptFromStorage(rawName) ?: rawName
                    result.add(
                        Contact(
                            id = obj.getString("id"),
                            name = name,
                            // Pokud dešifrování selže (např. neplatný Keystore klíč po
                            // obnově zařízení), vrátí se null - kontakt zůstane bez
                            // klíče a jde znovu spárovat, aplikace nespadne.
                            keyBase64 = encryptedKey?.let { KeystoreCryptoHelper.decryptFromStorage(it) },
                            // Cesta k fotce - necitlivá, ukládá se v plaintextu.
                            avatarPath = obj.optString("avatar", "").takeIf { it.isNotEmpty() }
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Přeskakuji poškozený záznam kontaktu na indexu $i", e)
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se načíst uložené kontakty, vracím prázdný seznam", e)
            emptyList()
        }
    }

    /** Vrátí true, pokud se uložení povedlo. Nikdy nevyhodí výjimku. */
    fun addOrUpdate(contact: Contact): Boolean {
        return try {
            val array = readArray()
            upsert(array, contact)
            writeArray(array)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se uložit kontakt", e)
            false
        }
    }

    /**
     * Hromadně přidá/aktualizuje víc kontaktů jedním zápisem - důležité při
     * importu zálohy, kde by opakované volání [addOrUpdate] bylo kvadratické.
     * Vrátí počet zpracovaných kontaktů, nebo 0 při selhání (zápis je atomický).
     */
    fun addOrUpdateAll(contacts: List<Contact>): Int {
        return try {
            val array = readArray()
            contacts.forEach { upsert(array, it) }
            writeArray(array)
            contacts.size
        } catch (e: Exception) {
            Log.e(TAG, "Hromadné uložení kontaktů selhalo", e)
            0
        }
    }

    /** Vrátí true, pokud se smazání povedlo. Nikdy nevyhodí výjimku. */
    fun delete(id: String): Boolean {
        return try {
            val array = readArray()
            val kept = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optString("id") != id) kept.put(obj)
            }
            writeArray(kept)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se smazat kontakt", e)
            false
        }
    }

    // --- Cílené úpravy uloženého JSONu ---
    // Zápisové operace mění jen dotčené záznamy; ostatní zůstávají tak, jak jsou
    // uložené (v původní zašifrované podobě). Nezměněné kontakty se tedy nikdy
    // nedešifrují ani znovu nešifrují - je to rychlejší a hlavně to zabraňuje
    // ztrátě dat: dřívější „přepiš celý seznam" by při přechodném selhání
    // dešifrování cizího klíče uložil ten klíč prázdný.

    private fun readArray(): JSONArray = try {
        JSONArray(prefs.getString(KEY_LIST, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun writeArray(array: JSONArray) {
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    /**
     * Zašifruje kontakt a vloží/nahradí jeho záznam v [array] podle id. Může
     * vyhodit výjimku (selhání Keystore) - volá se z bloků výše, které ji
     * odchytí, takže se na disk nic napůl nezapíše.
     */
    private fun upsert(array: JSONArray, contact: Contact) {
        val index = indexOfId(array, contact.id)
        val obj = JSONObject()
        obj.put("id", contact.id)
        // Jméno i klíč se ukládají zašifrované (stejný Keystore klíč).
        obj.put("name", KeystoreCryptoHelper.encryptForStorage(contact.name))
        // Cesta k fotce (necitlivá) - plaintext; null = fotku nemá (pole se vynechá).
        contact.avatarPath?.takeIf { it.isNotEmpty() }?.let { obj.put("avatar", it) }
        when {
            contact.keyBase64 != null ->
                obj.put("key", KeystoreCryptoHelper.encryptForStorage(contact.keyBase64))
            // Klíč v paměti chybí (nešel dešifrovat) - zachovej původní
            // zašifrovaný klíč ze stávajícího záznamu, ať se o něj kvůli
            // přechodnému selhání Keystore nepřijde.
            index >= 0 -> array.optJSONObject(index)?.optString("key")
                ?.takeIf { it.isNotEmpty() }?.let { obj.put("key", it) }
        }
        if (index >= 0) array.put(index, obj) else array.put(obj)
    }

    private fun indexOfId(array: JSONArray, id: String): Int {
        for (i in 0 until array.length()) {
            if (array.optJSONObject(i)?.optString("id") == id) return i
        }
        return -1
    }

    companion object {
        private const val PREFS_NAME = "crypto_chat_prefs"
        private const val KEY_LIST = "contacts_json"
        private const val TAG = "ContactRepository"
    }
}
