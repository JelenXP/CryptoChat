package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util

/**
 * Strana ČLENA: adopce řídicích artefaktů (klíčový balík + podepsaný roster) do
 * lokálního stavu skupiny ([GroupStore]). Sem se sbíhají všechny pojistky návrhu:
 *  - autorita admina (roster ověřen PINNUTÝM adminovým klíčem),
 *  - `GS` svázán s autoritou přes `gsCommit` (nikdo nepodstrčí vlastní GS),
 *  - **monotónní epocha** (rollback/replay starého stavu se odmítne),
 *  - „jsem v rosteru" (odebraný člen adopci neprovede),
 *  - roster odpovídá MÝM klíčům (jinak podvržený roster).
 */
object GroupControl {

    enum class ApplyResult {
        APPLIED,   // stav přijat a uložen
        STALE,     // starší/stejná epocha než mám → ignorovat
        NEEDS_KEY, // roster nové epochy, ale GS se změnil (rotace) → čekej na klíčový balík
        REMOVED,   // nejsem v novém rosteru → byl jsem odebrán
        INVALID,   // podvržený/poškozený artefakt
        FAILED,    // nepodařilo se uložit (zápis)
    }

    /**
     * Adoptuje klíčový balík ([GroupKeyPayload], GS) + podepsaný roster. Pro nováčka
     * (bootstrap) i pro zbývajícího člena po rotaci GS (odebrání). `mySign`/`mySeal`
     * jsou MOJE skupinové klíče (vygenerované při joinu).
     */
    fun applyBundle(
        payload: GroupKeyPayload,
        rosterBytesBase64: String,
        rosterSigBase64: String,
        mySign: GroupIdentity.SignKeyPair,
        mySeal: GroupIdentity.SealKeyPair,
        store: GroupStore,
    ): ApplyResult {
        val roster = decodeValid(rosterBytesBase64) ?: return ApplyResult.INVALID
        if (!payload.isSelfConsistent()) return ApplyResult.INVALID
        if (roster.groupIdHex != payload.groupIdHex || roster.groupEpoch != payload.epoch) return ApplyResult.INVALID
        // Autorita admina + vazba GS na ni.
        if (!GroupRoster.verify(roster, rosterSigBase64, payload.adminPublicKeyBase64)) return ApplyResult.INVALID
        if (!GroupRoster.matchesGsCommit(roster, payload.gsBase64)) return ApplyResult.INVALID
        if (GroupRoster.adminPublicKey(roster) != payload.adminPublicKeyBase64) return ApplyResult.INVALID

        val existing = store.getGroup(payload.groupIdHex)
        // Pinnutý admin klíč se nesmí změnit (jinak podvržení admina).
        if (existing != null && existing.adminPublicKeyBase64 != payload.adminPublicKeyBase64) return ApplyResult.INVALID
        if (existing != null && payload.epoch <= existing.groupEpoch) return ApplyResult.STALE

        // Kdo jsem v novém rosteru? Přednostně STÁVAJÍCÍ identita; pokud tam už není,
        // ale dorazil bootstrap/re-join balík s přiděleným memberId, který v rosteru
        // JE, vezmi ten — řeší re-add odebraného člena s novým memberId (#2). Jinak
        // jsem odebrán.
        val existingId = existing?.myMemberId?.takeIf { id -> roster.members.any { it.memberIdHex == id } }
        val assignedId = payload.assignedMemberIdHex?.takeIf { id -> roster.members.any { it.memberIdHex == id } }
        val reuseStoredKeys = existingId != null
        val myMemberId = existingId ?: assignedId ?: return ApplyResult.REMOVED

        val mine = roster.members.first { it.memberIdHex == myMemberId }
        // Roster musí sedět na MOJE klíče (stávající uložené při update, nebo čerstvé z joinu).
        val signPub = if (reuseStoredKeys) existing!!.mySignPublicKeyBase64 else mySign.publicKeyBase64
        val sealPub = if (reuseStoredKeys) existing!!.mySealPublicKeyBase64 else mySeal.publicKeyBase64
        if (mine.ed25519PublicKeyBase64 != signPub || mine.sealPublicKeyBase64 != sealPub) return ApplyResult.INVALID

        val group = buildGroup(payload, rosterBytesBase64, rosterSigBase64, roster, myMemberId, reuseStoredKeys, mySign, mySeal, existing)
        return applied(store.upsert(group))
    }

