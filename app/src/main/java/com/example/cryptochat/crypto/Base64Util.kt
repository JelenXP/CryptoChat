package com.example.cryptochat.crypto

import java.util.Base64

/**
 * Kódování Base64 pro kryptografické části appky. Používá `java.util.Base64`
 * (dostupné od Androidu API 26 = náš minSdk) místo `android.util.Base64` -
 * díky tomu jdou `CryptoManager` a `PostQuantumKem` testovat na čistém JVM
 * (bez Robolectricu / emulátoru).
 *
 * Výstup je standardní Base64 s paddingem a bez zalamování řádků (shodné
 * s dřívějším `android.util.Base64.NO_WRAP`), takže zůstává kompatibilní
 * s klíči vygenerovanými staršími verzemi appky.
 */
object Base64Util {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    /** Zakóduje bajty do standardního Base64 (s paddingem, bez zalamování). */
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Dekóduje Base64 řetězec. Tolerantní vůči bílým znakům (mezery, nové
     * řádky) - ty se před dekódováním odstraní, ať funguje i ručně vložený
     * text. Na neplatný vstup vyhodí `IllegalArgumentException`.
     */
    fun decode(text: String): ByteArray =
        decoder.decode(text.filterNot { it.isWhitespace() })
}
