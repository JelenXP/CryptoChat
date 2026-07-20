package com.jelenxp.cryptochat.data

/**
 * Rozhodování, jestli na STARTU aplikace ukázat obrazovku aktualizace pro
 * nalezenou novější verzi - **čistě funkčně, bez Androidu**.
 *
 * Vytaženo z `LaunchedEffect` v `MainActivity` (netriviální logika nepatří do
 * composable): dřív tam žila stejná pravidla jako v [UpdateNotifyPolicy.shouldNotify]
 * (cesta na pozadí), ale duplicitně a bez testu - úprava jednoho místa se snadno
 * rozešla s druhým. Tady je to obyčejná funkce nad čísly a řetězci, pokrytá
 * `UpdateStartupPolicyTest`.
 */
object UpdateStartupPolicy {

    /**
     * Co udělat s nalezenou verzí na startu.
     * @property show ukázat obrazovku aktualizace
     * @property markSnoozeImportantShown během pozastaveného připomínání si
     *   důležitou verzi poznač jako připomenutou, ať se příště (další den)
     *   neukáže znovu
     */
    data class Decision(val show: Boolean, val markSnoozeImportantShown: Boolean)

    /**
     * Pravidla musí ctít, co si uživatel odklikl v aplikaci:
     *  - během pozastavení ([snoozeUntil] v budoucnu) mlčíme u běžných verzí,
     *    důležitou pustíme, ale jen jednou ([snoozeImportantShown]),
     *  - mimo pozastavení: důležitou vždy; jinou hned, pokud ji uživatel nezavřel
     *    ([dismissedVersion]); zavřenou znovu až po [remindIntervalMs].
     */
    fun decide(
        important: Boolean,
        latestVersion: String,
        dismissedVersion: String?,
        dismissedAt: Long,
        snoozeUntil: Long,
        snoozeImportantShown: String?,
        remindIntervalMs: Long,
        now: Long
    ): Decision {
        val snoozeActive = snoozeUntil > now
        val show = if (snoozeActive) {
            important && snoozeImportantShown != latestVersion
        } else {
            when {
                important -> true
                latestVersion != dismissedVersion -> true
                else -> now - dismissedAt >= remindIntervalMs
            }
        }
        return Decision(show = show, markSnoozeImportantShown = show && snoozeActive && important)
    }
}
