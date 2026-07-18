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
                            keyBase64 = encryptedKey?.let { KeystoreCryptoHelper.decryptFromStorage(it) }
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
            val current = getContacts().toMutableList()
            val index = current.indexOfFirst { it.id == contact.id }
            if (index >= 0) current[index] = contact else current.add(contact)
            persist(current)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se uložit kontakt", e)
            false
        }
    }

    /** Vrátí true, pokud se smazání povedlo. Nikdy nevyhodí výjimku. */
    fun delete(id: String): Boolean {
        return try {
            val current = getContacts().toMutableList()
            current.removeAll { it.id == id }
            persist(current)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se smazat kontakt", e)
            false
        }
    }

    /**
     * Zapíše celý seznam na disk. Může vyhodit výjimku (např. selhání
     * Android Keystore při šifrování klíče) - proto se volá vždy z bloků
     * výše, které ji odchytí. Pokud šifrování jednoho klíče selže, celý
     * zápis se zruší a na disku zůstanou beze změny předchozí data (žádný
     * napůl zapsaný/poškozený stav).
     */
    private fun persist(list: List<Contact>) {
        val array = JSONArray()
        list.forEach { c ->
            val obj = JSONObject()
            obj.put("id", c.id)
            // Jméno se ukládá zašifrované (stejný Keystore klíč jako sdílený klíč).
            obj.put("name", KeystoreCryptoHelper.encryptForStorage(c.name))
            if (c.keyBase64 != null) {
                obj.put("key", KeystoreCryptoHelper.encryptForStorage(c.keyBase64))
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "crypto_chat_prefs"
        private const val KEY_LIST = "contacts_json"
        private const val TAG = "ContactRepository"
    }
}
