package com.example.cryptochat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Testy Base64 kódování (běží na čistém JVM). */
class Base64UtilTest {

    @Test
    fun roundTrip_zachovaData() {
        val data = byteArrayOf(0, 1, 2, 3, 127, -1, -128, 42, 99)
        val encoded = Base64Util.encode(data)
        assertArrayEquals(data, Base64Util.decode(encoded))
    }

    @Test
    fun decode_toleruje_bileZnaky() {
        val data = ByteArray(40) { it.toByte() }
        val encoded = Base64Util.encode(data)
        // Vložíme mezery a nové řádky - dekódování je má ignorovat.
        val messy = encoded.chunked(4).joinToString("\n  ")
        assertArrayEquals(data, Base64Util.decode(messy))
    }

    @Test
    fun prazdnyVstup() {
        assertEquals("", Base64Util.encode(ByteArray(0)))
        assertArrayEquals(ByteArray(0), Base64Util.decode(""))
    }
}
