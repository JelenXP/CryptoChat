package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import com.jelenxp.cryptochat.data.Contact

/**
 * Orchestrace SKUPINOVÝCH akcí přes 1:1 kanál (join handshake + správa členství).
 * Sedí nad [GroupAdmin] (produkuje rostery+balíky), [GroupStore] (perzistence),
 * [GroupAdminState] (adminova mapa člen↔kontakt) a [RelaySync.sendGroupCtrl]
 * (doručení). UI (fáze 7) i příjmová cesta ([GroupCtrlReceiver]) volají tyhle
 * funkce — logika je tak testovatelná nad [FakeRelay]+[FakeStorageCrypto], zatímco
 * v composable/obrazovce zůstane jen „zavolej akci".
 *
 * Handshake (vše 1:1, gatováno [WireExt.CAP_GROUPS]):
 *  1. admin [createGroup] (skupina jen s adminem) → [invite] (INVITE nováčkovi),
 *  2. nováček [acceptInvite] (vygeneruje klíče, pošle PUBKEYS),
 *  3. admin [onPubkeysReceived] (přidá člena, rozešle BUNDLE všem).
 * Odebrání: [removeMember] (rotace GS, BUNDLE zbývajícím).
 */
object GroupActions {

    /** Admin vytvoří skupinu (zatím jen se sebou). Vrací uloženou skupinu, null při selhání zápisu. */
    fun createGroup(
        context: Context,
        groupName: String,
        myDisplayName: String,
        crypto: StorageCrypto = KeystoreStorageCrypto,
    ): Group? {
        val result = GroupAdmin.create(groupName, myDisplayName, emptyList())
        return if (GroupStore(context, crypto).upsert(result.adminGroup)) result.adminGroup else null
    }

    /**
     * Admin pozve 1:1 [contact] do skupiny: rezervuje mu memberId a pošle INVITE
     * (kartu). Idempotentní — opětovné pozvání téhož kontaktu použije týž memberId
     * a jen znovu pošle kartu. Vrací, zda se INVITE podařilo doručit (rezervace se
     * uloží tak jako tak, aby šlo poslání zopakovat).
     */
    fun invite(
        context: Context,
        group: Group,
        contact: Contact,
        crypto: StorageCrypto = KeystoreStorageCrypto,
    ): Boolean {
        if (!group.amIAdmin) return false
        val store = GroupStore(context, crypto)
        val current = store.getGroup(group.groupId) ?: group
        // Už připojený člen? Znovu ho nezveme (nezaložíme mu druhé pending).
        if (GroupAdminState.memberIdForContact(context, current.groupId, contact.id, crypto) != null) return false
        if (current.members().size >= GroupRoster.MAX_GROUP_MEMBERS) return false
        GroupAdminState.reserveInvite(context, current.groupId, contact.id, current.usedMemberIds, crypto) ?: return false
        val invite = GroupInvite(current.groupId, current.name, current.members().size, current.adminPublicKeyBase64)
        return RelaySync.sendGroupCtrl(context, contact, WireExt.GROUP_CTRL_INVITE, GroupInvite.encode(invite))
    }

    /**
     * Nováček přijme pozvánku: vygeneruje (nebo znovupoužije) skupinové klíče, uloží
     * je do [GroupJoinStore] (aby je [GroupControl.applyBundle] mohl adoptovat, až
     * dorazí BUNDLE) a pošle je adminovi jako PUBKEYS. Idempotentní. Vrací, zda se
     * PUBKEYS podařilo odeslat.
     */
    fun acceptInvite(
        context: Context,
        adminContact: Contact,
        invite: GroupInvite,
        myDisplayName: String,
        crypto: StorageCrypto = KeystoreStorageCrypto,
    ): Boolean {
        // Už jsem člen (BUNDLE dorazil dřív) → není co dělat, jen ukliď kartu.
        if (GroupStore(context, crypto).getGroup(invite.groupIdHex) != null) {
            GroupInviteStore.remove(context, adminContact.id)
            return true
        }
        val existing = GroupJoinStore.get(context, invite.groupIdHex, crypto)
        val sign: GroupIdentity.SignKeyPair
        val seal: GroupIdentity.SealKeyPair
        if (existing != null) {
            sign = existing.first; seal = existing.second
        } else {
            sign = GroupIdentity.generateSignKeyPair()
            seal = GroupIdentity.generateSealKeyPair()
            // Klíče ulož PŘED odesláním PUBKEYS — jinak by mohl BUNDLE dorazit dřív,
            // než mám čím ho adoptovat (a musel bych o join přijít).
            if (!GroupJoinStore.save(context, invite.groupIdHex, sign, seal, crypto)) return false
        }
        val pubkeys = GroupPubkeys(invite.groupIdHex, myDisplayName, sign.publicKeyBase64, seal.publicKeyBase64)
        val sent = RelaySync.sendGroupCtrl(context, adminContact, WireExt.GROUP_CTRL_PUBKEYS, GroupPubkeys.encode(pubkeys))
        if (sent) GroupInviteStore.remove(context, adminContact.id)
        return sent
    }

