package com.jelenxp.cryptochat.crypto

import java.io.ByteArrayOutputStream

/**
 * Malý kontejner, který k obsahu souboru přibalí jeho **jméno a MIME typ**, aby
 * je druhá strana po dešifrování získala zpátky (a věděla, jak soubor pojmenovat
 * a otevřít). Zabalený obsah se celý zašifruje přes [FileCryptoManager], takže
 * i metadata jsou chráněná GCM tagem (nejdou nepozorovaně změnit).
 *
 * Formát: `VERSION(1) || nameLen(2 BE) || name(UTF-8) || mimeLen(2 BE) || mime(UTF-8) || data`.
 * Délky jsou 16bitové (max 65535 B pro jméno i MIME, což bohatě stačí).
 */
object FileContainer {

    private const val VERSION = 1
    private const val MAX_FIELD = 0xFFFF

    data class Unpacked(val name: String, val mime: String?, val data: ByteArray)

    /** Zabalí jméno, MIME a data do jednoho bajtového pole. */
    fun pack(name: String, mime: String?, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val mimeBytes = (mime ?: "").toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= MAX_FIELD) { "Jméno souboru je příliš dlouhé." }
        require(mimeBytes.size <= MAX_FIELD) { "MIME typ je příliš dlouhý." }

        val out = ByteArrayOutputStream(5 + nameBytes.size + mimeBytes.size + data.size)
        out.write(VERSION)
        writeLen(out, nameBytes.size)
        out.write(nameBytes)
        writeLen(out, mimeBytes.size)
        out.write(mimeBytes)
        out.write(data)
        return out.toByteArray()
    }

    /** Rozbalí to, co vytvořil [pack]. Neplatný/cizí formát → výjimka. */
    fun unpack(bytes: ByteArray): Unpacked {
        require(bytes.isNotEmpty() && bytes[0].toInt() == VERSION) { "Neznámý formát souboru." }
        var pos = 1

        fun readLen(): Int {
            require(pos + 2 <= bytes.size) { "Poškozená metadata souboru." }
            val len = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            return len
        }

        val nameLen = readLen()
        require(pos + nameLen <= bytes.size) { "Poškozené jméno souboru." }
        val name = String(bytes, pos, nameLen, Charsets.UTF_8)
        pos += nameLen

        val mimeLen = readLen()
        require(pos + mimeLen <= bytes.size) { "Poškozený MIME typ." }
        val mime = String(bytes, pos, mimeLen, Charsets.UTF_8)
        pos += mimeLen

        val data = bytes.copyOfRange(pos, bytes.size)
        return Unpacked(name = name, mime = mime.ifEmpty { null }, data = data)
    }

    private fun writeLen(out: ByteArrayOutputStream, len: Int) {
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
    }
}
