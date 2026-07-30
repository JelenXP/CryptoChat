package com.jelenxp.cryptochat.chat

import android.content.Context
import android.net.Uri
import com.jelenxp.cryptochat.crypto.Base64Util
import com.jelenxp.cryptochat.crypto.PostQuantumKem
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Doručování zpráv přes relay. Šifrovací vrstva je hotová jinde - tady je jen
 * „doprava": odesílání do schránky odesílatele a vyzvedávání ze schránky
 * příjemce.
 *
 * **Směr a rotace schránek:** každý kontakt má dvě schránky (dir 0 a 1). Kdo
 * posílá na kterou, plyne z role při párování ([Contact.initiator]): iniciátor
 * posílá na dir 0 a poslouchá na dir 1, odpovídající naopak. ID schránky se
 * navíc mění podle epochy (aktuálně 1 den) - server tak nespojí konverzaci
 * napříč dny. Příjemce kontroluje aktuální i předchozí epochu (kvůli přelomu dne).
 *
 * Metody blokují (síť) - volej z IO dispatcheru.
 */
object RelaySync {

    private const val TAG = "RelaySync"

    /**
     * Přenos k relayi. Ostrý je [RealRelayTransport]; testy si sem dosadí
     * `FakeRelay`, aby šla otestovat celá přijímací roura (viz [RelayTransport]).
     * Mimo testy tuhle hodnotu NEMĚŇ.
     */
    @Volatile
    var transport: RelayTransport = RealRelayTransport

    /**
     * Šifrování historie. Stejný důvod jako u [transport] - testy sem dosadí
     * průhlednou implementaci, jinak by `poll()` neuložilo ani jednu zprávu.
     */
    @Volatile
    var storageCrypto: com.jelenxp.cryptochat.crypto.StorageCrypto =
        com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto

    /** Repozitář historie se správným šifrováním (viz [storageCrypto]). */
    private fun repoFor(context: Context) = ChatRepository(context, storageCrypto)

    // Délka jedné epochy schránky (rotace). 1 den = rozumný kompromis mezi
    // soukromím (časté střídání ID) a spolehlivostí (server drží blob 24 h).
    private const val EPOCH_MS = 24L * 60 * 60 * 1000

    // Long-poll: server podrží GET aktuální schránky až tolik sekund, než dorazí
    // zpráva. Musí sedět pod čtecím timeoutem RelayClientu i pod serverovým stropem.
    //
    // Delší čekání = míň probuzení = míň vybité baterie (60 s místo 25 s ušetří
    // ~60 % round-tripů). Doručení se tím nezdrží: PUT probudí čekající GET hned.
    // Nechodíme na plný serverový strop (90 s), ať nečinný stream nezabije NAT.
    private const val LONGPOLL_SECONDS = 60

    // Jak dlouho po přelomu epochy ještě kontrolovat PŘEDCHOZÍ schránku. Mimo
    // tohle okno je kontrola zbytečná - a stála by druhý onion request v každém
    // cyklu, tedy dvojnásobek veškerého provozu i spotřeby.
    private const val EPOCH_OVERLAP_MS = 15L * 60 * 1000

    /**
     * Řídká pojistka: i mimo těsné okno po přelomu se předchozí schránka jednou za
     * tuhle dobu přece jen zkontroluje. Kryje ROZJETÉ HODINY - kdyby měl odesílatel
     * čas pozadu o víc než [EPOCH_OVERLAP_MS], poslal by do „včerejší" schránky až
     * potom, co ji příjemce přestal číst, a zpráva by tam navždy uvízla (ztráta po
     * TTL). Cena je jeden neblokující request za 30 min - proti 60s cyklu aktuální
     * epochy zanedbatelné.
     */
    private const val PREV_EPOCH_RECHECK_MS = 30L * 60 * 1000

    /** Poslední epocha, pro kterou už se u daného kontaktu kontrolovala stará schránka. */
    private val prevEpochChecked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Jak často se ŘÍDCE prohledávají ZÁLOŽNÍ relaye (failover příjmu), když je
     * primární zdravý. Při nedostupném primárním se sweep dělá HNED každý poll
     * (viz `finishPoll`), tohle je jen pojistka pro asymetrický výpadek.
     */
    private const val SECONDARY_SWEEP_MS = 3L * 60 * 1000

    /** Kdy (ms) se u kontaktu naposledy prohledávaly záložní relaye. */
    private val secondarySweptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Kdy (ms) se u daného kontaktu naposledy kontrolovala předchozí schránka. */
    private val prevEpochCheckedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Jak často se u ratchet kontaktu smí přečíst LEGACY grace schránka. Po přepnutí
     * protějšku na ratchet je trvale prázdná, takže tady zůstává jen jako řídká
     * pojistka na zprávy odeslané těsně PŘED přepnutím (TTL relaye 24 h >> tenhle
     * interval, takže se žádná neztratí). Dřív to byl onion GET každých 60 s napořád.
     */
    private const val LEGACY_GRACE_RECHECK_MS = 30L * 60 * 1000

    /** Kdy (ms) se u kontaktu naposledy četla legacy grace schránka (ratchet větev). */
    private val legacyGraceCheckedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Jak stará (ms) musí být zpráva ve stavu SENDING, aby ji outbox považoval za
     * ZASEKLOU a zkusil znovu. Čerstvé SENDING právě posílá UI - to nechme být, ať
     * se nepošle dvakrát. FAILED se retryuje bez ohledu na stáří (viz [outboxNeedsSend]).
     */
    private const val OUTBOX_STALE_MS = 60_000L

    /**
     * Za jak dlouho po odeslání (ms) bez potvrzení doručení začít zprávu posílat
     * ZNOVU. Relay může blob ztratit (restart serveru, eviction pod tlakem paměti,
     * vypršení TTL), pak doručenka nikdy nepřijde a zpráva by uvázla na jedné
     * fajfce. Dej doručence rozumný čas, ať se neposílá zbytečně.
     */
    private const val RESEND_AFTER_MS = 5L * 60 * 1000

    /** Nejčastěji jednou za tolik ms na zprávu (throttle), ať se nehameruje síť. */
    private const val RESEND_INTERVAL_MS = 30L * 60 * 1000

    /**
     * Po jak dlouhé době to vzdát: příjemce je nejspíš dlouho offline a blob na
     * relayi stejně vypršel (TTL). Tlouct do nekonečna by jen pálilo baterii.
     */
    private const val RESEND_GIVE_UP_MS = 3L * 60 * 60 * 1000

    /** Kdy (ms) se naposledy zkusilo znovuodeslání SENT zprávy bez doručenky (klíč = contactId:messageId). */
    private val receiptResendAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Velikost jednoho kousku souboru. Kousek i s obálkou se MUSÍ vejít pod
     * per-blob limit relaye ([ChatMediaStore.RELAY_BLOB_LIMIT]) - jinak server
     * odmítne každý `put` a přenos souboru tiše selže. Rezervu hlídá
     * `RelaySyncChunkLimitTest` (nález z mapy: rezerva pod limitem, dosud
     * nechráněná a bez sdílené konstanty). `internal` kvůli tomu testu.
     */
    internal const val CHUNK_SIZE = 1_800_000

    private fun currentEpoch() = System.currentTimeMillis() / EPOCH_MS

    /**
     * Má se teď kontrolovat schránka předchozí epochy? Ano jednou za epochu (první
     * poll po startu procesu nebo hned po přelomu dne - tehdy tam ještě může něco
     * ležet) a pak už jen prvních [EPOCH_OVERLAP_MS] nové epochy. Zbytek dne se
     * kontrola přeskočí, takže na cyklus vychází jeden onion request místo dvou.
     */
    private fun shouldCheckPrevEpoch(contactId: String, epoch: Long): Boolean =
        shouldCheckPrevEpochAt(
            now = System.currentTimeMillis(),
            epoch = epoch,
            lastCheckedEpoch = prevEpochChecked[contactId],
            lastCheckedAt = prevEpochCheckedAt[contactId],
            epochMs = EPOCH_MS,
            overlapMs = EPOCH_OVERLAP_MS,
            recheckMs = PREV_EPOCH_RECHECK_MS
        )

    /**
     * Vyčistí in-memory gating stav pollu (rozhoduje, kdy se řídce čte prev-epocha,
     * legacy grace a beacon). JEN pro testy - jinak by stav z jednoho testu ovlivnil
     * „první poll" v dalším (stejný singleton [RelaySync] v rámci JVM).
     */
    internal fun resetPollStateForTests() {
        prevEpochChecked.clear()
        prevEpochCheckedAt.clear()
        legacyGraceCheckedAt.clear()
        secondarySweptAt.clear()
        receiptResendAt.clear()
    }

    /**
     * Výsledek jednoho pollu. [failed] = má se zpomalit (síťová I lokální chyba,
     * hammering nemá smysl ani u disku). [reachable] = server SKUTEČNĚ odpověděl
     * (get prošel) - odlišuje síťový výpadek od lokálního selhání úložiště, aby
     * indikátor dostupnosti nehlásil „odpojeno" kvůli plnému disku.
     */
    data class PollResult(val received: Int, val failed: Boolean, val reachable: Boolean = false)

    /** Směr, na který kontakt POSÍLÁ. Iniciátor = 0, odpovídající = 1. */
    private fun sendDir(contact: Contact) = sendDirFor(contact.initiator)

    /** Směr, na kterém kontakt POSLOUCHÁ (opačný). */
    private fun recvDir(contact: Contact) = recvDirFor(contact.initiator)

