package com.jelenxp.cryptochat.chat

/**
 * Čistá politika **automatického KEM re-key** (Fáze 4c). Rozhoduje jen KDY má
 * iniciátor zahájit (nebo zopakovat) re-key - **bezhodinově**, výhradně z počítadel
 * stavu ratchetu. Bezstavová funkce vedle roury ([RelaySync.maybeAutoRekey]), aby
 * šla otestovat z požadavku (pravidlo 2: netriviální logika mimo composable/rouru).
 *
 * ## Proč právě tahle pravidla
 *
 * - **Jen iniciátor** ([com.jelenxp.cryptochat.data.Contact.initiator] == true)
 *   re-key řídí. Kdyby ho spouštěly obě strany, závodily by dvě generace naráz.
 * - **Protějšek musí umět `CAP_REKEY`** a **být PRÁVĚ online** (v tomhle pollu
 *   přišla zpráva). OFFER, na který nikdo neodpoví, je k ničemu; online protějšek
 *   navíc handshake dotáhne hned. „Online" = během pollu dorazil obsah - proto je
 *   to i důkaz pro bezpečné zotavení z [RatchetState.Rekey.INIT_CONFIRMED] (viz níž).
 * - **Bezhodinově, podle provozu**: re-key po každých [REKEY_AFTER_MSGS] zprávách
 *   (send+recv) v jedné generaci → PCS uzdravení úměrné aktivitě, bez závislosti
 *   na (možná špatně nastavených) hodinách.
 * - **Brzké PRVNÍ uzdravení**: generace 0 vznikla ze statického `M` kontaktu (to
 *   mohlo uniknout ještě před instalací téhle verze). Proto se gen 0 re-keyuje už
 *   po [REKEY_INITIAL_MSGS] provozu - `M` nemá zůstat jediným tajemstvím dlouho.
 * - **Zotavení zaseknutého handshake**: když se OFFER/ACCEPT/CONFIRM ztratil,
 *   po [REKEY_RETRY_MSGS] dalšího provozu se re-key zopakuje (iniciátor pošle nový
 *   OFFER s novým `rekeyId`, čímž starý pokus přepíše).
 *   - Restart z [RatchetState.Rekey.INIT_OFFERED] je **vždy bezpečný** - ani jedna
 *     strana ještě nepřešla na novou generaci.
 *   - Restart z [RatchetState.Rekey.INIT_CONFIRMED] je bezpečný **jen když je
 *     protějšek prokazatelně pozadu**: kdyby už přešel (poslal novou generaci),
 *     [RelaySync] by tu zprávu rovnou přesejnul a stav by NEbyl INIT_CONFIRMED.
 *     Že jsme v tomto pollu přijali obsah (`peerOnline`) a přesto ZŮSTALI
 *     INIT_CONFIRMED tedy dokazuje, že šlo o zprávu STARÉ generace → protějšek
 *     nepřešel → restart je bezpečný. Právě proto je `peerOnline` podmínkou i tady.
 */
object RekeyPolicy {

    /** Po kolika zprávách (send+recv) v generaci ≥1 spustit re-key (PCS heal). */
    const val REKEY_AFTER_MSGS = 200

    /** Generace 0 (seed ze statického `M`) se re-keyuje už po tomhle provozu. */
    const val REKEY_INITIAL_MSGS = 1

    /** Rozestup opakovaných pokusů, když handshake uvázl (další provoz od OFFER). */
    const val REKEY_RETRY_MSGS = 20

    /**
     * Vrací `true`, když má iniciátor teď (po pollu) zahájit re-key. Argumenty jsou
     * čistá čísla ze stavu, ať jde funkce testovat bez sítě a Keystore:
     *
     * @param initiator          jsem řídící strana ([com.jelenxp.cryptochat.data.Contact.initiator])
     * @param peerSupportsRekey  protějšek inzeroval `CAP_REKEY`
     * @param peerOnline         v tomhle pollu dorazila zpráva (protějšek je aktivní)
     * @param generation         aktuální krypto generace ([RatchetState.generation])
     * @param rekeyStage         fáze handshake ([RatchetState.rekeyStage], viz [RatchetState.Rekey])
     * @param msgsThisGeneration `sendMsgNo + recvMsgNo` v aktuální generaci
     * @param rekeyMarker        provoz při posledním OFFER ([RatchetState.rekeyMarker]); 0 = zatím žádný
     */
    fun shouldInitiate(
        initiator: Boolean,
        peerSupportsRekey: Boolean,
        peerOnline: Boolean,
        generation: Int,
        rekeyStage: Int,
        msgsThisGeneration: Int,
        rekeyMarker: Int
    ): Boolean {
        if (!initiator || !peerSupportsRekey || !peerOnline) return false
        // Provoz OD posledního pokusu (marker 0 = od začátku generace).
        val gap = msgsThisGeneration - rekeyMarker
        val threshold = when (rekeyStage) {
            // Žádný handshake neběží → čerstvé zahájení. Gen 0 dřív (uzdravit M).
            RatchetState.Rekey.NONE ->
                if (generation == 0) REKEY_INITIAL_MSGS else REKEY_AFTER_MSGS
            // Handshake uvázl → zopakuj. INIT_OFFERED vždy, INIT_CONFIRMED jen když
            // je protějšek pozadu (zaručeno podmínkou peerOnline výše, viz docstring).
            RatchetState.Rekey.INIT_OFFERED,
            RatchetState.Rekey.INIT_CONFIRMED -> REKEY_RETRY_MSGS
            // RESP_ACCEPTED = role odpovídajícího; ten re-key neiniciuje.
            else -> return false
        }
        return gap >= threshold
    }
}
