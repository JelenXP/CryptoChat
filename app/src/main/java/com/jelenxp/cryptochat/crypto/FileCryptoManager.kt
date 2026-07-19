package com.jelenxp.cryptochat.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Šifrování/dešifrování libovolných **binárních dat a souborů** (obrázky,
 * dokumenty…) stejným AES-256-GCM jádrem jako [CryptoManager], ale bez Base64 a
 * bez komprese.
 *
 * Rozdíly oproti [CryptoManager] (ten je na textové zprávy):
 *  - Výstup je **binární** (`ByteArray` / stream), ne Base64 - u souborů by Base64
 *    jen zbytečně nafouklo velikost o třetinu.
 *  - **Nekomprimuje** se. Typické cíle (JPEG, PNG, PDF, videa…) jsou už
 *    komprimované, takže Deflate by nic neušetřil a jen by přidal režii.
 *
 * Formát výstupu: `IV(12) || ciphertext || GCM tag(16)`. GCM zaručuje důvěrnost
 * i integritu - při poškození dat nebo špatném klíči dešifrování **záměrně
 * vyhodí výjimku** (AEADBadTagException), nikdy nevrátí tichá poškozená data.
 *
 * K dispozici je bajtové API (celý obsah v paměti) i streamové API pro velké
 * soubory. Klíč je tentýž sdílený AES-256 klíč kontaktu jako u zpráv.
 */
object FileCryptoManager {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val STREAM_BUFFER = 64 * 1024

    // --- Bajtové API (vhodné pro menší soubory / obrázky, které se vejdou do paměti) ---

    /** Zašifruje data a vrátí `IV || ciphertext || tag`. */
    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val iv = randomIv()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(data)
    }

    /**
     * Dešifruje data vytvořená [encrypt]. Vyhodí výjimku, když jsou data
     * poškozená / zkrácená nebo klíč nesedí (ověření GCM tagu).
     */
    fun decrypt(payload: ByteArray, key: SecretKey): ByteArray {
        require(payload.size > IV_SIZE_BYTES) { "Data jsou příliš krátká." }
        val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
        val cipherBytes = payload.copyOfRange(IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    /** Pohodlná varianta [encrypt] s klíčem v Base64. */
    fun encrypt(data: ByteArray, keyBase64: String): ByteArray =
        encrypt(data, CryptoManager.keyFromBase64(keyBase64))

    /** Pohodlná varianta [decrypt] s klíčem v Base64. */
    fun decrypt(payload: ByteArray, keyBase64: String): ByteArray =
        decrypt(payload, CryptoManager.keyFromBase64(keyBase64))

    // --- Streamové API (pro velké soubory - nemusí se držet celý obsah v paměti) ---

    /**
     * Zašifruje proud [input] do [output]. Na začátek zapíše IV, pak průběžně
     * šifruje po blocích a nakonec připojí GCM tag. Streamy nezavírá - to je na
     * volajícím.
     */
    fun encryptStream(input: InputStream, output: OutputStream, key: SecretKey) {
        val iv = randomIv()
        output.write(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        pump(input, output, cipher)
    }

    /**
     * Dešifruje proud [input] (ve formátu z [encryptStream]) do [output].
     * Nejdřív načte IV, pak dešifruje po blocích; závěrečné `doFinal` ověří GCM
     * tag a při poškození vyhodí výjimku (nevalidní data se nezapíšou celá).
     * Streamy nezavírá.
     */
    fun decryptStream(input: InputStream, output: OutputStream, key: SecretKey) {
        val iv = ByteArray(IV_SIZE_BYTES)
        readFully(input, iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        pump(input, output, cipher)
    }

    /** Pohodlná varianta [encryptStream] s klíčem v Base64. */
    fun encryptStream(input: InputStream, output: OutputStream, keyBase64: String) =
        encryptStream(input, output, CryptoManager.keyFromBase64(keyBase64))

    /** Pohodlná varianta [decryptStream] s klíčem v Base64. */
    fun decryptStream(input: InputStream, output: OutputStream, keyBase64: String) =
        decryptStream(input, output, CryptoManager.keyFromBase64(keyBase64))

    // --- Pomocné ---

    private fun randomIv(): ByteArray = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

    /** Přežene [input] přes [cipher] do [output] po blocích a zakončí `doFinal`. */
    private fun pump(input: InputStream, output: OutputStream, cipher: Cipher) {
        val buffer = ByteArray(STREAM_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val chunk = cipher.update(buffer, 0, read)
            if (chunk != null && chunk.isNotEmpty()) output.write(chunk)
        }
        val last = cipher.doFinal()
        if (last != null && last.isNotEmpty()) output.write(last)
    }

    /** Načte přesně [dst].size bajtů, nebo vyhodí výjimku (zkrácený vstup). */
    private fun readFully(input: InputStream, dst: ByteArray) {
        var offset = 0
        while (offset < dst.size) {
            val read = input.read(dst, offset, dst.size - offset)
            require(read >= 0) { "Neúplná data - chybí i IV." }
            offset += read
        }
    }
}
