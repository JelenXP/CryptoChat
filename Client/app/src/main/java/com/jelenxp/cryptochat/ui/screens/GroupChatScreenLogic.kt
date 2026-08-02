package com.jelenxp.cryptochat.ui.screens

import com.jelenxp.cryptochat.chat.GroupChatMessage

/**
 * Čistá logika skupinové konverzace — vytažená z `@Composable` kvůli testovatelnosti
 * (pravidlo projektu, viz [ChatScreenLogic]). Dnes řeší dohledání citace u odpovědí;
 * záměrně STEJNÁ sémantika jako 1:1 [ChatScreenLogic] (parita chování).
 */
object GroupChatScreenLogic {

    /**
     * Index zpráv podle [GroupChatMessage.msgIdHex] pro rychlé dohledání citace.
     * Při kolizi `msgId` (různí odesílatelé, anti-cenzura) vyhrává POSLEDNÍ — stejně
     * jako 1:1 `wireRefIndex`; citace míří na reálné zprávy, kolize je adversariální.
     */
    fun msgIdIndex(messages: List<GroupChatMessage>): Map<String, GroupChatMessage> =
        messages.associateBy { it.msgIdHex }

    /**
     * Zpráva, na kterou se odpovídá, jen když v seznamu pořád je (jinak null) — ať
     * nepíšeme odpověď na zmizelou zprávu. Analogie 1:1 [ChatScreenLogic.survivingReply].
     */
    fun survivingReply(messages: List<GroupChatMessage>, replyTo: GroupChatMessage?): GroupChatMessage? =
        replyTo?.takeIf { r -> messages.any { it.msgIdHex == r.msgIdHex } }

    /** Výsledek dohledání citované zprávy (jako 1:1 [ChatScreenLogic.Quote]). */
    data class Quote(
        /** Nalezená původní zpráva, nebo `null`. */
        val message: GroupChatMessage?,
        /** Odpověď odkaz nese, ale původní zpráva se nenašla (dorazila dřív / smazaná). */
        val missing: Boolean,
    )

    /**
     * Dohledá zprávu, na kterou [message] odpovídá. Tři stavy jako 1:1: není to odpověď
     * (`missing=false`, `message=null`), nalezená, a odpověď na zmizelou zprávu
     * (`missing=true` → UI ukáže „není dostupná"). Systémový řádek („X se připojil")
     * není platný cíl citace, bere se jako nedostupný.
     */
    fun resolveQuote(message: GroupChatMessage, index: Map<String, GroupChatMessage>): Quote {
        val ref = message.replyToMsgIdHex ?: return Quote(null, missing = false)
        val found = index[ref]?.takeIf { !it.isSystem }
        return Quote(found, missing = found == null)
    }
}
