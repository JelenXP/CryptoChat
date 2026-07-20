package com.jelenxp.cryptochat.ui.screens

import com.jelenxp.cryptochat.chat.ChatMessage

/**
 * Čistá logika obrazovky konverzace - **vytažená z `@Composable`, aby šla
 * otestovat**.
 *
 * Pravidlo projektu (viz testovací politika v `CLAUDE.md`): netriviální
 * rozhodování nesmí zůstat uvnitř composable ani `LaunchedEffect`. Přesně tam
 * se dřív schoval nález v1.2-23 - výběr zprávy, která se mezitím smazala, zůstal
 * viset a ukázal panel bez akcí. Jako čistá funkce nad seznamem je to triviálně
 * testovatelné bez Androidu.
 */
object ChatScreenLogic {

    /**
     * Emoji nabízená na dlouhý stisk. Zdroj pravdy je TADY (ne v composable),
     * aby na ně šel napsat test: každé z nich musí projít [WireExt.isValidEmoji],
     * jinak by se dalo vybrat emoji, které `sealReaction` odmítne a reakce by se
     * tiše neodeslala (nález v1.2-15). Protokol jich unese libovolně, UI zatím
     * nabízí tyhle.
     */
    val QUICK_REACTIONS: List<String> = listOf("👍", "❤️", "😂", "😮", "😭", "🙏")

    /**
     * Vrátí zprávu, na kterou se odpovídá, jen pokud v seznamu pořád je.
     * Stejný důvod jako [survivingIds] - nechceme odpovídat na zmizelou zprávu.
     */
    fun survivingReply(messages: List<ChatMessage>, replyTo: ChatMessage?): ChatMessage? =
        replyTo?.takeIf { r -> messages.any { it.id == r.id } }

    /**
     * Vybrané zprávy, které v seznamu pořád jsou. Smazané/zmizelé se z výběru
     * vypustí - jinak by lišta výběru tvrdila víc, než kolik je co vybrat.
     */
    fun survivingIds(messages: List<ChatMessage>, selectedIds: Set<String>): Set<String> {
        if (selectedIds.isEmpty()) return selectedIds
        val present = messages.mapTo(HashSet(messages.size)) { it.id }
        return selectedIds.filterTo(HashSet()) { it in present }
    }

    /** Přepne členství id ve výběru (klepnutí na vybranou zprávu ji odznačí). */
    fun toggleSelection(selectedIds: Set<String>, id: String): Set<String> =
        if (id in selectedIds) selectedIds - id else selectedIds + id

    /**
     * Text ke zkopírování z vybraných zpráv, v pořadí, ve kterém jsou v [messages].
     * Prázdné (fotka/soubor bez textu) se přeskočí; víc zpráv se spojí novým řádkem.
     */
    fun copyText(messages: List<ChatMessage>, selectedIds: Set<String>): String =
        messages.filter { it.id in selectedIds && it.text.isNotBlank() }
            .joinToString("\n") { it.text }

    /**
     * Index zpráv podle sdíleného odkazu ([ChatMessage.wireRef]) - pro rychlé
     * dohledání citace u odpovědí. Zprávy bez odkazu se přeskočí.
     */
    fun wireRefIndex(messages: List<ChatMessage>): Map<String, ChatMessage> =
        messages.mapNotNull { m -> m.wireRef?.let { it to m } }.toMap()

    /** Výsledek dohledání citované zprávy. */
    data class Quote(
        /** Nalezená původní zpráva, nebo `null`. */
        val message: ChatMessage?,
        /** Odpověď odkaz nese, ale původní zpráva se nenašla (smazaná / stará). */
        val missing: Boolean
    )

    /**
     * Dohledá zprávu, na kterou [message] odpovídá.
     *
     * Rozlišuje tři stavy: není to odpověď (`missing = false`, `message = null`),
     * odpověď s nalezenou zprávou, a odpověď na zprávu, která už není
     * (`missing = true`) - tehdy UI ukáže „původní zpráva není dostupná".
     */
    fun resolveQuote(message: ChatMessage, index: Map<String, ChatMessage>): Quote {
        val ref = message.replyToWireId ?: return Quote(null, missing = false)
        val found = index[ref]
        return Quote(found, missing = found == null)
    }

    /**
     * Nová hodnota MOJÍ reakce po klepnutí na emoji: stejné emoji podruhé ji
     * zruší (`null`), jiné ji nahradí.
     */
    fun toggledReaction(mine: String?, tapped: String): String? =
        if (mine == tapped) null else tapped

    /** Výchozí emoji pro rychlou reakci (double-tap). */
    const val DEFAULT_REACTION = "👍"

    /**
     * Nová hodnota MOJÍ reakce po DVOJKLEPNUTÍ: když už jakoukoli reakci mám,
     * dvojklep ji sundá (`null`); jinak přidá [DEFAULT_REACTION]. Na rozdíl od
     * [toggledReaction] tady nezáleží na tom, KTERÉ emoji mám - dvojklep vždy
     * jen přepíná „mám reakci / nemám".
     */
    fun doubleTapReaction(mine: String?): String? =
        if (mine != null) null else DEFAULT_REACTION
}
