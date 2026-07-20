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

    /**
     * Dokončil Tor bootstrap (100 %)?
     *
     * SOCKS listener se otevře výrazně dřív, než má Tor postavené okruhy. Posílat
     * do té doby požadavky na onion službu nemá smysl - connect se utne timeoutem
     * a další pokus začíná od nuly, takže první připojení trvalo i minuty.
     */
    @Volatile
    var bootstrapped: Boolean = false
        private set

    private val lock = Object()

    /** Zaznamená dokončený bootstrap a probudí čekatele. */
    fun markBootstrapped() {
        synchronized(lock) {
            bootstrapped = true
            lock.notifyAll()
        }
    }

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
            // `bootstrapped` se ZÁMĚRNĚ nemaže: hlášku „Bootstrapped 100%" vydá Tor
            // jen jednou za start démona. Kdyby ji tohle shodilo (stačí přechodné
            // zavření listeneru), už by se nikdy nevrátila a každý požadavek by
            // marně čekal na timeout - tichá smrt doručování až do restartu appky.
            // Resetuje se jen při zahození runtime (viz TorController.forgetRuntime).
            ready = false
            socksPort = -1
            lock.notifyAll()
        }
    }

    /** Zapomene i bootstrap - volá se, když se zahazuje celý runtime Toru. */
    fun resetBootstrap() {
        synchronized(lock) {
            bootstrapped = false
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
            // Čekáme i na dokončený bootstrap, ne jen na otevřený listener -
            // jinak by první požadavek odešel do nehotové sítě a utnul se.
            fun usable() = ready && socksPort > 0 && bootstrapped
            if (usable()) return true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!usable()) {
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
