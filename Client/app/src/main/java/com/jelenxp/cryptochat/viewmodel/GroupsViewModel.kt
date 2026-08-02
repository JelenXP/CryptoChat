package com.jelenxp.cryptochat.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jelenxp.cryptochat.chat.BlobQuarantine
import com.jelenxp.cryptochat.chat.Group
import com.jelenxp.cryptochat.chat.GroupActions
import com.jelenxp.cryptochat.chat.GroupAdminState
import com.jelenxp.cryptochat.chat.GroupChatRepository
import com.jelenxp.cryptochat.chat.GroupInvite
import com.jelenxp.cryptochat.chat.GroupMediaStore
import com.jelenxp.cryptochat.chat.GroupStore
import com.jelenxp.cryptochat.chat.ReplayGuard
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stav SKUPIN pro UI — paralela [ContactsViewModel] nad [GroupStore]. Skupiny se
 * v hlavním seznamu míchají s 1:1 kontakty; akce (vytvoření, pozvání, odebrání)
 * jdou přes [GroupActions] MIMO hlavní vlákno (síť/Keystore), seznam je `StateFlow`
 * a překreslí se sám. Jméno člena v rosteru VIDÍ všichni, proto „moje jméno" žije
 * v [SettingsRepository] ([SettingsRepository.getMyDisplayName]).
 */
class GroupsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = GroupStore(application)
    private val settings = SettingsRepository(application)

    private val _groups = MutableStateFlow(store.getGroups())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _groups.value = withContext(Dispatchers.IO) { store.getGroups() }
        }
    }

    fun getGroup(groupId: String): Group? =
        _groups.value.find { it.groupId == groupId } ?: store.getGroup(groupId)

    fun myDisplayName(): String = settings.getMyDisplayName()
    fun setMyDisplayName(name: String) = settings.setMyDisplayName(name)

    /**
     * Vytvoří skupinu (zatím jen se mnou jako adminem) a hned ji vloží do flow, ať
     * UI může rovnou navigovat. [myName] se uloží i jako profilové jméno. Vrací
     * skupinu, nebo null při selhání zápisu.
     */
    suspend fun createGroup(groupName: String, myName: String): Group? {
        if (myName.isNotBlank()) settings.setMyDisplayName(myName)
        // Keygen (Ed25519+ML-KEM) + Keystore-šifrovaný zápis NEsmí na hlavní vlákno (ANR).
        val group = withContext(Dispatchers.IO) {
            GroupActions.createGroup(getApplication(), groupName.trim(), myName.trim())
        }
        if (group != null) _groups.value = _groups.value + group
        refresh()
        return group
    }

    /** Admin pozve kontakt do skupiny (pošle INVITE kartu) — mimo hlavní vlákno. */
    fun invite(group: Group, contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) { GroupActions.invite(getApplication(), group, contact) }
    }

    /** Nováček přijme pozvánku (vygeneruje klíče, pošle adminovi PUBKEYS). */
    fun acceptInvite(adminContact: Contact, invite: GroupInvite, myName: String) {
        if (myName.isNotBlank()) settings.setMyDisplayName(myName)
        viewModelScope.launch(Dispatchers.IO) {
            GroupActions.acceptInvite(getApplication(), adminContact, invite, myName.trim())
        }
    }

    /** Admin odebere člena (rotace GS, roznesení novým klíčem zbývajícím). */
    fun removeMember(group: Group, memberIdHex: String, allContacts: List<Contact>) {
        viewModelScope.launch(Dispatchers.IO) {
            GroupActions.removeMember(getApplication(), group, memberIdHex, allContacts)
            _groups.value = store.getGroups()
        }
    }

    /** Nastaví/změní/odebere skupinovou fotku (jen lokálně; [avatarPath] null = odebrat). */
    fun setAvatar(groupId: String, avatarPath: String?) {
        val group = getGroup(groupId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            store.upsert(group.copy(avatarPath = avatarPath))
            _groups.value = store.getGroups()
        }
    }

    /**
     * Opustí / smaže skupinu VČETNĚ všeho, co k ní patří (historie, fotky, admin
     * mapa, replay otisky, karanténa) — stejná důkladnost jako u [ContactsViewModel.deleteContact].
     * Nástupnictví admina není, takže „opustit" = smazat lokálně.
     */
    fun deleteGroup(groupId: String) {
        _groups.value = _groups.value.filterNot { it.groupId == groupId }
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.delete(groupId) }
            runCatching { GroupChatRepository(ctx).clear(groupId) }
            runCatching { GroupMediaStore.clear(ctx, groupId) }
            runCatching { GroupAdminState.clear(ctx, groupId) }
            runCatching { ReplayGuard.clear(ctx, groupId) }
            runCatching { BlobQuarantine.clear(ctx, groupId) }
            runCatching { com.jelenxp.cryptochat.ui.util.AvatarStore.deleteAvatars(ctx, groupId) }
            _groups.value = store.getGroups()
        }
        refresh()
    }
}
