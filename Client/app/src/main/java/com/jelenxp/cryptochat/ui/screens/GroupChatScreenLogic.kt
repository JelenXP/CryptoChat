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
        ChatSelectionLogic.survivingReply(messages, replyTo) { it.msgIdHex }

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

    /**
     * Smí se tahle zpráva UPRAVIT? Jen MOJE textová a ještě nesmazaná (kind==TEXT vylučuje
     * fotku i systémový řádek, outgoing vylučuje cizí). msgId je vždy (na rozdíl od 1:1
     * `wireRef`), takže se netestuje. (Jako 1:1 [ChatScreenLogic.canEdit].)
     */
    fun canEdit(message: GroupChatMessage): Boolean =
        message.outgoing && message.kind == GroupChatMessage.Kind.TEXT && !message.deleted

    /**
     * Smí se vybrané zprávy SMAZAT PRO VŠECHNY? Jen když jsou VŠECHNY moje a ještě
     * nesmazané. „Smazat u mě" jde vždy, tohle je navíc. Prázdný výběr = ne. (Jako 1:1.)
     */
    fun canDeleteForEveryone(messages: List<GroupChatMessage>): Boolean =
        messages.isNotEmpty() && messages.all { it.outgoing && !it.deleted }

    /**
     * Text ke zkopírování z vybraných zpráv, v pořadí ze [messages]. Prázdné (fotka bez
     * textu) se přeskočí; víc zpráv se spojí novým řádkem. (Jako 1:1 [ChatScreenLogic.copyText].)
     */
    fun copyText(messages: List<GroupChatMessage>, selectedIds: Set<String>): String =
        ChatSelectionLogic.copyText(messages, selectedIds, { it.msgIdHex }, { it.text })

    /**
     * Má režim úpravy PŘEŽÍT? True, jen když upravovaná zpráva ([editingId]) v seznamu
     * pořád je a NENÍ smazaná — jinak by se rozepsaný text uložil do náhrobku/neexistující
     * zprávy. null = neupravuje se → false. (Jako 1:1 [ChatScreenLogic.survivingEdit].)
     */
    fun survivingEdit(messages: List<GroupChatMessage>, editingId: String?): Boolean =
        ChatSelectionLogic.survivingEdit(messages, editingId, { it.msgIdHex }, { it.deleted })

    /** Vybrané zprávy, které v seznamu pořád jsou (zmizelé se z výběru vypustí). */
    fun survivingIds(messages: List<GroupChatMessage>, selectedIds: Set<String>): Set<String> =
        ChatSelectionLogic.survivingIds(messages, selectedIds) { it.msgIdHex }

    /** Přepne členství id ve výběru (klepnutí na vybranou zprávu ji odznačí). */
    fun toggleSelection(selectedIds: Set<String>, id: String): Set<String> =
        ChatSelectionLogic.toggleSelection(selectedIds, id)

    /**
     * Zprávy odpovídající hledání [query] (prázdný dotaz = všechny). Hledá v textu bez
     * ohledu na velikost písmen; fotky/náhrobky bez textu a systémové řádky se neshodují.
     * (Jako 1:1 [ChatScreenLogic.filterMessages].)
     */
    fun filterMessages(messages: List<GroupChatMessage>, query: String): List<GroupChatMessage> {
        val q = query.trim()
        if (q.isEmpty()) return messages
        return messages.filter { !it.isSystem && it.text.contains(q, ignoreCase = true) }
    }
}
