package com.jelenxp.cryptochat.chat

import android.content.Context
import android.net.Uri
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
import java.io.File
import java.io.InputStream
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

    /** Kdy (ms) se u daného kontaktu naposledy kontrolovala předchozí schránka. */
    private val prevEpochCheckedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
    ): ChatMessage {
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
        repoFor(context).append(contact.id, message)
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
        val now = System.currentTimeMillis()
        val repo = repoFor(context)
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
                transport.put(baseUrl, RelayCrypto.mailboxId(key, dir, currentEpoch()), blob)
            }
        } catch (e: Exception) {
            DiagnosticsLog.warn(TAG, "odeslání reakce selhalo (${e.javaClass.simpleName})")
            false
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
        repoFor(context).append(contact.id, message)
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
        repoFor(context).append(contact.id, message)
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
        val put = try {
            transport.put(baseUrl, mailbox, blob)
        } catch (e: Exception) {
            DiagnosticsLog.warn(TAG, "odeslání ratchet blobu selhalo (${e.javaClass.simpleName})")
            false
        }
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
            val put = try {
                transport.put(baseUrl, RelayCrypto.ratchetMailboxId(key, dir, step.epoch), blob)
            } catch (e: Exception) {
                false
            }
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
                transport.put(baseUrl, mailbox, blob)
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
                    if (!transport.put(baseUrl, mailbox, manifest)) {
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
                                if (!transport.put(baseUrl, mailbox, blob)) {
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
        // Karanténu procházej jen jednou za poll (ne v každém fetchi zvlášť).
        var retryQuarantine = true

        // Vyzvedne jednu schránku (dané epochy), otevře bloby a uloží je. Vrací
        // počet nově přijatých zpráv. Síťovou chybu spolkne (0), ale poznamená ji
        // do `failed`, aby volající mohl zpomalit.
        fun fetch(mailbox: String, waitSeconds: Int, ratchet: Boolean): Int {
            // Reachable se vztahuje k TÉHLE operaci get (výsledek se vrací podle
            // ní). Reset na začátku, ať prev-epoch get, který uspěl, nemaskuje
            // následné síťové selhání aktuální epochy.
            reachable = false
            val fetched = try {
                transport.get(baseUrl, mailbox, waitSeconds)
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
                        // Teprve teď může existovat cíl reakce, která dorazila dřív.
                        PendingReactions.applyAll(contact.id) { ref, reactor, emoji, ts ->
                            repo.setReaction(contact.id, ref, reactor, emoji, ts) ==
                                ChatRepository.ReactionResult.APPLIED
                        }
                    }
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
                val result: ChatEnvelope.Result = if (ratchet) {
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
                        // Novější generace, než na jakou jsme přesejni (KEM re-key
                        // ještě nedoběhl) → karanténa; po zpracování re-key se přečte.
                        DoubleRatchet.RecvStep.FutureGeneration -> ChatEnvelope.Result.Unreadable
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
                        WireCompat.notePeer(
                            context, contact.id, result.senderMinor,
                            result.maxMajor ?: WireCompat.UNKNOWN
                        )
                        WireCompat.notePeerCapabilities(context, contact.id, result.capabilities)
                    }
                    is ChatEnvelope.Result.Unsupported ->
                        WireCompat.notePeerMinor(context, contact.id, result.senderMinor)
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
                    pendingRatchetState?.let { ratchetStore.saveRecv(contact.id, it) }
                    ReplayGuard.remember(context, contact.id, blob)
                    item.token?.let { BlobQuarantine.discard(context, contact.id, it) }
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

                    // KEM re-key (PCS): řídicí zpráva, není do historie. Fáze 4b-1
                    // definuje jen FORMÁT - handshake (OFFER/ACCEPT/CONFIRM →
                    // applyRekey) se zapojí ve 4b-2. Zatím zahodit a potvrdit; žádný
                    // re-key se ještě neiniciuje, takže sem reálně nic nechodí.
                    is ChatEnvelope.Opened.Rekey ->
                        DiagnosticsLog.log(TAG, "re-key zpráva (subtype ${opened.subtype}) - handshake zatím nezapojen")

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
                            ChatRepository.ReactionResult.APPLIED -> Unit
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
                                // Cíl reakce na soubor může existovat až teď.
                                PendingReactions.applyAll(contact.id) { ref, reactor, emoji, ts ->
                                    repo.setReaction(contact.id, ref, reactor, emoji, ts) ==
                                        ChatRepository.ReactionResult.APPLIED
                                }
                            }
                        }
                        // Kousky mohly dorazit dřív než manifest (zaparkované) -
                        // pak je soubor hotový už teď a nikdo by ho nesložil.
                        if (MediaTransfers.receivedCount(context, idHex) >= opened.totalChunks) {
                            if (!finishFile(context, repo, contact.id, idHex)) allSafe = false
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
                            if (!finishFile(context, repo, contact.id, idHex)) allSafe = false
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
            if (fetched.ackSeq >= 0 && allSafe) {
                if (!transport.ack(baseUrl, mailbox, fetched.ackSeq)) failed = true
            } else if (!allSafe) {
                DiagnosticsLog.warn(TAG, "dávka není celá uložená, potvrzení se neposílá")
                failed = true
            }
            if (n > 0) DiagnosticsLog.log(TAG, "přijato $n nových zpráv")
            return n
        }

        // Ratchet aktivní (stav existuje) → příjem na RATCHET schránky. Odesílatel
        // posílá major 4; legacy krátce dočítáme jako grace (zprávy v letu).
        val ratchetState = ratchetStore.load(contact.id)
        if (ratchetState != null) {
            var received = 0
            val re = ratchetState.recvEpoch
            // Rychle: sousední epocha (odesílatel mohl posunout epochu po 32 zprávách).
            // Chytí běžný jednokrokový posun HNED, bez čekání na beacon.
            received += fetch(RelayCrypto.ratchetMailboxId(key, dir, re + 1), 0, ratchet = true)
            // Rychle: legacy grace (zprávy odeslané ještě před přepnutím protějšku).
            received += fetch(RelayCrypto.mailboxId(key, dir, epoch), 0, ratchet = false)
            var target = ratchetStore.load(contact.id)?.recvEpoch ?: re
            if (target == re) {
                // Sousední epocha nic nepřinesla → odesílatel mohl utéct dál (dlouhé
                // offline). Beacon (ukazatel z neměnného M) řekne, na kterou epochu.
                // Čte se JEN v tomhle případě, ať se za běžného provozu neplatí navíc.
                val beaconEpoch = readBeacon(baseUrl, key, dir)
                if (beaconEpoch != null && beaconEpoch > re) target = beaconEpoch
            }
            // Long-poll cílové ratchet epochy (aktuální, sousední nebo z beaconu).
            received += fetch(RelayCrypto.ratchetMailboxId(key, dir, target), LONGPOLL_SECONDS, ratchet = true)
            return PollResult(received, failed, reachable)
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
            if (prev > 0) return PollResult(prev, failed, reachable)
        }
        // Long-poll aktuální epochy - server podrží spojení, dokud nedorazí zpráva,
        // takže chodí skoro okamžitě a mezitím se nic nebudí.
        return PollResult(fetch(RelayCrypto.mailboxId(key, dir, epoch), LONGPOLL_SECONDS, ratchet = false), failed, reachable)
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
        idHex: String
    ): Boolean = when (val r = MediaTransfers.assemble(context, idHex)) {
        is MediaTransfers.AssembleResult.Done -> {
            if (repo.updateMedia(contactId, idHex, r.path, ChatMessage.Status.RECEIVED)) {
                MediaTransfers.cleanup(context, idHex)
                MediaTransfers.clearProgress(idHex)
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
 * Směr schránky, na který strana POSÍLÁ, podle role při párování ([Contact.initiator]).
 * Iniciátor = 0, odpovídající (i neznámý) = 1. Čistá funkce - testuje se symetrie
 * s [recvDirFor], protože chyba tady = zpráva do schránky, kterou protějšek nečte.
 */
internal fun sendDirFor(initiator: Boolean?): Int = if (initiator == true) 0 else 1

/** Směr, na kterém strana POSLOUCHÁ (opačný než [sendDirFor]). */
internal fun recvDirFor(initiator: Boolean?): Int = 1 - sendDirFor(initiator)
