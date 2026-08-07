package com.jelenxp.cryptochat.ui.screens

/**
 * Sdílená čistá logika výběru / kopírování / „přežití" zpráv — STEJNÁ v 1:1
 * [ChatScreenLogic] i skupině [GroupChatScreenLogic]. Generická přes akcesory
 * (id / text / deleted), takže nezávisí na typu zprávy; oba logické objekty sem
 * delegují funkce, jejichž chování je IDENTICKÉ.
 *
 * Sem patří jen to, co je v 1:1 i skupině opravdu stejné. Naopak `canEdit` a
 * `canDeleteForEveryone` (1:1 navíc vyžaduje `wireRef`), `filterMessages`
 * (skupina vynechává systémové řádky) a `resolveQuote` (jiný klíč citace) se
 * mezi appkami liší, a tak zůstávají per-typ v příslušném logickém objektu.
 */
object ChatSelectionLogic {

    /** Přepne členství [id] ve výběru (klepnutí na vybranou zprávu ji odznačí). */
    fun toggleSelection(selectedIds: Set<String>, id: String): Set<String> =
        if (id in selectedIds) selectedIds - id else selectedIds + id

    /**
     * Vybrané zprávy, které v seznamu pořád jsou — zmizelé/smazané se z výběru
     * vypustí, jinak by lišta výběru tvrdila víc, než kolik je co vybrat.
     */
    fun <T> survivingIds(messages: List<T>, selectedIds: Set<String>, id: (T) -> String): Set<String> {
        if (selectedIds.isEmpty()) return selectedIds
        val present = messages.mapTo(HashSet(messages.size)) { id(it) }
        return selectedIds.filterTo(HashSet()) { it in present }
    }

    /**
     * Zpráva, na kterou se odpovídá, jen když v seznamu pořád je (jinak null) —
     * ať nepíšeme odpověď na zprávu, která mezitím zmizela.
     */
    fun <T> survivingReply(messages: List<T>, replyTo: T?, id: (T) -> String): T? {
        if (replyTo == null) return null
        val rid = id(replyTo)
        return if (messages.any { id(it) == rid }) replyTo else null
    }

    /**
     * Má režim úpravy PŘEŽÍT? True, jen když upravovaná zpráva ([editingId]) v
     * seznamu pořád je a NENÍ smazaná — jinak by se rozepsaný text uložil do
     * náhrobku / neexistující zprávy. [editingId] == null (neupravuje se) → false.
     */
    fun <T> survivingEdit(messages: List<T>, editingId: String?, id: (T) -> String, deleted: (T) -> Boolean): Boolean =
        editingId != null && messages.any { id(it) == editingId && !deleted(it) }

    /**
     * Text ke zkopírování z vybraných zpráv, v pořadí ze [messages]. Prázdné
     * (fotka/soubor bez textu) se přeskočí; víc zpráv se spojí novým řádkem.
     */
    fun <T> copyText(messages: List<T>, selectedIds: Set<String>, id: (T) -> String, text: (T) -> String): String =
        messages.filter { id(it) in selectedIds && text(it).isNotBlank() }
            .joinToString("\n") { text(it) }
}