    /**
     * Admin přijal PUBKEYS od [fromContact]: přidá člena a rozešle BUNDLE VŠEM.
     * [allContacts] = snímek kontaktů pro routing balíků ostatním členům. Vrací
     * **true** = durabilně zpracováno (smí se ACK), **false** = zápis skupiny selhal
     * (nepotvrzovat, PUBKEYS přijde znovu).
     *
     * Idempotence: memberId je rezervovaný už z pozvánky, takže druhé doručení týchž
     * PUBKEYS buď najde kontakt už jako člena (→ znovu pošle jeho balík), nebo použije
     * TÝŽ rezervovaný memberId (žádný duplicitní člen). Nevyžádané PUBKEYS (kontakt
     * nebyl pozván) se zahodí — groupId je sice tajné, tohle je obrana do hloubky.
     */
    fun onPubkeysReceived(
        context: Context,
        fromContact: Contact,
        pubkeys: GroupPubkeys,
        allContacts: List<Contact>,
        crypto: StorageCrypto = KeystoreStorageCrypto,
    ): Boolean {
        val store = GroupStore(context, crypto)
        val group = store.getGroup(pubkeys.groupIdHex) ?: return true // neznámá skupina → zahodit
        if (!group.amIAdmin) return true                              // nejsem admin → zahodit

        // Už člen? BUNDLE se nejspíš ztratil → pošli znovu jeho AKTUÁLNÍ balík.
        if (GroupAdminState.memberIdForContact(context, group.groupId, fromContact.id, crypto) != null) {
            sendGroupBundle(context, fromContact, currentPayload(group, assignedMemberId = null), group)
            return true
        }
        // Nepozván → nevyžádané, zahodit (ale ACK, ať to relay nenabízí donekonečna).
        val reservedId = GroupAdminState.pendingMemberId(context, group.groupId, fromContact.id, crypto) ?: return true

        val spec = GroupAdmin.MemberSpec(pubkeys.displayName, pubkeys.ed25519PublicKeyBase64, pubkeys.sealPublicKeyBase64)
        val result = try {
            GroupAdmin.addMember(group, spec, forcedMemberId = reservedId)
        } catch (_: Exception) {
            return true // plno / duplicitní klíče / poškozený roster → zahodit
        }
        if (!store.upsert(result.adminGroup)) return false // durabilní zápis selhal → PUBKEYS přijde znovu
        GroupAdminState.promote(context, group.groupId, fromContact.id, reservedId, crypto)
        distributeBundles(context, result, allContacts, newcomerId = reservedId, newcomerContact = fromContact, crypto = crypto)
        return true
    }

    /**
     * Admin odebere člena: rotuje GS a rozešle BUNDLE s NOVÝM GS zbývajícím (odebraný
     * na 1:1 kanál nedosáhne). [allContacts] pro routing. Vrací false při selhání
     * zápisu skupiny (změna se neprovede).
     */
    fun removeMember(
        context: Context,
        group: Group,
        memberIdHex: String,
        allContacts: List<Contact>,
        crypto: StorageCrypto = KeystoreStorageCrypto,
    ): Boolean {
        if (!group.amIAdmin) return false
        val store = GroupStore(context, crypto)
        val result = try {
            GroupAdmin.removeMember(group, memberIdHex)
        } catch (_: Exception) {
            return false
        }
        if (!store.upsert(result.adminGroup)) return false
        GroupAdminState.unbindMember(context, group.groupId, memberIdHex, crypto)
        distributeBundles(context, result, allContacts, newcomerId = null, newcomerContact = null, crypto = crypto)
        return true
    }

    // --- interní ---

    /** Rozešle balíky z [result] příslušným členům (kromě admina). Best-effort. */
    private fun distributeBundles(
        context: Context,
        result: GroupAdmin.Result,
        allContacts: List<Contact>,
        newcomerId: String?,
        newcomerContact: Contact?,
        crypto: StorageCrypto,
    ) {
        val byId = allContacts.associateBy { it.id }
        val groupId = result.adminGroup.groupId
        for (b in result.bundles) {
            val contact = if (b.memberIdHex == newcomerId) newcomerContact
            else GroupAdminState.contactForMember(context, groupId, b.memberIdHex, crypto)?.let { byId[it] }
            if (contact == null) continue // neznámý routing → přeskočit (member si vyžádá resend)
            val bundle = GroupBundle(b.payload, result.rosterBytesBase64, result.rosterSigBase64)
            RelaySync.sendGroupCtrl(context, contact, WireExt.GROUP_CTRL_BUNDLE, GroupBundle.encode(bundle))
        }
    }

    /** Pošle jednomu členovi balík s AKTUÁLNÍM stavem skupiny (pro idempotentní resend). */
    private fun sendGroupBundle(context: Context, contact: Contact, payload: GroupKeyPayload, group: Group) {
        val bundle = GroupBundle(payload, group.rosterBytesBase64, group.rosterSigBase64)
        RelaySync.sendGroupCtrl(context, contact, WireExt.GROUP_CTRL_BUNDLE, GroupBundle.encode(bundle))
    }

    /** Klíčový ukazatel na AKTUÁLNÍ GS/epochu skupiny (pro resend existujícímu členovi). */
    private fun currentPayload(group: Group, assignedMemberId: String?): GroupKeyPayload =
        GroupKeyPayload(
            group.groupId, group.groupEpoch, group.gsBase64,
            GroupCrypto.gsCommit(group.gsBase64, group.groupId, group.groupEpoch),
            group.adminPublicKeyBase64, assignedMemberId,
        )
}