    /**
     * Zapíše zprávu do lokální historie se stavem SENDING a vrátí ji. Nedělá síť -
     * díky tomu se dá hned zobrazit v UI. Doručení pak dokončí [deliver].
     */
    fun enqueue(
        context: Context,
        contact: Contact,
        text: String,
        replyToWireId: String? = null
    ): ChatMessage? {
        // Strop délky (A11): přerostlý text by dal blob nad limit relaye a zůstal
        // by navždy neodeslatelný (413 → FAILED → retry donekonečna). Radši vůbec
        // neuložit; UI má stejný strop, takže sem se v praxi nedostane.
        if (!textWithinRelayLimit(text)) {
            DiagnosticsLog.warn(TAG, "text nad limitem ($MAX_TEXT_BYTES B), neodesílám")
            return null
        }
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            outgoing = true,
            text = text,
            timestamp = System.currentTimeMillis(),
            status = ChatMessage.Status.SENDING,
            // Stabilní ID se vyrábí TEĎ, ne až při odeslání - opakovaný pokus
            // (`retry`) tak pošle tutéž zprávu se stejným ID a protějšku
            // nevznikne duplicita.
            wireId = WireExt.toHex(WireExt.randomMsgId()),
            replyToWireId = replyToWireId
        )
        // Zápis do historie MUSÍ uspět, než zprávu vrátíme (a volající ji odešle):
        // jinak by ji `deliver` poslal protějšku, ale u nás by v historii nebyla a
        // po restartu by z naší strany zmizela (nález v2.0-30 / B-N3). Vrať null =
        // volající zobrazí chybu a NEodešle.
        if (!repoFor(context).append(contact.id, message)) return null
        return message
    }

    /**
     * Nastaví nebo zruší NAŠI reakci u zprávy a pošle ji protějšku.
     *
     * Když protějšek reakce neumí (nebo jeho verzi ještě neznáme), **neuloží se
     * ani lokálně** a vrátí se [ReactionSend.PEER_UNSUPPORTED]. Uložit ji jen
     * u sebe by znamenalo trvalý rozdíl mezi telefony: odesílací fronta pro
     * reakce neexistuje, takže by se nikdy nedoslala.
     */
    fun sendReaction(
        context: Context,
        contact: Contact,
        wireRef: String,
        emoji: String?
    ): ReactionSend {
        // Stačí, když protějšek umí řídicí zprávu bezpečně ZAHODIT (minor >= 2),
        // ne až ji zobrazit (minor 3). v1.1 reakci tiše zahodí, takže mu ji
        // klidně pošleme - u sebe ji vidíme, on ji jen ignoruje. Blokujeme jen
        // v0.1 (minor 1), kde by naskočila prázdná bublina, a neznámou verzi.
        if (!WireCompat.peerKnownSupports(context, contact.id, WireCompat.MINOR_CONTROL_SAFE)) {
            DiagnosticsLog.log(TAG, "protějšek řídicí zprávu neumí zahodit (v0.1/neznámý), neposílám")
            return ReactionSend.PEER_UNSUPPORTED
        }
        val key = contact.keyBase64
        val baseUrl = SettingsRepository(context).getRelayUrl()
        val target = WireExt.fromHex(wireRef)
        if (key.isNullOrBlank() || baseUrl.isBlank() || target == null) {
            return ReactionSend.FAILED
        }
        // Validuj emoji DŘÍV, než ho uložíme lokálně. Sealovací cesta
        // (`WireExt.buildReaction`) má `require(isValidEmoji)`, ale to hodí výjimku
        // až po lokálním uložení - reakce by pak svítila u mě a protějšku nikdy
        // nedorazila (žádná fronta ji nedošle = trvalý desync, třída nálezu
        // v1.2-15). Fail-fast bez lokálního zápisu. (Zrušení `emoji==null`
        // validaci nepotřebuje.)
        if (emoji != null && !WireExt.isValidEmoji(emoji)) {
            DiagnosticsLog.warn(TAG, "neplatné emoji reakce, neposílám")
            return ReactionSend.FAILED
        }
        val now = System.currentTimeMillis()
        val repo = repoFor(context)
        // Stav reakce PŘED pokusem - kvůli rollbacku, kdyby odeslání selhalo (A6).
        val prior = repo.findByWireRef(contact.id, wireRef)?.reactions?.get(ChatMessage.REACTOR_ME)
        val stored = repo.setReaction(contact.id, wireRef, ChatMessage.REACTOR_ME, emoji, now)
        if (stored != ChatRepository.ReactionResult.APPLIED) {
            DiagnosticsLog.warn(TAG, "reakci se nepodařilo uložit ($stored)")
            return ReactionSend.FAILED
        }
        val delivered = try {
            if (shouldSendRatchet(context, contact)) {
                sendOneRatchet(context, contact, baseUrl) {
                    ChatEnvelope.buildReactionPayload(target, emoji ?: "", emoji == null, now)
                }
            } else {
                val dir = sendDir(contact)
                val blob = ChatEnvelope.sealReaction(
                    target, emoji ?: "", emoji == null, now, key, dir
                )
                putFailover(context, RelayCrypto.mailboxId(key, dir, currentEpoch()), blob)
            }
        } catch (e: Exception) {
            DiagnosticsLog.warn(TAG, "odeslání reakce selhalo (${e.javaClass.simpleName})")
            false
        }
        if (!delivered) {
            // Odeslání selhalo a pro reakce NENÍ outbox (na rozdíl od zpráv),
            // takže by u odesílatele trvale svítila reakce, kterou protějšek nikdy
            // nedostane (trvalý desync, A6). Vrať lokální stav přesně na to, co
            // bylo před pokusem - merge podle času by prostý revert neuměl.
            repo.forceReaction(contact.id, wireRef, ChatMessage.REACTOR_ME, prior)
        }
        DiagnosticsLog.log(TAG, "odeslání reakce: ${if (delivered) "doručeno" else "selhalo"}")
        return if (delivered) ReactionSend.SENT else ReactionSend.FAILED
    }

    /** Jak dopadl pokus o reakci. */
    enum class ReactionSend {
        /** Uloženo a odesláno. */
        SENT,

        /** Protějšek reakce neumí (nebo jeho verzi ještě neznáme) - neuloženo. */
        PEER_UNSUPPORTED,

        /** Uložení nebo odeslání selhalo. */
        FAILED
    }

    /**
     * Upraví text NAŠÍ zprávy (podle [wireRef]) lokálně a pošle úpravu protějšku.
     *
     * Na rozdíl od reakce se úprava **vždy uloží u nás** (je to naše zpráva).
     * Protějšku se pošle jen když umí řídicí zprávu bezpečně ZAHODIT (minor >= 2,
     * jako reakci) - starší verzi by se nová zpráva jevila jako duplicitní text.
     * Když poslat nejde, úprava zůstane jen lokálně (protějšek si nechá původní).
     */
    fun sendEdit(
        context: Context,
        contact: Contact,
        wireRef: String,
        newText: String
    ): MutationSend {
        val key = contact.keyBase64
        val target = WireExt.fromHex(wireRef)
        if (key.isNullOrBlank() || target == null) return MutationSend.FAILED
        // Delší text edit nepřenese (vejde se jen do jedné TLV hodnoty). Odmítni
        // PŘED lokálním zápisem, ať se u mě neobjeví text, který protějšku nepošlu.
        if (newText.toByteArray(Charsets.UTF_8).size > WireExt.MAX_EDIT_TEXT_BYTES) {
            return MutationSend.TOO_LONG
        }
        val now = System.currentTimeMillis()
        val repo = repoFor(context)
        val stored = repo.applyEdit(contact.id, wireRef, newText, now, outgoing = true)
        if (stored != ChatRepository.MutationResult.APPLIED) {
            DiagnosticsLog.warn(TAG, "úpravu se nepodařilo uložit ($stored)")
            return MutationSend.FAILED
        }
        val delivered = deliverControl(
            context, contact, key,
            seal = { dir -> ChatEnvelope.sealEdit(target, newText, now, key, dir) },
            ratchetPayload = { ChatEnvelope.buildEditPayload(target, newText, now) }
        )
        DiagnosticsLog.log(TAG, "odeslání úpravy: ${if (delivered) "doručeno" else "jen lokálně"}")
        return MutationSend.SENT
    }

    /**
     * Smaže NAŠI zprávu (podle [wireRef]) pro všechny: udělá z ní u nás náhrobek
     * a pošle smazání protějšku (stejná pravidla doručení jako [sendEdit]). Když
     * protějšek smazání nepřečte, degraduje to na „smazat u mě".
     */
    fun sendDeleteForEveryone(
        context: Context,
        contact: Contact,
        wireRef: String
    ): MutationSend {
        val key = contact.keyBase64
        val target = WireExt.fromHex(wireRef)
        if (key.isNullOrBlank() || target == null) return MutationSend.FAILED
        val repo = repoFor(context)
        val stored = repo.deleteForBoth(context, contact.id, wireRef, outgoing = true)
        if (stored != ChatRepository.MutationResult.APPLIED) {
            DiagnosticsLog.warn(TAG, "smazání se nepodařilo uložit ($stored)")
            return MutationSend.FAILED
        }
        val now = System.currentTimeMillis()
        val delivered = deliverControl(
            context, contact, key,
            seal = { dir -> ChatEnvelope.sealDelete(target, now, key, dir) },
            ratchetPayload = { ChatEnvelope.buildDeletePayload(target, now) }
        )
        DiagnosticsLog.log(TAG, "odeslání smazání: ${if (delivered) "doručeno" else "jen lokálně"}")
        return MutationSend.SENT
    }

    /** Jak dopadl pokus o úpravu / smazání pro všechny. */
    enum class MutationSend {
        /** Uloženo u mě (a nejlépe i doručeno protějšku). */
        SENT,

        /** Nový text úpravy je nad limit - neuloženo ani neodesláno. */
        TOO_LONG,

        /** Uložení selhalo. */
        FAILED
    }

    /**
     * Odešle řídicí zprávu (edit/delete) protějšku BEST-EFFORT. Pošle jen když ji
     * umí bezpečně zahodit (minor >= 2, jako reakci) a je nakonfigurovaný relay;
     * jinak vrátí false (změna zůstane jen lokálně). Ratchet-aware jako
     * [sendReaction]: [seal] pro legacy obálku, [ratchetPayload] pro ratchet.
     */
    private fun deliverControl(
        context: Context,
        contact: Contact,
        key: String,
        seal: (dir: Int) -> ByteArray,
        ratchetPayload: () -> ByteArray
    ): Boolean {
        val baseUrl = SettingsRepository(context).getRelayUrl()
        if (baseUrl.isBlank() ||
            !WireCompat.peerKnownSupports(context, contact.id, WireCompat.MINOR_CONTROL_SAFE)
        ) {
            DiagnosticsLog.log(TAG, "protějšek řídicí zprávu nepřečte / bez relaye - jen lokálně")
            return false
        }
        return try {
            if (shouldSendRatchet(context, contact)) {
                sendOneRatchet(context, contact, baseUrl, ratchetPayload)
            } else {
                val dir = sendDir(contact)
                putFailover(context, RelayCrypto.mailboxId(key, dir, currentEpoch()), seal(dir))
            }
        } catch (e: Exception) {
            DiagnosticsLog.warn(TAG, "odeslání řídicí zprávy selhalo (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Použije jednu odloženou úpravu/smazání ([PendingMutations]) na cílovou
     * PŘÍCHOZÍ zprávu. Vrací true jen při APPLIED - jinak zůstane odložená.
     */
    private fun applyPendingMutation(
        repo: ChatRepository,
        context: Context,
        contactId: String,
        wireRef: String,
        op: PendingMutations.Op
    ): Boolean = when (op) {
        is PendingMutations.Op.Edit ->
            repo.applyEdit(contactId, wireRef, op.newText, op.timestamp, outgoing = false) ==
                ChatRepository.MutationResult.APPLIED
        is PendingMutations.Op.Delete ->
            repo.deleteForBoth(context, contactId, wireRef, outgoing = false) ==
                ChatRepository.MutationResult.APPLIED
    }

    /**
     * Zařadí odchozí fotku (uloží ji lokálně, přidá do historie se stavem SENDING)
     * a vrátí zprávu. Nedělá síť - doručení dokončí [deliver]. Vrací null, když se
     * fotku nepodařilo uložit.
     */
    fun enqueueImage(context: Context, contact: Contact, jpeg: ByteArray): ChatMessage? {
        val path = ChatMediaStore.save(context, jpeg) ?: return null
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            outgoing = true,
            text = "",
            timestamp = System.currentTimeMillis(),
            status = ChatMessage.Status.SENDING,
            kind = ChatMessage.Kind.IMAGE,
            mediaPath = path,
            wireId = WireExt.toHex(WireExt.randomMsgId())
        )
        // Zápis do historie selhal → ukliď osiřelou fotku (jinak zůstane na disku
        // ~1,9 MB, na kterou nic neodkazuje) a vrať null (nález v2.0-30 / B-N3).
        if (!repoFor(context).append(contact.id, message)) {
            runCatching { File(path).delete() }
            return null
        }
        return message
    }

    /**
     * Zařadí odchozí soubor (video, dokument…): zkopíruje ho k sobě a přidá do
     * historie se stavem SENDING. Vrací null, když se nepovedlo přečíst/uložit
     * nebo je soubor nad limitem [ChatMediaStore.MAX_FILE_BYTES].
     */
    fun enqueueFile(context: Context, contact: Contact, uri: Uri): ChatMessage? {
        val info = ChatMediaStore.fileInfo(context, uri) ?: return null
        if (info.size > ChatMediaStore.MAX_FILE_BYTES) return null
        val path = ChatMediaStore.copyIn(context, uri, info.name) ?: return null
        val fileId = ByteArray(ChatEnvelope.FILE_ID_BYTES).also { SecureRandom().nextBytes(it) }
        val message = ChatMessage(
            // id zprávy = hex fileId, aby šel průběh přenosu spárovat s bublinou
            id = MediaTransfers.hex(fileId),
            outgoing = true,
            text = info.name,
            timestamp = System.currentTimeMillis(),
            status = ChatMessage.Status.SENDING,
            kind = ChatMessage.Kind.FILE,
            mediaPath = path,
            mimeType = info.mimeType
        )
        // Zápis do historie selhal → ukliď osiřelou kopii souboru a vrať null
        // (nález v2.0-30 / B-N3).
        if (!repoFor(context).append(contact.id, message)) {
            runCatching { File(path).delete() }
            return null
        }
        return message
    }

    // --- Ratchet odesílání (Fáze 3b) ---

    /** Zámky serializující odeslání per kontakt (proti opakování GCM klíče). */
    private val sendLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun sendLock(contactId: String): Any = sendLocks.getOrPut(contactId) { Any() }

    /**
     * Posílat ratchetem? Jen pro kontakt s bootstrapnutým stavem, jehož protějšek
     * autentizovaně inzeroval, že umí major 4. `peerCanReadMajor` je monotónní,
     * takže se rozhodnutí jednou zapnuté už nemění (žádné přepínání legacy↔ratchet).
     */
    private fun shouldSendRatchet(context: Context, contact: Contact): Boolean =
        contact.initiator != null &&
            WireCompat.peerCanReadMajor(context, contact.id, WireCompat.WIRE_MAJOR_RATCHET) &&
            RatchetStore(context, storageCrypto).load(contact.id) != null

    /**
     * Když se posunula odesílací epocha a beacon ještě neinzeruje aktuální, zapíše
     * beacon ukazatel (viz [RatchetBeacon]). `pointerMarker` = poslední epocha, pro
     * kterou beacon SKUTEČNĚ prošel; dokud se nepovede, každé další odeslání to
     * zkusí znovu (spolehlivost bez re-zápisu v každém cyklu). Best-effort.
     */
    private fun maybeWriteBeacon(
        context: Context,
        contactId: String,
        baseUrl: String,
        key: String,
        dir: Int,
        store: RatchetStore
    ) {
        val st = store.load(contactId) ?: return
        if (st.sendEpoch.toLong() <= st.pointerMarker) return
        val ok = try {
            transport.put(baseUrl, RelayCrypto.ratchetBeaconId(key, dir), RatchetBeacon.seal(st.sendEpoch, key, dir))
        } catch (e: Exception) {
            false
        }
        // JEN pointerMarker - saveSend by přepsal i sendMsgNo (možný souběžný posun).
        if (ok) store.updatePointerMarker(contactId, st.sendEpoch.toLong())
    }

    /**
     * Přečte beacon (ukazatel aktuální epochy odesílatele) z jeho stabilní schránky.
     * Vrací nejvyšší inzerovanou epochu, nebo null (beacon není / nejde přečíst).
     *
     * **Beacon se NEackuje** (nemaže): kdyby se smazal a následné vyzvednutí vzdálené
     * epochy selhalo, přišli bychom o ukazatel a odesílatel ho znovu nezapíše, dokud
     * neposune epochu. Stará se o něj TTL relaye; `readBeacon` se navíc volá jen když
     * sousední epocha nic nepřinesla (ne za běžného provozu), takže nakupení je malé.
     */
    private fun readBeacon(baseUrl: String, key: String, dir: Int): Int? {
        val fetched = try {
            transport.get(baseUrl, RelayCrypto.ratchetBeaconId(key, dir), 0)
        } catch (e: Exception) {
            return null
        }
        if (fetched.blobs.isEmpty()) return null
        return fetched.blobs.mapNotNull { RatchetBeacon.open(it, key, dir) }.maxOrNull()
    }

    /**
     * Odešle JEDEN ratchet blob (text/fotka/reakce). **Advance-immediately:**
     * odesílací řetěz se posune a ULOŽÍ ještě PŘED `put`, pod [sendLock]. Tím se
     * `msgNo` nikdy nepoužije pro dva různé obsahy (opakování GCM páru klíč+IV je
     * fatální). Cena je spálený `msgNo` při selhání `put` - příjemce ho přeskočí
     * (strop SKIP_MAX dává rezervu) a zpráva se retrykuje s NOVÝM `msgNo` a stejným
     * `wireId`, takže ji příjemce dedupuje podle obsahu. [buildPayload] proto MUSÍ
     * být deterministický (stabilní `wireId`/ts/obsah).
     */
    /**
     * PUT s FAILOVEREM: zkusí relaye z [SettingsRepository.getRelayUrls] v pořadí,
     * dokud jeden blob nepřijme. ID schránky na adrese relaye NEzávisí, takže tatáž
     * schránka existuje na kterémkoli serveru - failover nepotřebuje koordinaci
     * obsahu. Když primární relay spí, odchozí zpráva projde přes záložní. Vrací
     * true, když aspoň jeden relay uspěl. NEmirroruje (nezapisuje na víc naráz) -
     * to by dvěma serverům dalo stejný časový otisk téže dvojice.
     */
    private fun putFailover(context: Context, mailbox: String, blob: ByteArray): Boolean {
        for (url in SettingsRepository(context).getRelayUrls()) {
            if (url.isBlank()) continue
            val ok = try {
                transport.put(url, mailbox, blob)
            } catch (e: Exception) {
                DiagnosticsLog.warn(TAG, "PUT na relay selhal (${e.javaClass.simpleName}), zkouším další")
                false
            }
            if (ok) return true
        }
        return false
    }

    private fun sendOneRatchet(
        context: Context,
        contact: Contact,
        baseUrl: String,
        buildPayload: () -> ByteArray
    ): Boolean {
        val key = contact.keyBase64 ?: return false
        val dir = sendDir(contact)
        val store = RatchetStore(context, storageCrypto)
        val step = synchronized(sendLock(contact.id)) {
            val state = store.load(contact.id) ?: return false
            val s = DoubleRatchet.nextSendStep(state)
            // Posun ULOŽ HNED; když se nepovede, NEODESÍLEJ (stav se nezměnil,
            // retry re-derivuje týž msgNo).
            if (!store.saveSend(contact.id, s.state)) return false
            s
        }
        val blob = ChatEnvelope.encryptRatchet(buildPayload(), step.aesKey, step.iv, step.epoch, step.msgNo, step.generation, dir)
        val mailbox = RelayCrypto.ratchetMailboxId(key, dir, step.epoch)
        val put = putFailover(context, mailbox, blob)
        if (put) maybeWriteBeacon(context, contact.id, baseUrl, key, dir, store)
        return put
    }

    /**
     * Odešle soubor ratchetem (manifest + kousky); každý blob přes vlastní posun
     * řetězu (advance-immediately, uloženo po každém blobu).
     */
    private fun deliverFileRatchet(
        context: Context,
        contact: Contact,
        key: String,
        baseUrl: String,
        message: ChatMessage,
        file: File,
        totalChunks: Int,
        totalSize: Long,
        fileId: ByteArray
    ): Boolean {
        val dir = sendDir(contact)
        val store = RatchetStore(context, storageCrypto)
        fun sendPayload(payload: ByteArray): Boolean {
            val step = synchronized(sendLock(contact.id)) {
                val state = store.load(contact.id) ?: return false
                val s = DoubleRatchet.nextSendStep(state)
                if (!store.saveSend(contact.id, s.state)) return false
                s
            }
            val blob = ChatEnvelope.encryptRatchet(payload, step.aesKey, step.iv, step.epoch, step.msgNo, step.generation, dir)
            val put = putFailover(context, RelayCrypto.ratchetMailboxId(key, dir, step.epoch), blob)
            if (put) maybeWriteBeacon(context, contact.id, baseUrl, key, dir, store)
            return put
        }
        val manifest = ChatEnvelope.buildManifestPayload(
            fileId, totalChunks, totalSize,
            message.mimeType ?: "application/octet-stream", message.text, message.timestamp
        )
        if (!sendPayload(manifest)) return false
        var index = 0
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (index < totalChunks) {
                val read = readChunkFully(input, buffer)
                val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                if (!sendPayload(ChatEnvelope.buildChunkPayload(fileId, index, chunk, message.timestamp))) return false
                index++
                MediaTransfers.setProgress(message.id, index.toFloat() / totalChunks)
            }
        }
        return true
    }

    // --- KEM re-key handshake (Fáze 4b, PCS) ---

    /**
     * Zahájí KEM re-key. Volá auto-politika (4c) i testy; jen `initiator==true`
     * strana, když je ratchet aktivní a protějšek umí [WireExt.CAP_REKEY].
     * Vygeneruje ML-KEM pár, uloží handshake stav a pošle OFFER. Případný zaseknutý
     * re-key přepíše (nový rekeyId) - tím se sám zotaví. Vrací true při odeslání OFFER.
     */
    fun initiateRekey(context: Context, contact: Contact): Boolean {
        if (contact.initiator != true) return false
        val key = contact.keyBase64 ?: return false
        val baseUrl = SettingsRepository(context).getRelayUrl()
        if (baseUrl.isBlank()) return false
        if (!WireCompat.peerHasCapability(context, contact.id, WireExt.CAP_REKEY)) return false
        val store = RatchetStore(context, storageCrypto)
        if (store.load(contact.id) == null) return false
        val (pkI, skI) = DoubleRatchet.generateKemKeyPair()
        val rekeyId = ByteArray(WireExt.REKEY_ID_BYTES).also { SecureRandom().nextBytes(it) }
        if (!store.updateLocked(contact.id) {
                it.copy(
                    rekeyId = WireExt.toHex(rekeyId), rekeyPrivB64 = skI, rekeySsB64 = null,
                    rekeyConfirmB64 = null,
                    rekeyStage = RatchetState.Rekey.INIT_OFFERED,
                    // Zaznamenej provoz pro rozestup opakování (Fáze 4c). Vlastní OFFER
                    // teprve posune sendMsgNo, takže marker sedí na stavu PŘED odesláním.
                    rekeyMarker = it.sendMsgNo + it.recvMsgNo
                )
            }
        ) return false
        return sendOneRatchet(context, contact, baseUrl) {
            ChatEnvelope.buildRekeyPayload(WireExt.REKEY_OFFER, rekeyId, Base64Util.decode(pkI), System.currentTimeMillis())
        }
    }

    /**
     * Auto-politika re-key (Fáze 4c): po pollu zváží, jestli má iniciátor spustit
     * (nebo zopakovat) re-key. Vlastní rozhodnutí je čisté v [RekeyPolicy]; tady se
     * jen dosadí reálné signály - schopnost protějšku a „online" (v pollu něco
     * přišlo) - a případně zahájí handshake ([initiateRekey], ten sám přepíše
     * zaseknutý pokus novým `rekeyId`). Volá se z [poll] jen pro aktivní ratchet.
     */
    private fun maybeAutoRekey(context: Context, contact: Contact, store: RatchetStore, received: Int) {
        if (contact.initiator != true) return
        val st = store.load(contact.id) ?: return
        val decide = RekeyPolicy.shouldInitiate(
            initiator = true,
            peerSupportsRekey = WireCompat.peerHasCapability(context, contact.id, WireExt.CAP_REKEY),
            peerOnline = received > 0,
            generation = st.generation,
            rekeyStage = st.rekeyStage,
            msgsThisGeneration = st.sendMsgNo + st.recvMsgNo,
            rekeyMarker = st.rekeyMarker
        )
        if (!decide) return
        // KLÍČOVÉ (audit VYSOKÁ): z INIT_CONFIRMED se re-key NESMÍ RESTARTOVAT
        // (initiateRekey zahodí hotové ss → trvalá desynchronizace, když protějšek
        // mezitím přesejnul a dorazí jeho stará in-flight zpráva). Místo toho jen
        // idempotentně PŘEPOŠLI CONFIRM - odblokuje uvíznutý protějšek a ss zachová.
        if (st.rekeyStage == RatchetState.Rekey.INIT_CONFIRMED) {
            if (resendConfirm(context, contact)) {
                DiagnosticsLog.log(TAG, "auto re-key: CONFIRM přeposlán (gen ${st.generation})")
            }
        } else if (initiateRekey(context, contact)) {
            DiagnosticsLog.log(
                TAG, "auto re-key zahájen (gen ${st.generation}, provoz ${st.sendMsgNo + st.recvMsgNo})"
            )
        }
    }

    /**
     * Iniciátor v INIT_CONFIRMED (poslal CONFIRM, čeká na protějškovu novou generaci),
     * ale handshake možná uvázl (ztracený CONFIRM/ACCEPT). Znovu pošle IDENTICKÝ CONFIRM
     * (stejný rekeyId, uložený `ctR` z [RatchetState.rekeyConfirmB64]) - záměrně NE přes
     * [initiateRekey], protože ten zahodí hotové `ss` i schopnost dohnat příští generaci.
     * Re-CONFIRM je bezpečný VŽDY: protějšek v RESP_ACCEPTED ho dokončí, už přesejnutý
     * protějšek ho zahodí (stage != RESP_ACCEPTED). Aktualizuje jen [RatchetState.rekeyMarker]
     * (rozestup opakování), na `ss`/stage nesahá. Vrací true při odeslání.
     */
    private fun resendConfirm(context: Context, contact: Contact): Boolean {
        val baseUrl = SettingsRepository(context).getRelayUrl()
        if (baseUrl.isBlank()) return false
        val store = RatchetStore(context, storageCrypto)
        val st = store.load(contact.id) ?: return false
        if (st.rekeyStage != RatchetState.Rekey.INIT_CONFIRMED) return false
        val rekeyId = st.rekeyId?.let { WireExt.fromHex(it) } ?: return false
        val ctR = st.rekeyConfirmB64?.let { Base64Util.decode(it) } ?: return false
        // Posuň jen marker (rozestup opakování) - NEsahej na ss ani stage.
        if (!store.updateLocked(contact.id) { it.copy(rekeyMarker = it.sendMsgNo + it.recvMsgNo) }) return false
        return sendOneRatchet(context, contact, baseUrl) {
            ChatEnvelope.buildRekeyPayload(WireExt.REKEY_CONFIRM, rekeyId, ctR, System.currentTimeMillis())
        }
    }

    /** Tělo re-key zprávy se dvěma částmi: `[2B lenA][a][2B lenB][b]`. */
    private fun twoPartBody(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(2 + a.size + 2 + b.size)
        ByteBuffer.wrap(out).putShort(a.size.toShort()).put(a).putShort(b.size.toShort()).put(b)
        return out
    }

    private fun splitTwoPartBody(body: ByteArray): Pair<ByteArray, ByteArray>? = try {
        val buf = ByteBuffer.wrap(body)
        val a = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        val b = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        a to b
    } catch (e: Exception) {
        null
    }

    /**
     * Zpracuje jednu re-key řídicí zprávu (OFFER/ACCEPT/CONFIRM). KEM operace jsou
     * CPU (mimo zámek); zápisy stavu atomicky ([RatchetStore.updateLocked]),
     * přesejnutí (applyRekey, dotýká se OBOU půlek) pod [sendLock]. Idempotence:
     * stage + rekeyId zahodí duplicitní/zastaralé zprávy.
     */
    private fun handleRekey(
        context: Context,
        contact: Contact,
        baseUrl: String,
        store: RatchetStore,
        opened: ChatEnvelope.Opened.Rekey
    ) {
        val sendDir = sendDir(contact)
        val recvDir = recvDir(contact)
        val rekeyIdBytes = WireExt.fromHex(opened.rekeyIdHex) ?: return
        when (opened.subtype) {
            // Odpovídající: pkI → ssI, vygeneruj pkR, pošli ACCEPT (ctI‖pkR).
            WireExt.REKEY_OFFER -> {
                val st = store.load(contact.id) ?: return
                if (st.rekeyStage == RatchetState.Rekey.RESP_ACCEPTED && st.rekeyId == opened.rekeyIdHex) return
                val enc = try { PostQuantumKem.encapsulate(Base64Util.encode(opened.kem)) } catch (e: Exception) { return }
                val (pkR, skR) = DoubleRatchet.generateKemKeyPair()
                if (!store.updateLocked(contact.id) {
                        it.copy(
                            rekeyId = opened.rekeyIdHex, rekeyPrivB64 = skR,
                            rekeySsB64 = enc.sharedKeys.aesKeyBase64, rekeyStage = RatchetState.Rekey.RESP_ACCEPTED
                        )
                    }
                ) return
                val body = twoPartBody(Base64Util.decode(enc.encapsulationBase64), Base64Util.decode(pkR))
                sendOneRatchet(context, contact, baseUrl) {
                    ChatEnvelope.buildRekeyPayload(WireExt.REKEY_ACCEPT, rekeyIdBytes, body, System.currentTimeMillis())
                }
            }
            // Iniciátor: ctI → ssI, pkR → ssR, kombinuj ss, pošli CONFIRM (ctR).
            // NEpřesejni (čeká na protějškovu novou generaci).
            WireExt.REKEY_ACCEPT -> {
                val st = store.load(contact.id) ?: return
                if (st.rekeyStage != RatchetState.Rekey.INIT_OFFERED || st.rekeyId != opened.rekeyIdHex) return
                val skI = st.rekeyPrivB64 ?: return
                val (ctI, pkR) = splitTwoPartBody(opened.kem) ?: return
                val ssI = try { PostQuantumKem.decapsulate(skI, Base64Util.encode(ctI)).aesKeyBase64 } catch (e: Exception) { return }
                val encR = try { PostQuantumKem.encapsulate(Base64Util.encode(pkR)) } catch (e: Exception) { return }
                val ss = DoubleRatchet.combineSecrets(ssI, encR.sharedKeys.aesKeyBase64)
                // Ulož ctR do stavu (rekeyConfirmB64), ať jde CONFIRM idempotentně
                // PŘEPOSLAT při zaseknutí, aniž bychom zahodili ss (re-encapsulace by
                // dala jiné ss → desync). Viz resendConfirm / audit VYSOKÁ.
                if (!store.updateLocked(contact.id) {
                        it.copy(
                            rekeySsB64 = ss, rekeyPrivB64 = null,
                            rekeyStage = RatchetState.Rekey.INIT_CONFIRMED,
                            rekeyConfirmB64 = encR.encapsulationBase64
                        )
                    }
                ) return
                val ctR = Base64Util.decode(encR.encapsulationBase64)
                sendOneRatchet(context, contact, baseUrl) {
                    ChatEnvelope.buildRekeyPayload(WireExt.REKEY_CONFIRM, rekeyIdBytes, ctR, System.currentTimeMillis())
                }
            }
            // Odpovídající: ctR → ssR, kombinuj ss, PŘESEJNI.
            WireExt.REKEY_CONFIRM -> {
                val st = store.load(contact.id) ?: return
                if (st.rekeyStage != RatchetState.Rekey.RESP_ACCEPTED || st.rekeyId != opened.rekeyIdHex) return
                val skR = st.rekeyPrivB64 ?: return
                val ssI = st.rekeySsB64 ?: return
                val ssR = try { PostQuantumKem.decapsulate(skR, Base64Util.encode(opened.kem)).aesKeyBase64 } catch (e: Exception) { return }
                val ss = DoubleRatchet.combineSecrets(ssI, ssR)
                synchronized(sendLock(contact.id)) {
                    store.updateLocked(contact.id) { cur ->
                        if (cur.rekeyStage != RatchetState.Rekey.RESP_ACCEPTED || cur.rekeyId != opened.rekeyIdHex) return@updateLocked null
                        DoubleRatchet.applyRekey(cur, ss, sendDir, recvDir)
                            .copy(rekeyId = null, rekeyPrivB64 = null, rekeySsB64 = null, rekeyConfirmB64 = null, rekeyStage = RatchetState.Rekey.NONE)
                    }
                }
                DiagnosticsLog.log(TAG, "KEM re-key dokončen (odpovídající), nová generace")
            }
        }
    }

    /**
     * Iniciátor: protějšek přešel na PŘÍŠTÍ generaci (dokončil re-key). Máme-li
     * hotové ss ([RatchetState.Rekey.INIT_CONFIRMED]), přesejni jím (pod [sendLock]).
     * Vrací true, když se přesejnulo - pak lze zprávu zkusit otevřít znovu.
     */
    private fun maybeApplyPendingRekey(context: Context, contact: Contact, store: RatchetStore, msgGeneration: Int): Boolean {
        val st = store.load(contact.id) ?: return false
        if (st.rekeyStage != RatchetState.Rekey.INIT_CONFIRMED || msgGeneration != st.generation + 1) return false
        val ss = st.rekeySsB64 ?: return false
        val sendDir = sendDir(contact)
        val recvDir = recvDir(contact)
        var applied = false
        synchronized(sendLock(contact.id)) {
            store.updateLocked(contact.id) { cur ->
                if (cur.rekeyStage != RatchetState.Rekey.INIT_CONFIRMED || msgGeneration != cur.generation + 1) return@updateLocked null
                applied = true
                DoubleRatchet.applyRekey(cur, ss, sendDir, recvDir)
                    .copy(rekeyId = null, rekeyPrivB64 = null, rekeySsB64 = null, rekeyConfirmB64 = null, rekeyStage = RatchetState.Rekey.NONE)
            }
        }
        if (applied) DiagnosticsLog.log(TAG, "KEM re-key dokončen (iniciátor), nová generace")
        return applied
    }

    /**
     * Předehřeje ODESÍLACÍ Tor okruh pro kontakt: spočítá izolaci PŘESNĚ té
     * schránky, do které půjde příští odeslání (legacy i ratchet), a nechá
     * [transport] postavit okruh dopředu. Volá se, když uživatel začne psát, aby
     * první PUT nečekal na studený okruh.
     *
     * **Ratchet NEPOSOUVÁ** - čte jen uložený `sendEpoch` (schránka závisí na
     * epoše, ne na `msgNo`, viz [DoubleRatchet.nextSendStep]), takže je bezpečné
     * volat opakovaně a nespálí se tím žádný `msgNo`. Best-effort; při chybě mlčí.
     *
     * MUSÍ běžet mimo hlavní vlákno (čte SharedPreferences + Keystore a jde na síť).
     */
    fun prewarmSend(context: Context, contact: Contact) {
        val key = contact.keyBase64 ?: return
        val baseUrl = SettingsRepository(context).getRelayUrl()
        if (baseUrl.isBlank()) return
        val dir = sendDir(contact)
        val isolation = if (shouldSendRatchet(context, contact)) {
            // Read-only: sendEpoch bez posunu řetězu (příští zpráva jede na tuhle epochu).
            val state = RatchetStore(context, storageCrypto).load(contact.id) ?: return
            RelayCrypto.ratchetMailboxId(key, dir, state.sendEpoch)
        } else {
            RelayCrypto.mailboxId(key, dir, currentEpoch())
        }
        transport.prewarm(baseUrl, isolation)
    }

    /**
     * Po ÚSPĚŠNÉM pollu zkusí (znovu) doručit odchozí zprávy, které uvázly: FAILED
     * (relay byl předtím nedostupný) a STARÉ SENDING (proces umřel uprostřed
     * [deliver]). Volá se JEN po úspěšném pollu - relay právě odpověděl, takže se
     * přes výpadek NEopakuje (u ratchetu by každý pokus spálil `msgNo`). Čerstvé
     * SENDING právě posílá UI, ta se nechají být (jinak by šla zpráva dvakrát).
     *
     * Doručuje v pořadí historie; [deliver] si stav SENT/FAILED přepíná sám a na
     * SENT jde jen při 2xx, takže neúspěch se nikdy nepotvrdí. Idempotence stojí na
     * stabilním `wireId` (příjemce retry dedupuje). MUSÍ běžet mimo hlavní vlákno.
     */
    fun flushOutbox(context: Context, contact: Contact) {
        val now = System.currentTimeMillis()
        val messages = repoFor(context).getMessages(contact.id)
        // 1) Zaseklé zprávy: FAILED (relay byl nedostupný) a staré SENDING.
        for (message in messages) {
            if (outboxNeedsSend(message.outgoing, message.status, now - message.timestamp, OUTBOX_STALE_MS)) {
                deliver(context, contact, message)
            }
        }
        // 2) SENT bez potvrzení doručení: relay mohl blob ztratit → pošli znovu.
        resendUndelivered(context, contact, messages, now)
    }

    /**
     * (Znovu) pošle odchozí zprávy ve stavu SENT (na relayi), které dosud nedostaly
     * potvrzení doručení - relay je totiž mohl ztratit (restart, eviction pod tlakem
     * paměti, vypršení TTL). Bez toho by uvázly na jedné fajfce a příjemci nikdy
     * nedošly (nejhorší třída chyby v messengeru: tichá ztráta).
     *
     * Střídmě a bezpečně:
     *  - JEN když protějšek umí doručenky ([WireExt.CAP_RECEIPTS]) - jinak by
     *    DELIVERED nikdy nepřišlo a posílali bychom donekonečna (SUPPRESS).
     *  - jen v okně [RESEND_AFTER_MS]..[RESEND_GIVE_UP_MS] a nanejvýš jednou za
     *    [RESEND_INTERVAL_MS] na zprávu (in-memory throttle) - ať se nehameruje síť.
     *  - opakuje se stejný `wireId`, takže příjemce duplicitu zahodí
     *    (`appendIfAbsentByWireId` / [ReplayGuard]).
     * Volá se jen po ÚSPĚŠNÉM pollu (viz [flushOutbox]) - přes výpadek relaye by se
     * neopakovalo (u ratchetu by každý pokus zbytečně posunul `msgNo`).
     */
    private fun resendUndelivered(
        context: Context,
        contact: Contact,
        messages: List<ChatMessage>,
        now: Long
    ) {
        val peerSupportsReceipts = WireCompat.peerHasCapability(context, contact.id, WireExt.CAP_RECEIPTS)
        if (!peerSupportsReceipts) return
        // Prune mrtvých záznamů throttle mapy (A17): po give-up okně se zpráva už
        // znovu neposílá (doručena, nebo vzdáno), takže záznam jen zabírá paměť
        // v dlouhožijícím FGS. Bez toho mapa roste s každou SENT zprávou navždy.
        receiptResendAt.entries.removeIf { receiptEntryExpired(now, it.value, RESEND_GIVE_UP_MS) }
        for (message in messages) {
            // Stáří od skutečného ODESLÁNÍ (sentAt), ne od vzniku (timestamp) -
            // jinak by zpráva dlouho uvízlá ve FAILED byla po odeslání hned za
            // give-up stropem a resend síť by ji nepokryla (A13). Fallback na
            // timestamp u starých zpráv bez sentAt.
            if (!receiptResendDue(
                    message.status, now - (message.sentAt ?: message.timestamp), peerSupportsReceipts,
                    RESEND_AFTER_MS, RESEND_GIVE_UP_MS
                )
            ) continue
            val key = "${contact.id}:${message.id}"
            if (!resendThrottleOk(now, receiptResendAt[key], RESEND_INTERVAL_MS)) continue
            receiptResendAt[key] = now
            DiagnosticsLog.log(TAG, "SENT bez doručenky, posílám znovu (${message.kind})")
            deliver(context, contact, message)
        }
    }

    /**
     * Pošle protějšku potvrzení doručení za zprávy vyzvednuté v tomhle pollu
     * (jejich ID jsou v [refs]) - u něj se pak NAŠE... totiž JEHO odchozí zprávy
     * přepnou na druhou fajfku. Volá se na konci [poll] (přes `finishPoll`).
     *
     * **Gate:** posílá se JEN protějšku, který inzeruje [WireExt.CAP_RECEIPTS]
     * (strategie SUPPRESS) - starší verze by potvrzení stejně jen zahodila a byl
     * by to onion request navíc při KAŽDÉM příjmu (baterie). Schopnost je čerstvě
     * známá: právě jsme od protějšku dostali zprávu, jejíž trailer ji inzeruje.
     *
     * Řídicí zpráva jde stejnou cestou jako reakce (legacy seal / ratchet). Je
     * best-effort: když se nepošle, protějšek prostě zůstane na jedné fajfce.
     * Víc než [WireExt.MAX_DELIVERY_TARGETS] cílů se rozdělí do víc potvrzení.
     */
    private fun flushDeliveryReceipts(context: Context, contact: Contact, refs: Set<String>) {
        if (refs.isEmpty()) return
        if (!WireCompat.peerHasCapability(context, contact.id, WireExt.CAP_RECEIPTS)) return
        val key = contact.keyBase64 ?: return
        val baseUrl = SettingsRepository(context).getRelayUrl()
        if (baseUrl.isBlank()) return
        val targets = refs.mapNotNull { WireExt.fromHex(it) }
        if (targets.isEmpty()) return
        // Doručenky posílej LEGACY, dokud protějšek PROKAZATELNĚ nečte ratchet (od
        // něj dorazil ratchet blob → recvMsgNo/recvEpoch/generation > 0). Protějšek se
        // bootstrapne teprve když se z NAŠÍ zprávy dozví náš maxMajor≥4; doručenky
        // jsou časté a malé, takže spolehlivě donesou maxMajor ještě nebootstrapnutému
        // protějšku - i kdyby jedna při přechodu selhala (nález v2.0-28). Bez toho by se
        // u „čistého příjemce" po jediné selhané přechodové doručence přepnulo na
        // ratchet-only a příjem protějška by se jednosměrně rozpadl. Jakmile od protějška
        // dorazí ratchet blob (prokázal, že čte ratchet), přepni doručenky na ratchet.
        val peerReadsRatchet = RatchetStore(context, storageCrypto).load(contact.id)?.let {
            it.recvMsgNo > 0 || it.recvEpoch > 0 || it.generation > 0
        } ?: false
        val ratchet = shouldSendRatchet(context, contact) && peerReadsRatchet
        for (chunk in targets.chunked(WireExt.MAX_DELIVERY_TARGETS)) {
            val now = System.currentTimeMillis()
            val delivered = try {
                if (ratchet) {
                    sendOneRatchet(context, contact, baseUrl) {
                        ChatEnvelope.buildDeliveryPayload(chunk, now)
                    }
                } else {
                    val dir = sendDir(contact)
                    val blob = ChatEnvelope.sealDelivery(chunk, now, key, dir)
                    putFailover(context, RelayCrypto.mailboxId(key, dir, currentEpoch()), blob)
                }
            } catch (e: Exception) {
                DiagnosticsLog.warn(TAG, "odeslání potvrzení doručení selhalo (${e.javaClass.simpleName})")
                false
            }
            // Best-effort: neúspěch neblokuje potvrzení dávky (zpráva je bezpečně
            // uložená; potvrzení je jen ozdoba navíc). Když se nepošle, přerušíme -
            // relay je nejspíš nedostupný, další chunk by taky selhal.
            if (!delivered) break
        }
    }

    /**
     * Zašifruje a odešle už zařazenou zprávu (text, fotku nebo soubor) do schránky
     * a aktualizuje její stav (SENT/FAILED). Vrací, zda se doručila.
     */
    fun deliver(context: Context, contact: Contact, message: ChatMessage): Boolean {
        if (message.kind == ChatMessage.Kind.FILE) return deliverFile(context, contact, message)
        val key = contact.keyBase64
        val baseUrl = SettingsRepository(context).getRelayUrl()
        val delivered = try {
            if (key.isNullOrBlank() || baseUrl.isBlank()) {
                false
            } else if (shouldSendRatchet(context, contact)) {
                val msgId = message.wireId?.let { WireExt.fromHex(it) }
                val replyTo = message.replyToWireId?.let { WireExt.fromHex(it) }
                sendOneRatchet(context, contact, baseUrl) {
                    if (message.kind == ChatMessage.Kind.IMAGE && message.mediaPath != null) {
                        ChatEnvelope.buildImagePayload(
                            java.io.File(message.mediaPath).readBytes(), message.timestamp, msgId
                        )
                    } else {
                        ChatEnvelope.buildTextPayload(message.text, message.timestamp, msgId, replyTo)
                    }
                }
            } else {
                val dir = sendDir(contact)
                // Stabilní ID se veze v traileru obálky. Starší appka (minor 1)
                // trailer nečte, takže jí zpráva dorazí jako obyčejná - přesně
                // proto je to tam, kde to je.
                val msgId = message.wireId?.let { WireExt.fromHex(it) }
                val replyTo = message.replyToWireId?.let { WireExt.fromHex(it) }
                val blob = if (message.kind == ChatMessage.Kind.IMAGE && message.mediaPath != null) {
                    ChatEnvelope.sealImage(
                        java.io.File(message.mediaPath).readBytes(), message.timestamp, key, dir,
                        msgId
                    )
                } else {
                    ChatEnvelope.seal(message.text, message.timestamp, key, dir, msgId, replyTo)
                }
                val mailbox = RelayCrypto.mailboxId(key, dir, currentEpoch())
                putFailover(context, mailbox, blob)
            }
        } catch (e: Exception) {
            DiagnosticsLog.warn(TAG, "odeslání zprávy selhalo (${e.javaClass.simpleName})")
            false
        }
        // Jen typ zprávy a výsledek - žádný obsah, žádné jméno kontaktu.
        DiagnosticsLog.log(
            TAG,
            "odeslání zprávy (${message.kind}): ${if (delivered) "doručeno" else "selhalo"}"
        )
        val finalStatus = if (delivered) ChatMessage.Status.SENT else ChatMessage.Status.FAILED
        repoFor(context).updateStatus(contact.id, message.id, finalStatus)
        return delivered
    }

    /**
     * Odešle soubor po kouscích: nejdřív manifest (co přijde a na kolik kousků),
     * pak jednotlivé kousky. Průběh hlásí přes [MediaTransfers]. Vrací úspěch.
     */
    private fun deliverFile(context: Context, contact: Contact, message: ChatMessage): Boolean {
        val key = contact.keyBase64
        val baseUrl = SettingsRepository(context).getRelayUrl()
        val path = message.mediaPath
        val delivered = try {
            if (key.isNullOrBlank() || baseUrl.isBlank() || path == null) {
                false
            } else {
                val file = File(path)
                val totalSize = file.length()
                val totalChunks = ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE)
                    .toInt().coerceAtLeast(1)
                val fileId = MediaTransfers.fromHex(message.id)
                MediaTransfers.setProgress(message.id, 0f)
                if (shouldSendRatchet(context, contact)) {
                    deliverFileRatchet(
                        context, contact, key, baseUrl, message, file, totalChunks, totalSize, fileId
                    )
                } else {
                    val dir = sendDir(contact)
                    val mailbox = RelayCrypto.mailboxId(key, dir, currentEpoch())
                    val manifest = ChatEnvelope.sealFileManifest(
                        fileId, totalChunks, totalSize,
                        message.mimeType ?: "application/octet-stream",
                        message.text, message.timestamp, key, dir
                    )
                    if (!putFailover(context, mailbox, manifest)) {
                        false
                    } else {
                        var index = 0
                        var ok = true
                        file.inputStream().use { input ->
                            val buffer = ByteArray(CHUNK_SIZE)
                            // Krájíme PŘESNĚ totalChunks kousků. read() nemusí naplnit
                            // celý buffer ani uprostřed souboru (krátké čtení), proto
                            // readChunkFully - jinak by vzniklo víc kousků než totalChunks,
                            // příjemce by přebytek zahodil a složil ZKRÁCENÝ soubor.
                            // U 0bajtového souboru (totalChunks=1) se pošle jeden prázdný
                            // kousek, jinak by příjemce uvázl navždy v RECEIVING.
                            while (index < totalChunks) {
                                val read = readChunkFully(input, buffer)
                                val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                                val blob = ChatEnvelope.sealFileChunk(
                                    fileId, index, chunk, message.timestamp, key, dir
                                )
                                if (!putFailover(context, mailbox, blob)) {
                                    ok = false
                                    break
                                }
                                index++
                                MediaTransfers.setProgress(message.id, index.toFloat() / totalChunks)
                            }
                        }
                        ok
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
        MediaTransfers.clearProgress(message.id)
        repoFor(context).updateStatus(
            contact.id, message.id,
            if (delivered) ChatMessage.Status.SENT else ChatMessage.Status.FAILED
        )
        return delivered
    }

    /**
     * Vyzvedne nové zprávy pro daný kontakt a uloží je do historie. Vrací počet
     * nově přijatých zpráv a příznak, zda spojení selhalo (viz [PollResult]).
     */
    fun poll(context: Context, contact: Contact): PollResult {
        val key = contact.keyBase64 ?: return PollResult(0, false)
        val baseUrl = SettingsRepository(context).getRelayUrl()
        if (baseUrl.isBlank()) return PollResult(0, false)

        val repo = repoFor(context)
        val dir = recvDir(contact)
        val epoch = currentEpoch()
        val ratchetStore = RatchetStore(context, storageCrypto)
        // Bootstrap ratchetu, jakmile protějšek inzeruje, že umí major 4 (a jen pro
        // kontakty s definovanou rolí - jinak by se směry schránek kryly). `is Absent`:
        // nepřepisuj existující ani nečitelný stav.
        if (contact.initiator != null &&
            ratchetStore.read(contact.id) is RatchetStore.Load.Absent &&
            WireCompat.peerCanReadMajor(context, contact.id, WireCompat.WIRE_MAJOR_RATCHET)
        ) {
            ratchetStore.save(contact.id, DoubleRatchet.bootstrap(key, sendDir(contact), recvDir(contact)))
        }
        var failed = false
        // Server aspoň jednou odpověděl (get prošel) - pro indikátor dostupnosti.
        var reachable = false
        // Vztahuje se k POSLEDNÍMU [fetch]: schránka byla prokazatelně vyprázdněná
        // (GET prošel a celá dávka bezpečně uložená/odACKovaná, nebo prázdná).
        // Jen tehdy smí backfill posunout podlahu přes danou epochu. Reset uvnitř
        // [fetch] na začátku, aby jeden neúspěch nezůstal viset do dalšího čtení.
        var lastFetchDrained = false
        // Karanténu procházej jen jednou za poll (ne v každém fetchi zvlášť).
        var retryQuarantine = true
        // ID zpráv (wireRef), které jsme v tomhle pollu vyzvedli a uložili -
        // po dokončení pollu se za ně pošle JEDNO potvrzení doručení (druhá
        // fajfka u protějšku). LinkedHashSet: bez duplicit, v pořadí příchodu.
        val deliveredRefs = LinkedHashSet<String>()

        // Dokončí poll: pošle nasbíraná potvrzení doručení (gate + best-effort,
        // viz [flushDeliveryReceipts]) a vrátí výsledek. Jediné místo, kudy se
        // z pollu vrací - ať se receipt pošle bez ohledu na to, kterou větví
        // (ratchet / prev-epocha / aktuální) poll skončil.
        // Kolik zpráv přišlo ze ZÁLOŽNÍCH relayí (failover příjmu, sweep ve finishPoll).
        var secondaryReceived = 0
        // Podlaha backfillu ZAČÁTKU tohoto pollu (ratchet větev ji nastaví před prvním
        // fetchem). Sweep záložních relayí ji čte, aby prohledal celé durabilní přijímací
        // okno - primární backfill mohl v tomhle pollu posunout podlahu přes epochu, která
        // na fallbacku ještě leží (nález v2.1-P1b). Null v legacy větvi (bez backfillu).
        var sweepFloorStart: Int? = null

        // Vyzvedne jednu schránku (dané epochy), otevře bloby a uloží je. Vrací
        // počet nově přijatých zpráv. Síťovou chybu spolkne (0), ale poznamená ji
        // do `failed`, aby volající mohl zpomalit. [url] = z kterého relaye (default
        // primární; sweep níž ho volá pro záložní).
        fun fetch(mailbox: String, waitSeconds: Int, ratchet: Boolean, url: String = baseUrl): Int {
            // Reachable se vztahuje k TÉHLE operaci get (výsledek se vrací podle
            // ní). Reset na začátku, ať prev-epoch get, který uspěl, nemaskuje
            // následné síťové selhání aktuální epochy.
            reachable = false
            lastFetchDrained = false
            val fetched = try {
                transport.get(url, mailbox, waitSeconds)
            } catch (ex: Exception) {
                failed = true
                DiagnosticsLog.warn(TAG, "vyzvednutí zpráv selhalo (${ex.javaClass.simpleName})")
                return 0
            }
            // Get prošel = server je dosažitelný (i kdyby pak selhalo uložení).
            reachable = true
            val blobs = fetched.blobs
            var n = 0
            // Když se cokoli z dávky nepodaří bezpečně uložit ani odložit do
            // karantény, NESMÍME poslat potvrzení - server by zprávu smazal a
            // byla by nenávratně pryč. Radši ať dorazí znovu (duplicitu
            // odfiltruje ReplayGuard).
            var allSafe = true
            // Nepřečteno počítáme jen když konverzace není zrovna otevřená
            // (otevřený chat si zprávu rovnou přečte).
            fun arrived(message: ChatMessage) {
                // Se stabilním ID se dá poznat, že tatáž zpráva dorazila znovu
                // (ReplayGuard chytí jen shodný blob, ale opakované odeslání má
                // jiné IV). Duplicitu zahoď, ale považuj ji za úspěch - jinak by
                // se dávka nikdy nepotvrdila a schránka by se ucpala.
                val result = if (message.wireId != null) {
                    repo.appendIfAbsentByWireId(contact.id, message)
                } else if (repo.append(contact.id, message)) {
                    ChatRepository.AppendResult.ADDED
                } else {
                    ChatRepository.AppendResult.FAILED
                }
                when (result) {
                    // Když se zápis nepovede, NEhlas příjem - jinak by přišla
                    // notifikace o zprávě, která v historii není.
                    ChatRepository.AppendResult.FAILED -> {
                        android.util.Log.e("RelaySync", "Zprávu se nepodařilo uložit do historie")
                        DiagnosticsLog.error(TAG, "zápis zprávy do historie selhal")
                        // Fotka se uložila na disk před zápisem do historie. Blob
                        // se nepotvrdí a dorazí znovu - a další pokus uloží NOVÝ
                        // soubor. Bez smazání tohohle by se při každém neúspěšném
                        // pokusu hromadila osiřelá kopie (až ~1,9 MB). Smaž ji;
                        // opakované doručení fotku uloží čistě.
                        if (message.kind == ChatMessage.Kind.IMAGE) {
                            message.mediaPath?.let { runCatching { File(it).delete() } }
                        }
                        allSafe = false
                    }
                    ChatRepository.AppendResult.DUPLICATE -> {
                        DiagnosticsLog.log(TAG, "zpráva už v historii je, zahazuji duplicitu")
                        // Fotka se ukládá na disk ještě před dedupem, takže by po
                        // duplicitě zůstal soubor, na který nic neodkazuje.
                        if (message.kind == ChatMessage.Kind.IMAGE) {
                            message.mediaPath?.let { runCatching { File(it).delete() } }
                        }
                    }
                    ChatRepository.AppendResult.ADDED -> {
                        if (ActiveChat.currentId != contact.id) repo.incrementUnread(contact.id)
                        n++
                        // Teprve teď může existovat cíl reakce / úpravy / smazání,
                        // které dorazily dřív než tahle zpráva.
                        PendingReactions.applyAll(contact.id) { ref, reactor, emoji, ts ->
                            repo.setReaction(contact.id, ref, reactor, emoji, ts) ==
                                ChatRepository.ReactionResult.APPLIED
                        }
                        PendingMutations.applyAll(contact.id) { ref, op ->
                            applyPendingMutation(repo, context, contact.id, ref, op)
                        }
                    }
                }
                // Potvrzení doručení pošli i za DUPLICATE: protějšek zprávu
                // možná poslal znovu právě proto, že mu naše první potvrzení
                // nedorazilo - ať se ke druhé fajfce nakonec dostane.
                if (message.wireId != null &&
                    (result == ChatRepository.AppendResult.ADDED ||
                        result == ChatRepository.AppendResult.DUPLICATE)
                ) {
                    deliveredRefs.add(message.wireId)
                }
            }

            // K čerstvým blobům přimíchej ty odložené v karanténě (typicky zprávy
            // z jiné verze formátu). Když zase selžou, uloží se zpátky - jakmile
            // si obě strany sednou, samy se doberou.
            val pending = if (retryQuarantine) {
                retryQuarantine = false
                BlobQuarantine.takeAll(context, contact.id)
            } else {
                emptyList()
            }

            // Každý blob zvlášť: výjimka u jednoho (poškozená data, chyba zápisu)
            // nesmí shodit zpracování zbytku dávky - ty zprávy už relay smazal,
            // takže by byly nenávratně pryč.
            // Odložené bloby si nesou čas prvního odložení, ať se jim při
            // opakovaném uložení neresetuje stáří; čerstvé ze sítě začínají teď.
            val batch = pending + blobs.map { BlobQuarantine.Pending(it, System.currentTimeMillis()) }
            for (item in batch) try {
                val blob = item.blob
                // Relay může tentýž blob nabídnout znovu - duplicitu zahoď.
                if (!ReplayGuard.isNew(context, contact.id, blob)) {
                    // Už zpracovaný karanténní blob z karantény ukliď, ať se
                    // nezkouší dokola až do vypršení.
                    item.token?.let { BlobQuarantine.discard(context, contact.id, it) }
                    continue
                }
                // Zprávu s jiným MAJOR nemá smysl zkoušet otevřít - jen si
                // poznamenej, kdo je pozadu, ať to appka umí uživateli říct
                // (dřív se takový blob tiše zahodil a "zprávy prostě nechodily").
                if (!WireCompat.acceptMajor(context, contact.id, blob)) {
                    // NEZAHAZOVAT: server blob při GETu smazal, takže by byl pryč
                    // navždy. Odlož ho a zkus znovu, až si obě strany sednou.
                    DiagnosticsLog.warn(
                        TAG,
                        "nekompatibilní verze formátu (major=${WireCompat.readMajor(blob)}), " +
                            "blob odložen do karantény"
                    )
                    if (!BlobQuarantine.save(context, contact.id, blob, item.firstSeenAt)) {
                        allSafe = false
                    }
                    continue
                }
                // Otevírá se PŘIJÍMACÍM směrem: blob zapsaný do odchozí schránky
                // (a relayí přehozený sem) má v AAD druhý směr a neprojde.
                //
                // Ratchet stav se smí uložit AŽ po ÚSPĚŠNÉM zpracování (tentativní -
                // viz DoubleRatchet.recvKey). Drží se tu a commituje se níž (u
                // Unsupported i v úspěšné větvi).
                var pendingRatchetState: RatchetState? = null
                // Dekóduj podle VLASTNÍHO majoru blobu, ne podle flagu fetche.
                // Karanténa se přimíchává jen do PRVNÍHO fetche pollu, a ten je u
                // ratchet kontaktu vždy ratchet=true; kdyby se řídilo flagem,
                // LEGACY blob (major 3) odložený do karantény ještě před přechodem
                // na ratchet by se navždy zkoušel jako ratchet (readRatchetHeader →
                // null → Unreadable → zpátky do karantény) a po 30 dnech se ztratil.
                val blobIsRatchet = WireCompat.readMajor(blob) == WireCompat.WIRE_MAJOR_RATCHET
                val result: ChatEnvelope.Result = if (blobIsRatchet) {
                    val header = ChatEnvelope.readRatchetHeader(blob)
                    val st = if (header != null) ratchetStore.load(contact.id) else null
                    if (header == null || st == null) {
                        ChatEnvelope.Result.Unreadable
                    } else when (val step = DoubleRatchet.recvKey(st, header.epoch, header.generation, header.msgNo)) {
                        is DoubleRatchet.RecvStep.Key -> {
                            val r = ChatEnvelope.openRatchet(blob, step.aesKey, step.iv, dir)
                            // Stav ulož jen když blob fakt otevřel (Ok/Unsupported) -
                            // při Unreadable ho zahoď, ať podvrh nespotřebuje klíč.
                            if (r !is ChatEnvelope.Result.Unreadable) pendingRatchetState = step.state
                            r
                        }
                        // Skok za strop → karanténa (mezera se může uzavřít mezizprávami).
                        DoubleRatchet.RecvStep.SkipTooLarge -> ChatEnvelope.Result.Unreadable
                        // Novější generace: protějšek dokončil re-key a přešel dál.
                        // Iniciátor s hotovým ss teď přesejne a zkusí zprávu znovu;
                        // jinak karanténa (odpovídající re-key dorazí později).
                        DoubleRatchet.RecvStep.FutureGeneration -> {
                            if (maybeApplyPendingRekey(context, contact, ratchetStore, header.generation)) {
                                val st2 = ratchetStore.load(contact.id)
                                val step2 = if (st2 != null)
                                    DoubleRatchet.recvKey(st2, header.epoch, header.generation, header.msgNo)
                                else null
                                if (step2 is DoubleRatchet.RecvStep.Key) {
                                    val r = ChatEnvelope.openRatchet(blob, step2.aesKey, step2.iv, dir)
                                    if (r !is ChatEnvelope.Result.Unreadable) pendingRatchetState = step2.state
                                    r
                                } else {
                                    ChatEnvelope.Result.Unreadable
                                }
                            } else {
                                ChatEnvelope.Result.Unreadable
                            }
                        }
                        // Pozici už jsme zpracovali → zahoď a nech potvrdit (klíč se
                        // sem už nevrátí, karanténa by nikdy nepomohla).
                        DoubleRatchet.RecvStep.AlreadyConsumed -> {
                            ReplayGuard.remember(context, contact.id, blob)
                            item.token?.let { BlobQuarantine.discard(context, contact.id, it) }
                            continue
                        }
                    }
                } else {
                    ChatEnvelope.open(blob, key, dir)
                }
                // Minor odesílatele, inzerovaný maxMajor i bitmapa schopností
                // jsou až uvnitř šifry, takže jsou známé (a autentizované) teprve
                // teď.
                when (result) {
                    is ChatEnvelope.Result.Ok -> {
                        // ts přetížení = recency guard: přehraná stará (ale autentická)
                        // zpráva nesmí zdegradovat minor/schopnosti protějška (downgrade
                        // relayem, v horším případě strhnutí CAP_RECEIPTS → ztráta zpráv).
                        val ts = result.content.timestamp
                        WireCompat.notePeer(
                            context, contact.id, result.senderMinor,
                            result.maxMajor ?: WireCompat.UNKNOWN, ts
                        )
                        WireCompat.notePeerCapabilities(context, contact.id, result.capabilities, ts)
                    }
                    is ChatEnvelope.Result.Unsupported -> {
                        // I řídicí zprávu, kterou neumíme, protějšek autentizovaně
                        // opatřil minorem, maxMajorem i bitmapou schopností - zaznamenej
                        // je VŠECHNY (ne jen minor), ať se capability-gate ani major
                        // migrace nezaseknou jen proto, že tahle zpráva nese neznámou
                        // funkci (re-audit #9).
                        val ts = result.timestamp
                        WireCompat.notePeer(
                            context, contact.id, result.senderMinor,
                            result.maxMajor ?: WireCompat.UNKNOWN, ts
                        )
                        WireCompat.notePeerCapabilities(context, contact.id, result.capabilities, ts)
                    }
                    ChatEnvelope.Result.Unreadable -> Unit
                }
                // Rozumíme šifře, ale ne obsahu (novější funkce). Karanténa by
                // nepomohla - opakování to nikdy nerozluští, jen by se 30 dní
                // zkoušelo dokola. Zahoď, zapamatuj otisk a nech dávku potvrdit.
                if (result is ChatEnvelope.Result.Unsupported) {
                    DiagnosticsLog.warn(
                        TAG,
                        "zpráva používá funkci, kterou tahle verze neumí " +
                            "(minor protějšku ${result.senderMinor}), zahazuji"
                    )
                    // Ratchet: zprávu jsme autentizovaně „přečetli", jen neumíme
                    // funkci → posuň stav, ať nevznikne mezera. (Zahazujeme obsah,
                    // ne pozici v řetězu.) saveRecv: přepiš jen přijímací půlku.
                    // Když posun stavu selže (disk), dávku NEPOTVRZUJ - stejně jako
                    // úspěšná větev níž (jinak by se ACKlo bez posunu → mezera).
                    val recvSaved = pendingRatchetState?.let { ratchetStore.saveRecv(contact.id, it) } ?: true
                    if (!recvSaved) {
                        DiagnosticsLog.error(TAG, "posun ratchet stavu (Unsupported) selhal, dávku nepotvrzuji")
                        allSafe = false
                    } else {
                        ReplayGuard.remember(context, contact.id, blob)
                        item.token?.let { BlobQuarantine.discard(context, contact.id, it) }
                    }
                    continue
                }
                val ok = result as? ChatEnvelope.Result.Ok
                // Otisk proti replay se zapíše AŽ po úspěšném zpracování (viz níž).
                // Kdyby se zapsal teď a uložení selhalo, další pokus by blob
                // zahodil jako duplicitu - a potvrzení by ho smazalo ze serveru.
                val safeBefore = allSafe
                when (val opened = ok?.content) {
                    is ChatEnvelope.Opened.Text -> arrived(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            outgoing = false,
                            text = opened.text,
                            timestamp = opened.timestamp,
                            status = ChatMessage.Status.RECEIVED,
                            // Volí ho protějšek, proto zvlášť od našeho `id`.
                            wireId = ok.msgIdHex,
                            replyToWireId = ok.replyToHex
                        )
                    )

                    // KEM re-key (PCS): řídicí zpráva, není do historie. Nejdřív ulož
                    // posun recvKey na TÉTO zprávě (aby applyRekey v handleRekey stavěl
                    // na uloženém stavu a úspěšná větev to nepřepsala), pak zpracuj
                    // handshake. Když se posun neuloží, dávku nepotvrzuj.
                    is ChatEnvelope.Opened.Rekey -> {
                        val saved = pendingRatchetState?.let { ratchetStore.saveRecv(contact.id, it) } ?: true
                        pendingRatchetState = null
                        if (!saved) {
                            allSafe = false
                        } else {
                            handleRekey(context, contact, baseUrl, ratchetStore, opened)
                        }
                    }

                    // Reakce: není to zpráva do historie, jen se přilepí k cílové
                    // zprávě. Schválně NEjde přes arrived() - nesmí zvýšit počet
                    // nepřečtených ani vyvolat notifikaci.
                    is ChatEnvelope.Opened.Reaction -> {
                        val emoji = if (opened.remove) null else opened.emoji
                        when (
                            repo.setReaction(
                                contact.id, opened.targetHex, ChatMessage.REACTOR_PEER,
                                emoji, opened.timestamp
                            )
                        ) {
                            ChatRepository.ReactionResult.APPLIED -> {
                                // Notifikace: protějšek reagoval na NAŠI zprávu. Jen
                                // přidání (ne zrušení), jen na naši odchozí zprávu a
                                // jen když konverzaci nemáme otevřenou (tam si reakci
                                // zobrazí obrazovka sama).
                                if (!opened.remove && ActiveChat.currentId != contact.id) {
                                    val target = repo.getMessages(contact.id).firstOrNull {
                                        it.wireRef == opened.targetHex && it.outgoing && !it.deleted
                                    }
                                    if (target != null) {
                                        ChatNotifications.notifyReaction(
                                            context, contact.id, contact.name, opened.emoji, target
                                        )
                                    }
                                }
                            }
                            // Cíl zatím nedorazil - odlož, ať se reakce neztratí.
                            // Dávku klidně potvrdíme: reakci si držíme my.
                            ChatRepository.ReactionResult.TARGET_MISSING -> {
                                DiagnosticsLog.log(TAG, "reakce dorazila dřív než zpráva, odkládám")
                                PendingReactions.remember(
                                    contact.id, opened.targetHex, ChatMessage.REACTOR_PEER,
                                    emoji, opened.timestamp
                                )
                            }
                            // Zápis selhal - dávku nepotvrzuj, ať dorazí znovu.
                            ChatRepository.ReactionResult.FAILED -> {
                                DiagnosticsLog.error(TAG, "uložení reakce selhalo")
                                allSafe = false
                            }
                        }
                    }

                    // Potvrzení doručení: NAŠE odchozí zprávy s těmito ID protějšek
                    // vyzvedl → označ je za doručené na zařízení (druhá fajfka).
                    // Není to zpráva do historie - schválně NEjde přes arrived()
                    // (nezvyšuje nepřečtené ani nenotifikuje).
                    is ChatEnvelope.Opened.Delivery -> {
                        for (ref in opened.targetHexes) {
                            when (repo.markDelivered(contact.id, ref)) {
                                // Zápis selhal - dávku nepotvrzuj, ať dorazí znovu.
                                ChatRepository.DeliveryResult.FAILED -> allSafe = false
                                // Označeno, nebo cíl v historii není - obojí se smí
                                // potvrdit (u chybějícího cíle není co dělat).
                                ChatRepository.DeliveryResult.UPDATED,
                                ChatRepository.DeliveryResult.TARGET_MISSING -> Unit
                            }
                        }
                    }

                    // Úprava textu: přepíše text cílové PŘÍCHOZÍ zprávy. Není to
                    // zpráva do historie - schválně NEjde přes arrived() (nezvyšuje
                    // nepřečtené ani nenotifikuje). outgoing = false: protějšek smí
                    // upravit jen svoje zprávy, ne ty naše.
                    is ChatEnvelope.Opened.Edit -> {
                        when (
                            repo.applyEdit(
                                contact.id, opened.targetHex, opened.newText,
                                opened.timestamp, outgoing = false
                            )
                        ) {
                            ChatRepository.MutationResult.APPLIED -> Unit
                            // Cíl zatím nedorazil - odlož, ať se úprava neztratí.
                            ChatRepository.MutationResult.TARGET_MISSING -> {
                                DiagnosticsLog.log(TAG, "úprava dorazila dřív než zpráva, odkládám")
                                PendingMutations.remember(
                                    contact.id, opened.targetHex,
                                    PendingMutations.Op.Edit(opened.newText, opened.timestamp)
                                )
                            }
                            // Zápis selhal - dávku nepotvrzuj, ať dorazí znovu.
                            ChatRepository.MutationResult.FAILED -> {
                                DiagnosticsLog.error(TAG, "uložení úpravy selhalo")
                                allSafe = false
                            }
                        }
                    }

                    // Smazání pro všechny: z cílové PŘÍCHOZÍ zprávy udělá náhrobek.
                    // Stejná pravidla jako u úpravy (mimo arrived(), outgoing = false).
                    is ChatEnvelope.Opened.Delete -> {
                        when (
                            repo.deleteForBoth(context, contact.id, opened.targetHex, outgoing = false)
                        ) {
                            ChatRepository.MutationResult.APPLIED -> Unit
                            ChatRepository.MutationResult.TARGET_MISSING -> {
                                DiagnosticsLog.log(TAG, "smazání dorazilo dřív než zpráva, odkládám")
                                PendingMutations.remember(
                                    contact.id, opened.targetHex,
                                    PendingMutations.Op.Delete(opened.timestamp)
                                )
                            }
                            ChatRepository.MutationResult.FAILED -> {
                                DiagnosticsLog.error(TAG, "uložení smazání selhalo")
                                allSafe = false
                            }
                        }
                    }

                    is ChatEnvelope.Opened.Image -> {
                        val path = ChatMediaStore.save(context, opened.bytes)
                        if (path == null) {
                            // Zápis fotky selhal (plný disk). NEZAHAZOVAT - odlož
                            // a nepotvrzuj, ať ji server podrží na další pokus.
                            DiagnosticsLog.error(TAG, "uložení přijaté fotky selhalo")
                            BlobQuarantine.save(context, contact.id, blob, item.firstSeenAt)
                            allSafe = false
                            continue
                        }
                        arrived(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                outgoing = false,
                                text = "",
                                timestamp = opened.timestamp,
                                status = ChatMessage.Status.RECEIVED,
                                kind = ChatMessage.Kind.IMAGE,
                                mediaPath = path,
                                wireId = ok.msgIdHex
                            )
                        )
                    }

                    // Ohlášení souboru: založíme příjem a hned ukážeme bublinu
                    // se stavem „přijímá se" (kousky dorazí vzápětí).
                    is ChatEnvelope.Opened.FileManifest -> {
                        val idHex = MediaTransfers.hex(opened.fileId)
                        if (!MediaTransfers.startReceive(
                                context, idHex, opened.totalChunks, opened.totalSize,
                                opened.mimeType, opened.fileName
                            )
                        ) {
                            // Bez metadat by kousky nešlo složit a manifest by se
                            // mezitím potvrdil a smazal ze serveru.
                            DiagnosticsLog.error(TAG, "založení příjmu souboru selhalo")
                            BlobQuarantine.save(context, contact.id, blob, item.firstSeenAt)
                            allSafe = false
                            continue
                        }
                        MediaTransfers.setProgress(idHex, 0f)
                        // Id zprávy je odvozené z fileId, takže opakovaně poslaný
                        // manifest téhož souboru by vytvořil DVĚ zprávy se stejným
                        // id - a LazyColumn klíčovaný id by obrazovku chatu shodil.
                        val added = repo.appendIfAbsent(
                            contact.id,
                            ChatMessage(
                                id = idHex,
                                outgoing = false,
                                text = opened.fileName,
                                timestamp = opened.timestamp,
                                status = ChatMessage.Status.RECEIVING,
                                kind = ChatMessage.Kind.FILE,
                                mediaPath = null,
                                mimeType = opened.mimeType
                            )
                        )
                        when (added) {
                            // Zápis selhal (nešla přečíst historie) - dávku
                            // NEPOTVRZOVAT, jinak by manifest relay smazal a
                            // zpráva o souboru by zmizela, zatímco kousky by se
                            // poskládaly do souboru, na který nic neodkazuje.
                            ChatRepository.AppendResult.FAILED -> {
                                DiagnosticsLog.error(TAG, "zápis manifestu do historie selhal")
                                BlobQuarantine.save(context, contact.id, blob, item.firstSeenAt)
                                allSafe = false
                                continue
                            }
                            ChatRepository.AppendResult.DUPLICATE -> Unit
                            ChatRepository.AppendResult.ADDED -> {
                                if (ActiveChat.currentId != contact.id) {
                                    repo.incrementUnread(contact.id)
                                }
                                n++
                                // Cíl reakce / úpravy / smazání souboru může
                                // existovat až teď.
                                PendingReactions.applyAll(contact.id) { ref, reactor, emoji, ts ->
                                    repo.setReaction(contact.id, ref, reactor, emoji, ts) ==
                                        ChatRepository.ReactionResult.APPLIED
                                }
                                PendingMutations.applyAll(contact.id) { ref, op ->
                                    applyPendingMutation(repo, context, contact.id, ref, op)
                                }
                            }
                        }
                        // Kousky mohly dorazit dřív než manifest (zaparkované) -
                        // pak je soubor hotový už teď a nikdo by ho nesložil.
                        if (MediaTransfers.receivedCount(context, idHex) >= opened.totalChunks) {
                            if (!finishFile(context, repo, contact.id, idHex, deliveredRefs)) allSafe = false
                        }
                    }

                    // Kousek souboru: ulož a po posledním slož výsledek.
                    is ChatEnvelope.Opened.FileChunk -> {
                        val idHex = MediaTransfers.hex(opened.fileId)
                        val stored = MediaTransfers.storeChunk(context, idHex, opened.index, opened.bytes)
                        if (!stored.written) {
                            // Kousek se nepodařilo uložit (plný disk). Bez tohohle
                            // by se potvrdil a soubor by nešlo nikdy složit.
                            DiagnosticsLog.error(TAG, "uložení kousku souboru selhalo")
                            BlobQuarantine.save(context, contact.id, blob, item.firstSeenAt)
                            allSafe = false
                            continue
                        }
                        val complete = stored.complete
                        val total = MediaTransfers.totalChunks(context, idHex)
                        if (total > 0) {
                            MediaTransfers.setProgress(
                                idHex,
                                MediaTransfers.receivedCount(context, idHex).toFloat() / total
                            )
                        }
                        if (complete) {
                            if (!finishFile(context, repo, contact.id, idHex, deliveredRefs)) allSafe = false
                        }
                    }

                    // Dešifrování neprošlo (jiná verze formátu, poškození, cizí
                    // klíč). Odlož a hlas - tichý `continue` tady kdysi stál
                    // uživatele zprávy, které už nešlo nijak získat zpátky.
                    null -> {
                        android.util.Log.w(
                            "RelaySync",
                            "Blob se nepodařilo otevřít (${blob.size} B, major=" +
                                "${WireCompat.readMajor(blob)}), odkládám do karantény"
                        )
                        DiagnosticsLog.warn(
                            TAG,
                            "blob se nepodařilo dešifrovat (${blob.size} B, " +
                                "major=${WireCompat.readMajor(blob)}), odložen do karantény"
                        )
                        if (!BlobQuarantine.save(context, contact.id, blob, item.firstSeenAt)) {
                            allSafe = false
                        }
                    }
                }
                // Zpracováno bez zádrhelu - teprve teď si blob zapamatuj,
                // ať ho příště nezpracujeme podruhé.
                if (ok != null && allSafe == safeBefore) {
                    // Ratchet: teprve TEĎ (po úspěšném dispatchi) se smí posunout stav.
                    // Když se neuloží, dávku nepotvrzuj - blob dorazí znovu a klíč se
                    // re-derivuje (stav se nezměnil). ReplayGuard/quarantine úklid pak
                    // taky ne, ať se blob zpracuje znovu celý.
                    // saveRecv: přepiš jen přijímací půlku (odesílání běží souběžně).
                    val stateSaved = pendingRatchetState?.let { ratchetStore.saveRecv(contact.id, it) } ?: true
                    if (!stateSaved) {
                        DiagnosticsLog.error(TAG, "uložení ratchet stavu selhalo, dávku nepotvrzuji")
                        allSafe = false
                    } else {
                        ReplayGuard.remember(context, contact.id, blob)
                        // Úspěšně zpracovaný karanténní blob teď z karantény ukliď -
                        // do teď tam ležel jako jediná kopie (viz BlobQuarantine.takeAll).
                        item.token?.let { BlobQuarantine.discard(context, contact.id, it) }
                    }
                }
            } catch (ex: Throwable) {
                // Throwable: dešifrování velkého blobu může hodit OutOfMemoryError,
                // který není Exception a shodil by celou poll smyčku.
                // Jeden vadný blob nesmí shodit zbytek dávky.
                android.util.Log.w("RelaySync", "Zpracování blobu selhalo, pokračuji", ex)
                DiagnosticsLog.warn(TAG, "zpracování blobu selhalo (${ex.javaClass.simpleName})")
                // Blob odlož: pokud přišel z karantény, `takeAll` ho z disku už
                // smazal a ze serveru je dávno pryč - bez tohohle by byl ztracený.
                // Díky odložení smíme dávku potvrdit a schránka se neucpe.
                val parked = runCatching {
                    BlobQuarantine.save(context, contact.id, item.blob, item.firstSeenAt)
                }.getOrDefault(false)
                if (!parked) allSafe = false
            }

            // Potvrzení POSÍLÁME JEN když je celá dávka bezpečně uložená nebo
            // odložená v karanténě - teprve tehdy ji server smí zahodit. Jinak
            // ať dorazí znovu; duplicitu odfiltruje ReplayGuard.
            // `lastFetchDrained` = schránka je vyprázdněná (relay ji smaže) →
            // backfill smí posunout podlahu přes tuhle epochu.
            if (fetched.ackSeq >= 0 && allSafe) {
                if (transport.ack(url, mailbox, fetched.ackSeq)) lastFetchDrained = true
                else failed = true
            } else if (!allSafe) {
                DiagnosticsLog.warn(TAG, "dávka není celá uložená, potvrzení se neposílá")
                failed = true
            } else {
                // allSafe && ackSeq < 0 → nic k potvrzení, schránka byla prázdná.
                lastFetchDrained = true
            }
            if (n > 0) DiagnosticsLog.log(TAG, "přijato $n nových zpráv")
            return n
        }

        // Dokončí poll: FAILOVER PŘÍJMU (sweep záložních relayí) + potvrzení
        // doručení. Jediné místo, kudy se z pollu vrací.
        fun finishPoll(n: Int, allowSweep: Boolean = true): PollResult {
            // Sweep záložních relayí: protějšek mohl při výpadku primárního poslat
            // tam. Aditivní (jen fetche na jiné URL; primární logika nedotčená,
            // duplicity řeší ReplayGuard/wireId). Když primární právě NEodpověděl
            // (!reachable), zkus zálohy HNED; jinak jen ŘÍDCE (šetření baterie).
            // Platí se jen s nakonfigurovanými zálohami (jinak seznam prázdný).
            // [allowSweep]=false: volající ještě neposunul stav durabilně (migrační
            // bail, bf<0) - sweep by přes fetch→saveRecv posunul recvEpoch mimo
            // durabilní podlahu → ztráta epochy (nález v2.1-P1a).
            val fallbacks = SettingsRepository(context).getRelayUrls().drop(1)
            val nowMs = System.currentTimeMillis()
            val lastSwept = secondarySweptAt[contact.id]
            val sweep = allowSweep && fallbacks.isNotEmpty() &&
                (!reachable || lastSwept == null || nowMs - lastSwept >= SECONDARY_SWEEP_MS)
            if (sweep) {
                // Stav záloh NESMÍ ovlivnit backoff/dostupnost primárního (mrtvá
                // záloha by jinak shodila reachable a nutila backoff i při zdravém
                // primárním) - ulož a obnov.
                val savedFailed = failed
                val savedReachable = reachable
                val rs = ratchetStore.load(contact.id)
                for (fb in fallbacks) {
                    if (rs != null) {
                        val re2 = rs.recvEpoch
                        // Prohledej celé DURABILNÍ přijímací okno [podlaha-začátku-pollu ..
                        // re2+1], ne jen 2 sousední epochy: zpráva ležící JEN na záložním
                        // relayi v nižší epoše (primární ji floornul jako prázdnou) by se
                        // jinak nikdy nevyzvedla (nález v2.1-P1b). Podlaha ZAČÁTKU pollu
                        // (ne aktuální) proto, že primární backfill ji mohl v tomhle pollu
                        // posunout přes epochu, která na fallbacku ještě leží. Strop
                        // LOOKAHEAD drží počet onion GETů rozumný; zbytek okna dožene další
                        // sweep, jak podlaha stoupá.
                        val floorStart = (sweepFloorStart ?: re2).coerceAtMost(re2)
                        val to = minOf(re2, floorStart + DoubleRatchet.LOOKAHEAD)
                        val sweepEpochs = ((floorStart..to) + re2 + (re2 + 1)).distinct()
                        for (e in sweepEpochs) {
                            secondaryReceived += fetch(RelayCrypto.ratchetMailboxId(key, dir, e), 0, ratchet = true, url = fb)
                        }
                    } else {
                        // Legacy sweep: fetchni i PŘEDCHOZÍ epochu (epoch-1), ne jen
                        // aktuální. Zpráva položená na fallback do VČEREJŠÍ epochy
                        // (primární down přes přelom dne) by se z fallbacku jinak nikdy
                        // nevyzvedla a po TTL zmizela (re-audit #10). Symetrické k
                        // prev-epoch kontrole primární cesty; sweep je throttlovaný
                        // (SECONDARY_SWEEP_MS), takže jeden GET navíc je zanedbatelný.
                        secondaryReceived += fetch(RelayCrypto.mailboxId(key, dir, epoch), 0, ratchet = false, url = fb)
                        secondaryReceived += fetch(RelayCrypto.mailboxId(key, dir, epoch - 1), 0, ratchet = false, url = fb)
                    }
                }
                failed = savedFailed
                reachable = savedReachable
                secondarySweptAt[contact.id] = nowMs
            }
            flushDeliveryReceipts(context, contact, deliveredRefs)
            return PollResult(n + secondaryReceived, failed, reachable)
        }

        // Ratchet aktivní (stav existuje) → příjem na RATCHET schránky. Odesílatel
        // posílá major 4; legacy krátce dočítáme jako grace (zprávy v letu).
        val ratchetState = ratchetStore.load(contact.id)
        if (ratchetState != null) {
            var received = 0
            val re = ratchetState.recvEpoch
            val now = System.currentTimeMillis()
            // Podlaha na začátku pollu (než ji sousední fetch / backfill posune) - sweep
            // fallbacků z ní odvodí spodní hranu okna (viz finishPoll / nález v2.1-P1b).
            sweepFloorStart = ratchetState.backfillFloor.let { if (it < 0) re else it }
            // Migrace stavů z doby před polem `backfillFloor` (bf<0): připni podlahu
            // na aktuální recvEpoch DURABILNĚ, JEŠTĚ než sousední fetch recvEpoch
            // posune - jinak by restart mezi posunem a backfillem podlahu (a s ní
            // celý interval přeskočených epoch) ztratil. Nové stavy mají bf>=0
            // (bootstrap = 0), takže tenhle zápis proběhne max jednou za život stavu.
            // Když durabilní zápis podlahy SELŽE, poll ukonči DŘÍV, než cokoli posune
            // recvEpoch (jako „save před ACK") - jinak by restart po posunu recvEpoch,
            // ale před připnutím podlahy, epochu ztratil (nález round-3-a-1). failed=true
            // → volající zpomalí (backoff), migrace se zkusí příště.
            if (ratchetState.backfillFloor < 0 && !ratchetStore.updateBackfillFloor(contact.id, re)) {
                failed = true
                // allowSweep=false: podlaha není durabilní, takže sweep NESMÍ posunout
                // recvEpoch (jako „save před ACK") - jinak nález v2.1-P1a.
                return finishPoll(0, allowSweep = false)
            }
            // Rychle: sousední epocha (odesílatel mohl posunout epochu po 32 zprávách).
            // Chytí běžný jednokrokový posun HNED, bez čekání na beacon.
            received += fetch(RelayCrypto.ratchetMailboxId(key, dir, re + 1), 0, ratchet = true)
            // BACKFILL přeskočených epoch (nález v2.0-27 + jeho reziduum). Sousední
            // fetch re+1 mohl posunout recvEpoch PŘES epochu re, jejíž BLOBY (na které
            // máme skipped klíče) pořád leží na relayi. `recvKey` posune recvEpoch už
            // samotným dešifrováním vyšší epochy, ale schránky nižších epoch se musí
            // teprve dočíst - jinak se po TTL relaye (24 h) ztratí až celá epocha (do
            // 32) zpráv (porušení RATCHET_WIRE.md §6 „pollni okno e_recv..e_recv+W").
            //
            // Podlaha `backfillFloor` je DURABILNÍ a NEZÁVISLÁ na recvEpoch: posune se
            // teprve, když se schránka epochy prokazatelně vyprázdní (GET prošel a celá
            // dávka odACKovaná, `lastFetchDrained`). Když backfill GET přechodně selže
            // (rozpadlý Tor okruh), podlaha zůstane a příští poll (i po restartu) to
            // zkusí znovu → reziduum v2.0-27: jediný neúspěšný pokus epochu neztratí.
            // Zastropováno LOOKAHEAD - to jen rozloží dlouhou díru do víc pollů (za
            // jeden poll se posune max o LOOKAHEAD), zbytek dožene další poll. POZOR:
            // dešifrovatelnost backfill blobů NEzávisí na LOOKAHEAD, ale na tom, že
            // skipped-store pořád drží klíče pro NEJNIŽŠÍ dočítané msgNo. `boundSkipped`
            // ořezává na SKIP_MAX a odhazuje nejnižší (gen,msgNo) první - přesně ty,
            // co backfill (odspodu nahoru) potřebuje.
            //
            // INVARIANT (nález 2026-07-29-v2.3-RA3): skipped-store nesmí přerůst
            // SKIP_MAX, jinak `boundSkipped` evikuje ty nejnižší (backfill-potřebné)
            // klíče → jejich bloby spadnou v recvKey do AlreadyConsumed, poll je ACKne
            // a relay smaže = tichá ztráta. Mezera se AKUMULUJE přes víc pollů (leap +
            // částečný backfill + další leap), takže to NENÍ prostý vztah konstant
            // `(recvEpoch - backfillFloor)*EPOCH_MSGS <= SKIP_MAX` - naivní hlídací
            // test tvaru `SKIP_MAX >= f(EPOCH_MSGS, LOOKAHEAD)` by byl ŠPATNĚ. Invariant
            // teď VYNUCUJE `recvKey` běhově (`DoubleRatchet.wouldOverfillSkipped`):
            // leap, který by store přeplnil, odmítne (SkipTooLarge → karanténa,
            // recvEpoch se NEposune); backfill store sníží a příště se leap vejde
            // (self-healing). Kryto `DoubleRatchetTest` (invariant recvKey) +
            // `RatchetPipelineTest.dvaLeapy_sBackfillem_neztratiZadnouZpravu` (roura).
            run {
                val cur = ratchetStore.load(contact.id) ?: ratchetState
                val recvNow = cur.recvEpoch
                // bf<0 (migrace ještě nezapsala / selhala) → ber re jako podlahu.
                val floor = cur.backfillFloor.let { if (it < 0) re else minOf(it, recvNow) }
                if (floor < recvNow) {
                    val end = minOf(recvNow, floor + DoubleRatchet.LOOKAHEAD)
                    var newFloor = floor
                    var contiguous = true
                    var e = floor
                    while (e < end) {
                        received += fetch(RelayCrypto.ratchetMailboxId(key, dir, e), 0, ratchet = true)
                        // Podlahu posuň jen přes SOUVISLE vyprázdněné epochy odspodu:
                        // první nevyprázdněná (GET/uložení selhalo) ji zastaví, ať se
                        // výš ležící díra příště dočte znovu.
                        if (contiguous && lastFetchDrained) newFloor = e + 1 else contiguous = false
                        e++
                    }
                    if (newFloor > floor) ratchetStore.updateBackfillFloor(contact.id, newFloor)
                }
            }
            // Legacy grace (zprávy odeslané ještě PŘED přepnutím protějšku na ratchet):
            // po startu a pak už jen ŘÍDCE (ne každý cyklus). Po přepnutí je legacy
            // schránka trvale prázdná, tak zbytečně neplatíme onion GET každých 60 s -
            // TTL relaye (24 h) >> interval, takže žádná „zpráva v letu" se neztratí.
            if (shouldCheckLegacyGraceAt(now, legacyGraceCheckedAt[contact.id], LEGACY_GRACE_RECHECK_MS)) {
                received += fetch(RelayCrypto.mailboxId(key, dir, epoch), 0, ratchet = false)
                // Čas zaznamenej JEN když get prošel (jinak zkus dřív - jako prev-epocha).
                if (reachable) legacyGraceCheckedAt[contact.id] = now
            }
            // Legacy grace i pro PŘEDCHOZÍ denní epochu kolem přelomu dne: legacy
            // zpráva odeslaná protějškem těsně před přechodem na ratchet mohla spadnout
            // do VČEREJŠÍ denní schránky. Bez tohohle by se po přechodu příjemce na
            // ratchet už nikdy nevyzvedla (nález v2.0-32 / B-N2) - stejná asymetrie,
            // jakou legacy větev (níž) řeší přes shouldCheckPrevEpoch. Týž řídký
            // mechanismus (jen překryvové okno po přelomu dne + 30min pojistka na
            // rozjeté hodiny), takže mimo to úzké okno žádný GET navíc.
            if (shouldCheckPrevEpoch(contact.id, epoch)) {
                received += fetch(RelayCrypto.mailboxId(key, dir, epoch - 1), 0, ratchet = false)
                if (reachable) {
                    prevEpochChecked[contact.id] = epoch
                    prevEpochCheckedAt[contact.id] = System.currentTimeMillis()
                }
            }
            var target = ratchetStore.load(contact.id)?.recvEpoch ?: re
            if (target == re) {
                // Sousední epocha nic nepřinesla → odesílatel mohl utéct dál (dlouhé
                // offline). Beacon (ukazatel z neměnného M) řekne, na kterou epochu.
                // Čte se JEN v tomhle případě, ať se za běžného provozu neplatí navíc.
                // (Beacon se ZÁMĚRNĚ NEgatuje časem: je potřeba i na pollu hned po
                // předchozím - obnova vzdálené epochy nesmí čekat na „mezeru".)
                val beaconEpoch = readBeacon(baseUrl, key, dir)
                if (beaconEpoch != null && beaconEpoch > re) target = beaconEpoch
            }
            // Long-poll cílové ratchet epochy (aktuální, sousední nebo z beaconu).
            received += fetch(RelayCrypto.ratchetMailboxId(key, dir, target), LONGPOLL_SECONDS, ratchet = true)
            // Auto-politika PCS re-key (Fáze 4c): teď víme, jestli protějšek právě
            // psal (received) - zváž zahájení/zopakování re-key.
            maybeAutoRekey(context, contact, ratchetStore, received)
            return finishPoll(received)
        }

        // Kolem přelomu dne nejdřív rychlá (neblokující) kontrola předchozí epochy;
        // když něco přišlo, ukaž to hned. Mimo to okno se přeskočí - jinak by se
        // každý cyklus platil onion request navíc.
        if (shouldCheckPrevEpoch(contact.id, epoch)) {
            val prev = fetch(RelayCrypto.mailboxId(key, dir, epoch - 1), 0, ratchet = false)
            // Za vyřízenou ji považuj AŽ po úspěšném dotazu. Kdyby se označila
            // rovnou, jediný neúspěšný pokus (nedostupný server) by kontrolu
            // spotřeboval a zpráva odeslaná těsně před přelomem dne by se už
            // nikdy nevyzvedla - tichá a nevratná ztráta.
            if (!failed) {
                prevEpochChecked[contact.id] = epoch
                prevEpochCheckedAt[contact.id] = System.currentTimeMillis()
            }
            if (prev > 0) return finishPoll(prev)
        }
        // Long-poll aktuální epochy - server podrží spojení, dokud nedorazí zpráva,
        // takže chodí skoro okamžitě a mezitím se nic nebudí.
        return finishPoll(fetch(RelayCrypto.mailboxId(key, dir, epoch), LONGPOLL_SECONDS, ratchet = false))
    }

    /**
     * Dokončí příjem souboru po posledním kousku. Vrací **true**, když se dávka
     * SMÍ potvrdit (dokončení trvale zapsané, nebo obsah trvale vadný), a
     * **false**, když se má zkusit znovu (přechodná chyba složení, nebo selhání
     * zápisu stavu) - volající pak nastaví `allSafe = false`, dávku nepotvrdí a
     * kousky zůstanou ležet pro další pokus.
     *
     * Úklid dočasných kousků se dělá AŽ po ÚSPĚŠNÉM zápisu stavu do historie -
     * jinak by selhání zápisu smazalo kousky dřív, než je dokončení zaznamenané,
     * a přenos by navždy uvázl ve stavu „přijímá se".
     */
    private fun finishFile(
        context: Context,
        repo: ChatRepository,
        contactId: String,
        idHex: String,
        deliveredRefs: MutableSet<String>? = null
    ): Boolean = when (val r = MediaTransfers.assemble(context, idHex)) {
        is MediaTransfers.AssembleResult.Done -> {
            if (repo.updateMedia(contactId, idHex, r.path, ChatMessage.Status.RECEIVED)) {
                MediaTransfers.cleanup(context, idHex)
                MediaTransfers.clearProgress(idHex)
                // Soubor je celý u nás → potvrď doručení (wireRef souboru = hex
                // fileId = idHex). Až po úspěšném zápisu, ať se nepotvrzuje předčasně.
                deliveredRefs?.add(idHex)
                true
            } else {
                DiagnosticsLog.error(TAG, "zápis stavu souboru selhal")
                false
            }
        }
        MediaTransfers.AssembleResult.Corrupt -> {
            if (repo.updateMedia(contactId, idHex, null, ChatMessage.Status.FAILED)) {
                MediaTransfers.cleanup(context, idHex)
                MediaTransfers.clearProgress(idHex)
                true
            } else {
                DiagnosticsLog.error(TAG, "zápis stavu souboru selhal")
                false
            }
        }
        MediaTransfers.AssembleResult.Retry -> {
            DiagnosticsLog.error(TAG, "složení souboru se nezdařilo (přechodně), zkusím znovu")
            false
        }
    }
}

/**
 * Přečte z [input] až [buffer].size bajtů: opakuje `read()`, dokud buffer nenaplní
 * nebo nenarazí na konec streamu. Vrací počet skutečně načtených bajtů (0 = hned
 * konec).
 *
 * [InputStream.read] NEGARANTUJE naplnění celého bufferu - klidně vrátí míň i
 * uprostřed souboru. Kdyby se kousky souboru krájely přímo podle návratové hodnoty
 * `read()`, krátké čtení by vyrobilo VÍC kousků, než kolik hlásí manifest
 * (`totalChunks`); příjemce by přebytek zahodil a složil ZKRÁCENÝ soubor označený
 * jako doručený. Proto se každý kousek plní až po `CHUNK_SIZE` a kratší je jen
 * poslední, na skutečném konci souboru.
 */
internal fun readChunkFully(input: InputStream, buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val r = input.read(buffer, filled, buffer.size - filled)
        if (r < 0) break
        filled += r
    }
    return filled
}

