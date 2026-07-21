package com.jelenxp.cryptochat.chat

import android.util.Base64
import com.jelenxp.cryptochat.crypto.Base64Util
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Odvozování anonymních identifikátorů schránek (dead-drop) a pomocných klíčů
 * pro relay. Server je „slepá schránka" - vidí jen náhodně vypadající ID; že za
 * ním stojí konkrétní dvojice a směr, ví jen telefony, protože ID se počítá
 * z jejich sdíleného klíče, který server nikdy neviděl.
 *
 * Vše stojí na HKDF-SHA256 (RFC 5869) s doménovou separací přes `info` label -
 * stejný princip, jaký appka používá u SAS kódu. ID schránky se navíc mění podle
 * `epoch` (rotace), takže server nespojí, že dnešní a zítřejší schránka patří
 * téže dvojici.
 */
object RelayCrypto {

    // Délka ID schránky v bajtech (24 B -> 32 znaků base64url, splňuje server regex).
    private const val MAILBOX_ID_BYTES = 24

    private const val INFO_MAILBOX = "CryptoChat/relay/mailbox/v1"
    private const val INFO_RENDEZVOUS = "CryptoChat/relay/rendezvous/v1"
    private const val INFO_INVITE_KEY = "CryptoChat/relay/invite-key/v1"

    /**
     * Seed pro RATCHET schránky. Odvozuje se z neměnného hlavního klíče `M`
     * kontaktu (viz `RATCHET_WIRE.md`, přijatý kompromis: adresy nejsou
     * forward-secret vůči pozdější kompromitaci zařízení). Label je odlišný od
     * [INFO_MAILBOX], takže ratchet a legacy schránky se nikdy nepotkají.
     */
    private const val INFO_RATCHET_MAILBOX = "CryptoChat/relay/ratchet-mailbox/v1"

    /**
     * ID schránky pro daný sdílený klíč, směr (0 = iniciátor→odpovídající,
     * 1 = opačně) a epochu. Výstup je base64url bez výplně - neprůhledný řetězec.
     */
    fun mailboxId(sharedKeyBase64: String, direction: Int, epoch: Long): String {
        val ikm = Base64Util.decode(sharedKeyBase64)
        val info = "$INFO_MAILBOX|dir=$direction|epoch=$epoch"
        return b64url(hkdf(ikm, info, MAILBOX_ID_BYTES))
    }

    /**
     * ID rendezvous schránky pro online párování. Odvozuje se z jednorázové
     * pozvánky; `leg` odlišuje směr výměny ("init" = veřejný klíč iniciátora,
     * "resp" = odpověď se zapouzdřením).
     */
    fun rendezvousId(inviteCode: String, leg: String): String {
        val ikm = inviteCode.toByteArray(Charsets.UTF_8)
        val info = "$INFO_RENDEZVOUS|leg=$leg"
        return b64url(hkdf(ikm, info, MAILBOX_ID_BYTES))
    }

    /**
     * Dočasný AES-256 klíč (Base64) odvozený z pozvánky. Používá se k zabalení
     * handshake blobů při párování, aby s nimi na relayi nešlo manipulovat bez
     * znalosti pozvánky (server ji nezná). Skutečnou obranu proti MITM ale dělá
     * až potvrzení SAS kódu oběma stranami.
     */
    fun inviteKeyBase64(inviteCode: String): String {
        val ikm = inviteCode.toByteArray(Charsets.UTF_8)
        return Base64Util.encode(hkdf(ikm, INFO_INVITE_KEY, 32))
    }

    /**
     * ID RATCHET schránky pro daný sdílený klíč `M`, směr a **ratchet epochu**
     * (u32 čítač, NE den). Dvoustupňové odvození: `seed = HKDF(M, …)`,
     * `id = HKDF(seed, "dir=<dir>|epoch=<e>")`. Obě strany spočítají stejně
     * (znají `M`), takže se najdou i s rozbitými hodinami (Návrh 2).
     */
    fun ratchetMailboxId(sharedKeyBase64: String, direction: Int, epoch: Int): String {
        val seed = hkdf(Base64Util.decode(sharedKeyBase64), INFO_RATCHET_MAILBOX, 32)
        return b64url(hkdf(seed, "dir=$direction|epoch=$epoch", MAILBOX_ID_BYTES))
    }

    /**
     * ID beacon (rendezvous) schránky - stabilní záchytný bod z neměnného `M`,
     * kam odesílatel při posunu epochy položí ukazatel aktuální epochy pro
     * příjemce, který utekl za look-ahead okno (viz `RATCHET_WIRE.md`).
     */
    fun ratchetBeaconId(sharedKeyBase64: String, direction: Int): String {
        val seed = hkdf(Base64Util.decode(sharedKeyBase64), INFO_RATCHET_MAILBOX, 32)
        return b64url(hkdf(seed, "beacon|dir=$direction", MAILBOX_ID_BYTES))
    }

    // --- HKDF-SHA256 (RFC 5869) přes javax.crypto (bez závislostí navíc) ---

    private fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract: salt = 32 nul (výchozí dle RFC 5869).
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand.
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val output = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(prev)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            prev = mac.doFinal() // Mac se po doFinal resetuje na stejný klíč
            val take = minOf(prev.size, length - pos)
            System.arraycopy(prev, 0, output, pos, take)
            pos += take
            counter++
        }
        return output
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
