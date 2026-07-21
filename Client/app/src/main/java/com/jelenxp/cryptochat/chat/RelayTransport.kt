package com.jelenxp.cryptochat.chat

/**
 * Přenos k relayi jako rozhraní - kvůli testovatelnosti celé přijímací roury.
 *
 * Dokud [RelaySync] volal [RelayClient] napřímo, nešlo `poll()` otestovat vůbec
 * (potřebuje síť a Tor) - a přitom právě tam vznikly nejdražší chyby projektu:
 * dávka se potvrdila, i když se zápis nepovedl, a relay zprávu smazal.
 *
 * Testy sem dosadí `FakeRelay`, který umí simulovat i to nepříjemné, co dělá
 * skutečný server: mazání po potvrzení, opakované doručení téhož blobu,
 * přeházené pořadí a výpadek uprostřed dávky.
 */
interface RelayTransport {

    /** Uloží blob do schránky. Vrací úspěch. */
    fun put(baseUrl: String, mailboxId: String, blob: ByteArray): Boolean

    /**
     * Vyzvedne čekající bloby. Server je NESMÍ smazat - to udělá až [ack].
     * [waitSeconds] > 0 znamená long-polling.
     */
    fun get(baseUrl: String, mailboxId: String, waitSeconds: Int = 0): RelayClient.Fetched

    /** Potvrdí bezpečné uložení blobů až po `seq` - teprve teď je server smaže. */
    fun ack(baseUrl: String, mailboxId: String, seq: Long): Boolean

    /**
     * Předehřeje Tor okruh pro danou SOCKS [isolation] (= odesílací schránku), aby
     * první reálný [put] nemusel čekat na studenou stavbu okruhu. Čistě best-effort
     * optimalizace bez záruky; výchozí implementace nedělá nic (přenosy, které okruhy
     * neřeší, ji ignorují).
     */
    fun prewarm(baseUrl: String, isolation: String) {}
}

/** Ostrý přenos: skutečný relay přes HTTP nebo Tor. */
object RealRelayTransport : RelayTransport {
    override fun put(baseUrl: String, mailboxId: String, blob: ByteArray): Boolean =
        RelayClient.put(baseUrl, mailboxId, blob)

    override fun get(baseUrl: String, mailboxId: String, waitSeconds: Int): RelayClient.Fetched =
        RelayClient.get(baseUrl, mailboxId, waitSeconds)

    override fun ack(baseUrl: String, mailboxId: String, seq: Long): Boolean =
        RelayClient.ack(baseUrl, mailboxId, seq)

    override fun prewarm(baseUrl: String, isolation: String) =
        RelayClient.prewarm(baseUrl, isolation)
}