/**
 * Rozhodne, jestli teď kontrolovat schránku PŘEDCHOZÍ epochy. Čistá funkce, aby
 * šla otestovat bez sítě. Vrací true, když:
 *  - se pro tuhle epochu ještě nekontrolovala (start procesu / hned po přelomu), NEBO
 *  - jsme v těsném okně po přelomu ([overlapMs]) - normální rollover dne, NEBO
 *  - od poslední kontroly uplynulo aspoň [recheckMs] - ŘÍDKÁ POJISTKA na rozjeté
 *    hodiny: bez ní by zpráva od odesílatele s časem pozadu o víc než [overlapMs]
 *    uvízla ve schránce, kterou už nikdo nečte (tichá ztráta po TTL).
 */
internal fun shouldCheckPrevEpochAt(
    now: Long,
    epoch: Long,
    lastCheckedEpoch: Long?,
    lastCheckedAt: Long?,
    epochMs: Long,
    overlapMs: Long,
    recheckMs: Long
): Boolean {
    if (lastCheckedEpoch != epoch) return true
    if (now % epochMs < overlapMs) return true
    if (lastCheckedAt == null) return true
    return now - lastCheckedAt >= recheckMs
}

/**
 * Má se teď u ratchet kontaktu přečíst LEGACY grace schránka? Čistá funkce (bez
 * sítě), ať jde otestovat. První poll ([lastCheckedAt] == null) VŽDY, pak už jen
 * jednou za [recheckMs] - po startu se legacy dočte, dál je to řídká pojistka.
 */
