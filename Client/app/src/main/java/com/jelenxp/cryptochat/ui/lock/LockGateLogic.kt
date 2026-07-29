package com.jelenxp.cryptochat.ui.lock

/**
 * Čistá rozhodovací logika app-locku, vytažená z composable `AppLockGate`
 * (pravidlo 2 testovací politiky: netriviální stavové rozhodnutí nesmí bydlet
 * v `@Composable`, jinak ho nejde otestovat a mutace projde zeleně).
 *
 * Composable jen změří monotonní čas ([android.os.SystemClock.elapsedRealtime])
 * a výsledek aplikuje - žádné rozhodování v UI.
 */
object LockGateLogic {

    /**
     * Má se appka po návratu z pozadí zamknout?
     *
     * @param enabled je app-lock zapnutý
     * @param alreadyLocked appka už je zamčená (další zamčení nemá smysl)
     * @param backgroundedAt MONOTONNÍ čas odchodu na pozadí; `0L` = na pozadí jsme
     *   nebyli (nebo už jsme odchod spotřebovali)
     * @param now aktuální MONOTONNÍ čas (elapsedRealtime)
     * @param delayMs prodleva zámku v ms (0 = zamknout okamžitě při každém odchodu)
     *
     * Používá se monotonní čas (elapsedRealtime), NE wall-clock: útočník s
     * odemčeným telefonem (což je vlastní model hrozby zámku) by posunutím hodin
     * zpět udělal `elapsed` záporné a zámek by nikdy nesepnul. elapsedRealtime
     * jde jen dopředu a nejde ovlivnit změnou času ani NTP.
     */
    fun shouldLock(
        enabled: Boolean,
        alreadyLocked: Boolean,
        backgroundedAt: Long,
        now: Long,
        delayMs: Long
    ): Boolean {
        if (!enabled || alreadyLocked) return false
        if (backgroundedAt == 0L) return false
        // `>=` (ne `>`): u delayMs=0 (IMMEDIATELY) musí zamknout i při elapsed==0,
        // a přesně na hranici elapsed==delayMs se zamyká taky.
        return now - backgroundedAt >= delayMs
    }
}
