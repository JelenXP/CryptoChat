package com.jelenxp.cryptochat.data

import com.jelenxp.cryptochat.crypto.BackupCrypto
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializace seznamu kontaktů do/z šifrované zálohy. Kontakty se převedou na
 * JSON (id, jméno, klíč) a celé se zašifrují heslem přes [BackupCrypto].
 *
 * Pozn.: v záloze jsou i sdílené AES klíče kontaktů (v čitelné Base64 podobě
 * uvnitř zašifrovaného balíčku) - proto MUSÍ být záloha chráněná silným heslem.
 */
object ContactBackup {

    private const val VERSION = 1

    /** Zašifrovaná záloha kontaktů (celý obsah souboru). */
    fun export(contacts: List<Contact>, password: CharArray): ByteArray {
        val array = JSONArray()
        contacts.forEach { c ->
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            if (c.keyBase64 != null) obj.put("key", c.keyBase64)
            array.put(obj)
        }
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("contacts", array)
        return BackupCrypto.encrypt(root.toString().toByteArray(Charsets.UTF_8), password)
    }

    /**
     * Rozšifruje a rozparsuje zálohu na seznam kontaktů. Vyhodí výjimku při
     * špatném hesle / poškozeném souboru (viz [BackupCrypto.decrypt]).
     */
    fun import(blob: ByteArray, password: CharArray): List<Contact> {
        val json = String(BackupCrypto.decrypt(blob, password), Charsets.UTF_8)
        val root = JSONObject(json)
        val array = root.getJSONArray("contacts")
        val result = mutableListOf<Contact>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                Contact(
                    id = obj.getString("id"),
                    name = obj.optString("name", ""),
                    keyBase64 = if (obj.has("key")) obj.getString("key") else null
                )
            )
        }
        return result
    }
}
