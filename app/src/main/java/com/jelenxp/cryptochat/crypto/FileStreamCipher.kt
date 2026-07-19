package com.jelenxp.cryptochat.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Rámcové (streamové) šifrování souborů AES-256-GCM. Na rozdíl od
 * [FileCryptoManager] (jeden blok v paměti) rozseká obsah na **rámce po
 * [FRAME_SIZE]** a šifruje/dešifruje proud přímo mezi vstupem a výstupem, takže
 * **paměť zůstává nízká i pro velké soubory** (drží se jen jeden rámec).
 *
 * Formát souboru:
 * ```
 * MAGIC(4) | VERSION(1) | baseNonce(8)
 * pak rámce: LENFIELD(4 BE) | ciphertext(len)          (LENFIELD nejvyšší bit = poslední rámec)
 * ```
 * Každý rámec je samostatná GCM operace:
 *  - nonce = baseNonce(8) || counter(4 BE)   (unikátní pro každý rámec i soubor),
 *  - AAD = counter(4 BE) || isLast(1)         (chrání pořadí a konec),
 *  - rámec 0 nese **metadata** (jméno + MIME přes [FileContainer]), rámce 1..N data.
 *
 * Bezpečnost proti manipulaci: poškození/záměna/přeházení rámce → GCM tag selže;
 * **useknutí** (zahození koncových rámců) → chybí rámec s příznakem „poslední" →
 * dešifrování skončí chybou. Nic se nevydá bez ověření.
 */
object FileStreamCipher {

    /** Velikost jednoho rámce (otevřený text). Malý = nízká paměť, zanedbatelná režie. */
    const val FRAME_SIZE = 1 * 1024 * 1024

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val VERSION = 1
    private const val BASE_NONCE_LEN = 8
    private const val LAST_FLAG = 0x80000000.toInt() // nejvyšší bit LENFIELD = poslední rámec
    private const val LEN_MASK = 0x7FFFFFFF
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'C'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())

    /** Metadata souboru získaná z [decrypt]. */
    data class Metadata(val name: String, val mime: String?)

    /**
     * Zašifruje proud [input] do [output]. Jméno a MIME se uloží jako první
     * (šifrovaný) rámec. [maxBytes] omezí velikost vstupu (překročení → výjimka).
     * [onProgress] dostává počet dosud přečtených bajtů vstupu.
     */
    fun encrypt(
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
        name: String,
        mime: String?,
        maxBytes: Long,
        onProgress: (Long) -> Unit
    ) {
        val baseNonce = ByteArray(BASE_NONCE_LEN).also { SecureRandom().nextBytes(it) }
        output.write(MAGIC)
        output.write(VERSION)
        output.write(baseNonce)

        var counter = 0
        // Načteme první datový rámec dopředu, ať víme, jestli je rámec s metadaty
        // zároveň poslední (prázdný soubor).
        var chunk = readChunk(input)
        writeFrame(output, key, baseNonce, counter, isLast = (chunk == null), plain = FileContainer.pack(name, mime, ByteArray(0)))
        counter++

        var total = 0L
        while (chunk != null) {
            total += chunk.size
            if (total > maxBytes) throw IOException("Soubor překročil povolený limit.")
            onProgress(total)
            val next = readChunk(input)
            writeFrame(output, key, baseNonce, counter, isLast = (next == null), plain = chunk)
            counter++
            chunk = next
        }
        output.flush()
    }

    /**
     * Dešifruje proud [input] (formát z [encrypt]) do [output] a vrátí metadata.
     * [maxBytes] omezí velikost výstupu. [onProgress] dostává počet přečtených
     * bajtů vstupu. Poškození / špatný klíč / useknutí → výjimka.
     */
    fun decrypt(
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
        maxBytes: Long,
        onProgress: (Long) -> Unit
    ): Metadata {
        val magic = ByteArray(4)
        readFully(input, magic)
        require(magic.contentEquals(MAGIC)) { "Není to zašifrovaný soubor CryptoChat." }
        val version = input.read()
        require(version == VERSION) { "Nepodporovaná verze souboru." }
        val baseNonce = ByteArray(BASE_NONCE_LEN)
        readFully(input, baseNonce)

        var counter = 0
        var metadata: Metadata? = null
        var sawLast = false
        var outTotal = 0L
        var inTotal = (4 + 1 + BASE_NONCE_LEN).toLong()

        while (true) {
            val lenField = readIntOrEof(input) ?: break // konec mezi rámci
            val isLast = (lenField and LAST_FLAG) != 0
            val len = lenField and LEN_MASK
            require(len in 1..(FRAME_SIZE + 64)) { "Poškozený rámec." }
            val ct = ByteArray(len)
            readFully(input, ct)
            inTotal += 4 + len

            val plain = decryptFrame(key, baseNonce, counter, isLast, ct)
            if (counter == 0) {
                val meta = FileContainer.unpack(plain)
                metadata = Metadata(meta.name, meta.mime)
            } else {
                outTotal += plain.size
                if (outTotal > maxBytes) throw IOException("Soubor překročil povolený limit.")
                output.write(plain)
            }
            onProgress(inTotal)
            counter++
            if (isLast) {
                sawLast = true
                break
            }
        }
        output.flush()
        require(sawLast) { "Soubor je neúplný (chybí konec)." }
        return metadata ?: throw IOException("Chybí metadata souboru.")
    }

    // --- interní ---

    private fun writeFrame(
        output: OutputStream,
        key: SecretKey,
        baseNonce: ByteArray,
        counter: Int,
        isLast: Boolean,
        plain: ByteArray
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce(baseNonce, counter)))
        cipher.updateAAD(aad(counter, isLast))
        val ct = cipher.doFinal(plain)
        var lenField = ct.size
        if (isLast) lenField = lenField or LAST_FLAG
        writeIntBE(output, lenField)
        output.write(ct)
    }

    private fun decryptFrame(
        key: SecretKey,
        baseNonce: ByteArray,
        counter: Int,
        isLast: Boolean,
        ct: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce(baseNonce, counter)))
        cipher.updateAAD(aad(counter, isLast))
        return cipher.doFinal(ct)
    }

    /** 12bajtový nonce = baseNonce(8) || counter(4 BE). */
    private fun nonce(baseNonce: ByteArray, counter: Int): ByteArray =
        baseNonce + intToBytes(counter)

    private fun aad(counter: Int, isLast: Boolean): ByteArray =
        intToBytes(counter) + byteArrayOf(if (isLast) 1 else 0)

    /** Načte až [FRAME_SIZE] bajtů; vrátí přesně velké pole, nebo null na konci. */
    private fun readChunk(input: InputStream): ByteArray? {
        val buffer = ByteArray(FRAME_SIZE)
        var off = 0
        while (off < FRAME_SIZE) {
            val read = input.read(buffer, off, FRAME_SIZE - off)
            if (read < 0) break
            off += read
        }
        return when (off) {
            0 -> null
            FRAME_SIZE -> buffer
            else -> buffer.copyOf(off)
        }
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val read = input.read(dst, off, dst.size - off)
            if (read < 0) throw IOException("Neúplný soubor.")
            off += read
        }
    }

    /** Přečte 4bajtové BE číslo, nebo null když je hned konec proudu. */
    private fun readIntOrEof(input: InputStream): Int? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b1 < 0 || b2 < 0 || b3 < 0) throw IOException("Neúplný rámec.")
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun writeIntBE(output: OutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}
