package com.jelenxp.cryptochat.chat

/**
 * Přístup k Toru pro připojení na `.onion` relay. Appka má Tor zabudovaný, aby
 * koncový uživatel nemusel instalovat Orbot ani nic dalšího.
 *
 * Tahle třída je záměrně tenká fasáda: drží adresu SOCKS proxy, přes kterou se
 * chodí na `.onion`, a stav Toru. Samotné spuštění Tor démona (nativní knihovna)
 * se doplní zvlášť - proto je oddělené, ať zbytek appky (síť) na knihovně nezávisí
 * a jde otestovat i proti externí SOCKS proxy.
 *
 * Dokud embedded Tor démon není spuštěný, míří [socksHost]/[socksPort] na výchozí
 * lokální port; jakmile démon naběhne, nastaví skutečný port přes [configure].
 */
object TorManager {

    @Volatile
    var socksHost: String = "127.0.0.1"
        private set

    @Volatile
    var socksPort: Int = 9050
        private set

    /** Je Tor připravený obsluhovat spojení? Nastaví běh Tor démona. */
    @Volatile
    var ready: Boolean = false

    /** Nastaví adresu SOCKS proxy (volá spouštěč Tor démona po bootstrapu). */
    fun configure(host: String, port: Int, ready: Boolean) {
        this.socksHost = host
        this.socksPort = port
        this.ready = ready
    }

    /**
     * Vrátí SOCKS proxy pro daný cílový host, nebo null když se má jít napřímo.
     * `.onion` adresy vždy přes Tor; ostatní (http/https) napřímo.
     */
    fun socksFor(host: String): Pair<String, Int>? =
        if (host.endsWith(".onion")) socksHost to socksPort else null
}