internal fun shouldCheckLegacyGraceAt(now: Long, lastCheckedAt: Long?, recheckMs: Long): Boolean =
    lastCheckedAt == null || now - lastCheckedAt >= recheckMs

/**
 * Má outbox (znovu) doručit tuhle odchozí zprávu? Čistá funkce (testovatelná bez
 * sítě). Jen odchozí; FAILED vždy (relay byl nedostupný); SENDING jen když je
 * starší než [staleMs] (zaseklá - proces umřel v deliveru); jinak (SENT/přijaté) ne.
 */
internal fun outboxNeedsSend(
    outgoing: Boolean,
    status: ChatMessage.Status,
    ageMs: Long,
    staleMs: Long
): Boolean = outgoing && when (status) {
    ChatMessage.Status.FAILED -> true
    ChatMessage.Status.SENDING -> ageMs > staleMs
    else -> false
}

/**
 * Má se zpráva ve stavu SENT (na relayi, ale bez potvrzení doručení) poslat
 * ZNOVU? Čistá funkce (testovatelná bez sítě).
 *
 * Podmínky (VŠECHNY musí platit):
 *  - stav SENT (DELIVERED už dorazilo, FAILED/SENDING řeší [outboxNeedsSend]),
 *  - protějšek umí doručenky ([peerSupportsReceipts]) - jinak by DELIVERED nikdy
 *    nepřišlo a posílali bychom donekonečna,
 *  - stáří v okně `[resendAfterMs, giveUpAfterMs)` - před prahem dej doručence
 *    čas, po stropu to vzdej (příjemce je dlouho offline, blob stejně vypršel).
 */
