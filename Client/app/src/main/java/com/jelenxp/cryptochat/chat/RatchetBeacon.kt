package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util
import com.jelenxp.cryptochat.crypto.Hkdf
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Beacon (rendezvous ukazatel) pro RATCHET: odesílatel při posunu epochy zapíše
 * do stabilní schránky ([RelayCrypto.ratchetBeaconId]) svou aktuální epochu, aby
 * ji příjemce, který utekl za look-ahead okno (dlouhé offline), našel a dorovnal
 * se (viz `RATCHET_WIRE.md`).
 *
 * Obsah je AES-256-GCM šifrovaný klíčem z neměnného hlavního klíče `M`, takže
 * relay epochu nevidí ani ji nemůže podvrhnout (podvržený beacon by neprošel GCM;
 * i kdyby ano, příjemce jen pollne prázdnou schránku - žádná škoda).
 *
 * Formát: `IV[12] || ciphertext(epoch[4B BE]) || GCM tag[16]`, AAD `ccrb|dir=<dir>`.
 */
object RatchetBeacon {

    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val INFO_BEACON_KEY = "CryptoChat/ratchet/beacon-key/v1"

    private fun beaconKey(masterKeyB64: String): SecretKeySpec =
        SecretKeySpec(Hkdf.derive(Base64Util.decode(masterKeyB64), INFO_BEACON_KEY, 32), "AES")

    private fun aad(dir: Int): ByteArray = "ccrb|dir=$dir".toByteArray(Charsets.US_ASCII)

    /** Zašifruje ukazatel na [epoch]. */
    fun seal(epoch: Int, masterKeyB64: String, dir: Int): ByteArray {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, beaconKey(masterKeyB64), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(dir))
        val payload = ByteBuffer.allocate(4).putInt(epoch).array()
        return iv + cipher.doFinal(payload)
    }

    /** Rozšifruje epochu, nebo null (cizí klíč, poškození, jiný směr, nesmysl). */
    fun open(blob: ByteArray, masterKeyB64: String, dir: Int): Int? {
        return try {
            if (blob.size < IV_SIZE + GCM_TAG_BITS / 8) return null
            val iv = blob.copyOfRange(0, IV_SIZE)
            val ct = blob.copyOfRange(IV_SIZE, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, beaconKey(masterKeyB64), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad(dir))
            val pt = cipher.doFinal(ct)
            if (pt.size < 4) return null
            val epoch = ByteBuffer.wrap(pt).int
            if (epoch < 0) null else epoch
        } catch (e: Exception) {
            null
        }
    }
}
