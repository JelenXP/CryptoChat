package com.jelenxp.cryptochat.data

/**
 * Rozhodování, jestli na STARTU aplikace ukázat obrazovku aktualizace pro
 * nalezenou novější verzi - **čistě funkčně, bez Androidu**.
 *
 * Vytaženo z `LaunchedEffect` v `MainActivity` (netriviální logika nepatří do
 * composable), aby to šlo otestovat (`UpdateStartupPolicyTest`).
 *
 * **Pozor - tohle NENÍ totéž co [UpdateNotifyPolicy.shouldNotify]** (cesta na
 * pozadí). Jsou to dva ZÁMĚRNĚ jiné kanály: tady blokující overlay při startu,
 * který zavřenou verzi po `remindIntervalMs` připomene znovu a důležitou ukáže
 * vždy; tam JEDNORÁZOVÁ notifikace, která zavřenou verzi (i důležitou) už
 * nepřipomíná. Nesjednocovat je do jednoho predikátu - liší se úmyslně.
 */
object UpdateStartupPolicy {

    /**
     * Pravidla musí ctít, co si uživatel odklikl v aplikaci:
     *  - důležitou verzi ukaž vždy,
     *  - jinou hned, pokud ji uživatel nezavřel ([dismissedVersion]),
     *  - zavřenou verzi znovu až po [remindIntervalMs].
     */
    fun decide(
        important: Boolean,
        latestVersion: String,
        dismissedVersion: String?,
        dismissedAt: Long,
        remindIntervalMs: Long,
        now: Long
    ): Boolean = when {
        important -> true
        latestVersion != dismissedVersion -> true
        else -> now - dismissedAt >= remindIntervalMs
    }
}
