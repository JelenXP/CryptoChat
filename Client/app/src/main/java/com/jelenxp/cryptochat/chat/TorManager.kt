package com.jelenxp.cryptochat.chat

/**
 * Přístup k Toru pro připojení na `.onion` relay. Appka má Tor zabudovaný, aby
 * koncový uživatel nemusel instalovat Orbot ani nic dalšího.
 *
 * Tenká fasáda: drží adresu SOCKS proxy zabudovaného Toru a jestli je Tor
 * připravený. Skutečný SOCKS port přiděluje Tor automaticky až po startu
 * (proto default „neznámý" = -1, ne napevno 9050 jako Orbot) - nastaví ho
 * [configure] z observeru v [TorController], jakmile Tor otevře SOCKS listener.
 *
 * `.onion` požadavky se NESMÍ posílat, dokud Tor neběží - k tomu slouží
 * [awaitReady], na které [RelayClient] čeká, než otevře tunel.
 */
object TorManager {

    @Volatile
    var socksHost: String = "127.0.0.1"
        private set

    /** Skutečný SOCKS port zabudovaného Toru; -1 = ještě neznámý (Tor nenaběhl). */
    @Volatile
    var socksPort: Int = -1
        private set

    /** Je Tor připravený (SOCKS listener otevřený)? */
    @Volatile
    var ready: Boolean = false
        private set

    private val lock = Object()

    /**
     * Nastaví adresu SOCKS proxy (volá se z observeru Toru po otevření listeneru)
     * a probudí případné čekatele v [awaitReady].
     */
    fun configure(host: String, port: Int, ready: Boolean) {
        synchronized(lock) {
            socksHost = host
            socksPort = port
            this.ready = ready
            lock.notifyAll()
        }
    }

    /** Označí Tor za nedostupný (zastavený / listener zavřený). */
    fun markStopped() {
        synchronized(lock) {
            ready = false
            socksPort = -1
            lock.notifyAll()
        }
    }

    /**
     * Blokuje, dokud Tor neotevře SOCKS listener (ready), nejdéle [timeoutMs].
     * Vrací true, když je Tor připravený; false při vypršení času. Volej jen
     * z IO vlákna (blokuje).
     */
    fun awaitReady(timeoutMs: Long): Boolean {
        synchronized(lock) {
            if (ready && socksPort > 0) return true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!(ready && socksPort > 0)) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return false
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    /** Je cílový host `.onion` (má se jít přes Tor)? */
    fun isOnion(host: String): Boolean = host.endsWith(".onion", ignoreCase = true)
}
