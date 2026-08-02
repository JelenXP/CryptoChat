package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import com.jelenxp.cryptochat.data.ContactRepository

/**
 * Překlad `memberId` → zobrazované jméno ve skupině. **Lokální jméno uloženého
 * kontaktu má PŘEDNOST** před jménem, které si člen zvolil pro skupinu — když už
 * mám protějšek pojmenovaný, chci vidět své jméno, ne jeho skupinovou přezdívku.
 *
 * Vazbu člen↔kontakt drží [GroupAdminState] (admin ji má na všechny pozvané; člen
 * aspoň na admina, viz `GroupCtrlReceiver`). Kdo vazbu nemá, spadne na roster jméno.
 *
 * Čte Keystore (kontakty) → volej MIMO hlavní vlákno a výsledek drž ve stavu.
 */
object GroupMemberNames {

    /** memberId → jméno (lokální kontakt, jinak roster). Pro celou skupinu jedním čtením. */
    fun resolvedNames(context: Context, group: Group, crypto: StorageCrypto = KeystoreStorageCrypto): Map<String, String> {
        val contactsById = ContactRepository(context, crypto).getContacts().associateBy { it.id }
        val links = GroupAdminState.memberContacts(context, group.groupId, crypto) // memberId → contactId
        return group.members().associate { m ->
            val localName = links[m.memberIdHex]?.let { contactsById[it]?.name }?.takeIf { it.isNotBlank() }
            m.memberIdHex to (localName ?: m.displayName)
        }
    }

    /** Jméno jednoho člena (lokální kontakt má přednost). */
    fun nameOf(context: Context, group: Group, memberIdHex: String, crypto: StorageCrypto = KeystoreStorageCrypto): String {
        val contactId = GroupAdminState.contactForMember(context, group.groupId, memberIdHex, crypto)
        val localName = contactId?.let { ContactRepository(context, crypto).getContacts().firstOrNull { c -> c.id == it }?.name }?.takeIf { it.isNotBlank() }
        return localName ?: group.members().firstOrNull { it.memberIdHex == memberIdHex }?.displayName ?: memberIdHex.take(6)
    }
}
