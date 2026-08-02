package com.jelenxp.cryptochat.chat

/**
 * Čistá logika: které PŘÍCHOZÍ skupinové zprávy jsou NOVÉ mezi dvěma snímky historie
 * (kolem jednoho pollu) — podklad pro notifikaci. Vytaženo ze smyčky služby, aby
 * šlo otestovat (pravidlo 2).
 *
 * **Klíč je `(odesílatel, msgId)`, ne jen `msgId`** — stejně jako dedup v
 * [GroupChatRepository.appendIfAbsent]. Různí odesílatelé smí legitimně sdílet
 * `msgId`; klíčování jen podle `msgId` by insiderovi, který znovupoužije cizí
 * `msgId`, umožnilo uloženou zprávu potlačit z notifikace (nález v3.2 A5).
 */
object GroupNotifyLogic {

    fun newIncoming(
        before: List<GroupChatMessage>,
        after: List<GroupChatMessage>,
    ): List<GroupChatMessage> {
        val seen = before.asSequence()
            .filter { !it.outgoing }
            .mapTo(HashSet()) { it.senderMemberIdHex to it.msgIdHex }
        // Systémové události (připojení/odebrání) se NEnotifikují — nejsou to zprávy.
        return after.filter { !it.outgoing && !it.isSystem && (it.senderMemberIdHex to it.msgIdHex) !in seen }
    }
}
