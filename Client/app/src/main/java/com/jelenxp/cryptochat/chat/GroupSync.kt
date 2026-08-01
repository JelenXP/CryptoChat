package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto

/**
 * Odesílací a přijímací roura skupinových zpráv — analogie 1:1 [RelaySync], ale
 * pro skupinový model (sdílený `GS`, fan-out do per-příjemce schránek, podepsané
 * doručenky). Testovatelná: [transport] a [storageCrypto] se v testech přepnou na
 * `FakeRelay` / `FakeStorageCrypto`, jinak se NEMĚNÍ.
 *
 * **Invariant (jako u 1:1):** schránka se ACKne (relay smaže) AŽ když je KAŽDÝ blob
 * dávky durabilně zpracovaný (uložen do historie, nebo do karantény). Když jediný
 * zápis selže, dávka se NEACKuje a relay bloby podrží — příště se zkusí znovu. Co
 * relay smazal, MUSÍ být v historii nebo v karanténě.
 *
 * **Rozsah fáze 4:** text + doručenky + fotky (příjem). Řídicí cesty (roster gossip,
 * rotace GS, resync) přijdou ve fázi 5 — zprávy cizí epochy / neznámého odesílatele
 * se zatím jen odloží do karantény (retry), nikdy nezahodí.
 */
object GroupSync {

    var transport: RelayTransport = RealRelayTransport
    var storageCrypto: StorageCrypto = KeystoreStorageCrypto

    private const val DAY_MS = 86_400_000L

    /**
     * Horní mez `msgNo` z otevřené (NEautentizované) hlavičky. `messageKeyAt` je
     * O(msgNo) HKDF a `msgNo` řídí relay/insider PŘED ověřením — bez stropu by blob
     * s `msgNo ≈ 2^31` zamrazil poll (miliardy HKDF). Analogie 1:1 `DoubleRatchet.SKIP_MAX`.
     * Strop je velkorysý strop reálného provozu na epochu (zprávy + doručenky); nad
     * něj = útok/junk → zahodit bez derivace. (Nález auditu fáze 4 #1 — kritická.)
     */
    private const val MAX_RECV_MSG_NO = 100_000

    /** Strop velikosti OBSAHU odchozí zprávy (pod per-blob limitem relaye ~2 MB). */
    private const val MAX_CONTENT_BYTES = 1_900_000

    data class SendResult(val msgIdHex: String, val sent: Int, val failed: Int)
    data class PollResult(val received: Int, val failed: Int)

    fun dayEpoch(nowMs: Long): Long = nowMs / DAY_MS

    private fun repo(context: Context) = GroupChatRepository(context, storageCrypto)

    // --- odesílání ---

    fun sendText(context: Context, group: Group, text: String, baseUrl: String, nowMs: Long): SendResult =
        send(context, group, GroupEnvelope.KIND_TEXT, text.toByteArray(Charsets.UTF_8), text, null, GroupChatMessage.Kind.TEXT, baseUrl, nowMs)

    fun sendImage(context: Context, group: Group, bytes: ByteArray, mediaPath: String?, baseUrl: String, nowMs: Long): SendResult =
        send(context, group, GroupEnvelope.KIND_IMAGE, bytes, "", mediaPath, GroupChatMessage.Kind.IMAGE, baseUrl, nowMs)

