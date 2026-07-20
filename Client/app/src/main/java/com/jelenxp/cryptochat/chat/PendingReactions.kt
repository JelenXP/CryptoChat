package com.jelenxp.cryptochat.chat

/**
 * Reakce, které dorazily dřív než zpráva, ke které patří.
 *
 * **Proč to musí existovat:** relay doručuje po dávkách a zpráva se může
 * opozdit - třeba když se předtím nepodařilo uložit její fotku a skončila
 * v [BlobQuarantine]. Reakce na ni ale mezitím projde a potvrdí se. Bez
 * odložení by se tiše zahodila a u zprávy by pak nikdy nic nebylo.
 *
 * Drží se **jen v paměti** a záměrně: reakce je ozdoba, ne obsah. Ztratit ji
 * při restartu procesu je přijatelné, zatímco kvůli ní sahat na disk (a šifrovat
 * přes Keystore) by byla nepoměrná režie. Z téhož důvodu se neodkládá donekonečna
 * - fronta má strop, aby vadný protějšek nemohl nafouknout paměť.
 */
object PendingReactions {

    /** Kolik odložených reakcí nejvýš na kontakt. Nejstarší se zahazují. */
    private const val MAX_PER_CONTACT = 50

    private class Item(
        val wireRef: String,
        val reactor: String,
        val emoji: String?,
        val timestamp: Long
    )

    private val lock = Any()
    private val pending = HashMap<String, ArrayDeque<Item>>()

    /** Odloží reakci, jejíž cílová zpráva zatím není v historii. */
    fun remember(
        contactId: String,
        wireRef: String,
        reactor: String,
        emoji: String?,
        timestamp: Long
    ) = synchronized(lock) {
        val queue = pending.getOrPut(contactId) { ArrayDeque() }
        // Ke stejnému cíli od stejného autora drž jen tu nejnovější.
        queue.removeAll { it.wireRef == wireRef && it.reactor == reactor }
        queue.addLast(Item(wireRef, reactor, emoji, timestamp))
        while (queue.size > MAX_PER_CONTACT) queue.removeFirst()
    }

    /**
     * Zkusí odložené reakce použít. Volej po přidání zprávy do historie -
     * teprve tehdy může cíl existovat.
     *
     * Reakce, jejichž cíl pořád chybí, zůstávají odložené. Ty, které selhaly na
     * zápisu, se taky nechávají - další pokus přijde s další zprávou.
     */
    fun applyAll(
        contactId: String,
        apply: (wireRef: String, reactor: String, emoji: String?, timestamp: Long) -> Boolean
    ) {
        val items = synchronized(lock) {
            val queue = pending[contactId] ?: return
            if (queue.isEmpty()) return
            queue.toList().also { queue.clear() }
        }
        val unresolved = ArrayList<Item>()
        for (item in items) {
            val applied = runCatching {
                apply(item.wireRef, item.reactor, item.emoji, item.timestamp)
            }.getOrDefault(false)
            if (!applied) unresolved.add(item)
        }
        if (unresolved.isEmpty()) return
        synchronized(lock) {
            val queue = pending.getOrPut(contactId) { ArrayDeque() }
            // Nevyřešené vrať na začátek fronty (jsou starší než cokoli, co
            // mezitím přišlo), ale při přetečení obětuj právě je - nejnovější
            // reakce mají větší šanci, že se jejich cíl ještě objeví.
            unresolved.asReversed().forEach { queue.addFirst(it) }
            while (queue.size > MAX_PER_CONTACT) queue.removeFirst()
        }
    }

    /** Zapomene odložené reakce kontaktu (při jeho smazání). */
    fun clear(contactId: String) = synchronized(lock) {
        pending.remove(contactId)
        Unit
    }
}
