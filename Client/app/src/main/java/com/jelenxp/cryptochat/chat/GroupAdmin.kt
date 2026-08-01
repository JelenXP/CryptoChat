package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util
import java.security.SecureRandom

/**
 * Operace ADMINA nad členstvím (jediná autorita, viz `GROUP_CHAT_PLAN.md` D2).
 * Produkuje **podepsaný roster** + **klíčové balíky** ([GroupKeyPayload]) k doručení
 * členům; samotné doručení (1:1 pro GS, group kanál pro roster) je věc fáze 6.
 *
 * Invarianty:
 *  - **Přidání NErotuje GS** (jen bump epochy + nový roster); existující členové jen
 *    adoptují roster, GS už mají. Nováček dostane balík s AKTUÁLNÍM GS.
 *  - **Odebrání ROTUJE GS** (nový GS, bump epochy); zbývající dostanou balík s NOVÝM
 *    GS přes 1:1 (odebraný na něj nedosáhne). memberId se NErecyklují.
 *  - Roster vždy nese `gsCommit(GS, groupId, epoch)`, čímž je GS svázán s podpisem admina.
 */
object GroupAdmin {

    /** Veřejné klíče člena získané při join handshake (přes 1:1). */
    data class MemberSpec(val displayName: String, val ed25519PublicKeyBase64: String, val sealPublicKeyBase64: String)

    /** Klíčový balík pro jednoho člena (payload = GS ukazatel; roster je společný ve výsledku). */
    data class Bundle(val memberIdHex: String, val payload: GroupKeyPayload)

    data class Result(
        val adminGroup: Group,
        val rosterBytesBase64: String,
        val rosterSigBase64: String,
        /** Komu poslat GS balík (u create: všem; u add: nováčkovi; u remove: zbývajícím). */
        val bundles: List<Bundle>,
    )

    private fun randomGs(): String = Base64Util.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun freshMemberId(used: Set<String>): String {
        var id = GroupCrypto.randomMemberId()
        while (id in used) id = GroupCrypto.randomMemberId()
        return id
    }

    /** Vytvoří skupinu. Admin si vygeneruje vlastní Ed25519+ML-KEM klíče. */
    fun create(groupName: String, adminName: String, initialMembers: List<MemberSpec>): Result {
        require(initialMembers.size + 1 <= GroupRoster.MAX_GROUP_MEMBERS) { "Skupina má strop ${GroupRoster.MAX_GROUP_MEMBERS} členů." }
        val groupId = GroupCrypto.randomGroupId()
        val gs = randomGs()
        val epoch = 0L
        val adminSign = GroupIdentity.generateSignKeyPair()
        val adminSeal = GroupIdentity.generateSealKeyPair()
        val adminId = GroupCrypto.randomMemberId()

        val used = HashSet<String>().apply { add(adminId) }
        val members = ArrayList<GroupRoster.Member>()
        members.add(GroupRoster.Member(adminId, adminName, adminSign.publicKeyBase64, adminSeal.publicKeyBase64))
        val assigned = ArrayList<Pair<String, MemberSpec>>()
        for (m in initialMembers) {
            val id = freshMemberId(used); used.add(id)
            members.add(GroupRoster.Member(id, m.displayName, m.ed25519PublicKeyBase64, m.sealPublicKeyBase64))
            assigned.add(id to m)
        }

        val roster = GroupRoster.Roster(groupId, epoch, groupName, adminId, GroupCrypto.gsCommit(gs, groupId, epoch), members)
        val rb = Base64Util.encode(GroupRoster.encode(roster))
        val rsig = GroupRoster.sign(roster, adminSign.privateKeyBase64)

        val adminGroup = Group(
            groupId = groupId, name = groupName, avatarPath = null, groupEpoch = epoch, gsBase64 = gs,
            rosterBytesBase64 = rb, rosterSigBase64 = rsig, adminPublicKeyBase64 = adminSign.publicKeyBase64,
            myMemberId = adminId, adminMemberId = adminId, amIAdmin = true,
            mySignPrivateKeyBase64 = adminSign.privateKeyBase64, mySignPublicKeyBase64 = adminSign.publicKeyBase64,
            mySealPrivateKeyBase64 = adminSeal.privateKeyBase64, mySealPublicKeyBase64 = adminSeal.publicKeyBase64,
            usedMemberIds = used,
        )
        val bundles = assigned.map { (id, _) -> Bundle(id, payload(groupId, epoch, gs, adminSign.publicKeyBase64, id)) }
        return Result(adminGroup, rb, rsig, bundles)
    }