    /**
     * Adoptuje SAMOTNÝ roster (bez GS) — pro existující členy při PŘIDÁNÍ (GS se
     * nemění). Když se `gsCommit` neshoduje s mým GS, GS se změnil (rotace) →
     * [ApplyResult.NEEDS_KEY], počkej na klíčový balík.
     */
    fun applyRosterOnly(rosterBytesBase64: String, rosterSigBase64: String, store: GroupStore): ApplyResult {
        val roster = decodeValid(rosterBytesBase64) ?: return ApplyResult.INVALID
        val existing = store.getGroup(roster.groupIdHex) ?: return ApplyResult.INVALID // roster sám nováčka nezaloží (chybí GS)
        if (!GroupRoster.verify(roster, rosterSigBase64, existing.adminPublicKeyBase64)) return ApplyResult.INVALID
        if (GroupRoster.adminPublicKey(roster) != existing.adminPublicKeyBase64) return ApplyResult.INVALID
        if (roster.groupEpoch <= existing.groupEpoch) return ApplyResult.STALE
        if (roster.members.none { it.memberIdHex == existing.myMemberId }) return ApplyResult.REMOVED
        // GS beze změny? (přidání). Pokud ne, je to rotace → potřebuju klíčový balík.
        if (!GroupRoster.matchesGsCommit(roster, existing.gsBase64)) return ApplyResult.NEEDS_KEY

        val updated = existing.copy(
            name = roster.name, groupEpoch = roster.groupEpoch,
            rosterBytesBase64 = rosterBytesBase64, rosterSigBase64 = rosterSigBase64,
            adminMemberId = roster.adminMemberIdHex,
            usedMemberIds = existing.usedMemberIds + roster.members.map { it.memberIdHex },
        )
        return applied(store.upsert(updated))
    }

    private fun applied(ok: Boolean) = if (ok) ApplyResult.APPLIED else ApplyResult.FAILED

    private fun buildGroup(
        payload: GroupKeyPayload, rb: String, rsig: String, roster: GroupRoster.Roster, myMemberId: String,
        reuseStoredKeys: Boolean, mySign: GroupIdentity.SignKeyPair, mySeal: GroupIdentity.SealKeyPair, existing: Group?,
    ): Group = Group(
        groupId = payload.groupIdHex, name = roster.name, avatarPath = existing?.avatarPath, groupEpoch = payload.epoch,
        gsBase64 = payload.gsBase64, rosterBytesBase64 = rb, rosterSigBase64 = rsig,
        adminPublicKeyBase64 = payload.adminPublicKeyBase64, myMemberId = myMemberId, adminMemberId = roster.adminMemberIdHex,
        amIAdmin = myMemberId == roster.adminMemberIdHex,
        mySignPrivateKeyBase64 = if (reuseStoredKeys) existing!!.mySignPrivateKeyBase64 else mySign.privateKeyBase64,
        mySignPublicKeyBase64 = if (reuseStoredKeys) existing!!.mySignPublicKeyBase64 else mySign.publicKeyBase64,
        mySealPrivateKeyBase64 = if (reuseStoredKeys) existing!!.mySealPrivateKeyBase64 else mySeal.privateKeyBase64,
        mySealPublicKeyBase64 = if (reuseStoredKeys) existing!!.mySealPublicKeyBase64 else mySeal.publicKeyBase64,
        usedMemberIds = (existing?.usedMemberIds ?: emptySet()) + roster.members.map { it.memberIdHex },
    )

    private fun decodeValid(rosterBytesBase64: String): GroupRoster.Roster? {
        val roster = try { GroupRoster.decode(Base64Util.decode(rosterBytesBase64)) } catch (_: Exception) { null } ?: return null
        return if (GroupRoster.validate(roster)) roster else null
    }
}
