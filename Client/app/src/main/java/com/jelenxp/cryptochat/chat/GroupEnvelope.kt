package com.jelenxp.cryptochat.chat

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Drátová obálka jedné SKUPINOVÉ zprávy (wire major 5). Leží na skupinových
 * schránkách (viz [GroupCrypto.inboxId]), které čtou jen členové skupiny, takže má
 * vlastní kind-space nezávislý na zmražených 1:1 `kind` z [ChatEnvelope].
 *
 * Blob:
 * ```
 * [1B major=5][8B senderMemberId][8B groupEpoch BE][4B msgNo BE][12B IV][GCM ciphertext+tag]
 *  \_______________ otevřená hlavička (čitelná před dešifrováním) _______________/
 * ```
 * Otevřená hlavička je **routing hint** — příjemce z ní pozná odesílatele a epochu,
 * aby zvolil správný sender-key řetěz (viz [GroupCrypto.senderRootKey]/[GroupCrypto.senderStep]).
 * Sama je NEautentizovaná, ale je celá svázaná v AAD, takže ji relay nemůže přehodit.
 *
 * Otevřený obsah (uvnitř šifry, chráněný GCM tagem), zarovnaný paddingem do košů
 * [ChatEnvelope.bucketFor] (text) / [ChatEnvelope.mediaBucketFor] (fotka):
 * ```
 * [1B kind][8B ts BE][4B dataLen BE][data][2B sigLen BE][Ed25519 podpis][výplň 0]
 * ```
 * **Podpis odesílatele** (Ed25519, [GroupIdentity]) je klíčová obrana: `GS` zná
 * každý člen, takže bez podpisu by kdokoli podvrhl zprávu cizí identitou. Podpis
 * pokrývá `groupId | senderMemberId | epoch | msgNo | kind | ts | data` (NE příjemce,
 * takže je společný pro všech N−1 kopií; příjemce sváže AAD).
 *
 * **Náhodný IV per blob** — nikdy odvozený z klíče (viz `GROUP_CHAT_PLAN.md` crypto#3):
 * i když má sender-key v epoše stabilní klíč a N−1 kopií se liší jen recipient-AAD,
 * čerstvý IV vylučuje opakování (key, IV) = GCM forbidden attack.
 *
 * Klíč zprávy (`messageKey`) i verifikační pubkey odesílatele dodává volající (roura),
 * takže obálka je čistě JVM a plně testovatelná.
 */
object GroupEnvelope {

    const val WIRE_MAJOR_GROUP = 5

    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16

    private const val SENDER_ID_BYTES = 8
    // major(1) + senderMemberId(8) + groupEpoch(8) + msgNo(4)
    private const val OPEN_HEADER = 1 + SENDER_ID_BYTES + 8 + 4
    // kind(1) + ts(8) + dataLen(4)
    private const val INNER_HEADER = 1 + 8 + 4
    private const val SIG_LEN_FIELD = 2

    const val KIND_TEXT: Byte = 0
    const val KIND_IMAGE: Byte = 1

    private const val LABEL_MSG = "CryptoChat/group/msg/v1"

    /** Rozbalený obsah skupinové zprávy. */
    sealed interface Opened {
        val timestamp: Long
        data class Text(override val timestamp: Long, val text: String) : Opened
        data class Image(override val timestamp: Long, val bytes: ByteArray) : Opened
    }

    /**
     * Výsledek [open]. `Ok` nese identitu odesílatele a pozici pro rouru;
     * `Unreadable` = cizí klíč / poškození / neplatný podpis / neznámý kind →
     * karanténa a zkusit znovu (nikdy se nesmí tiše ztratit).
     */
    sealed interface Result {
        data class Ok(
            val senderMemberIdHex: String,
            val groupEpoch: Long,
            val msgNo: Int,
            val content: Opened,
        ) : Result

        data object Unreadable : Result
    }

    /** Otevřená hlavička přečtená BEZ dešifrování (routing hint). */
    data class Header(
        val senderMemberIdHex: String,
        val groupEpoch: Long,
        val msgNo: Int,
        val ciphertextOffset: Int,
    )

    /**
     * Přečte otevřenou hlavičku (major, odesílatel, epocha, msgNo) bez dešifrování.
     * Vrací null, když blob nevypadá na major 5 nebo je useknutý.
     */
    fun readHeader(blob: ByteArray): Header? {
        if (blob.size < OPEN_HEADER + IV_SIZE_BYTES + GCM_TAG_BYTES) return null
        if ((blob[0].toInt() and 0xFF) != WIRE_MAJOR_GROUP) return null
        val sender = GroupCrypto.bytesToHex(blob.copyOfRange(1, 1 + SENDER_ID_BYTES))
        val buf = ByteBuffer.wrap(blob, 1 + SENDER_ID_BYTES, 12)
        val epoch = buf.long
        val msgNo = buf.int
        if (msgNo < 0) return null
        return Header(sender, epoch, msgNo, OPEN_HEADER)
    }

    /**
     * Zapečetí skupinovou zprávu. `messageKey` je per-zpráva klíč odesílatele
     * (z [GroupCrypto.senderStep]); `senderSignPrivateKeyBase64` je Ed25519 privát
     * odesílatele. Vrací hotový blob k zápisu do příchozí schránky příjemce.
     */
    fun seal(
        kind: Byte,
        data: ByteArray,
        timestamp: Long,
        msgNo: Int,
        messageKey: ByteArray,
        senderSignPrivateKeyBase64: String,
        senderMemberIdHex: String,
        groupEpoch: Long,
        groupIdHex: String,
        recipientMemberIdHex: String,
    ): ByteArray {
        require(msgNo >= 0) { "msgNo nesmí být záporné." }
        val openHeader = openHeaderBytes(senderMemberIdHex, groupEpoch, msgNo)

        val sig = com.jelenxp.cryptochat.crypto.Base64Util.decode(
            GroupIdentity.sign(
                senderSignPrivateKeyBase64, LABEL_MSG,
                sigData(groupIdHex, senderMemberIdHex, groupEpoch, msgNo, kind, timestamp, data)
            )
        )

        val preLen = INNER_HEADER + data.size + SIG_LEN_FIELD + sig.size
        val bucket = if (kind == KIND_TEXT) ChatEnvelope.bucketFor(preLen) else ChatEnvelope.mediaBucketFor(preLen)
        val plaintext = ByteArray(bucket)
        plaintext[0] = kind
        ByteBuffer.wrap(plaintext, 1, INNER_HEADER - 1).putLong(timestamp).putInt(data.size)
        System.arraycopy(data, 0, plaintext, INNER_HEADER, data.size)
        var pos = INNER_HEADER + data.size
        plaintext[pos] = ((sig.size ushr 8) and 0xFF).toByte()
        plaintext[pos + 1] = (sig.size and 0xFF).toByte()
        pos += SIG_LEN_FIELD
        System.arraycopy(sig, 0, plaintext, pos, sig.size)
        // zbytek plaintextu už je 0 (výplň)

        val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(groupIdHex, recipientMemberIdHex, openHeader))
        val ct = cipher.doFinal(plaintext)

        return openHeader + iv + ct
    }

    /**
     * Dešifruje a ověří skupinový blob. `messageKey` odvodí volající z pozice v
     * hlavičce; `senderSignPublicKeyBase64` je Ed25519 pubkey odesílatele z rosteru.
     * Ověří GCM (klíč, AAD = groupId+příjemce+hlavička) i podpis odesílatele; při
     * jakékoli neshodě vrací [Result.Unreadable].
     */
    fun open(
        blob: ByteArray,
        messageKey: ByteArray,
        senderSignPublicKeyBase64: String,
        groupIdHex: String,
        recipientMemberIdHex: String,
    ): Result {
        val header = readHeader(blob) ?: return Result.Unreadable
        val openHeader = blob.copyOfRange(0, header.ciphertextOffset)

        val plaintext = try {
            val iv = blob.copyOfRange(header.ciphertextOffset, header.ciphertextOffset + IV_SIZE_BYTES)
            val ct = blob.copyOfRange(header.ciphertextOffset + IV_SIZE_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad(groupIdHex, recipientMemberIdHex, openHeader))
            cipher.doFinal(ct)
        } catch (_: Exception) {
            return Result.Unreadable
        }

        return try {
            parse(plaintext, header, senderSignPublicKeyBase64, groupIdHex)
        } catch (_: Exception) {
            Result.Unreadable
        }
    }

    private fun parse(
        plaintext: ByteArray,
        header: Header,
        senderSignPublicKeyBase64: String,
        groupIdHex: String,
    ): Result {
        if (plaintext.size < INNER_HEADER) return Result.Unreadable
        val kind = plaintext[0]
        val buf = ByteBuffer.wrap(plaintext, 1, INNER_HEADER - 1)
        val timestamp = buf.long
        val dataLen = buf.int
        // Odečítat, ne přičítat — INNER_HEADER + dataLen by u obřího dataLen přeteklo.
        if (dataLen < 0 || dataLen > plaintext.size - INNER_HEADER - SIG_LEN_FIELD) return Result.Unreadable
        val data = plaintext.copyOfRange(INNER_HEADER, INNER_HEADER + dataLen)

        var pos = INNER_HEADER + dataLen
        if (pos + SIG_LEN_FIELD > plaintext.size) return Result.Unreadable
        val sigLen = ((plaintext[pos].toInt() and 0xFF) shl 8) or (plaintext[pos + 1].toInt() and 0xFF)
        pos += SIG_LEN_FIELD
        if (sigLen < 0 || pos + sigLen > plaintext.size) return Result.Unreadable
        val sig = plaintext.copyOfRange(pos, pos + sigLen)

        // Ověř podpis odesílatele nad autentizovaným kontextem (anti-impersonation).
        val ok = GroupIdentity.verify(
            senderSignPublicKeyBase64, LABEL_MSG,
            sigData(groupIdHex, header.senderMemberIdHex, header.groupEpoch, header.msgNo, kind, timestamp, data),
            com.jelenxp.cryptochat.crypto.Base64Util.encode(sig)
        )
        if (!ok) return Result.Unreadable

        val content = when (kind) {
            KIND_TEXT -> Opened.Text(timestamp, String(data, Charsets.UTF_8))
            KIND_IMAGE -> Opened.Image(timestamp, data)
            else -> return Result.Unreadable // neznámý kind → karanténa (řídicí kindy přijdou později)
        }
        return Result.Ok(header.senderMemberIdHex, header.groupEpoch, header.msgNo, content)
    }

    private fun openHeaderBytes(senderMemberIdHex: String, groupEpoch: Long, msgNo: Int): ByteArray {
        val sender = GroupCrypto.hexToBytes(senderMemberIdHex)
        require(sender.size == SENDER_ID_BYTES) { "senderMemberId musí mít $SENDER_ID_BYTES B." }
        val out = ByteArray(OPEN_HEADER)
        out[0] = WIRE_MAJOR_GROUP.toByte()
        System.arraycopy(sender, 0, out, 1, SENDER_ID_BYTES)
        ByteBuffer.wrap(out, 1 + SENDER_ID_BYTES, 12).putLong(groupEpoch).putInt(msgNo)
        return out
    }

    /**
     * AAD: doménový štítek + groupId + PŘÍJEMCE + major + celá otevřená hlavička.
     * Sváže blob se schránkou příjemce (relay ho nepřehodí jinam) i s odesílatelem/
     * epochou/msgNo (nejde přepsat otevřenou hlavičku bez rozbití GCM tagu).
     */
    private fun aad(groupIdHex: String, recipientMemberIdHex: String, openHeader: ByteArray): ByteArray =
        "ccg|g=$groupIdHex|to=$recipientMemberIdHex|w=$WIRE_MAJOR_GROUP".toByteArray(Charsets.US_ASCII) + openHeader

    /** Autentizovaný kontext pro podpis odesílatele (bez příjemce → společný pro všechny kopie). */
    private fun sigData(
        groupIdHex: String,
        senderMemberIdHex: String,
        groupEpoch: Long,
        msgNo: Int,
        kind: Byte,
        timestamp: Long,
        data: ByteArray,
    ): ByteArray {
        val gid = GroupCrypto.hexToBytes(groupIdHex)
        val sid = GroupCrypto.hexToBytes(senderMemberIdHex)
        val fixed = ByteArray(8 + 4 + 1 + 8) // epoch + msgNo + kind + ts
        ByteBuffer.wrap(fixed).putLong(groupEpoch).putInt(msgNo).put(kind).putLong(timestamp)
        return gid + sid + fixed + data
    }
}
