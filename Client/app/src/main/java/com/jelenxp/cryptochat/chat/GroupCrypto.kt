package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import java.util.Base64

/**
 * Odvozování skupinových identifikátorů a klíčů z hlavního skupinového klíče `GS`.
 * Vše stojí na HKDF-SHA256 s doménovou separací přes `info` label — stejný princip
 * jako [RelayCrypto] u 1:1, jen s vlastními labely.
 *
 * Klíčové vlastnosti (viz `GROUP_CHAT_PLAN.md`):
 *  - [inboxId] se odvozuje JEN z `GS` (rotuje pouze při odebrání) a denní epochy —
 *    **NE z `groupEpoch`**. Přidání člena tak nepřejmenuje schránky a gossip doteče
 *    i online členům; jen odebrání (nový `GS`) je záměrně změní.
 *  - [gsCommit] sváže `GS` s epochou a `groupId` — admin ho vloží do podepsaného
 *    rosteru, takže žádný člen nepodstrčí vlastní `GS` pod pravým rosterem.
 *  - [recovId] se rotuje TÝDNĚ (omezení linkovatelnosti relayem a DoS okna) a
 *    slouží jen jako best-effort záloha pro obnovu klíče.
 *
 * Čistě JVM (BouncyCastle HKDF + `java.util.Base64`) → testovatelné bez Robolectricu.
 */
object GroupCrypto {

    // Délka ID schránky v bajtech (24 B -> 32 znaků base64url, splňuje regex serveru).
    private const val MAILBOX_ID_BYTES = 24
    private const val GS_COMMIT_BYTES = 32
    private const val SENDER_KEY_BYTES = 32

    private const val INFO_INBOX = "CryptoChat/group/inbox/v1"
    private const val INFO_RECOVERY = "CryptoChat/group/recovery/v1"
    private const val INFO_GS_COMMIT = "CryptoChat/group/gs-commit/v1"
    private const val INFO_SENDER_ROOT = "CryptoChat/group/sender/v1"
    private const val INFO_SENDER_MSG = "CryptoChat/group/sender-msg/v1"
    private const val INFO_SENDER_STEP = "CryptoChat/group/sender-step/v1"

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Závazek k `GS` epochy `epoch` skupiny `groupId`. Admin ho podepíše v rosteru;
     * příjemce adoptuje `GS` jen když `gsCommit(GS, …)` sedí s rosterem té epochy.
     * `GS` má 256 b entropie → z commitu ho nejde offline uhádnout (nulová sůl HKDF
     * nad plně náhodným IKM je bezpečná). Base64 (32 B).
     */
    fun gsCommit(sharedGroupKeyBase64: String, groupIdHex: String, epoch: Long): String {
        val info = "$INFO_GS_COMMIT|g=$groupIdHex|e=$epoch"
        return Base64Util.encode(hkdf(Base64Util.decode(sharedGroupKeyBase64), info, GS_COMMIT_BYTES))
    }

    /**
     * ID příchozí skupinové schránky člena `recipientMemberId` v den `dayEpoch`.
     * Odvozeno JEN z `GS` + dne (ne z groupEpoch). Odesílatel do ní zapíše kopii;
     * příjemce ji pollује. base64url bez výplně.
     */
    fun inboxId(sharedGroupKeyBase64: String, groupIdHex: String, recipientMemberIdHex: String, dayEpoch: Long): String {
        val info = "$INFO_INBOX|g=$groupIdHex|to=$recipientMemberIdHex|day=$dayEpoch"
        return b64url(hkdf(Base64Util.decode(sharedGroupKeyBase64), info, MAILBOX_ID_BYTES))
    }

    /**
     * ID recovery schránky člena — stabilní v rámci týdne (`weekEpoch`), aby ji
     * našel i člen, který je za aktuálním `GS` pozadu. IKM = `groupId || memberId ||
     * sealPub` (veřejné hodnoty z rosteru), takže ji umí spočítat kterýkoli člen a
     * doručit sem zapečetěný `GS`. Týdenní rotace omezuje linkovatelnost a DoS okno.
     * base64url bez výplně.
     */
    fun recovId(groupIdHex: String, memberIdHex: String, sealPublicKeyBase64: String, weekEpoch: Long): String {
        // Length-prefix částí IKM: bez něj by nefixní délky (posun hranice mezi poli)
        // mohly vyrobit kolizi adres recovery schránek dvou různých členů.
        val ikm = lengthPrefixed(hexToBytes(groupIdHex), hexToBytes(memberIdHex), Base64Util.decode(sealPublicKeyBase64))
        val info = "$INFO_RECOVERY|week=$weekEpoch"
        return b64url(hkdf(ikm, info, MAILBOX_ID_BYTES))
    }