    private fun send(
        context: Context, group: Group, kind: Byte, data: ByteArray, text: String,
        mediaPath: String?, chatKind: GroupChatMessage.Kind, baseUrl: String, nowMs: Long,
    ): SendResult {
        val msgId = GroupCrypto.randomMsgId()
        val recipients = group.otherMembers().map { it.memberIdHex }

        // Strop velikosti (#5): přerostlá zpráva by relay odmítl (413) a věčně by se
        // resendovala. Ulož ji jako FAILED (ať ji uživatel vidí) a NEODESÍLEJ.
        if (data.size > MAX_CONTENT_BYTES) {
            repo(context).appendIfAbsent(
                group.groupId,
                GroupChatMessage(msgId, null, text, nowMs, GroupChatMessage.Status.FAILED, chatKind, mediaPath)
            )
            return SendResult(msgId, 0, recipients.size)
        }

        // Nejdřív DURABILNĚ ulož odchozí zprávu (SENDING). Když zápis SELŽE, neodešleme
        // nic (#2) — jinak by příjemci zprávu dostali, ale u odesílatele by v historii
        // nebyla (trvalý desync + falešný `sent>0`).
        if (repo(context).appendIfAbsent(
                group.groupId,
                GroupChatMessage(msgId, null, text, nowMs, GroupChatMessage.Status.SENDING, chatKind, mediaPath, recipients.toSet())
            ) == GroupChatRepository.AppendResult.FAILED
        ) {
            return SendResult(msgId, 0, recipients.size)
        }

        val epoch = group.groupEpoch
        val msgNo = GroupSendCounter.next(context, group.groupId, epoch)
        val msgKey = GroupCrypto.messageKeyAt(
            GroupCrypto.senderRootKey(group.gsBase64, group.groupId, group.myMemberId, epoch), msgNo
        )
        val day = dayEpoch(nowMs)
        var sent = 0
        var failed = 0
        for (r in recipients) {
            val blob = GroupEnvelope.seal(
                kind, data, nowMs, msgNo, msgId, msgKey, group.mySignPrivateKeyBase64,
                group.myMemberId, epoch, group.groupId, r
            )
            val inbox = GroupCrypto.inboxId(group.gsBase64, group.groupId, r, day)
            // Backoff na 409/507 řeší volající (service) — put vrací jen úspěch/neúspěch.
            if (transport.put(baseUrl, inbox, blob)) sent++ else failed++
        }
        if (failed == 0 && recipients.isNotEmpty()) {
            repo(context).setStatus(group.groupId, msgId, GroupChatMessage.Status.SENT)
        }
        return SendResult(msgId, sent, failed)
    }

