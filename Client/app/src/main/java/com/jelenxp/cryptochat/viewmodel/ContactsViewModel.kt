package com.jelenxp.cryptochat.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.jelenxp.cryptochat.chat.ChatRepository
import com.jelenxp.cryptochat.chat.ReplayGuard
import com.jelenxp.cryptochat.chat.WireCompat
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.ContactBackup
import com.jelenxp.cryptochat.data.ContactRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ContactRepository(application)

    private val _contacts = MutableStateFlow(repository.getContacts())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    fun refresh() {
        _contacts.value = repository.getContacts()
    }

    fun addOrUpdateContact(contact: Contact): Boolean {
        val success = repository.addOrUpdate(contact)
        refresh()
        return success
    }

    /**
     * Smaže kontakt VČETNĚ všeho, co k němu patří. Samotný záznam kontaktu
     * nestačí - historie zpráv (i s fotkami), počítadlo nepřečtených, otisky
     * proti replay i stav kompatibility by jinak zůstaly na disku, ačkoli
     * uživatel čeká, že smazáním kontaktu je pryč všechno.
     */
    fun deleteContact(id: String): Boolean {
        val success = repository.delete(id)
        val ctx = getApplication<Application>()
        runCatching { ChatRepository(ctx).clear(id) }
        runCatching { ReplayGuard.clear(ctx, id) }
        runCatching { WireCompat.clear(ctx, id) }
        refresh()
        return success
    }

    fun getContact(id: String): Contact? =
        _contacts.value.find { it.id == id } ?: repository.getContacts().find { it.id == id }

    /**
     * Uloží právě vyměněný klíč. Když je [contactId] daný a kontakt existuje,
     * jen mu vymění klíč (zachová jméno i fotku - re-key / obnova klíče). Jinak
     * založí nový kontakt s novým id. Vrátí true při úspěchu.
     */
    /**
     * Uloží kontakt po výměně klíče.
     *
     * [initiator] MUSÍ být na obou zařízeních OPAČNÝ - určuje, do které z dvojice
     * schránek se posílá a ze které se čte ([com.jelenxp.cryptochat.chat.RelaySync]).
     * Když zůstane `null` u obou (jak tomu dřív bylo u osobního i ML-KEM párování),
     * obě strany zapisují do téže schránky a čtou z téže druhé - odeslání se tváří
     * úspěšně, ale zpráva nikdy nedorazí. Kdo klíč vytvořil / zahájil = `true`,
     * kdo ho přijal / dokončil = `false`.
     */
    fun saveExchangedKey(
        contactId: String?,
        name: String,
        keyBase64: String,
        initiator: Boolean
    ): Boolean {
        val existing = contactId?.let { getContact(it) }
        val contact = existing?.copy(keyBase64 = keyBase64, initiator = initiator)
            ?: Contact(
                id = contactId ?: UUID.randomUUID().toString(),
                name = name,
                keyBase64 = keyBase64,
                initiator = initiator
            )
        return addOrUpdateContact(contact)
    }

    /**
     * Uloží kontakt vzniklý online párováním pozvánkou (chat přes relay). Kromě
     * klíče zaznamená i roli [initiator] (kdo pozvánku vytvořil) - z ní plyne směr
     * schránek. Když [contactId] odpovídá existujícímu kontaktu, jen mu vymění klíč
     * a roli (zachová jméno i fotku); jinak založí nový. Vrátí true při úspěchu.
     */
    fun saveChatContact(contactId: String?, name: String, keyBase64: String, initiator: Boolean): Boolean {
        val existing = contactId?.let { getContact(it) }
        val contact = existing?.copy(keyBase64 = keyBase64, initiator = initiator)
            ?: Contact(
                id = contactId ?: UUID.randomUUID().toString(),
                name = name,
                keyBase64 = keyBase64,
                initiator = initiator
            )
        return addOrUpdateContact(contact)
    }

    /** Počet kontaktů (pro UI zálohy - kolik se jich vyexportuje). */
    fun contactCount(): Int = _contacts.value.size

    /** Zašifrovaná kompletní záloha (kontakty, klíče, fotky, chaty), chráněná heslem. */
    fun exportBackup(password: CharArray): ByteArray =
        ContactBackup.export(getApplication(), repository.getContacts(), password)

    /**
     * Naimportuje kompletní zálohu (kontakty, klíče, fotky, chaty) a vrátí počet
     * obnovených kontaktů. Kontakty se ukládají pod původním id (aktualizují se,
     * nezaloží duplikát). Vyhodí výjimku při špatném hesle / poškozeném souboru.
     */
    fun importBackup(blob: ByteArray, password: CharArray): Int {
        val count = ContactBackup.import(getApplication(), blob, password)
        refresh()
        return count
    }
}
