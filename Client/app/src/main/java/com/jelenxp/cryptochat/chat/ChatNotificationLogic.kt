package com.jelenxp.cryptochat.chat

/**
 * Čistá logika bohaté notifikace zprávy - **vytažená z [ChatNotifications], aby
 * šla otestovat** (viz testovací politika v `CLAUDE.md`). Samotné sestavení
 * `MessagingStyle` a akcí je Android a testuje se ručně; ROZHODNUTÍ, které zprávy
 * v notifikaci ukázat a jaký řádek u nich napsat, je tady.
 */
object ChatNotificationLogic {

    /**
     * Kolik nepřečtených zpráv nanejvýš vypsat do notifikace. Systém stejně delší
     * historii sbalí; strop chrání před sestavením stovek `MessagingStyle.Message`
     * u kontaktu s velkým nedoručeným nákladem.
     */
    const val MAX_UNSEEN = 8

    /**
     * Nepřečtené PŘÍCHOZÍ zprávy k zobrazení v notifikaci: posledních
     * [unreadCount] příchozích (odchozí se přeskočí), max [MAX_UNSEEN].
     *
     * `unreadCount` je počítadlo z [ChatRepository] (nuluje ho `markRead`), takže
     * poslední tolik příchozích ≈ ty, co uživatel ještě neviděl. Když by bylo 0
     * nebo záporné (závod s `markRead`), ukáže se aspoň poslední příchozí - jinak
     * by notifikace, kterou service posílá jen při `received > 0`, byla prázdná.
     */
    fun unseenIncoming(messages: List<ChatMessage>, unreadCount: Int): List<ChatMessage> {
        val incoming = messages.filter { !it.outgoing }
        if (incoming.isEmpty()) return emptyList()
        val n = unreadCount.coerceIn(1, MAX_UNSEEN)
        return incoming.takeLast(n)
    }

    /**
     * Zpráva, na kterou zamíří tlačítko „To se mi líbí" (👍) - POSLEDNÍ příchozí
     * s odkazem ([ChatMessage.wireRef]), na který jde reakci navěsit. `null`, když
     * žádná taková není (staré zprávy bez wireRef) - tehdy se tlačítko nepřidá.
     */
    fun likeTarget(unseen: List<ChatMessage>): ChatMessage? =
        unseen.lastOrNull { it.wireRef != null }

    /**
     * Text jednoho řádku notifikace. Médium bez textu dostane zástupný popisek
     * (fotka/soubor); prázdný text jiného druhu spadne na [fallback]. Fyzické
     * řetězce se předávají už přeložené (překlad zůstává v Androidu).
     */
    fun lineText(
        message: ChatMessage,
        photo: String,
        file: String,
        fallback: String
    ): String = when {
        message.text.isNotBlank() -> message.text
        message.kind == ChatMessage.Kind.IMAGE -> photo
        message.kind == ChatMessage.Kind.FILE -> file
        else -> fallback
    }
}
