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

        val myMemberId = existing?.myMemberId ?: payload.assignedMemberIdHex ?: return ApplyResult.INVALID
        val mine = roster.members.firstOrNull { it.memberIdHex == myMemberId } ?: return ApplyResult.REMOVED
        // Roster musí odpovídat MÝM klíčům (admin použil moje pubkeys).
        if (mine.ed25519PublicKeyBase64 != mySign.publicKeyBase64 || mine.sealPublicKeyBase64 != mySeal.publicKeyBase64) return ApplyResult.INVALID

        return applied(store.upsert(buildGroup(payload, rosterBytesBase64, rosterSigBase64, roster, myMemberId, mySign, mySeal, existing)))
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
        mySign: GroupIdentity.SignKeyPair, mySeal: GroupIdentity.SealKeyPair, existing: Group?,
    ): Group = Group(
        groupId = payload.groupIdHex, name = roster.name, avatarPath = existing?.avatarPath, groupEpoch = payload.epoch,
        gsBase64 = payload.gsBase64, rosterBytesBase64 = rb, rosterSigBase64 = rsig,
        adminPublicKeyBase64 = payload.adminPublicKeyBase64, myMemberId = myMemberId, adminMemberId = roster.adminMemberIdHex,
        amIAdmin = myMemberId == roster.adminMemberIdHex,
        mySignPrivateKeyBase64 = existing?.mySignPrivateKeyBase64 ?: mySign.privateKeyBase64,
        mySignPublicKeyBase64 = existing?.mySignPublicKeyBase64 ?: mySign.publicKeyBase64,
        mySealPrivateKeyBase64 = existing?.mySealPrivateKeyBase64 ?: mySeal.privateKeyBase64,
        mySealPublicKeyBase64 = existing?.mySealPublicKeyBase64 ?: mySeal.publicKeyBase64,
        usedMemberIds = (existing?.usedMemberIds ?: emptySet()) + roster.members.map { it.memberIdHex },
    )

    private fun decodeValid(rosterBytesBase64: String): GroupRoster.Roster? {
        val roster = try { GroupRoster.decode(Base64Util.decode(rosterBytesBase64)) } catch (_: Exception) { null } ?: return null
        return if (GroupRoster.validate(roster)) roster else null
    }
}
