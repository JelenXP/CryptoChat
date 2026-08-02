package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto

/**
 * Systémové události členství v chatu (kdo se připojil / byl odebrán) — jako
 * „dnes/včera" oddělovače. Vzniknou LOKÁLNĚ z diffu rosteru (nejdou po drátě):
 * kdykoli se na tomhle zařízení změní složení skupiny, doplní se do historie
 * řádek. Text nese už ROZŘEŠENÉ jméno (lokální kontakt má přednost), takže se
 * nemusí překládat při vykreslení.
 *
 * Volá se z každého místa, kde se roster mění: admin ([GroupActions.onPubkeysReceived],
 * [GroupActions.removeMember]) i člen ([GroupCtrlReceiver] po adopci balíku). Dedup
 * přes deterministický `msgId` (`sys:<typ>:<memberId>:<epocha>`) zajistí, že se
 * událost nezapíše dvakrát.
 */
object GroupSystemEvents {

    /** Zapíše řádky za přechod [oldGroup] → [newGroup]. `oldGroup == null` = bootstrap (poprvé). */
    fun recordRosterChange(context: Context, oldGroup: Group?, newGroup: Group, crypto: StorageCrypto = KeystoreStorageCrypto) {
        val repo = GroupChatRepository(context, crypto)
        val gid = newGroup.groupId
        val myId = newGroup.myMemberId
        val epoch = newGroup.groupEpoch
        val now = System.currentTimeMillis()

        if (oldGroup == null) {
            // Poprvé adoptuji skupinu → jen „Připojili jste se" (ne backlog všech členů).
            append(repo, gid, "sys:join:$myId:$epoch", myId, "", GroupChatMessage.Kind.SYSTEM_JOIN, now)
            return
        }

        val oldIds = oldGroup.members().mapTo(HashSet()) { it.memberIdHex }
        val newIds = newGroup.members().mapTo(HashSet()) { it.memberIdHex }

        // Noví členové (kromě mě) → „X se připojil".
        val newNames by lazy { GroupMemberNames.resolvedNames(context, newGroup, crypto) }
        for (m in newGroup.members()) {
            if (m.memberIdHex !in oldIds && m.memberIdHex != myId) {
                append(repo, gid, "sys:join:${m.memberIdHex}:$epoch", m.memberIdHex, newNames[m.memberIdHex] ?: m.displayName, GroupChatMessage.Kind.SYSTEM_JOIN, now)
            }
        }
        // Odebraní členové (byli, už nejsou; kromě mě) → „X byl odebrán".
        val oldNames by lazy { GroupMemberNames.resolvedNames(context, oldGroup, crypto) }
        for (m in oldGroup.members()) {
            if (m.memberIdHex !in newIds && m.memberIdHex != myId) {
                append(repo, gid, "sys:leave:${m.memberIdHex}:$epoch", m.memberIdHex, oldNames[m.memberIdHex] ?: m.displayName, GroupChatMessage.Kind.SYSTEM_LEAVE, now)
            }
        }
    }

    private fun append(repo: GroupChatRepository, groupId: String, msgId: String, subjectMemberId: String, name: String, kind: GroupChatMessage.Kind, now: Long) {
        repo.appendIfAbsent(
            groupId,
            GroupChatMessage(msgIdHex = msgId, senderMemberIdHex = subjectMemberId, text = name, timestamp = now, status = GroupChatMessage.Status.SENT, kind = kind),
        )
    }
}
