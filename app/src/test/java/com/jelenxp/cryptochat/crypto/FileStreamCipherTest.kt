package com.jelenxp.cryptochat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.crypto.SecretKey

/**
 * Testy rámcového streamového šifrování souborů ([FileStreamCipher]).
 * Běží na čistém JVM (jen javax.crypto).
 */
class FileStreamCipherTest {

    private val F = FileStreamCipher.FRAME_SIZE

    private fun key() = CryptoManager.generateKey()
    private fun rnd(n: Int, seed: Long = 1) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun enc(data: ByteArray, key: SecretKey, name: String = "f", mime: String? = "x/y", max: Long = Long.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        FileStreamCipher.encrypt(ByteArrayInputStream(data), out, key, name, mime, max) {}
        return out.toByteArray()
    }

    private fun dec(blob: ByteArray, key: SecretKey, max: Long = Long.MAX_VALUE): Pair<FileStreamCipher.Metadata, ByteArray> {
        val out = ByteArrayOutputStream()
        val meta = FileStreamCipher.decrypt(ByteArrayInputStream(blob), out, key, max) {}
        return meta to out.toByteArray()
    }

    @Test
    fun roundTrip_ruzneVelikostiVcetneHranicRamcu() {
        val key = key()
        for (size in listOf(0, 1, 100, F - 1, F, F + 1, 2 * F, 2 * F + 123)) {
            val data = rnd(size, size.toLong() + 1)
            val (meta, back) = dec(enc(data, key, name = "n$size"), key)
            assertArrayEquals("velikost $size", data, back)
            assertEquals("n$size", meta.name)
        }
    }

    @Test
    fun metadata_stejnyKlic() {
        val k = key()
        val (meta, data) = dec(enc(rnd(10), k, name = "obr.png", mime = "image/png"), k)
        assertEquals("obr.png", meta.name)
        assertEquals("image/png", meta.mime)
        assertEquals(10, data.size)
    }

    @Test
    fun nullMime_zustaneNull() {
        val k = key()
        val (meta, _) = dec(enc(rnd(5), k, name = "x", mime = null), k)
        assertNull(meta.mime)
    }

    @Test
    fun poskozenyBajt_selze() {
        val k = key()
        val blob = enc(rnd(3 * F + 7), k)
        blob[blob.size / 2] = (blob[blob.size / 2] + 1).toByte()
        assertThrows(Exception::class.java) { dec(blob, k) }
    }

    @Test
    fun useknutyKonec_selze() {
        val k = key()
        val blob = enc(rnd(2 * F), k)
        val truncated = blob.copyOf(blob.size - 200)
        assertThrows(Exception::class.java) { dec(truncated, k) }
    }

    @Test
    fun spatnyKlic_selze() {
        val blob = enc(rnd(1000), key())
        assertThrows(Exception::class.java) { dec(blob, key()) }
    }

    @Test
    fun cizaData_selze() {
        assertThrows(Exception::class.java) { dec(rnd(500), key()) }
    }

    @Test
    fun limit_prekroceni_selze() {
        assertThrows(Exception::class.java) { enc(rnd(2 * F), key(), max = F.toLong()) }
    }

    @Test
    fun kazdeSifrovani_jinyVystup() {
        val k = key()
        val data = rnd(1000)
        assertFalse(enc(data, k).contentEquals(enc(data, k)))
    }
}
