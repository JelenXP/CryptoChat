package com.jelenxp.cryptochat.data

import android.content.Context
import com.jelenxp.cryptochat.chat.ChatMediaStore
import com.jelenxp.cryptochat.chat.ChatMessage
import com.jelenxp.cryptochat.chat.ChatRepository
import com.jelenxp.cryptochat.crypto.BackupCrypto
import com.jelenxp.cryptochat.ui.util.AvatarStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Serializace kompletní zálohy do/z šifrovaného souboru. Do zálohy jde VŠE, co
 * uživatel má, aby po importu (i na jiném telefonu) měl přesně to samé:
 *  - kontakty: id, jméno, sdílený AES klíč, role při párování (`initiator`),
 *  - profilové fotky (jako bajty JPEG),
 *  - historie chatů (všechny zprávy) + počet nepřečtených.
 *
 * Celý balíček se zašifruje heslem přes [BackupCrypto]. Pozn.: jsou v něm i
 * sdílené klíče a obsah zpráv - proto MUSÍ být záloha chráněná silným heslem.
 */
object ContactBackup {

    private const val VERSION = 2

    /** Zašifrovaná záloha všeho (celý obsah souboru). */
    fun export(context: Context, contacts: List<Contact>, password: CharArray): ByteArray {
        val chatRepo = ChatRepository(context)
        val encoder = Base64.getEncoder()
        val array = JSONArray()
        contacts.forEach { c ->
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            if (c.keyBase64 != null) obj.put("key", c.keyBase64)
            c.initiator?.let { obj.put("initiator", it) }

            // Profilová fotka jako base64 bajty (cesta je na každém zařízení jiná).
            c.avatarPath?.let { path ->
                runCatching {
                    val f = File(path)
                    if (f.isFile) obj.put("avatar", encoder.encodeToString(f.readBytes()))
                }
            }

            // Historie chatu.
            val messages = chatRepo.getMessages(c.id)
            if (messages.isNotEmpty()) {
                val marr = JSONArray()
                messages.forEach { m ->
                    val mo = JSONObject()
                        .put("id", m.id)
                        .put("out", m.outgoing)
                        .put("txt", m.text)
                        .put("ts", m.timestamp)
                        .put("st", m.status.name)
                        .put("kind", m.kind.name)
                    m.mimeType?.let { mo.put("mime", it) }
                    // U fotky přibalíme i bajty obrázku, ať je po importu vidět.
                    // Velké soubory (video, dokumenty) se do zálohy nebalí - nafoukly
                    // by ji o desítky MB; zůstane po nich jen název a typ.
                    if (m.kind == ChatMessage.Kind.IMAGE && m.mediaPath != null) {
                        runCatching {
                            val f = File(m.mediaPath)
                            if (f.isFile) mo.put("img", encoder.encodeToString(f.readBytes()))
                        }
                    }
                    marr.put(mo)
                }
                obj.put("messages", marr)
            }
            val unread = chatRepo.getUnreadCount(c.id)
            if (unread > 0) obj.put("unread", unread)

            array.put(obj)
        }
        val root = JSONObject().put("version", VERSION).put("contacts", array)
        return BackupCrypto.encrypt(root.toString().toByteArray(Charsets.UTF_8), password)
    }

    /**
     * Rozšifruje zálohu a obnoví VŠE (kontakty, fotky, chaty, nepřečtené). Vrací
     * počet obnovených kontaktů. Vyhodí výjimku při špatném hesle / poškozeném
     * souboru (viz [BackupCrypto.decrypt]). Zpětně načte i starší verzi 1 (jen
     * kontakty + klíče).
     */
    fun import(context: Context, blob: ByteArray, password: CharArray): Int {
        val json = String(BackupCrypto.decrypt(blob, password), Charsets.UTF_8)
        val root = JSONObject(json)
        val array = root.getJSONArray("contacts")
        val contactRepo = ContactRepository(context)
        val chatRepo = ChatRepository(context)
        val decoder = Base64.getDecoder()
        var count = 0

        // Kontakty, které už v appce jsou - kvůli kontrole kolizí id níže.
        val existing = contactRepo.getContacts().associateBy { it.id }

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val backupId = obj.getString("id")
            val importedKey = if (obj.has("key")) obj.getString("key") else null

            // OCHRANA: záloha nesmí tiše přepsat klíč kontaktu, který už v appce
            // je. Podvržený soubor („obnov si to, heslo je…") by jinak nechal
            // jméno beze změny, ale klíč vyměnil za útočníkův - uživatel by pak
            // pod známým jménem psal někomu jinému. Při kolizi id s JINÝM klíčem
            // proto importujeme jako samostatný nový kontakt.
            val collision = existing[backupId]
            val id = if (collision != null && collision.keyBase64 != importedKey) {
                java.util.UUID.randomUUID().toString()
            } else {
                backupId
            }

            // Fotku ulož ze zálohovaných bajtů a získej novou cestu (na tomto zařízení).
            var avatarPath: String? = null
            if (obj.has("avatar")) {
                runCatching {
                    val bytes = decoder.decode(obj.getString("avatar"))
                    avatarPath = AvatarStore.saveAvatarBytes(context, id, bytes)
                }
            }

            val contact = Contact(
                id = id,
                name = obj.optString("name", ""),
                keyBase64 = importedKey,
                avatarPath = avatarPath,
                initiator = if (obj.has("initiator")) obj.getBoolean("initiator") else null
            )
            if (contactRepo.addOrUpdate(contact)) count++

            // Historie chatu.
            if (obj.has("messages")) {
                val marr = obj.getJSONArray("messages")
                val messages = ArrayList<ChatMessage>(marr.length())
                for (j in 0 until marr.length()) {
                    val mo = marr.getJSONObject(j)
                    val status = runCatching { ChatMessage.Status.valueOf(mo.optString("st")) }
                        .getOrDefault(ChatMessage.Status.SENT)
                    val kind = runCatching { ChatMessage.Kind.valueOf(mo.optString("kind", "TEXT")) }
                        .getOrDefault(ChatMessage.Kind.TEXT)
                    // Fotku ulož ze zálohovaných bajtů a získej novou cestu.
                    var mediaPath: String? = null
                    if (kind == ChatMessage.Kind.IMAGE && mo.has("img")) {
                        runCatching {
                            mediaPath = ChatMediaStore.save(context, decoder.decode(mo.getString("img")))
                        }
                    }
                    messages.add(
                        ChatMessage(
                            id = mo.optString("id"),
                            outgoing = mo.optBoolean("out"),
                            text = mo.optString("txt"),
                            timestamp = mo.optLong("ts"),
                            status = status,
                            kind = kind,
                            mediaPath = mediaPath,
                            mimeType = if (mo.has("mime")) mo.optString("mime") else null
                        )
                    )
                }
                chatRepo.restore(id, messages)
            }
            if (obj.has("unread")) chatRepo.setUnread(id, obj.optInt("unread", 0))
        }
        return count
    }
}
