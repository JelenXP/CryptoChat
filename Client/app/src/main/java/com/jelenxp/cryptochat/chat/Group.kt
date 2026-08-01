package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util

/**
 * Perzistentní stav jedné skupiny (členství + klíče) na tomto zařízení. Ukládá ho
 * [GroupStore] šifrovaně přes [com.jelenxp.cryptochat.crypto.StorageCrypto].
 *
 * Zdrojem pravdy o SLOŽENÍ skupiny je **podepsaný roster** ([rosterBytesBase64] +
 * [rosterSigBase64]); [name]/[groupEpoch] jsou jen denormalizované pro rychlé
 * zobrazení. [adminPublicKeyBase64] je **pinnutý** adminův Ed25519 klíč (trust
 * anchor získaný při joinu přes SAS-ověřený 1:1 kanál) — roster se ověřuje proti
 * němu, NE proti klíči z rosteru samotného.
 *
 * `GS` ([gsBase64]) je aktuální skupinový klíč (rotuje jen při odebrání); jeho
 * závazek `gsCommit` leží uvnitř rosteru, takže adopce `GS` je vázaná na autoritu
 * admina (viz [GroupRoster.matchesGsCommit]).
 */
data class Group(
    val groupId: String,
    val name: String,
    val avatarPath: String?,
    val groupEpoch: Long,
    val gsBase64: String,
    val rosterBytesBase64: String,
    val rosterSigBase64: String,
    val adminPublicKeyBase64: String,
    val myMemberId: String,
    val adminMemberId: String,
    val amIAdmin: Boolean,
    val mySignPrivateKeyBase64: String,
    val mySignPublicKeyBase64: String,
    val mySealPrivateKeyBase64: String,
    val mySealPublicKeyBase64: String,
    /** Všechna kdy přidělená memberId (i odebraná) — nikdy se nerecyklují. */
    val usedMemberIds: Set<String>,
) {
    /** Rozparsovaný roster, nebo null když je uložený byte-obraz poškozený. */
    fun roster(): GroupRoster.Roster? = GroupRoster.decode(Base64Util.decode(rosterBytesBase64))

    /** Členové z rosteru (prázdné, když roster nejde přečíst). */
    fun members(): List<GroupRoster.Member> = roster()?.members ?: emptyList()

    /** Ostatní členové (bez sebe) — komu se fan-outuje / od koho se přijímá. */
    fun otherMembers(): List<GroupRoster.Member> = members().filter { it.memberIdHex != myMemberId }

    /** Ed25519 veřejný klíč člena podle memberId (pro ověření podpisu jeho zpráv). */
    fun memberSignKey(memberIdHex: String): String? =
        members().firstOrNull { it.memberIdHex == memberIdHex }?.ed25519PublicKeyBase64
}
