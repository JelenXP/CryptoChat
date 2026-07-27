package com.jelenxp.cryptochat.chat

/**
 * Sloučení úprav a mazání zpráv - **čistě funkčně, bez Androidu** (stejný princip
 * jako [ReactionMerge]).
 *
 * Vytaženo z [ChatRepository] schválně: tam by šlo o kód za Android Keystore,
 * tedy neotestovatelný, a chyby v pořadí by se hledaly ručně. Tady je to
 * obyčejná funkce nad zprávou, kterou pokrývá `MessageMutationMergeTest`.
 *
 * **Proč je to netriviální:** úpravy a smazání nemusí dorazit v pořadí, ve
 * kterém vznikly - blob může uvíznout v [BlobQuarantine] a přijít až po
 * novějším. A **smazání je TERMINÁLNÍ**: jakmile je ze zprávy náhrobek, žádná
 * opožděná úprava (ani reakce) ji nesmí vzkřísit zpět na obsah.
 */
object MessageMutationMerge {

    /**
     * Zpráva po úpravě textu, nebo `null` když se nic nemění (a nemá tedy smysl
     * zapisovat).
     *
     * Pravidla:
     *  - **Smazanou zprávu úprava NEmění** - smazání je terminální.
     *  - **Starší úprava neplatí:** když zpráva už nese novější
     *    [ChatMessage.editedAt], opožděná úprava se zahodí. Shodný čas projde,
     *    ať se nezaseknou dvě úpravy ve stejné milisekundě.
     *  - Shodný text i čas = beze změny.
     */
    fun applyEdit(message: ChatMessage, newText: String, timestamp: Long): ChatMessage? {
        if (message.deleted) return null
        val edited = message.editedAt
        if (edited != null && edited > timestamp) return null
        if (message.text == newText && message.editedAt == timestamp) return null
        return message.copy(text = newText, editedAt = timestamp)
    }

    /**
     * Zpráva po smazání (náhrobek), nebo `null` když už náhrobkem je (idempotence -
     * opakované smazání nic nemění).
     *
     * Vyprázdní text, reakce, značku úpravy i odkaz na média; případný soubor na
     * disku uklidí volající PO zápisu (má na něj původní odkaz). Smazání je
     * **bezpodmínečné a terminální** - proto se čas neporovnává: odesílatel maže
     * jako POSLEDNÍ akci (po smazání už zprávu upravit nejde), takže žádná
     * platná úprava nemůže být novější než smazání.
     */
    fun applyDelete(message: ChatMessage): ChatMessage? {
        if (message.deleted) return null
        return message.copy(
            deleted = true,
            text = "",
            editedAt = null,
            mediaPath = null,
            reactions = emptyMap()
        )
    }
}
