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
    val wireId: String? = null,

    /**
     * [wireRef] zprávy, na kterou tahle odpovídá, nebo `null`. Citovaný text se
     * dohledává lokálně - neveze se s sebou, ať se obsah nešifruje a neposílá
     * podruhé. Když původní zpráva u příjemce není (smazal ji, nebo přišla před
     * aktualizací), ukáže se místo náhledu poznámka.
     */
    val replyToWireId: String? = null,

    /**
     * Reakce na tuhle zprávu, klíčované [REACTOR_ME] / [REACTOR_PEER].
     * V konverzaci jsou právě dva účastníci, takže víc klíčů nikdy nevznikne.
     */
    val reactions: Map<String, Reaction> = emptyMap()
) {
    /**
     * Stavy odchozí zprávy: SENDING → SENT (doručeno na relay = jedna fajfka) →
     * DELIVERED (protějšek vyzvedl na zařízení = dvě fajfky); FAILED při selhání.
     * RECEIVED = přijatá zpráva. RECEIVING = přijímá se po kouscích (velký soubor),
     * ještě není kompletní.
     */
    enum class Status { SENDING, SENT, DELIVERED, FAILED, RECEIVED, RECEIVING }
    enum class Kind { TEXT, IMAGE, FILE }

    /**
     * Jedna reakce. [timestamp] rozhoduje při přeházeném pořadí: reakce, která
     * dorazí opožděně (třeba z karantény), nesmí přebít novější.
     *
     * Prázdné [emoji] je **náhrobek** po zrušené reakci - drží se kvůli času,
     * aby šlo poznat, že zrušení je novější než reakce, která dorazí opožděně.
     * Do UI se nepropisuje (viz [visibleReactions]).
     */
    data class Reaction(val emoji: String, val timestamp: Long) {
        val isRemoved: Boolean get() = emoji.isEmpty()
    }

    /** Reakce k zobrazení - bez náhrobků po zrušených. */
    val visibleReactions: Map<String, Reaction>
        get() = reactions.filterValues { !it.isRemoved }

    /** Emoji daného autora, nebo null když nereagoval (nebo reakci zrušil). */
    fun reactionOf(reactor: String): String? =
        reactions[reactor]?.takeIf { !it.isRemoved }?.emoji

    /**
     * Odkaz na tuhle zprávu použitelný NA OBOU zařízeních - to, čím se míří
     * z odpovědí a reakcí.
     *
     * U textu a fotek je to [wireId]. U souborů se použije [id], protože to je
     * hex `fileId` a ten je shodný na obou stranách sám o sobě. `null` znamená
     * „na tuhle zprávu se odkázat nedá" - typicky zpráva z doby před minorem 2.
     */
    val wireRef: String?
        get() = wireId ?: id.takeIf { kind == Kind.FILE && it.isNotEmpty() }

    companion object {
        const val REACTOR_ME = "me"
        const val REACTOR_PEER = "peer"
    }
}
