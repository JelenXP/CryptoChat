package com.jelenxp.cryptochat.chat

/**
 * Jedna zpráva ve skupinové konverzaci. Na rozdíl od 1:1 [ChatMessage] nese
 * [senderMemberIdHex] — kdo ve skupině zprávu poslal (`null` = já, odchozí).
 *
 * [msgIdHex] je stabilní ID z obálky ([GroupEnvelope.Result.Ok.msgIdHex]) a slouží
 * jako klíč deduplikace (resend/replay nesmí zprávu zobrazit dvakrát).
 */
data class GroupChatMessage(
    val msgIdHex: String,
    val senderMemberIdHex: String?,
    val text: String,
    val timestamp: Long,
    val status: Status,
    val kind: Kind = Kind.TEXT,
    val mediaPath: String? = null,
    /**
     * U ODCHOZÍ zprávy: memberId příjemců, kteří ještě NEpotvrdili doručení
     * (podepsanou doručenkou). Prázdné = doručeno všem → [Status.DELIVERED].
     */
    val pendingRecipients: Set<String> = emptySet(),
) {
    /** Odchozí = poslal jsem já. */
    val outgoing: Boolean get() = senderMemberIdHex == null

    enum class Status { SENDING, SENT, DELIVERED, FAILED }
    enum class Kind { TEXT, IMAGE }
}
