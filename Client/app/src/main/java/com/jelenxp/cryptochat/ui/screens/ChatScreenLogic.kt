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

    /** Pod touhle průhledností je paleta reakcí neviditelná a MUSÍ se odmontovat. */
    const val PICKER_ALPHA_EPSILON = 0.01f

    /**
     * Doba mizení palety reakcí (ms). Zároveň je to STROP, jak dlouho smí
     * neviditelná paleta polykat doteky - viz [reactionPickerMounted]. Proto
     * krátce a proto `tween`, ne pružina.
     */
    const val PICKER_FADE_MS = 120

    /**
     * Má být plovoucí paleta reakcí ještě SLOŽENÁ (jako `Popup`)?
     *
     * Popup se schválně drží namontovaný i po zavření, dokud nedojede fade ven -
     * jinak by paleta jen zmizela místo aby „odtála". Jenže `graphicsLayer
     * { alpha = 0f }` **nevypíná hit-testing**: dokud je Popup složený, je to
     * plnohodnotné okno, které polyká doteky na ploše NAD bublinou, tedy na
     * zprávě o řádek výš. Uživateli se to jeví tak, že klepnutí nefunguje a musí
     * klikat opakovaně.
     *
     * Proto se paleta odmontuje, jakmile je průhledná - a proto animace MUSÍ být
     * `tween` ([PICKER_FADE_MS]), ne pružina: pružina se k nule blíží
     * asymptoticky, takže by neviditelné okno žralo klepnutí neomezeně dlouho
     * (na pomalejším zařízení citelně - přesně tam se to projevilo).
     */
    fun reactionPickerMounted(wanted: Boolean, alpha: Float): Boolean =
        wanted || alpha > PICKER_ALPHA_EPSILON

    /**
     * Zprávy konverzace odpovídající hledanému výrazu [query]. Vytaženo z
     * composable schválně (pravidlo projektu) - filtr je čistá funkce, takže jde
     * otestovat bez Androidu.
     *
     * Prázdný (nebo jen mezery) dotaz vrátí VŠECHNY zprávy - hledání otevřené,
     * ale ještě nic nenapsáno. Hledá se v textu zprávy (u souboru je to název);
     * fotky bez textu se nikdy neshodují. Bez rozlišení velikosti písmen.
     */
    fun filterMessages(messages: List<ChatMessage>, query: String): List<ChatMessage> {
        val q = query.trim()
        if (q.isEmpty()) return messages
        // `filter` vrací každou zprávu nanejvýš jednou - i když v ní výraz je
        // vícekrát, ve výsledcích se NEzdvojí (zvýrazní se pak oba výskyty).
        // `ignoreCase` (ne lowercase()) drží stejnou logiku jako [highlightRanges].
        return messages.filter { it.text.contains(q, ignoreCase = true) }
    }

    /**
     * Rozsahy VŠECH výskytů [query] v [text] (bez ohledu na velikost písmen) -
     * pro bílé podbarvení nalezené části v bublině při hledání. Vytaženo
     * z composable schválně (pravidlo projektu): hledání všech výskytů je
     * netriviální a musí jít otestovat.
     *
     * Rozsahy jsou nepřekrývající (po nálezu se posune za jeho konec) a indexy
     * míří do PŮVODNÍHO textu - proto `indexOf(..., ignoreCase = true)`, ne
     * `lowercase()` (to může u některých znaků změnit délku a rozhodit indexy).
     * Prázdný dotaz nebo delší než text → prázdný seznam.
     */
    fun highlightRanges(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (from <= text.length - q.length) {
            val idx = text.indexOf(q, from, ignoreCase = true)
            if (idx < 0) break
            ranges.add(idx until idx + q.length)
            from = idx + q.length
        }
        return ranges
    }

    /**
     * Jeden řádek seznamu konverzace. Kromě zpráv nese i oddělovač dne a jednu
     * čáru „Nové zprávy". Vytaženo z composable schválně (pravidlo projektu):
     * skládání řádků je netriviální (přelom dne, pozice první nepřečtené) a musí
     * jít otestovat bez Androidu.
     */
    sealed interface ChatRow {
        /** Stabilní klíč pro `LazyColumn` (recyklace položek). */
        val key: String

        /**
         * Hlavička dne (číslo dne v lokální zóně; překlad na „Dnes/Včera/datum"
         * až v UI).
         *
         * [anchorId] = id PRVNÍ zprávy pod hlavičkou. Slouží jen ke [key] a MUSÍ
         * tam být: zprávy nechodí nutně v pořadí podle času (příchozí nesou čas
         * odesílatele → posun hodin mezi telefony; zpožděné doručení / catch-up
         * přes relay), takže se stejný den může v seznamu objevit NESOUVISLE
         * dvakrát. Kdyby byl klíč jen „day_$epochDay", obě hlavičky by ho sdílely
         * a `LazyColumn` by na duplicitní klíč spadl („Key was already used") ve
         * chvíli, kdy se druhá zarolováním dostane do měření - přesně ten pád při
         * scrollu nahoru. Kotvením na unikátní id první zprávy je klíč unikátní
         * i stabilní (append-only historie první zprávu skupiny nemění).
         */
        data class DayHeader(val epochDay: Long, val anchorId: String) : ChatRow {
            override val key: String get() = "day_${epochDay}_$anchorId"
        }

        /** Čára „Nové zprávy" nad první nepřečtenou. */
        data class UnreadDivider(val count: Int) : ChatRow {
            override val key: String get() = "unread_divider"
        }

        /** Běžná zpráva. */
        data class Msg(val message: ChatMessage) : ChatRow {
            override val key: String get() = message.id
        }
    }

    /**
     * Poskládá zprávy do řádků s oddělovači dní a jednou čárou „Nové zprávy".
     *
     * @param dayOf převede timestamp na číslo dne v LOKÁLNÍ zóně - volající ho dodá
     *   (`Instant…toLocalDate().toEpochDay()`), aby funkce zůstala čistá a
     *   testovatelná bez Androidu i bez závislosti na aktuální časové zóně.
     * @param unreadCount počet nepřečtených zpráv při otevření (0 = bez čáry). Čára
     *   se vloží nad **první nepřečtenou PŘÍCHOZÍ** zprávu = `unreadCount`-tou
     *   příchozí od konce (odchozí se nepočítají).
     */
    fun buildRows(
        messages: List<ChatMessage>,
        unreadCount: Int,
        dayOf: (Long) -> Long
    ): List<ChatRow> {
        if (messages.isEmpty()) return emptyList()
        // První nepřečtená = unreadCount-tá příchozí zpráva od konce. Když je
        // nepřečtených víc než příchozích v historii (dorazily a byly smazané),
        // čára se nevloží (firstUnreadId zůstane null) - lepší než ji dát špatně.
        val firstUnreadId: String? = if (unreadCount <= 0) null else {
            var seen = 0
            var found: String? = null
            for (i in messages.indices.reversed()) {
                if (!messages[i].outgoing) {
                    seen++
                    if (seen == unreadCount) { found = messages[i].id; break }
                }
            }
            found
        }
        val rows = ArrayList<ChatRow>(messages.size + 8)
        var lastDay = Long.MIN_VALUE
        for (m in messages) {
            val day = dayOf(m.timestamp)
            if (day != lastDay) {
                // Kotva = id téhle zprávy (první svého dne-bloku) → unikátní klíč
                // i při nesouvislém opakování dne, viz [ChatRow.DayHeader].
                rows.add(ChatRow.DayHeader(day, m.id))
                lastDay = day
            }
            // Čára jde POD hlavičku dne, těsně nad první nepřečtenou zprávu.
            if (m.id == firstUnreadId) rows.add(ChatRow.UnreadDivider(unreadCount))
            rows.add(ChatRow.Msg(m))
        }
        return rows
    }

    /** Popisek hlavičky dne (překlad na text až v UI, ať `dayLabel` zůstane čistý). */
    enum class DayLabel { TODAY, YESTERDAY, OLDER }

    /** „Dnes" / „Včera" / starší (podle rozdílu dní vůči dnešku). */
    fun dayLabel(epochDay: Long, todayEpochDay: Long): DayLabel = when (todayEpochDay - epochDay) {
        0L -> DayLabel.TODAY
        1L -> DayLabel.YESTERDAY
        else -> DayLabel.OLDER
    }

    /**
     * Je seznam konverzace „u dna"? Rozhoduje, jestli se má při změně obsahu nebo
     * výšky viewportu (dekódování fotky, přidání reakce, otevření klávesnice)
     * ZNOVU přirolovat dolů, nebo ne.
     *
     * Vytaženo z composable schválně: přesně tenhle výpočet stál za třemi UI bugy
     * (chat se neotevřel úplně dole u fotky; psaní odscrollované zprávy skočilo na
     * konec; reakce zaskočila konec pod vstupní lištu). Rozhodnutí „jsem dole"
     * musí jít otestovat bez Androidu, proto bere jen čísla z `LazyListState`.
     *
     * @param lastVisibleIndex index posledního VIDITELNÉHO prvku (`null` = seznam prázdný)
     * @param lastVisibleItemEnd spodní hrana posledního viditelného prvku (offset + size)
     * @param totalItems celkový počet prvků v seznamu
     * @param viewportEnd spodní hrana viewportu (`layoutInfo.viewportEndOffset`)
     * @param tolerancePx rezerva na zaokrouhlení; do téhle vzdálenosti od dna se
     *   pořád považujeme za „u dna"
     * @return `true` když je poslední prvek seznamu vidět celý (není co odrolovat)
     */
    fun isAtBottom(
        lastVisibleIndex: Int?,
        lastVisibleItemEnd: Int,
        totalItems: Int,
        viewportEnd: Int,
        tolerancePx: Int
    ): Boolean {
        // Prázdný seznam bereme jako „u dna" - není kam rolovat, a chceme, aby se
        // první příchozí zpráva ukázala.
        if (totalItems == 0 || lastVisibleIndex == null) return true
        // Poslední prvek musí být vidět jako poslední v seznamu…
        if (lastVisibleIndex < totalItems - 1) return false
        // …a jeho spodní hrana nesmí být pod dnem viewportu (s tolerancí). U velmi
        // vysoké poslední zprávy to platí až po odrolování na její konec.
        return lastVisibleItemEnd <= viewportEnd + tolerancePx
    }
}