    /** Přepošle nedoručenou zprávu příjemcům, kteří ji ještě nepotvrdili (pending). */
    fun resend(context: Context, group: Group, msgIdHex: String, baseUrl: String, nowMs: Long): Int {
        val msg = repo(context).getMessages(group.groupId).firstOrNull { it.outgoing && it.msgIdHex == msgIdHex } ?: return 0
        if (msg.pendingRecipients.isEmpty()) return 0
        val epoch = group.groupEpoch
        // Resend používá STEJNÝ msgId, ale ČERSTVÝ msgNo (viz plán v3.1-#1) — jinak by
        // ho příjemcův replay-guard mohl zahodit. Dedup u příjemce je msgId-based.
        val msgNo = GroupSendCounter.next(context, group.groupId, epoch)
        val msgKey = GroupCrypto.messageKeyAt(
            GroupCrypto.senderRootKey(group.gsBase64, group.groupId, group.myMemberId, epoch), msgNo
        )
        val kind = if (msg.kind == GroupChatMessage.Kind.IMAGE) GroupEnvelope.KIND_IMAGE else GroupEnvelope.KIND_TEXT
        val data = if (msg.kind == GroupChatMessage.Kind.IMAGE)
            (msg.mediaPath?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() } ?: return 0)
        else msg.text.toByteArray(Charsets.UTF_8)
        val day = dayEpoch(nowMs)
        var sent = 0
        for (r in msg.pendingRecipients) {
            val blob = GroupEnvelope.seal(kind, data, msg.timestamp, msgNo, msgIdHex, msgKey, group.mySignPrivateKeyBase64, group.myMemberId, epoch, group.groupId, r)
            if (transport.put(baseUrl, GroupCrypto.inboxId(group.gsBase64, group.groupId, r, day), blob)) sent++
        }
        return sent
    }

    // --- příjem ---

    private enum class Outcome { HANDLED, QUARANTINED, FAILED_WRITE }

    fun poll(context: Context, group: Group, baseUrl: String, nowMs: Long, waitSeconds: Int): PollResult {
        val gid = group.groupId
        var received = 0
        var failed = 0

        // 1) Zkus znovu odložené bloby (karanténa) — třeba se mezitím spravila epocha/roster.
        for (p in BlobQuarantine.takeAll(context, gid)) {
            when (processBlob(context, group, p.blob, baseUrl, nowMs, fromQuarantine = true)) {
                Outcome.HANDLED -> { p.token?.let { BlobQuarantine.discard(context, gid, it) }; received++ }
                Outcome.QUARANTINED -> {} // zůstává (takeAll ho re-queuel), zkusí se příště
                Outcome.FAILED_WRITE -> failed++
            }
        }

        // 2) Poll mojí příchozí schránky: dnešek (long-poll) + včerejšek (rychle, přelom dne).
        val day = dayEpoch(nowMs)
        val inboxToday = GroupCrypto.inboxId(group.gsBase64, gid, group.myMemberId, day)
        val inboxYesterday = GroupCrypto.inboxId(group.gsBase64, gid, group.myMemberId, day - 1)
        for ((mbox, wait) in listOf(inboxToday to waitSeconds, inboxYesterday to 0)) {
            val fetched = try {
                transport.get(baseUrl, mbox, wait)
            } catch (_: Exception) {
                failed++
                continue
            }
            if (fetched.blobs.isEmpty()) continue
            var anyFailedWrite = false
            for (blob in fetched.blobs) {
                when (processBlob(context, group, blob, baseUrl, nowMs, fromQuarantine = false)) {
                    Outcome.HANDLED -> received++
                    Outcome.QUARANTINED -> received++ // durabilně odloženo → smí se ACKnout
                    Outcome.FAILED_WRITE -> { anyFailedWrite = true; failed++ }
                }
            }
            // ACK (relay smaže) JEN když KAŽDÝ blob dávky durabilně dopadl.
            if (!anyFailedWrite) transport.ack(baseUrl, mbox, fetched.ackSeq)
        }
        return PollResult(received, failed)
    }

    private fun processBlob(context: Context, group: Group, blob: ByteArray, baseUrl: String, nowMs: Long, fromQuarantine: Boolean): Outcome {
        val gid = group.groupId
        // Už zpracované (relay ho nabídl znovu) → nic neděláme, ale je „vyřízené".
        if (!ReplayGuard.isNew(context, gid, blob)) return Outcome.HANDLED

        val header = GroupEnvelope.readHeader(blob) ?: return quarantineOrFail(context, gid, blob, fromQuarantine)
        // Strop msgNo (#1, kritická): `msgNo` je v NEautentizované hlavičce a
        // `messageKeyAt` je O(msgNo). Absurdní hodnota = útok/junk → zahoď BEZ derivace
        // (ACK-delete). Legitimní zpráva takový msgNo nikdy nenese (viz MAX_RECV_MSG_NO).
        if (header.msgNo > MAX_RECV_MSG_NO) return Outcome.HANDLED
        // Epoch filtr: jiná epocha než moje → neumím odvodit klíč (starý GS smazán /
        // jsem pozadu). Odlož do LOKÁLNÍ karantény a zkoušej znovu; po resyncu (fáze 5)
        // se epocha srovná a blob se dobere. Pozor: karanténa má strop (30 dní / 30 ks /
        // 24 MB) — resync musí proběhnout dřív, než odložený blob vyprší.
        if (header.groupEpoch != group.groupEpoch) return quarantineOrFail(context, gid, blob, fromQuarantine)

        // Pubkey odesílatele STRIKTNĚ podle memberId z hlavičky (jinak padá anti-impersonation).
        val senderPub = group.memberSignKey(header.senderMemberIdHex)
            ?: return quarantineOrFail(context, gid, blob, fromQuarantine)

        val msgKey = GroupCrypto.messageKeyAt(
            GroupCrypto.senderRootKey(group.gsBase64, gid, header.senderMemberIdHex, header.groupEpoch), header.msgNo
        )
        return when (val opened = GroupEnvelope.open(blob, msgKey, senderPub, gid, group.myMemberId)) {
            is GroupEnvelope.Result.Ok -> handleOpened(context, group, blob, opened, baseUrl, nowMs)
            GroupEnvelope.Result.Unreadable -> quarantineOrFail(context, gid, blob, fromQuarantine)
        }
    }

    private fun handleOpened(context: Context, group: Group, blob: ByteArray, ok: GroupEnvelope.Result.Ok, baseUrl: String, nowMs: Long): Outcome {
        val gid = group.groupId
        val r = repo(context)
        when (val c = ok.content) {
            is GroupEnvelope.Opened.Receipt -> {
                // Doručenka od ok.senderMemberIdHex potvrzuje MOJI zprávu c.ackedMsgIdHex.
                if (r.markDeliveredBy(gid, c.ackedMsgIdHex, ok.senderMemberIdHex) == GroupChatRepository.MutationResult.FAILED) {
                    return Outcome.FAILED_WRITE
                }
                ReplayGuard.remember(context, gid, blob)
                return Outcome.HANDLED
            }
            is GroupEnvelope.Opened.Text, is GroupEnvelope.Opened.Image -> {
                val (chatKind, text, mediaPath) = when (c) {
                    is GroupEnvelope.Opened.Text -> Triple(GroupChatMessage.Kind.TEXT, c.text, null)
                    is GroupEnvelope.Opened.Image -> {
                        // Fotku ulož na disk PŘED zápisem do historie; null = selhání zápisu.
                        val path = GroupMediaStore.save(context, gid, c.bytes) ?: return Outcome.FAILED_WRITE
                        Triple(GroupChatMessage.Kind.IMAGE, "", path)
                    }
                    else -> return Outcome.FAILED_WRITE // nedosažitelné
                }
                val msg = GroupChatMessage(ok.msgIdHex, ok.senderMemberIdHex, text, ok.content.timestamp, GroupChatMessage.Status.SENT, chatKind, mediaPath)
                return when (r.appendIfAbsent(gid, msg)) {
                    GroupChatRepository.AppendResult.ADDED, GroupChatRepository.AppendResult.DUPLICATE -> {
                        ReplayGuard.remember(context, gid, blob)
                        // Pošli (i u DUPLICATE přepošli — mohla se ztratit) doručenku odesílateli. Best-effort.
                        sendReceipt(context, group, ok.senderMemberIdHex, ok.msgIdHex, baseUrl, nowMs)
                        Outcome.HANDLED
                    }
                    GroupChatRepository.AppendResult.FAILED -> {
                        // Ukliď právě uloženou osiřelou fotku (#3) — jinak by ji každý
                        // retry ukládal znovu (hromadění ~1,9 MB souborů).
                        mediaPath?.let { runCatching { java.io.File(it).delete() } }
                        Outcome.FAILED_WRITE
                    }
                }
            }
        }
    }

    private fun sendReceipt(context: Context, group: Group, toMemberId: String, ackedMsgIdHex: String, baseUrl: String, nowMs: Long) {
        try {
            val epoch = group.groupEpoch
            val msgNo = GroupSendCounter.next(context, group.groupId, epoch)
            val msgKey = GroupCrypto.messageKeyAt(
                GroupCrypto.senderRootKey(group.gsBase64, group.groupId, group.myMemberId, epoch), msgNo
            )
            val blob = GroupEnvelope.seal(
                GroupEnvelope.KIND_RECEIPT, GroupCrypto.hexToBytes(ackedMsgIdHex), nowMs, msgNo,
                GroupCrypto.randomMsgId(), msgKey, group.mySignPrivateKeyBase64, group.myMemberId,
                epoch, group.groupId, toMemberId
            )
            transport.put(baseUrl, GroupCrypto.inboxId(group.gsBase64, group.groupId, toMemberId, dayEpoch(nowMs)), blob)
        } catch (_: Exception) {
            // Ztráta doručenky se sama zahojí: odesílatel po chvíli přepošle, dostanu DUPLICATE,
            // a doručenku pošlu znovu.
        }
    }

    private fun quarantineOrFail(context: Context, groupId: String, blob: ByteArray, fromQuarantine: Boolean): Outcome {
        if (fromQuarantine) return Outcome.QUARANTINED // už leží v karanténě (takeAll ho re-queuel)
        return if (BlobQuarantine.save(context, groupId, blob)) Outcome.QUARANTINED else Outcome.FAILED_WRITE
    }
}
