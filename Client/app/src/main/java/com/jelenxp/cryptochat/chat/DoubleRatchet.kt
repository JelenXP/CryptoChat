package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util
import com.jelenxp.cryptochat.crypto.Hkdf

/**
 * Symetrický ratchet (Vrstva 1 z plánu R2 + PCS) - čistá logika bez sítě a bez
 * Keystore, aby šla celá otestovat. Operuje nad [RatchetState].
 *
 * Odpovídá drátovému kontraktu (`RATCHET_WIRE.md`):
 *  - kořen `RK_0` deterministicky z hlavního klíče `M` kontaktu,
 *  - dva řetěze (odchozí/příchozí), klíč zprávy per krok (forward secrecy),
 *  - epocha se posouvá po každých [EPOCH_MSGS] odeslaných zprávách (Návrh 2;
 *    KEM krok, který epochu posouvá taky, přijde ve Fázi 4),
 *  - `msgNo` je globální monotónní pořadí ve směru; řetěz se posunem epochy
 *    NEpřeseje (to dělá až KEM krok).
 *
 * **Šifrování samotné tu NENÍ** - [DoubleRatchet] jen spravuje klíče a stav;
 * AES-GCM dělá [ChatEnvelope] s klíčem odsud. Perzistenci a atomicitu (uložit
 * PŘED ACK) řeší volající ([RelaySync], Fáze 3).
 */
object DoubleRatchet {

    /** Posuň adresovací epochu po každých K odeslaných zprávách (Návrh 2). */
    const val EPOCH_MSGS = 32

    /** Kolik kandidátních schránek dopředu pollovat (look-ahead okno). */
    const val LOOKAHEAD = 8

    /** Strop přeskočení / přetočení řetězu. Nad → do karantény, ne odvozovat. */
    const val SKIP_MAX = 1000

    private const val ROOT_INIT = "CryptoChat/ratchet/root-init/v1"
    private const val CHAIN = "CryptoChat/ratchet/chain/v1"
    private const val LABEL_MK = "CryptoChat/ratchet/mk/v1"
    private const val LABEL_CK = "CryptoChat/ratchet/ck/v1"
    private const val LABEL_AEAD = "CryptoChat/ratchet/aead/v1"

    private const val KEY_BYTES = 32
    private const val AEAD_BYTES = 44   // 32 klíč + 12 IV

    /**
     * Deterministický start ratchetu ze statického klíče kontaktu `M`
     * ([com.jelenxp.cryptochat.data.Contact.keyBase64]). Obě strany dojdou ke
     * stejnému stavu bez jediné zprávy navíc. `sendDir`/`recvDir` jako dnes podle
     * `Contact.initiator` (můj odchozí směr = protějškův příchozí, proto symetrické
     * labely dají oběma stranám tytéž řetěze).
     */
    fun bootstrap(masterKeyB64: String, sendDir: Int, recvDir: Int): RatchetState {
        val rk = Hkdf.derive(Base64Util.decode(masterKeyB64), ROOT_INIT, KEY_BYTES)
        val cks = Hkdf.derive(rk, "$CHAIN|dir=$sendDir", KEY_BYTES)
        val ckr = Hkdf.derive(rk, "$CHAIN|dir=$recvDir", KEY_BYTES)
        return RatchetState(
            rootKeyB64 = enc(rk),
            sendChainKeyB64 = enc(cks),
            recvChainKeyB64 = enc(ckr),
            sendEpoch = 0, sendMsgNo = 0, msgsSinceEpoch = 0,
            recvEpoch = 0, recvMsgNo = 0,
            selfKemPublicB64 = null, selfKemPrivateB64 = null,
            retainedKemPrivatesB64 = emptyList(),
            peerKemPublicB64 = null,
            skipped = emptyList(),
            inboundMarker = 0, pointerMarker = 0,
            mode = RatchetState.Mode.DUAL
        )
    }

    /** Klíč + hlavičková pole pro odeslání JEDNÉ zprávy a posunutý stav. */
    class SendStep(
        val aesKey: ByteArray,
        val iv: ByteArray,
        val epoch: Int,
        val msgNo: Int,
        val state: RatchetState
    )

    /**
     * Připraví klíč pro další odchozí zprávu a posune odesílací řetěz.
     *
     * **Invariant volajícího:** jeden `msgNo` = právě jeden plaintext. Retransmise
     * TÉŽE zprávy musí použít týž [SendStep] (bajtově identický blob); jiný obsah
     * pod stejným msgNo se NIKDY neposílá (jinak by se opakoval pár (klíč, IV) GCM).
     */
    fun nextSendStep(state: RatchetState): SendStep {
        val ck = Base64Util.decode(
            state.sendChainKeyB64 ?: error("odesílací řetěz není inicializovaný")
        )
        val mk = Hkdf.derive(ck, LABEL_MK, KEY_BYTES)
        val nextCk = Hkdf.derive(ck, LABEL_CK, KEY_BYTES)
        val keyIv = Hkdf.derive(mk, LABEL_AEAD, AEAD_BYTES)

        val epoch = state.sendEpoch
        val msgNo = state.sendMsgNo

        // Posun epochy podle Návrhu 2: THIS zpráva jede na current epoch; čítač
        // se zvyšuje a po dosažení K posune epochu pro PŘÍŠTÍ zprávu.
        var newEpoch = state.sendEpoch
        var newSince = state.msgsSinceEpoch + 1
        if (newSince >= EPOCH_MSGS) {
            newEpoch += 1
            newSince = 0
        }
        val newState = state.copy(
            sendChainKeyB64 = enc(nextCk),
            sendMsgNo = state.sendMsgNo + 1,
            sendEpoch = newEpoch,
            msgsSinceEpoch = newSince
        )
        return SendStep(keyIv.copyOfRange(0, KEY_BYTES), keyIv.copyOfRange(KEY_BYTES, AEAD_BYTES), epoch, msgNo, newState)
    }