    /**
     * Kořenový klíč odesílacího řetězu člena `memberId` v epoše `epoch`. Z něj se
     * krokem [senderStep] odvozují per-zpráva klíče (forward secrecy uvnitř epochy).
     */
    fun senderRootKey(sharedGroupKeyBase64: String, groupIdHex: String, memberIdHex: String, epoch: Long): ByteArray {
        val info = "$INFO_SENDER_ROOT|g=$groupIdHex|m=$memberIdHex|e=$epoch"
        return hkdf(Base64Util.decode(sharedGroupKeyBase64), info, SENDER_KEY_BYTES)
    }

    /**
     * Klíč zprávy odesílatele na pozici `msgNo` (0 = první). Posune řetěz z kořene
     * o `msgNo` kroků a vrátí klíč té pozice — příjemce tak odvodí stejný klíč jako
     * odesílatel, i když zprávy dorazí přeházené. O(msgNo) HKDF (stateless z kořene;
     * FS je na úrovni epochy, viz `GROUP_CHAT_PLAN.md` §1.4).
     */
    fun messageKeyAt(rootKey: ByteArray, msgNo: Int): ByteArray {
        require(msgNo >= 0) { "msgNo nesmí být záporné." }
        var chain = rootKey
        repeat(msgNo) { chain = senderStep(chain).nextChainKey }
        return senderStep(chain).messageKey
    }

    /** Jeden krok odesílacího řetězu: z `chainKey` odvodí (klíč zprávy, další chainKey). */
    data class SenderStep(val messageKey: ByteArray, val nextChainKey: ByteArray)

    /**
     * Posune odesílací řetěz o jeden krok. `messageKey` se použije na jednu zprávu a
     * pak zahodí; `nextChainKey` nahradí předchozí. Krok je jednosměrný (HKDF) —
     * z `nextChainKey` ani `messageKey` nejde spočítat předchozí `chainKey`.
     */
    fun senderStep(chainKey: ByteArray): SenderStep {
        return SenderStep(
            messageKey = hkdf(chainKey, INFO_SENDER_MSG, SENDER_KEY_BYTES),
            nextChainKey = hkdf(chainKey, INFO_SENDER_STEP, SENDER_KEY_BYTES)
        )
    }

    /** Náhodné 16 B ID skupiny (hex). */
    fun randomGroupId(): String = bytesToHex(randomBytes(16))

    /** Náhodné 8 B ID člena (hex). Nikdy se nerecykluje (viz `GROUP_CHAT_PLAN.md` P20). */
    fun randomMemberId(): String = bytesToHex(randomBytes(8))

    /** Náhodné 16 B ID zprávy (hex) — stabilní napříč zařízeními a resendy (dedup). */
    fun randomMsgId(): String = bytesToHex(randomBytes(16))

    // --- pomocné ---

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** Spojí části do `[4B délka][část]...` — jednoznačné i pro nefixní délky. */
    private fun lengthPrefixed(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += 4 + p.size
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            out[pos] = ((p.size ushr 24) and 0xFF).toByte()
            out[pos + 1] = ((p.size ushr 16) and 0xFF).toByte()
            out[pos + 2] = ((p.size ushr 8) and 0xFF).toByte()
            out[pos + 3] = (p.size and 0xFF).toByte()
            System.arraycopy(p, 0, out, pos + 4, p.size)
            pos += 4 + p.size
        }
        return out
    }

    private fun b64url(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)

    private fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, null, info.toByteArray(Charsets.UTF_8)))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    private const val HEX = "0123456789abcdef"

    internal fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    internal fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex musí mít sudou délku." }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Neplatný hex znak." }
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
