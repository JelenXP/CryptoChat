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
    private const val REKEY_ROOT = "CryptoChat/ratchet/rekey-root/v1"
    private const val SAFETY = "CryptoChat/ratchet/safety-number/v1"

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
            mode = RatchetState.Mode.DUAL,
            // Čerstvý stav: recvEpoch=0, nic přeskočeného → podlaha = 0.
            backfillFloor = 0
        )
    }

    /** Klíč + hlavičková pole pro odeslání JEDNÉ zprávy a posunutý stav. */
    class SendStep(
        val aesKey: ByteArray,
        val iv: ByteArray,
        val epoch: Int,
        val msgNo: Int,
        /** Krypto generace (Fáze 4). `msgNo` je index UVNITŘ této generace. */
        val generation: Int,
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
        return SendStep(
            keyIv.copyOfRange(0, KEY_BYTES), keyIv.copyOfRange(KEY_BYTES, AEAD_BYTES),
            epoch, msgNo, state.generation, newState
        )
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

        /**
         * Zpráva z NOVĚJŠÍ generace, než na jakou jsme přesejni (KEM re-key ještě
         * nedoběhl). Do karantény - jakmile se zpracuje odpovídající re-key
         * ([applyRekey]), zpráva se dorozšifruje. Fáze 4.
         */
        object FutureGeneration : RecvStep
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
    fun recvKey(state: RatchetState, epoch: Int, generation: Int, msgNo: Int): RecvStep {
        if (epoch < 0 || msgNo < 0 || generation < 0) return RecvStep.AlreadyConsumed  // podvrh/přetečení

        // Novější generace: ještě nemáme její řetěz (re-key nedoběhl) → karanténa.
        if (generation > state.generation) return RecvStep.FutureGeneration

        // 1) Přeskočená (out-of-order) zpráva: klíč máme uložený. Matchuje se
        //    (generation, msgNo) - obslouží i opožděnou zprávu STARŠÍ generace
        //    z mezery (staré klíče ve skipped-store tagované svou generací).
        state.skipped.firstOrNull { it.generation == generation && it.msgNo == msgNo }?.let { hit ->
            val keyIv = Hkdf.derive(Base64Util.decode(hit.messageKeyB64), LABEL_AEAD, AEAD_BYTES)
            val newState = state.copy(
                skipped = state.skipped.filterNot { it.generation == generation && it.msgNo == msgNo },
                recvEpoch = maxOf(state.recvEpoch, epoch)
            )
            return RecvStep.Key(keyIv.copyOfRange(0, KEY_BYTES), keyIv.copyOfRange(KEY_BYTES, AEAD_BYTES), newState)
        }

        // 2) Aktuální generace: přetoč přijímací řetěz (mezeru ulož do skipped).
        if (generation == state.generation) {
            if (msgNo < state.recvMsgNo) return RecvStep.AlreadyConsumed
            if (msgNo - state.recvMsgNo > SKIP_MAX) return RecvStep.SkipTooLarge
            // KUMULATIVNÍ strop (nález 2026-07-29-v2.3-RA3): jeden leap hlídá řádek
            // výš, ale mezera se AKUMULUJE přes víc pollů. Když by přírůstek přeplnil
            // skipped-store, karanténa místo consume - viz [wouldOverfillSkipped].
            if (wouldOverfillSkipped(state.skipped.size, msgNo - state.recvMsgNo)) return RecvStep.SkipTooLarge
            val adv = advanceChain(
                state.recvChainKeyB64 ?: error("přijímací řetěz není inicializovaný"),
                state.recvMsgNo, msgNo, epoch, generation
            )
            return RecvStep.Key(adv.aesKey, adv.iv, state.copy(
                recvChainKeyB64 = adv.nextChainKeyB64,
                recvMsgNo = adv.nextMsgNo,
                recvEpoch = maxOf(state.recvEpoch, epoch),
                skipped = boundSkipped(state.skipped + adv.skips)
            ))
        }

        // 3) PŘEDCHOZÍ generace (grace): in-order konec, co odesílatel poslal ještě
        //    před re-key a dorazil AŽ teď. Přetoč uložený PŘEDCHOZÍ řetěz - bez toho
        //    by se in-flight konec staré generace tiše ztratil (nález review 4a).
        if (generation == state.prevRecvGeneration && state.prevRecvChainKeyB64 != null) {
            if (msgNo < state.prevRecvMsgNo) return RecvStep.AlreadyConsumed
            if (msgNo - state.prevRecvMsgNo > SKIP_MAX) return RecvStep.SkipTooLarge
            // Kumulativní strop i pro grace řetěz předchozí generace (viz [wouldOverfillSkipped]):
            // i tahle větev sype do TÉHOŽ skipped-store, takže může vyvolat eviction.
            if (wouldOverfillSkipped(state.skipped.size, msgNo - state.prevRecvMsgNo)) return RecvStep.SkipTooLarge
            val adv = advanceChain(state.prevRecvChainKeyB64, state.prevRecvMsgNo, msgNo, epoch, generation)
            return RecvStep.Key(adv.aesKey, adv.iv, state.copy(
                prevRecvChainKeyB64 = adv.nextChainKeyB64,
                prevRecvMsgNo = adv.nextMsgNo,
                recvEpoch = maxOf(state.recvEpoch, epoch),
                skipped = boundSkipped(state.skipped + adv.skips)
            ))
        }

        // 4) Starší než předchozí generace nebo neznámé → spotřebované/pryč.
        return RecvStep.AlreadyConsumed
    }

    /** Klíč cílové zprávy + posunutý řetěz + přeskočené klíče mezery. */
    private class ChainAdvance(
        val aesKey: ByteArray,
        val iv: ByteArray,
        val nextChainKeyB64: String,
        val nextMsgNo: Int,
        val skips: List<SkippedMessageKey>
    )

    /**
     * Přetoč řetěz z [fromMsgNo] na [targetMsgNo]: vrátí klíč cílové zprávy,
     * posunutý řetěz a přeskočené klíče mezery (tagované [generation]). Volající
     * hlídá `targetMsgNo - fromMsgNo <= SKIP_MAX`.
     */
    private fun advanceChain(
        chainKeyB64: String, fromMsgNo: Int, targetMsgNo: Int, epoch: Int, generation: Int
    ): ChainAdvance {
        var ck = Base64Util.decode(chainKeyB64)
        val skips = ArrayList<SkippedMessageKey>(targetMsgNo - fromMsgNo)
        var n = fromMsgNo
        while (n < targetMsgNo) {
            val mk = Hkdf.derive(ck, LABEL_MK, KEY_BYTES)
            skips.add(SkippedMessageKey(epoch, n, enc(mk), n.toLong(), generation))
            ck = Hkdf.derive(ck, LABEL_CK, KEY_BYTES)
            n++
        }
        val targetMk = Hkdf.derive(ck, LABEL_MK, KEY_BYTES)
        val nextCk = Hkdf.derive(ck, LABEL_CK, KEY_BYTES)
        val keyIv = Hkdf.derive(targetMk, LABEL_AEAD, AEAD_BYTES)
        return ChainAdvance(
            keyIv.copyOfRange(0, KEY_BYTES), keyIv.copyOfRange(KEY_BYTES, AEAD_BYTES),
            enc(nextCk), targetMsgNo + 1, skips
        )
    }

    /**
     * Přeplnil by přírůstek [gap] přeskočených klíčů skipped-store nad [SKIP_MAX]?
     *
     * **Proč (nález 2026-07-29-v2.3-RA3):** [boundSkipped] evikuje NEJNIŽŠÍ
     * (generace, msgNo) klíče - jenže právě ty potřebuje `RelaySync` backfill
     * nižších epoch (dočítá schránky odspodu nahoru, klíč bere ze skipped-store).
     * Eviction potřebného klíče = jeho blob v [recvKey] spadne do
     * [RecvStep.AlreadyConsumed], poll ho ACKne a relay ho SMAŽE → nenávratná
     * tichá ztráta. Jeden leap sice hlídá strop `msgNo - recvMsgNo <= SKIP_MAX`,
     * jenže mezera se AKUMULUJE přes víc pollů: leap (naplní store) → částečný
     * backfill (store nestihne dojet) → další leap → součet přeteče SKIP_MAX.
     * Skutečný invariant proto NENÍ prostý vztah konstant
     * (SKIP_MAX/EPOCH_MSGS/LOOKAHEAD), ale běhová podmínka na velikost store:
     * `skipped.size + gap <= SKIP_MAX` (přesně tehdy [boundSkipped] neevikuje).
     *
     * Nad limit → volající pošle blob do karantény (`SkipTooLarge`), recvEpoch se
     * NEposune; backfill mezitím store sníží (spotřebuje nižší klíče) a příště se
     * leap vejde → **self-healing** bez ztráty. In-order zpráva (gap 0) projde vždy.
     */
    private fun wouldOverfillSkipped(currentSkipped: Int, gap: Int): Boolean =
        currentSkipped + gap > SKIP_MAX

    /**
     * Ořízne skipped-store na [SKIP_MAX] - nejstarší (nejnižší (generace, index))
     * padají první. Staré generace jdou ven dřív než nižší indexy v aktuální.
     * Bez toho by protějšek mohl posílat samé mezery a nafouknout paměť.
     *
     * **S kumulativním stropem ([wouldOverfillSkipped]) je to už jen pojistka**
     * (defense-in-depth): recvKey brání store přerůst SKIP_MAX, takže tady se za
     * běžného chodu nic neevikuje. Kdyby přesto (budoucí cesta bez guardu), pořadí
     * eviction je zafixované testem (viz níž).
     *
     * **Politika eviction je zafixovaná testem** (nález v2.0-10): opožděná
     * out-of-order zpráva s oříznutým msgNo dostane `AlreadyConsumed` = tichá
     * ztráta, takže KTERÁ půlka se obětuje je součást kontraktu - refaktor řazení
     * by ji jinak tiše změnil. `internal` kvůli testu (jako `ChatEnvelope.bucketFor`).
     */
    internal fun boundSkipped(list: List<SkippedMessageKey>): List<SkippedMessageKey> {
        if (list.size <= SKIP_MAX) return list
        return list
            .sortedWith(compareByDescending<SkippedMessageKey> { it.generation }.thenByDescending { it.msgNo })
            .take(SKIP_MAX)
            .sortedWith(compareBy<SkippedMessageKey> { it.generation }.thenBy { it.msgNo })
    }

    // --- KEM re-key (Fáze 4, PCS) ---

    private const val REKEY_COMBINE = "CryptoChat/ratchet/rekey-combine/v1"

    /**
     * Vygeneruje čerstvý ML-KEM pár pro re-key. Vrací (public, private) Base64.
     */
    fun generateKemKeyPair(): Pair<String, String> {
        val kp = com.jelenxp.cryptochat.crypto.PostQuantumKem.generateKeyPair()
        return kp.publicKeyBase64 to kp.privateKeyBase64
    }

    /**
     * Zkombinuje dvě KEM tajemství do jednoho 32B (Base64). **Pořadí je vázané na
     * ROLI KLÍČE, ne na „já/protějšek":** `ssI` = tajemství k INICIÁTOROVU ephemeral
     * pubkey (`pkI`), `ssR` = tajemství k ODPOVÍDAJÍCÍHO pubkey (`pkR`). Obě strany
     * tak skládají `(K_pkI ‖ K_pkR)` ve STEJNÉM pořadí → shodné `ss` → shodný kořen.
     * (HKDF NENÍ komutativní; kdyby se pořadí odvozovalo z „mého/protějškova" pohledu,
     * byl by pro každou stranu opačný a řetěze by se navždy rozešly - proto to takhle
     * NEMĚNIT.) Vmíchání OBOU stran = re-key injektuje entropii z obou → PCS uzdravení
     * proti kompromitaci kterékoli z nich.
     */
    fun combineSecrets(ssIB64: String, ssRB64: String): String {
        val ikm = Base64Util.decode(ssIB64) + Base64Util.decode(ssRB64)
        return Base64Util.encode(Hkdf.derive(ikm, REKEY_COMBINE, KEY_BYTES))
    }

    /**
     * Aplikuje dokončený KEM re-key: z čerstvého sdíleného tajemství [ssB64]
     * (32B Base64, dohodnuté oběma stranami) vmíchá entropii do NOVÉ generace
     * kořene a přeseje OBA řetěze. `sendMsgNo`/`recvMsgNo` (index v generaci) se
     * resetují na 0; skipped-store se ZACHOVÁ (staré klíče tagované starou generací
     * kvůli grace). **Obě strany MUSÍ volat se stejným `ssB64` a stejným kořenem**,
     * jinak se řetěze rozejdou. Dotýká se obou půlek → aplikuj pod plným zámkem.
     *
     * **Hranice grace (nález v2.1-R1, by-design):** chrání se JEN JEDNA předchozí
     * generace (`prevRecv*`). Když se stihnou DVA re-key za sebou (G→G+1→G+2) dřív,
     * než dorazí opožděný in-order konec generace G (jehož klíč není ve `skipped`),
     * spadne taková zpráva do `AlreadyConsumed` a klíč gen G je už přepsán → ztráta.
     * Okno je prakticky nedosažitelné (dva 3-cestné handshaky mezi sebou vyžadují
     * provoz gatovaný `rekeyMarker`, který ten in-flight tail mezitím dotáhne), takže
     * druhou prev generaci schválně nedržíme.
     */
    fun applyRekey(state: RatchetState, ssB64: String, sendDir: Int, recvDir: Int): RatchetState {
        val ikm = Base64Util.decode(state.rootKeyB64) + Base64Util.decode(ssB64)
        val newGen = state.generation + 1
        val newRoot = Hkdf.derive(ikm, "$REKEY_ROOT|gen=$newGen", KEY_BYTES)
        val newSend = Hkdf.derive(newRoot, "$CHAIN|dir=$sendDir|gen=$newGen", KEY_BYTES)
        val newRecv = Hkdf.derive(newRoot, "$CHAIN|dir=$recvDir|gen=$newGen", KEY_BYTES)
        return state.copy(
            rootKeyB64 = enc(newRoot),
            generation = newGen,
            sendChainKeyB64 = enc(newSend),
            recvChainKeyB64 = enc(newRecv),
            sendMsgNo = 0,
            recvMsgNo = 0,
            // Nová generace = auto-politika (Fáze 4c) měří provoz od nuly.
            rekeyMarker = 0,
            // Ulož DOSAVADNÍ přijímací řetěz jako předchozí generaci: in-order konec
            // staré generace, co dorazí přes relay AŽ po re-key, se pak ještě
            // dešifruje (grace, viz recvKey větev 3). Bez toho by se tiše ztratil.
            prevRecvChainKeyB64 = state.recvChainKeyB64,
            prevRecvGeneration = state.generation,
            prevRecvMsgNo = state.recvMsgNo
            // skipped se ZÁMĚRNĚ nemaže - klíče z mezer (tag = generace) slouží
            // opožděným out-of-order zprávám; ořízne je až SKIP_MAX.
        )
    }

    private fun enc(bytes: ByteArray): String = Base64Util.encode(bytes)

    /**
     * Krátký ověřovací kód **aktuální generace** ratchetu (Fáze 5). Obě strany,
     * které jsou v synchronizaci (stejná generace = stejný kořen), spočítají
     * stejný kód a můžou si ho porovnat jiným kanálem - ověří tím, že ratchet
     * nesešel z cesty (útokem ani bugem/ztrátou stavu). Na rozdíl od
     * [com.jelenxp.cryptochat.crypto.CryptoManager.fingerprint] (otisk STATICKÉHO
     * klíče M) se tenhle mění při každém re-key, takže ověřuje čerstvý stav, ne
     * jen původní kotvu.
     *
     * Odvozeno HKDF z kořene s vlastním doménovým labelem → kořen neprozradí a
     * nekoliduje s klíči řetězů/re-key. **16 bajtů (128 bitů)** hex po skupinách
     * (`AB12 CD34 … 78AB`) - dost na odolnost proti birthday kolizi (audit: 64 bitů
     * bylo málo). Nezávisí na směru (kořen taky ne), takže odesílatel i příjemce
     * dostanou shodný kód.
     */
    fun safetyNumber(state: RatchetState): String {
        val out = Hkdf.derive(Base64Util.decode(state.rootKeyB64), SAFETY, 16)
        return out.joinToString("") { "%02x".format(it) }.uppercase().chunked(4).joinToString(" ")
    }
}
