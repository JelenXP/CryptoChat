package com.jelenxp.cryptochat.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.ContactBackup
import com.jelenxp.cryptochat.data.ContactRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun deleteContact(id: String): Boolean {
        val success = repository.delete(id)
        refresh()
        return success
    }

    fun getContact(id: String): Contact? =
        _contacts.value.find { it.id == id } ?: repository.getContacts().find { it.id == id }

    /** Počet kontaktů (pro UI zálohy - kolik se jich vyexportuje). */
    fun contactCount(): Int = _contacts.value.size

    /** Zašifrovaná záloha všech kontaktů, chráněná heslem. */
    fun exportBackup(password: CharArray): ByteArray =
        ContactBackup.export(repository.getContacts(), password)

    /**
     * Naimportuje kontakty ze zálohy a vrátí počet přidaných/aktualizovaných.
     * Kontakty se ukládají pod původním id (stejný kontakt se aktualizuje,
     * nezaloží duplikát). Vyhodí výjimku při špatném hesle / poškozeném souboru.
     */
    fun importBackup(blob: ByteArray, password: CharArray): Int {
        val imported = ContactBackup.import(blob, password)
        val count = repository.addOrUpdateAll(imported)
        refresh()
        return count
    }
}
