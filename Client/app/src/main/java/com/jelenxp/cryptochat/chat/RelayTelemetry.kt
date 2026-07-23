package com.jelenxp.cryptochat.chat

import java.util.concurrent.atomic.AtomicReference

/**
 * Neměnný snímek zdraví spojení s relayem. Přechody ([withSuccess]/[withFailure])
 * jsou **čisté funkce** (mimo Android i mimo hodiny - čas se předává), takže jdou
 * otestovat; [RelayTelemetry] je jen drží v `AtomicReference`.
 *
 * Obsahuje JEN agregovaná čísla a TYPY chyb - **žádný obsah, žádná .onion adresa,
 * žádné ID schránky** (soukromí, stejně jako `DiagnosticsLog`).
 */
data class TelemetryState(
    /** Celkem pokusů (úspěch i neúspěch). */
    val requests: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    /** Kolik selhání po sobě (0 = poslední uspěl). Ukazatel „server spí". */
    val consecutiveFailures: Int = 0,
    /** RTT posledního úspěšného requestu v ms (`-1` = zatím žádný). */
    val lastRttMs: Long = -1,
    /** Klouzavý průměr RTT (EWMA, α=1/8) v ms (`-1` = zatím žádný). */
    val avgRttMs: Long = -1,
    /** Kdy (ms) naposledy request uspěl (`0` = nikdy). */
    val lastSuccessAt: Long = 0,
    /** Typ poslední chyby (název výjimky), nebo `null`. Nikdy ne text (může nést adresu). */
    val lastErrorType: String? = null
) {
    /** Zaznamená úspěšný request s daným RTT. */
    fun withSuccess(rttMs: Long, now: Long): TelemetryState = copy(
        requests = requests + 1,
        successes = successes + 1,
        consecutiveFailures = 0,
        lastRttMs = rttMs,
        // EWMA α=1/8: nový průměr = 7/8 starého + 1/8 vzorku (první vzorek = sám sebe).
        avgRttMs = if (avgRttMs < 0) rttMs else (avgRttMs * 7 + rttMs) / 8,
        lastSuccessAt = now,
        lastErrorType = null
    )

    /** Zaznamená selhaný request (po vyčerpání pokusů) daného typu. */
    fun withFailure(errorType: String): TelemetryState = copy(
        requests = requests + 1,
        failures = failures + 1,
        consecutiveFailures = consecutiveFailures + 1,
        lastErrorType = errorType
    )
}

/**
 * Sběr telemetrie spojení s relayem: RTT, úspěšnost, počet selhání po sobě.
 * `RelayClient.onionRequest` už čas měří (dnes ho jen loguje) - tady se agreguje,
 * aby šlo odlišit „server spí" od „Tor se pomalu staví" a bezpečně ladit intervaly.
 * Thread-safe přes CAS (aktualizuje se z více síťových vláken).
 */
object RelayTelemetry {

    private val ref = AtomicReference(TelemetryState())

    fun recordSuccess(rttMs: Long) = update { it.withSuccess(rttMs, System.currentTimeMillis()) }

    fun recordFailure(errorType: String) = update { it.withFailure(errorType) }

    fun snapshot(): TelemetryState = ref.get()

    fun reset() = ref.set(TelemetryState())

    private inline fun update(f: (TelemetryState) -> TelemetryState) {
        while (true) {
            val cur = ref.get()
            if (ref.compareAndSet(cur, f(cur))) return
        }
    }
}
