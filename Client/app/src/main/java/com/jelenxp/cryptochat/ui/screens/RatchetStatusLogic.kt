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

    /**
     * KTEROU variantu karty vykreslit. Tohle rozhodnutí (dřív zapečené v composable,
     * neotestované) patří sem - skrývá se v něm bug (priorita, práh uzdravení), ne
     * v tom, jaký string se ukáže. Composable jen mapuje [Display] na ikonu/text.
     */
    sealed interface Display {
        /** Stav se ještě načítá (I/O) - karta se NEkreslí, ať neblikne „vypnutá". */
        data object Loading : Display
        /** Ratchet neběží. */
        data object Inactive : Display
        /** Rotace běží, zatím bez uzdravení (generace 0). */
        data object Active : Display
        /** Rotace běží, [count]× se už klíč obnovil (generace > 0). */
        data class Healed(val count: Int) : Display
        /** Právě probíhá výměna - má PŘEDNOST před počtem uzdravení. */
        data object Rekeying : Display
    }

    /**
     * @param loaded stav už byl načten z úložiště (false = ještě běží I/O → [Display.Loading])
     * @param status výsledek [status]
     *
     * Priorita je záměrná: probíhající výměna přebije počet uzdravení; „uzdraveno"
     * platí jen pro generaci > 0 (gen 0 je čerstvý seed, ještě se nehojilo).
     */
    fun display(loaded: Boolean, status: Status): Display = when {
        !loaded -> Display.Loading
        status is Status.Active && status.rekeying -> Display.Rekeying
        status is Status.Active && status.generation > 0 -> Display.Healed(status.generation)
        status is Status.Active -> Display.Active
        else -> Display.Inactive
    }
}
