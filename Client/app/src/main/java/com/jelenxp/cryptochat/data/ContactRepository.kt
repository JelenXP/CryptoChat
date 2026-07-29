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
class ContactRepository(
    context: Context,
    /**
     * Šifrování jména a klíče at rest. Výchozí Keystore; testy si dosadí
     * průhlednou implementaci (jinak by tenhle repozitář nešlo otestovat).
     */
    private val crypto: com.jelenxp.cryptochat.crypto.StorageCrypto =
        com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Vrátí uložené kontakty. Pokud je uložený JSON poškozený, vrátí prázdný
     * seznam místo pádu appky. Pokud je poškozený jen jeden konkrétní záznam,
     * přeskočí se jen ten a ostatní se načtou normálně.
     */
    fun getContacts(): List<Contact> = synchronized(lock) {
        try {
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
                    val name = crypto.decrypt(rawName) ?: rawName
                    result.add(
                        Contact(
                            id = obj.getString("id"),
                            name = name,
                            // Pokud dešifrování selže (např. neplatný Keystore klíč po
                            // obnově zařízení), vrátí se null - kontakt zůstane bez
                            // klíče a jde znovu spárovat, aplikace nespadne.
                            keyBase64 = encryptedKey?.let { crypto.decrypt(it) },
                            // Cesta k fotce - necitlivá, ukládá se v plaintextu.
                            avatarPath = obj.optString("avatar", "").takeIf { it.isNotEmpty() },
                            // Role při online párování (chat); chybí u starších/osobních kontaktů.
                            // optBoolean (ne getBoolean): typově vadná hodnota nesmí kvůli
                            // NEPODSTATNÉMU poli shodit jinak funkční kontakt i s klíčem.
                            initiator = if (obj.has("initiator")) obj.optBoolean("initiator") else null
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Přeskakuji poškozený záznam kontaktu na indexu $i (${e.javaClass.simpleName})")
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se načíst uložené kontakty (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    /** Vrátí true, pokud se uložení povedlo. Nikdy nevyhodí výjimku. */
    fun addOrUpdate(contact: Contact): Boolean = synchronized(lock) {
        try {
            val array = readArray()
            upsert(array, contact)
            writeArray(array)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se uložit kontakt (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Hromadně přidá/aktualizuje víc kontaktů jedním zápisem - důležité při
     * importu zálohy, kde by opakované volání [addOrUpdate] bylo kvadratické.
     * Vrátí počet zpracovaných kontaktů, nebo 0 při selhání (zápis je atomický).
     */
    fun addOrUpdateAll(contacts: List<Contact>): Int = synchronized(lock) {
        try {
            val array = readArray()
            contacts.forEach { upsert(array, it) }
            writeArray(array)
            contacts.size
        } catch (e: Exception) {
            Log.e(TAG, "Hromadné uložení kontaktů selhalo (${e.javaClass.simpleName})")
            0
        }
    }

    /** Vrátí true, pokud se smazání povedlo. Nikdy nevyhodí výjimku. */
    fun delete(id: String): Boolean = synchronized(lock) {
        try {
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
        // Jméno se ukládá zašifrované, ale POZOR na dvojité šifrování (nález A7):
        // když Keystore při čtení přechodně selže, getContacts vrátí do Contact.name
        // surový ciphertext (fallback). Následné bezpodmínečné `encrypt(name)` by
        // vyrobilo E(E(name)) a po zotavení Keystore by se jméno navždy zobrazovalo
        // jako nečitelný base64. Rozpoznáme to podle shody s uloženým zašifrovaným
        // jménem: když se in-memory jméno rovná tomu na disku, je to ten
        // nedešifrovaný fallback → ulož beze změny (klíč níž má stejnou ochranu,
        // jen díky nullable typu). Nezměněné legacy-plaintext jméno tím zůstane
        // plaintext, což je přijatelné (nekazí se data, jen se nemigruje).
        val storedName = if (index >= 0)
            array.optJSONObject(index)?.optString("name")?.takeIf { it.isNotEmpty() } else null
        obj.put("name", if (contact.name == storedName) storedName else crypto.encrypt(contact.name))
        // Cesta k fotce (necitlivá) - plaintext; null = fotku nemá (pole se vynechá).
        contact.avatarPath?.takeIf { it.isNotEmpty() }?.let { obj.put("avatar", it) }
        // Role při online párování (necitlivá); null = pole se vynechá.
        contact.initiator?.let { obj.put("initiator", it) }
        when {
            contact.keyBase64 != null ->
                obj.put("key", crypto.encrypt(contact.keyBase64))
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

        // Procesový (statický) zámek: read-modify-write nad polem kontaktů běží
        // z UI i z importu zálohy (Dispatchers.Default) přes RŮZNÉ instance nad
        // týmž prefs souborem; bez sdíleného zámku si zápisy last-writer-wins
        // přepíší a úprava kontaktu se ztratí (nález A16). Stejný vzor jako
        // ChatRepository. Statický, aby platil napříč instancemi.
        private val lock = Any()
    }
}
