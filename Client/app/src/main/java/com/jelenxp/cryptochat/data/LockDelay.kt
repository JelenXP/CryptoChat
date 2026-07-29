package com.jelenxp.cryptochat.data

/**
 * Jak dlouho po odchodu appky na pozadí počkat, než se zámek zamkne (grace
 * period). Krátká prodleva je pohodlná - rychlé přepnutí do jiné appky a zpět
 * nezamkne; delší je bezpečnější. [IMMEDIATELY] (0 ms) zamkne při každém odchodu
 * na pozadí.
 *
 * Ukládá se názvem enumu (viz [SettingsRepository]). Výchozí [SEC_10] odpovídá
 * původnímu pevnému chování (10 s).
 */
enum class LockDelay(val millis: Long) {
    IMMEDIATELY(0L),
    SEC_10(10_000L),
    SEC_30(30_000L),
    MIN_1(60_000L),
    MIN_5(5L * 60 * 1000)
}