internal fun receiptResendDue(
    status: ChatMessage.Status,
    ageMs: Long,
    peerSupportsReceipts: Boolean,
    resendAfterMs: Long,
    giveUpAfterMs: Long
): Boolean =
    status == ChatMessage.Status.SENT &&
        peerSupportsReceipts &&
        ageMs >= resendAfterMs &&
        ageMs < giveUpAfterMs

/**
 * Smí se teď zpráva znovuodeslat, nebo je moc brzy od minulého pokusu? Throttle
 * proti hameru sítě: `null` (ještě nikdy) → ano, jinak až po [intervalMs].
 * Čistá funkce.
 */
internal fun resendThrottleOk(now: Long, lastResendAt: Long?, intervalMs: Long): Boolean =
    lastResendAt == null || now - lastResendAt >= intervalMs

/**
 * Je záznam throttle mapy `receiptResendAt` mrtvý (starší než give-up okno)? Po
 * něm se zpráva už znovu neposílá (doručena / vzdáno), takže záznam jen zabírá
 * paměť v dlouhožijícím FGS - prune ho odstraní. Čistá funkce (A17).
 */
internal fun receiptEntryExpired(now: Long, lastResendAt: Long, giveUpAfterMs: Long): Boolean =
    now - lastResendAt > giveUpAfterMs