    /** Přidá člena BEZ rotace GS (bump epochy + nový roster). Balík dostane jen nováček. */
    fun addMember(group: Group, newMember: MemberSpec): Result {
        require(group.amIAdmin) { "Členství smí měnit jen admin." }
        val roster = group.roster() ?: error("Poškozený roster.")
        require(roster.members.size + 1 <= GroupRoster.MAX_GROUP_MEMBERS) { "Skupina má strop ${GroupRoster.MAX_GROUP_MEMBERS} členů." }
        val newEpoch = group.groupEpoch + 1
        val newId = freshMemberId(group.usedMemberIds)
        val members = roster.members + GroupRoster.Member(newId, newMember.displayName, newMember.ed25519PublicKeyBase64, newMember.sealPublicKeyBase64)
        val newRoster = roster.copy(groupEpoch = newEpoch, gsCommit = GroupCrypto.gsCommit(group.gsBase64, group.groupId, newEpoch), members = members)
        val rb = Base64Util.encode(GroupRoster.encode(newRoster))
        val rsig = GroupRoster.sign(newRoster, group.mySignPrivateKeyBase64)
        val newGroup = group.copy(groupEpoch = newEpoch, rosterBytesBase64 = rb, rosterSigBase64 = rsig, usedMemberIds = group.usedMemberIds + newId)
        // Nováček dostane AKTUÁLNÍ GS (nerotuje se). Existující jen adoptují roster (mají GS).
        val bundle = Bundle(newId, payload(group.groupId, newEpoch, group.gsBase64, group.mySignPublicKeyBase64, newId))
        return Result(newGroup, rb, rsig, listOf(bundle))
    }

    /** Odebere člena S rotací GS (nový GS, bump epochy). Balík s NOVÝM GS dostanou zbývající. */
    fun removeMember(group: Group, memberIdToRemove: String): Result {
        require(group.amIAdmin) { "Členství smí měnit jen admin." }
        require(memberIdToRemove != group.myMemberId) { "Admin nemůže odebrat sám sebe." }
        val roster = group.roster() ?: error("Poškozený roster.")
        val remaining = roster.members.filter { it.memberIdHex != memberIdToRemove }
        require(remaining.size < roster.members.size) { "Člen ve skupině není." }
        val newEpoch = group.groupEpoch + 1
        val newGs = randomGs()
        val newRoster = roster.copy(groupEpoch = newEpoch, gsCommit = GroupCrypto.gsCommit(newGs, group.groupId, newEpoch), members = remaining)
        val rb = Base64Util.encode(GroupRoster.encode(newRoster))
        val rsig = GroupRoster.sign(newRoster, group.mySignPrivateKeyBase64)
        // GS rotuje; usedMemberIds se NEZMENŠUJE (odebrané memberId se nerecykluje).
        val newGroup = group.copy(groupEpoch = newEpoch, gsBase64 = newGs, rosterBytesBase64 = rb, rosterSigBase64 = rsig)
        val bundles = remaining.filter { it.memberIdHex != group.myMemberId }
            .map { Bundle(it.memberIdHex, payload(group.groupId, newEpoch, newGs, group.mySignPublicKeyBase64, null)) }
        return Result(newGroup, rb, rsig, bundles)
    }

    private fun payload(groupId: String, epoch: Long, gs: String, adminPub: String, assignedMemberId: String?) =
        GroupKeyPayload(groupId, epoch, gs, GroupCrypto.gsCommit(gs, groupId, epoch), adminPub, assignedMemberId)
}
