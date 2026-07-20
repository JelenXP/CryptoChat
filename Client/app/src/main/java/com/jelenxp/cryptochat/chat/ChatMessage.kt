package com.jelenxp.cryptochat.chat

/**
 * Jedna zpráva v lokální historii konverzace. Historie leží jen na zařízení
 * (šifrovaně, viz [ChatRepository]) - relay si žádné zprávy nedrží.
 *
 * [kind] rozlišuje text, fotku a obecný soubor (video, dokument…):
 *  - TEXT: obsah je v [text].
 *  - IMAGE: [mediaPath] je cesta k lokálnímu (dešifrovanému) obrázku, [text] prázdný.
 *  - FILE: [text] je název souboru, [mimeType] jeho typ a [mediaPath] cesta k němu
 *    (u přijímaného souboru je `null`, dokud se nesloží všechny kousky).
 */
data class ChatMessage(
    val id: String,
    val outgoing: Boolean,     // true = odeslaná mnou, false = přijatá
    val text: String,
    val timestamp: Long,       // epoch millis (čas odeslání)
    val status: Status,
    val kind: Kind = Kind.TEXT,
    val mediaPath: String? = null,
    val mimeType: String? = null
) {
    /** RECEIVING = přijímá se po kouscích (velký soubor), ještě není kompletní. */
    enum class Status { SENDING, SENT, FAILED, RECEIVED, RECEIVING }
    enum class Kind { TEXT, IMAGE, FILE }
}
