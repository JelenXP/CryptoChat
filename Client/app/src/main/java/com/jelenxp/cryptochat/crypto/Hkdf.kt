package com.jelenxp.cryptochat.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869) - jedna sdílená implementace pro odvozování klíčů
 * s doménovou separací přes `info` label.
 *
 * `RelayCrypto` má vlastní privátní kopii pro LEGACY schránky (schválně se
 * nemění, ať se bajty starých ID nikdy nehnou). Nový kód (ratchet, ratchet
 * schránky) používá tuhle sdílenou variantu. Obě jsou RFC 5869, takže dávají
 * shodný výstup - hlídá to křížový test.
 */
object Hkdf {

    /** [info] jako UTF-8 řetězec. */
    fun derive(ikm: ByteArray, info: String, length: Int): ByteArray =
        derive(ikm, info.toByteArray(Charsets.UTF_8), length)

    fun derive(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF délka mimo rozsah: $length" }
        val mac = Mac.getInstance("HmacSHA256")
        // Extract: salt = 32 nul (výchozí dle RFC 5869).
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand.
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val output = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(prev)
            mac.update(info)
            mac.update(counter.toByte())
            prev = mac.doFinal() // Mac se po doFinal resetuje na stejný klíč
            val take = minOf(prev.size, length - pos)
            System.arraycopy(prev, 0, output, pos, take)
            pos += take
            counter++
        }
        return output
    }
}
