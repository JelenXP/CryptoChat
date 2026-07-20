package com.jelenxp.cryptochat.chat

/**
 * Sloučení reakcí - **čistě funkčně, bez Androidu**.
 *
 * Je to vytažené z [ChatRepository] schválně: tam by šlo o kód za Android
 * Keystore, tedy neotestovatelný, a chyby v pořadí by se hledaly ručně. Tady
 * je to obyčejná funkce nad mapou, kterou pokrývá `ReactionMergeTest`.
 *
 * **Proč je to vůbec netriviální:** reakce nemusí dorazit v pořadí, ve kterém
 * vznikly. Blob může uvíznout v [BlobQuarantine] a přijít až po novějším.
 * Rozhoduje proto [ChatMessage.Reaction.timestamp], ne pořadí doručení.
 */
object ReactionMerge {

    /**
     * Nový stav reakcí zprávy, nebo `null` když se nic nemění (a nemá tedy
     * smysl zapisovat).
     *
     * [emoji] `null` znamená zrušení. Zrušení se **neukládá jako smazání**, ale
     * jako náhrobek s prázdným emoji - jinak by po něm nezbyla stopa, nebylo by
     * co porovnat s časem a opožděná reakce by tu zrušenou vzkřísila.
     */
    fun apply(
        current: Map<String, ChatMessage.Reaction>,
        reactor: String,
        emoji: String?,
        timestamp: Long
    ): Map<String, ChatMessage.Reaction>? {
        val existing = current[reactor]
        // Přísně starší záznam neplatí. Shodný čas projde, ať se nezaseknou
        // dvě akce ve stejné milisekundě.
        if (existing != null && existing.timestamp > timestamp) return null
        val next = ChatMessage.Reaction(emoji ?: "", timestamp)
        if (existing == next) return null
        return current.toMutableMap().also { it[reactor] = next }
    }
}
