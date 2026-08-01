package com.jelenxp.cryptochat.chat

import java.io.ByteArrayOutputStream

/**
 * Řídicí balík skupiny doručovaný členovi přes **1:1 kanál** (censurovzdorný, D2):
 * klíčový [GroupKeyPayload] (tajný GS) + podepsaný roster. Roster (~12,5 kB) se nevejde
 * do 1:1 TLV, takže celý balík jde jako OBSAH řídicí 1:1 zprávy (gatováno `CAP_GROUPS`,
 * takže starší appka ho nedostane). Příjemce ho předá do [GroupControl.applyBundle].
 *
 * Tímhle jedním kanálem jde vytvoření skupiny, přidání i rotace při odebrání —
 * uniformně, bez samostatného roster gossipu a bez problému s ověřením u nováčka.
 */
data class GroupBundle(
    val payload: GroupKeyPayload,
    val rosterBytesBase64: String,
    val rosterSigBase64: String,
) {
    companion object {
        private const val FORMAT_VERSION = 1

        fun encode(b: GroupBundle): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(FORMAT_VERSION)
            val p = GroupKeyPayload.encode(b.payload)
            putInt(out, p.size); out.write(p)
            putString(out, b.rosterBytesBase64)
            putString(out, b.rosterSigBase64)
            return out.toByteArray()
        }

        fun decode(bytes: ByteArray): GroupBundle? {
            return try {
                val r = Reader(bytes)
                if (r.readByte() != FORMAT_VERSION) return null
                val payload = GroupKeyPayload.decode(r.readBlob()) ?: return null
                val rb = r.readString()
                val rsig = r.readString()
                if (!r.atEnd()) return null
                if (rb.isEmpty() || rsig.isEmpty()) return null
                GroupBundle(payload, rb, rsig)
            } catch (_: Exception) {
                null
            }
        }

        private fun putInt(out: ByteArrayOutputStream, v: Int) {
            out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        }

        private fun putString(out: ByteArrayOutputStream, s: String) {
            val b = s.toByteArray(Charsets.UTF_8); putInt(out, b.size); out.write(b)
        }

        private class Reader(private val data: ByteArray) {
            private var pos = 0
            fun atEnd() = pos == data.size
            fun readByte(): Int { require(pos + 1 <= data.size); return data[pos++].toInt() and 0xFF }
            fun readInt(): Int {
                require(pos + 4 <= data.size)
                var v = 0; repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xFF) }; return v
            }
            fun readBlob(): ByteArray {
                val len = readInt(); require(len in 0..(data.size - pos))
                val b = data.copyOfRange(pos, pos + len); pos += len; return b
            }
            fun readString(): String = String(readBlob(), Charsets.UTF_8)
        }
    }
}
