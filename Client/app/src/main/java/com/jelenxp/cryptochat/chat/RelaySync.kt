package com.jelenxp.cryptochat.chat

import android.content.Context
import android.net.Uri
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.SettingsRepository
import java.io.File
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

    /** Poslední epocha, pro kterou už se u daného kontaktu kontrolovala stará schránka. */
    private val prevEpochChecked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Velikost jednoho kousku souboru (relay bere blob do 2 MB, necháme rezervu). */
    private const val CHUNK_SIZE = 1_800_000

    private fun currentEpoch() = System.currentTimeMillis() / EPOCH_MS

    /**
     * Má se teď kontrolovat schránka předchozí epochy? Ano jednou za epochu (první
     * poll po startu procesu nebo hned po přelomu dne - tehdy tam ještě může něco
     * ležet) a pak už jen prvních [EPOCH_OVERLAP_MS] nové epochy. Zbytek dne se
     * kontrola přeskočí, takže na cyklus vychází jeden onion request místo dvou.
     */
    private fun shouldCheckPrevEpoch(contactId: String, epoch: Long): Boolean {
        if (prevEpochChecked[contactId] != epoch) return true
        return System.currentTimeMillis() % EPOCH_MS < EPOCH_OVERLAP_MS
    }

    /**
     * Výsledek jednoho pollu: kolik zpráv dorazilo a jestli spojení selhalo.
     * Selhání hlásíme ven, aby volající mohl zpomalit (backoff) místo toho, aby
     * při nedostupném serveru donekonečna stavěl Tor okruhy a pálil baterii.
     */
    data class PollResult(val received: Int, val failed: Boolean)

    /** Směr, na který kontakt POSÍLÁ. Iniciátor = 0, odpovídající = 1. */
    private fun sendDir(contact: Contact) = if (contact.initiator == true) 0 else 1

    /** Směr, na kterém kontakt POSLOUCHÁ (opačný). */
    private fun recvDir(contact: Contact) = 1 - sendDir(contact)

    /**
     * Zapíše zprávu do lokální historie se stavem SENDING a vrátí ji. Nedělá síť -
     * díky tomu se dá hned zobrazit v UI. Doručení pak dokončí [deliver].
     */
    fun enqueue(context: Context, contact: Contact, text: String): ChatMessage {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            outgoing = true,
            text = text,
            timestamp = System.currentTimeMillis(),
            status = ChatMessage.Status.SENDING
        )
        ChatRepository(context).append(contact.id, message)
        return message
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
            mediaPath = path
        )
        ChatRepository(context).append(contact.id, message)
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
        ChatRepository(context).append(contact.id, message)
        return message
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
            } else {
                val dir = sendDir(contact)
                val blob = if (message.kind == ChatMessage.Kind.IMAGE && message.mediaPath != null) {
                    ChatEnvelope.sealImage(
                        java.io.File(message.mediaPath).readBytes(), message.timestamp, key, dir
                    )
                } else {
                    ChatEnvelope.seal(message.text, message.timestamp, key, dir)
                }
                val mailbox = RelayCrypto.mailboxId(key, dir, currentEpoch())
                RelayClient.put(baseUrl, mailbox, blob)
            }
        } catch (e: Exception) {
            false
        }
        val finalStatus = if (delivered) ChatMessage.Status.SENT else ChatMessage.Status.FAILED
        ChatRepository(context).updateStatus(contact.id, message.id, finalStatus)
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
                val dir = sendDir(contact)
                val mailbox = RelayCrypto.mailboxId(key, dir, currentEpoch())
                MediaTransfers.setProgress(message.id, 0f)

                val manifest = ChatEnvelope.sealFileManifest(
                    fileId, totalChunks, totalSize,
                    message.mimeType ?: "application/octet-stream",
                    message.text, message.timestamp, key, dir
                )
                if (!RelayClient.put(baseUrl, mailbox, manifest)) {
                    false
                } else {
                    var index = 0
                    var ok = true
                    file.inputStream().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                            val blob = ChatEnvelope.sealFileChunk(
                                fileId, index, chunk, message.timestamp, key, dir
                            )
                            if (!RelayClient.put(baseUrl, mailbox, blob)) {
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
        } catch (e: Exception) {
            false
        }
        MediaTransfers.clearProgress(message.id)
        ChatRepository(context).updateStatus(
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

        val repo = ChatRepository(context)
        val dir = recvDir(contact)
        val epoch = currentEpoch()
        var failed = false

        // Vyzvedne jednu schránku (dané epochy), otevře bloby a uloží je. Vrací
        // počet nově přijatých zpráv. Síťovou chybu spolkne (0), ale poznamená ji
        // do `failed`, aby volající mohl zpomalit.
        fun fetch(e: Long, waitSeconds: Int): Int {
            val mailbox = RelayCrypto.mailboxId(key, dir, e)
            val blobs = try {
                RelayClient.get(baseUrl, mailbox, waitSeconds)
            } catch (ex: Exception) {
                failed = true
                return 0
            }
            var n = 0
            // Nepřečteno počítáme jen když konverzace není zrovna otevřená
            // (otevřený chat si zprávu rovnou přečte).
            fun arrived(message: ChatMessage) {
                repo.append(contact.id, message)
                if (ActiveChat.currentId != contact.id) repo.incrementUnread(contact.id)
                n++
            }

            // Každý blob zvlášť: výjimka u jednoho (poškozená data, chyba zápisu)
            // nesmí shodit zpracování zbytku dávky - ty zprávy už relay smazal,
            // takže by byly nenávratně pryč.
            for (blob in blobs) try {
                // Relay může tentýž blob nabídnout znovu - duplicitu zahoď.
                if (!ReplayGuard.isNew(context, contact.id, blob)) continue
                // Zprávu s jiným MAJOR nemá smysl zkoušet otevřít - jen si
                // poznamenej, kdo je pozadu, ať to appka umí uživateli říct
                // (dřív se takový blob tiše zahodil a "zprávy prostě nechodily").
                if (!WireCompat.acceptMajor(context, contact.id, blob)) continue
                // Otevírá se PŘIJÍMACÍM směrem: blob zapsaný do odchozí schránky
                // (a relayí přehozený sem) má v AAD druhý směr a neprojde.
                val decoded = ChatEnvelope.open(blob, key, dir)
                // Minor odesílatele je až uvnitř šifry, takže je známý teprve teď.
                // Zároveň je to jediný okamžik, kdy je jisté, že blob je pravý -
                // proto se až tady zapíše i otisk proti replay.
                if (decoded != null) {
                    WireCompat.notePeerMinor(context, contact.id, decoded.senderMinor)
                    ReplayGuard.remember(context, contact.id, blob)
                }
                when (val opened = decoded?.content) {
                    is ChatEnvelope.Opened.Text -> arrived(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            outgoing = false,
                            text = opened.text,
                            timestamp = opened.timestamp,
                            status = ChatMessage.Status.RECEIVED
                        )
                    )

                    is ChatEnvelope.Opened.Image -> {
                        val path = ChatMediaStore.save(context, opened.bytes) ?: continue
                        arrived(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                outgoing = false,
                                text = "",
                                timestamp = opened.timestamp,
                                status = ChatMessage.Status.RECEIVED,
                                kind = ChatMessage.Kind.IMAGE,
                                mediaPath = path
                            )
                        )
                    }

                    // Ohlášení souboru: založíme příjem a hned ukážeme bublinu
                    // se stavem „přijímá se" (kousky dorazí vzápětí).
                    is ChatEnvelope.Opened.FileManifest -> {
                        val idHex = MediaTransfers.hex(opened.fileId)
                        MediaTransfers.startReceive(
                            context, idHex, opened.totalChunks, opened.totalSize,
                            opened.mimeType, opened.fileName
                        )
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
                        if (added) {
                            if (ActiveChat.currentId != contact.id) repo.incrementUnread(contact.id)
                            n++
                        }
                        // Kousky mohly dorazit dřív než manifest (zaparkované) -
                        // pak je soubor hotový už teď a nikdo by ho nesložil.
                        if (MediaTransfers.receivedCount(context, idHex) >= opened.totalChunks) {
                            val path = MediaTransfers.assemble(context, idHex)
                            MediaTransfers.clearProgress(idHex)
                            repo.updateMedia(
                                contact.id, idHex, path,
                                if (path != null) ChatMessage.Status.RECEIVED
                                else ChatMessage.Status.FAILED
                            )
                        }
                    }

                    // Kousek souboru: ulož a po posledním slož výsledek.
                    is ChatEnvelope.Opened.FileChunk -> {
                        val idHex = MediaTransfers.hex(opened.fileId)
                        val complete = MediaTransfers.saveChunk(context, idHex, opened.index, opened.bytes)
                        val total = MediaTransfers.totalChunks(context, idHex)
                        if (total > 0) {
                            MediaTransfers.setProgress(
                                idHex,
                                MediaTransfers.receivedCount(context, idHex).toFloat() / total
                            )
                        }
                        if (complete) {
                            val path = MediaTransfers.assemble(context, idHex)
                            MediaTransfers.clearProgress(idHex)
                            repo.updateMedia(
                                contact.id, idHex, path,
                                if (path != null) ChatMessage.Status.RECEIVED
                                else ChatMessage.Status.FAILED
                            )
                        }
                    }

                    null -> {}
                }
            } catch (ex: Exception) {
                // Jeden vadný blob nesmí shodit zbytek dávky.
                android.util.Log.w("RelaySync", "Zpracování blobu selhalo, pokračuji", ex)
            }
            return n
        }

        // Kolem přelomu dne nejdřív rychlá (neblokující) kontrola předchozí epochy;
        // když něco přišlo, ukaž to hned. Mimo to okno se přeskočí - jinak by se
        // každý cyklus platil onion request navíc.
        if (shouldCheckPrevEpoch(contact.id, epoch)) {
            val prev = fetch(epoch - 1, waitSeconds = 0)
            // Za vyřízenou ji považuj AŽ po úspěšném dotazu. Kdyby se označila
            // rovnou, jediný neúspěšný pokus (nedostupný server) by kontrolu
            // spotřeboval a zpráva odeslaná těsně před přelomem dne by se už
            // nikdy nevyzvedla - tichá a nevratná ztráta.
            if (!failed) prevEpochChecked[contact.id] = epoch
            if (prev > 0) return PollResult(prev, failed)
        }
        // Long-poll aktuální epochy - server podrží spojení, dokud nedorazí zpráva,
        // takže chodí skoro okamžitě a mezitím se nic nebudí.
        return PollResult(fetch(epoch, waitSeconds = LONGPOLL_SECONDS), failed)
    }
}
