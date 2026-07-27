package com.jelenxp.cryptochat.chat

/**
 * Úpravy a smazání, které dorazily dřív než zpráva, ke které patří.
 *
 * Analogie [PendingReactions] pro edit/delete: relay doručuje po dávkách a
 * zpráva se může opozdit (třeba fotka, co skončila v [BlobQuarantine]). Úprava
 * nebo smazání na ni ale mezitím projde a potvrdí se. Bez odložení by se tiše
 * zahodily a u zprávy by zůstal starý text, případně by nebyla smazaná.
 *
 * Drží se **jen v paměti** a záměrně - stejný důvod jako u [PendingReactions]:
 * ztratit odloženou operaci při restartu procesu je přijatelné, sahat kvůli ní
 * na Keystore by byla nepoměrná režie. Fronta má strop, aby vadný protějšek
 * nemohl nafouknout paměť.
 *
 * **Pořadí operací nevadí:** [MessageMutationMerge] rozhoduje o úpravě podle
 * času a smazání je terminální, takže se ke stejnému cíli klidně odloží úprava
 * i smazání a výsledek je stejný, ať se použijí v jakémkoli pořadí.
 */
object PendingMutations {

    /** Kolik odložených operací nejvýš na kontakt. Nejstarší se zahazují. */
    private const val MAX_PER_CONTACT = 50

    /** Odložená operace na cílovou zprávu. */
    sealed interface Op {
        val timestamp: Long
        data class Edit(val newText: String, override val timestamp: Long) : Op
        data class Delete(override val timestamp: Long) : Op
    }

    private class Item(val wireRef: String, val op: Op)

    private val lock = Any()
    private val pending = HashMap<String, ArrayDeque<Item>>()

    /** Odloží operaci, jejíž cílová zpráva zatím není v historii. */
    fun remember(contactId: String, wireRef: String, op: Op) = synchronized(lock) {
        val queue = pending.getOrPut(contactId) { ArrayDeque() }
        queue.addLast(Item(wireRef, op))
        while (queue.size > MAX_PER_CONTACT) queue.removeFirst()
    }

    /**
     * Zkusí odložené operace použít. Volej po přidání zprávy do historie - teprve
     * tehdy může cíl existovat.
     *
     * Operace, jejichž cíl pořád chybí (nebo selhaly na zápisu), zůstávají
     * odložené - další pokus přijde s další zprávou.
     */
    fun applyAll(
        contactId: String,
        apply: (wireRef: String, op: Op) -> Boolean
    ) {
        val items = synchronized(lock) {
            val queue = pending[contactId] ?: return
            if (queue.isEmpty()) return
            queue.toList().also { queue.clear() }
        }
        val unresolved = ArrayList<Item>()
        for (item in items) {
            val applied = runCatching { apply(item.wireRef, item.op) }.getOrDefault(false)
            if (!applied) unresolved.add(item)
        }
        if (unresolved.isEmpty()) return
        synchronized(lock) {
            val queue = pending.getOrPut(contactId) { ArrayDeque() }
            // Nevyřešené vrať na začátek fronty (jsou starší než cokoli, co
            // mezitím přišlo); při přetečení obětuj právě je.
            unresolved.asReversed().forEach { queue.addFirst(it) }
            while (queue.size > MAX_PER_CONTACT) queue.removeFirst()
        }
    }

    /** Zapomene odložené operace kontaktu (při jeho smazání). */
    fun clear(contactId: String) = synchronized(lock) {
        pending.remove(contactId)
        Unit
    }
}