/** Strop délky textové zprávy v bajtech UTF-8 (viz [textWithinRelayLimit], A11). */
internal const val MAX_TEXT_BYTES = 1_000_000

/**
 * Vejde se text pod strop pro relay? Přerostlý text (~2 MB+) by přes `bucketFor`
 * vyrobil blob nad `RELAY_BLOB_LIMIT` (2 MB) → relay ho odmítne 413 → FAILED →
 * každý retry pošle stejný přerostlý blob donekonečna. 1 MB je pro chatovou
 * zprávu víc než dost a s velkou rezervou pod limitem blobu (viz ChatTextLimitTest).
 * Čistá funkce (A11).
 */
internal fun textWithinRelayLimit(text: String): Boolean =
    text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES

/**
 * Směr schránky, na který strana POSÍLÁ, podle role při párování ([Contact.initiator]).
 * Iniciátor = 0, odpovídající (i neznámý) = 1. Čistá funkce - testuje se symetrie
 * s [recvDirFor], protože chyba tady = zpráva do schránky, kterou protějšek nečte.
 */
internal fun sendDirFor(initiator: Boolean?): Int = if (initiator == true) 0 else 1

/** Směr, na kterém strana POSLOUCHÁ (opačný než [sendDirFor]). */
internal fun recvDirFor(initiator: Boolean?): Int = 1 - sendDirFor(initiator)
