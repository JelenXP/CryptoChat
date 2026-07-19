package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.CryptoManager
import java.security.SecureRandom

/**
 * Online párování pozvánkovým kódem. Jeden člověk vytvoří jednorázový kód, řekne
 * ho druhému (SMS, osobně, jiný chat…), ten ho zadá - a přes relay proběhne
 * automaticky post-kvantová výměna klíče (ML-KEM). Server u toho vidí jen dvě
 * náhodná ID schránek, nezná identity ani klíč.
 *
 * Kód je Crockfordova base32 (bez zaměnitelných znaků I/L/O/U), ať se dobře čte
 * a přepisuje. Kanonická podoba (velká písmena, bez oddělovačů) se používá pro
 * odvození schránek přes [RelayCrypto] - proto se vstup od uživatele normalizuje.
 */
object Pairing {

    // Crockford base32 abeceda (bez I, L, O, U).
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CODE_CHARS = 16 // 16 znaků base32 = 80 bitů náhody

    /** Vygeneruje novou kanonickou pozvánku (16 znaků A–Z/0–9 bez oddělovačů). */
    fun generateInvite(): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(CODE_CHARS)
        repeat(CODE_CHARS) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Rozdělí kód po čtveřicích pro čitelné zobrazení, např. „ABCD-EFGH-JKMN-PQRS". */
    fun formatForDisplay(canonical: String): String =
        canonical.chunked(4).joinToString("-")

    /**
     * Převede uživatelský vstup na kanonickou podobu: velká písmena, zahodí
     * oddělovače a namapuje běžné překlepy zaměnitelných znaků (I/L→1, O→0).
     * Obě strany tak z „abcd efgh…" i „ABCD-EFGH…" dostanou stejný řetězec.
     */
    fun normalize(input: String): String {
        val sb = StringBuilder()
        for (ch in input.uppercase()) {
            val mapped = when (ch) {
                'I', 'L' -> '1'
                'O' -> '0'
                'U' -> 'V'
                else -> ch
            }
            if (mapped in ALPHABET) sb.append(mapped)
        }
        return sb.toString()
    }

    /** Základní kontrola tvaru kódu (délka). */
    fun looksValid(canonical: String): Boolean = canonical.length == CODE_CHARS

    // --- Balení handshake blobů dočasným klíčem z pozvánky ---
    // Chrání veřejný klíč / zapouzdření na relayi před manipulací bez znalosti
    // pozvánky. Skutečnou obranu proti MITM dělá až potvrzení SAS kódu.

    fun wrap(text: String, inviteKeyBase64: String): ByteArray =
        CryptoManager.encrypt(text, CryptoManager.keyFromBase64(inviteKeyBase64))
            .toByteArray(Charsets.UTF_8)

    fun unwrap(blob: ByteArray, inviteKeyBase64: String): String? = try {
        CryptoManager.decrypt(String(blob, Charsets.UTF_8), CryptoManager.keyFromBase64(inviteKeyBase64))
    } catch (e: Exception) {
        null
    }
}