    /** Výsledek pokusu o získání klíče pro příchozí zprávu. */
    sealed interface RecvStep {
        /** Klíč nalezen/odvozen; [state] je posunutý stav (ulož PŘED ACK). */
        class Key(val aesKey: ByteArray, val iv: ByteArray, val state: RatchetState) : RecvStep

        /** Skok za [SKIP_MAX] - do karantény (možná dorazí mezizprávy a zmenší mezeru). */
        object SkipTooLarge : RecvStep

        /**
         * `msgNo` je pod aktuální pozicí a klíč už není (spotřebovaný a
         * neuchovaný) - stará/opakovaná zpráva. Volající ji smí ZAHODIT a potvrdit
         * (na rozdíl od karantény: klíč se sem už nikdy nevrátí).
         */
        object AlreadyConsumed : RecvStep
    }

    /**
     * Vydá klíč pro příchozí zprávu na pozici (`epoch`, `msgNo`) a posune
     * přijímací stav. Zvládá:
     *  - **out-of-order** (zpráva už přeskočená dřív → klíč ze skipped-store),
     *  - **mezeru** (přetočí řetěz a přeskočené klíče uloží, se stropem [SKIP_MAX]),
     *  - **starou/opakovanou** ([RecvStep.AlreadyConsumed]).
     *
     * **KRITICKÝ KONTRAKT: vrácený [RecvStep.Key.state] je TENTATIVNÍ.** Ulož ho
     * (a potvrď zprávu relayi) **až po ÚSPĚŠNÉM dešifrování** blobu klíčem odsud.
     * Když dešifrování selže (podvržený/poškozený blob), stav ZAHOĎ - jinak by
     * nedůvěryhodný relay podstrčil nesmysl s kolidujícím `msgNo`, tím by se
     * spotřeboval (odebral) přeskočený klíč, a skutečná zpráva na té pozici by
     * pak byla nenávratně nedešifrovatelná (odebraný klíč + relay ji po ACK maže).
     * Stejně tak `msgNo` a řetěz smí postoupit jen o zprávu, kterou jsme fakt
     * ověřili (GCM tag). Vynucuje `RelaySync` (Fáze 3), hlídá `RatchetPipelineTest`.
     *
     * Idempotence příjmu (reprocessing po neúspěšném ACK) stojí primárně na
     * `ReplayGuard` (dedup blobu bez dešifrování) - viz plán, bug oblast A.
     */
    fun recvKey(state: RatchetState, epoch: Int, msgNo: Int): RecvStep {
        if (epoch < 0 || msgNo < 0) return RecvStep.AlreadyConsumed  // přetečení / podvrh

        // 1) Přeskočená (out-of-order) zpráva: klíč máme uložený.
        state.skipped.firstOrNull { it.msgNo == msgNo }?.let { hit ->
            val keyIv = Hkdf.derive(Base64Util.decode(hit.messageKeyB64), LABEL_AEAD, AEAD_BYTES)
            val newState = state.copy(
                skipped = state.skipped.filterNot { it.msgNo == msgNo },
                recvEpoch = maxOf(state.recvEpoch, epoch)
            )
            return RecvStep.Key(keyIv.copyOfRange(0, KEY_BYTES), keyIv.copyOfRange(KEY_BYTES, AEAD_BYTES), newState)
        }

        // 2) Pod aktuální pozicí a není ve skipped → spotřebovaná/stará.
        if (msgNo < state.recvMsgNo) return RecvStep.AlreadyConsumed

        // 3) Aktuální nebo budoucí: přetoč řetěz (mezeru ulož jako skipped).
        val gap = msgNo - state.recvMsgNo
        if (gap > SKIP_MAX) return RecvStep.SkipTooLarge

        var ck = Base64Util.decode(
            state.recvChainKeyB64 ?: error("přijímací řetěz není inicializovaný")
        )
        val newSkips = ArrayList<SkippedMessageKey>(gap)
        var n = state.recvMsgNo
        while (n < msgNo) {
            val mk = Hkdf.derive(ck, LABEL_MK, KEY_BYTES)
            newSkips.add(SkippedMessageKey(epoch, n, enc(mk), n.toLong()))
            ck = Hkdf.derive(ck, LABEL_CK, KEY_BYTES)
            n++
        }
        val targetMk = Hkdf.derive(ck, LABEL_MK, KEY_BYTES)
        val nextCk = Hkdf.derive(ck, LABEL_CK, KEY_BYTES)
        val keyIv = Hkdf.derive(targetMk, LABEL_AEAD, AEAD_BYTES)

        val newState = state.copy(
            recvChainKeyB64 = enc(nextCk),
            recvMsgNo = msgNo + 1,
            recvEpoch = maxOf(state.recvEpoch, epoch),
            skipped = boundSkipped(state.skipped + newSkips)
        )
        return RecvStep.Key(keyIv.copyOfRange(0, KEY_BYTES), keyIv.copyOfRange(KEY_BYTES, AEAD_BYTES), newState)
    }

    /**
     * Ořízne skipped-store na [SKIP_MAX] - nejstarší (nejnižší `msgNo`) padají
     * první. Bez toho by protějšek mohl posílat samé mezery a nafouknout paměť.
     */
    private fun boundSkipped(list: List<SkippedMessageKey>): List<SkippedMessageKey> {
        if (list.size <= SKIP_MAX) return list
        return list.sortedByDescending { it.msgNo }.take(SKIP_MAX).sortedBy { it.msgNo }
    }

    private fun enc(bytes: ByteArray): String = Base64Util.encode(bytes)
}
