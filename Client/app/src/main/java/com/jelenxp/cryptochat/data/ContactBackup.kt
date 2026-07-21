package com.jelenxp.cryptochat.data

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.chat.ChatMediaStore
import com.jelenxp.cryptochat.chat.ChatMessage
import com.jelenxp.cryptochat.chat.ChatRepository
import com.jelenxp.cryptochat.chat.RatchetState
import com.jelenxp.cryptochat.chat.RatchetStore
import com.jelenxp.cryptochat.crypto.BackupCrypto
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
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

    private const val TAG = "ContactBackup"

    /**
     * Verze formátu zálohy.
     *  - 1: kontakty + klíče.
     *  - 2: + profilové fotky, historie chatů, nepřečtené, `initiator`.
     *  - 3: + stav Double Ratchetu per kontakt ([RatchetState]). Starší verze
     *       (1, 2) se dál načtou - ratchet stav prostě chybí a kontakt jede
     *       legacy cestou.
     */
    private const val VERSION = 3

    /**
     * Zašifrovaná záloha všeho (celý obsah souboru).
     *
     * [crypto] je šifrování historie - výchozí Keystore; testy si dosadí
     * průhlednou implementaci, jinak by roundtrip zálohy nešel otestovat.
     */
    fun export(
        context: Context,
        contacts: List<Contact>,
        password: CharArray,
        crypto: StorageCrypto = KeystoreStorageCrypto
    ): ByteArray {
        val chatRepo = ChatRepository(context, crypto)
        val ratchetStore = RatchetStore(context, crypto)
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
                    // Stabilní ID zprávy - bez něj by po obnově zálohy přestaly
                    // fungovat odkazy na zprávu (odpovědi, reakce).
                    m.wireId?.let { mo.put("wid", it) }
                    m.replyToWireId?.let { mo.put("rto", it) }
                    if (m.reactions.isNotEmpty()) {
                        val rx = JSONObject()
                        m.reactions.forEach { (reactor, r) ->
                            rx.put(reactor, JSONObject().put("e", r.emoji).put("t", r.timestamp))
                        }
                        mo.put("rx", rx)
                    }
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

            // Stav Double Ratchetu (verze 3). Chybí u kontaktů, které ještě
            // nepřešly na ratchet - pak se do zálohy nepřidá.
            ratchetStore.load(c.id)?.let { obj.put("ratchet", it.toJson()) }

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
    fun import(
        context: Context,
        blob: ByteArray,
        password: CharArray,
        crypto: StorageCrypto = KeystoreStorageCrypto
    ): Int {
        val json = String(BackupCrypto.decrypt(blob, password), Charsets.UTF_8)
        val root = JSONObject(json)
        val array = root.getJSONArray("contacts")
        val contactRepo = ContactRepository(context, crypto)
        val chatRepo = ChatRepository(context, crypto)
        val ratchetStore = RatchetStore(context, crypto)
        val decoder = Base64.getDecoder()
        var count = 0

        // Kontakty, které už v appce jsou - kvůli kontrole kolizí id níže. Do téže
        // mapy přidáváme i právě naimportované, aby se kolize řešila i UVNITŘ jedné
        // zálohy (dva záznamy se stejným id a jiným klíčem = druhý nesmí přepsat první).
        val existing = contactRepo.getContacts().associateBy { it.id }.toMutableMap()

        for (i in 0 until array.length()) try {
            val obj = array.getJSONObject(i)
            // id jde přímo do navigační trasy (`chat/$id`…). Cizí/podvržená záloha
            // s id obsahujícím `/`, `?`, `#`… by trasu rozbila, proto se přijme jen
            // jednoduchý segment, jinak se vygeneruje nové.
            val rawId = obj.optString("id")
            val backupId = if (isSafeRouteId(rawId)) rawId else java.util.UUID.randomUUID().toString()
            val importedKey = if (obj.has("key")) obj.getString("key") else null

            // OCHRANA: záloha nesmí tiše přepsat klíč kontaktu, který už v appce
            // je. Podvržený soubor („obnov si to, heslo je…") by jinak nechal
            // jméno beze změny, ale klíč vyměnil za útočníkův - uživatel by pak
            // pod známým jménem psal někomu jinému. Při kolizi id s JINÝM klíčem
            // proto importujeme jako samostatný nový kontakt.
            // Kolize s JINÝM ČITELNÝM klíčem = ochrana: importuj jako samostatný
            // kontakt (viz importKeepsBackupId). Když ale stávající klíč nejde
            // přečíst (null - typicky po obnově zařízení, kdy selže dešifrování
            // z Keystore), je kontakt stejně nefunkční a záloha ho má OBNOVIT pod
            // původním id, ne založit duplikát.
            val collision = existing[backupId]
            val id = if (collision != null && !importKeepsBackupId(collision.keyBase64, importedKey)) {
                java.util.UUID.randomUUID().toString()
            } else {
                backupId
            }

            // Fotku ze zálohy zatím jen DEKÓDUJ, NEUKLÁDEJ. `saveAvatarBytes` maže
            // stávající fotku před zápisem nové, a to nesmí proběhnout dřív, než je
            // jisté, že kontakt uložíme - jinak by neúspěch (nebo záloha bez fotky)
            // připravil EXISTUJÍCÍ kontakt o jeho současnou fotku. Při obnově přes
            // stávající kontakt proto zatím zachovej jeho dosavadní fotku.
            val avatarBytes: ByteArray? = if (obj.has("avatar")) {
                runCatching { decoder.decode(obj.getString("avatar")) }.getOrNull()
            } else null
            val keptAvatar = existing[id]?.takeIf { it.id == id }?.avatarPath

            val contact = Contact(
                id = id,
                name = obj.optString("name", ""),
                keyBase64 = importedKey,
                avatarPath = keptAvatar,
                // optBoolean (ne getBoolean): typově vadné pole nesmí shodit celý import.
                initiator = if (obj.has("initiator")) obj.optBoolean("initiator") else null
            )
            // Když se kontakt neuloží (přechodné selhání Keystore), NEobnovuj pod
            // jeho id historii ani nepřečtené - jinak by na disku zůstala osiřelá
            // data bez kontaktu (a při pozdějším znovuzaložení téhož id by se
            // „vzkřísila"). Obnova jen ve větvi, kde uložení uspělo. Fotka se
            // zatím netkla, takže existující kontakt o ni při selhání nepřijde.
            if (!contactRepo.addOrUpdate(contact)) continue
            count++

            // Kontakt je uložený - teprve TEĎ nahraď fotku. Napřed zapiš NOVOU
            // (starou zatím nemaž), propiš ji do záznamu, a AŽ POTOM smaž starou.
            // Kdyby druhý zápis selhal, stará fotka i odkaz na ni zůstanou (kontakt
            // o fotku nepřijde), jen se uklidí nedopsaná nová.
            var stored = contact
            if (avatarBytes != null) {
                val newPath = AvatarStore.writeAvatar(context, id, avatarBytes)
                if (newPath != null && contactRepo.addOrUpdate(contact.copy(avatarPath = newPath))) {
                    stored = contact.copy(avatarPath = newPath)
                    AvatarStore.pruneAvatars(context, id, newPath)
                } else {
                    newPath?.let { runCatching { File(it).delete() } }
                }
            }
            existing[id] = stored   // aby další záznam téže zálohy viděl kolizi

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
                    // Soubory (ne fotky) se do zálohy neukládají s bajty (velikost),
                    // takže po obnově nemají obsah. Bez tohohle by taková zpráva
                    // vypadala jako doručený soubor, ale nešla by otevřít - proto
                    // ji označíme jako nedostupnou (FAILED).
                    val effectiveStatus =
                        if (kind == ChatMessage.Kind.FILE && mediaPath == null) ChatMessage.Status.FAILED
                        else status
                    messages.add(
                        ChatMessage(
                            id = mo.optString("id"),
                            outgoing = mo.optBoolean("out"),
                            text = mo.optString("txt"),
                            timestamp = mo.optLong("ts"),
                            status = effectiveStatus,
                            kind = kind,
                            mediaPath = mediaPath,
                            mimeType = if (mo.has("mime")) mo.optString("mime") else null,
                            wireId = if (mo.has("wid")) mo.optString("wid") else null,
                            replyToWireId = if (mo.has("rto")) mo.optString("rto") else null,
                            reactions = readBackupReactions(mo.optJSONObject("rx"))
                        )
                    )
                }
                // Sloučit (ne přepsat): obnova nesmí zahodit zprávy přijaté PO
                // vytvoření zálohy u kontaktu, který v appce už je.
                chatRepo.restoreMerging(id, messages)
            }
            if (obj.has("unread")) chatRepo.setUnread(id, obj.optInt("unread", 0))

            // Stav Double Ratchetu (verze 3). Guardované zvlášť: poškozený stav
            // ratchetu NESMÍ shodit obnovu kontaktu - kontakt i historie se obnoví
            // i bez něj (jede pak legacy cestou / dorovná se přes rouru, Fáze 3).
            if (obj.has("ratchet")) runCatching {
                RatchetState.fromJson(obj.getJSONObject("ratchet"))?.let { ratchetStore.save(id, it) }
            }.onFailure {
                Log.w(TAG, "Přeskakuji poškozený ratchet stav kontaktu $id (${it.javaClass.simpleName})")
            }
        } catch (e: Exception) {
            // Jeden vadný záznam v záloze nesmí přerušit celý import (zbytek
            // kontaktů má pořád dorazit). Přeskoč a pokračuj.
            Log.w(TAG, "Přeskakuji poškozený záznam zálohy na indexu $i (${e.javaClass.simpleName})")
        }
        return count
    }

    /** Přijme jen jednoduchý segment do navigační trasy (UUID i starší formáty id). */
    private fun isSafeRouteId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Reakce ze zálohy. Záloha může přijít odkudkoli, takže se poškozený záznam
     * přeskočí a zbytek zprávy se načte dál.
     */
    private fun readBackupReactions(obj: JSONObject?): Map<String, ChatMessage.Reaction> {
        if (obj == null) return emptyMap()
        val out = HashMap<String, ChatMessage.Reaction>(2)
        for (reactor in obj.keys()) {
            val r = obj.optJSONObject(reactor) ?: continue
            // Prázdné emoji je platný záznam - NÁHROBEK po zrušené reakci. Musí
            // přežít import i s časem, jinak by opožděná reakce z karantény
            // neměla co přebít a zrušenou reakci vzkřísila. (Shodně s
            // ChatRepository.readReactions - dvě kopie serializace se nesmí
            // rozejít; hlídá ContactBackupRoundtripTest.)
            out[reactor] = ChatMessage.Reaction(r.optString("e"), r.optLong("t"))
        }
        return out
    }
}

/**
 * Má import zachovat původní id kontaktu (obnova/aktualizace), nebo jde o kolizi
 * s jiným kontaktem? Volá se jen když kontakt s daným id v appce už je.
 *
 *  - stejný klíč      -> aktualizace téhož kontaktu (zachovej id),
 *  - stávající null    -> nefunkční kontakt (nejde přečíst z Keystore, např. po
 *                         obnově zařízení); záloha ho obnoví (zachovej id),
 *  - jiný ČITELNÝ klíč -> ochrana proti podvržené záloze (import jako nový kontakt).
 */
internal fun importKeepsBackupId(existingKey: String?, importedKey: String?): Boolean =
    existingKey == null || existingKey == importedKey
