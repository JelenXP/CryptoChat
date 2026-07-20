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
    val mimeType: String? = null,
    /**
     * Stabilní ID zprávy **sdílené oběma zařízeními** (hex 16 bajtů), na rozdíl
     * od [id], které si každá strana generuje sama. Veze se v traileru obálky
     * (viz [WireExt]), takže na něj půjde odkazovat z odpovědí a reakcí.
     *
     * `null` u zpráv z doby před minorem 2 a u souborů, kde stejnou roli plní
     * už `fileId` (to je shodné na obou stranách samo o sobě).
     *
     * **Držet odděleně od [id] je záměr:** hodnotu volí protějšek, takže se
     * nesmí míchat s naším lokálním klíčem, podle kterého se zprávy hledají
     * a aktualizují.
     */
    val wireId: String? = null
) {
    /** RECEIVING = přijímá se po kouscích (velký soubor), ještě není kompletní. */
    enum class Status { SENDING, SENT, FAILED, RECEIVED, RECEIVING }
    enum class Kind { TEXT, IMAGE, FILE }
}
