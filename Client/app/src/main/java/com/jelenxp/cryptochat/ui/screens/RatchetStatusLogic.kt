package com.jelenxp.cryptochat.ui.screens

import com.jelenxp.cryptochat.chat.RatchetState

/**
 * Čistá logika **indikátoru stavu rotace klíčů** (Fáze 5) - vytažená z
 * `@Composable`, aby šla otestovat (viz testovací politika v `CLAUDE.md`,
 * pravidlo 2). Composable jen přeloží [Status] na ikonu/`stringResource`.
 *
 * ROZHODNUTÍ (co ukázat) žije tady; skrývá se v něm bug, ne v tom, jaký text se
 * vykreslí. Např. „uzdraveno" musí platit jen pro generaci > 0 (gen 0 je čerstvý
 * seed ze statického `M`, ještě se nic nehojilo) a „probíhá výměna" má přednost
 * před počtem uzdravení.
 */
object RatchetStatusLogic {

    /** Co ukázat o stavu automatické rotace klíčů daného kontaktu. */
    sealed interface Status {
        /**
         * Ratchet ještě neběží - stav chybí (protějšek zatím neinzeroval, že umí
         * novější formát, nebo je kontakt čerstvý). Zprávy jedou po statickém klíči.
         */
        data object Inactive : Status

        /**
         * Automatická rotace běží. [generation] = kolikrát se klíč už obnovil
         * KEM re-keyem (uzdravení po možném úniku, PCS); 0 = zatím ani jednou.
         * [rekeying] = právě probíhá výměna (handshake není v klidu).
         */
        data class Active(val generation: Int, val rekeying: Boolean) : Status
    }

    /**
     * Odvodí stav z přítomnosti ratchet stavu a jeho polí.
     *
     * @param ratchetPresent stav ratchetu existuje ([com.jelenxp.cryptochat.chat.RatchetStore.load] != null)
     * @param generation     [RatchetState.generation]
     * @param rekeyStage      [RatchetState.rekeyStage] (viz [RatchetState.Rekey])
     */
    fun status(ratchetPresent: Boolean, generation: Int, rekeyStage: Int): Status =
        if (!ratchetPresent) {
            Status.Inactive
        } else {
            Status.Active(
                generation = generation,
                rekeying = rekeyStage != RatchetState.Rekey.NONE
            )
        }
}
