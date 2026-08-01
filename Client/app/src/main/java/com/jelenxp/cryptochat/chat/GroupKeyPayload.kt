package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util
import java.io.ByteArrayOutputStream

/**
 * Klíčový ukazatel doručovaný členovi (přes 1:1 kanál admin↔člen, viz D2 —
 * censurovzdorný, odebraný člen na něj nedosáhne). Nese **tajný `GS`** a kontext;
 * VELKÝ podepsaný roster se doručuje zvlášť (group kanál) a sváže se s `GS` přes
 * `gsCommit` (viz `GroupControl`).
 *
 * Pár desítek/stovek bajtů → vejde se do 1:1 TLV (na rozdíl od ~12,5 kB rosteru).
 * `adminPublicKeyBase64` pinne nováček jako trust anchor (přišel po SAS-ověřeném
 * 1:1 kanálu). `assignedMemberId` je nováčkovo přidělené memberId (u rotace null).
 */
data class GroupKeyPayload(
    val groupIdHex: String,
    val epoch: Long,
    val gsBase64: String,
    val gsCommit: String,
    val adminPublicKeyBase64: String,
    val assignedMemberIdHex: String?,
) {
    /**
     * Self-konzistence: `gsCommit` musí odpovídat `GS`, `groupId` a epoše. Odolné
     * proti poškozenému `GS` (neplatný Base64) — vrací false, NIKDY nevyhazuje (jinak
     * by podvržený 1:1 payload shodil poll smyčku fáze 6).
     */
    fun isSelfConsistent(): Boolean = try {
        GroupCrypto.gsCommit(gsBase64, groupIdHex, epoch) == gsCommit
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val FORMAT_VERSION = 1

        fun encode(p: GroupKeyPayload): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(FORMAT_VERSION)
            putString(out, p.groupIdHex)
            putLong(out, p.epoch)
            putString(out, p.gsBase64)
            putString(out, p.gsCommit)
            putString(out, p.adminPublicKeyBase64)
            putString(out, p.assignedMemberIdHex ?: "")
            return out.toByteArray()
        }

        fun decode(bytes: ByteArray): GroupKeyPayload? {
            return try {
                val r = Reader(bytes)
                if (r.readByte() != FORMAT_VERSION) return null
                val gid = r.readString()
                val epoch = r.readLong()
                val gs = r.readString()
                val commit = r.readString()
                val admin = r.readString()
                val assigned = r.readString().ifEmpty { null }
                if (!r.atEnd()) return null
                // Zamítni zjevně poškozený payload hned: GS musí být 32 B Base64,
                // groupId neprázdný hex, admin klíč a commit neprázdné, epoch ≥ 0.
                if (epoch < 0 || gid.isEmpty() || commit.isEmpty() || admin.isEmpty()) return null
                if (Base64Util.decode(gs).size != 32) return null
                GroupKeyPayload(gid, epoch, gs, commit, admin, assigned)
            } catch (_: Exception) {
                null
            }
        }

        /** Base64 obal pro pohodlné vložení do 1:1 TLV. */
        fun encodeBase64(p: GroupKeyPayload): String = Base64Util.encode(encode(p))
        fun decodeBase64(s: String): GroupKeyPayload? = try { decode(Base64Util.decode(s)) } catch (_: Exception) { null }

        private fun putLong(out: ByteArrayOutputStream, v: Long) {
            for (shift in 56 downTo 0 step 8) out.write(((v ushr shift) and 0xFF).toInt())
        }

        private fun putString(out: ByteArrayOutputStream, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write((b.size ushr 24) and 0xFF); out.write((b.size ushr 16) and 0xFF)
            out.write((b.size ushr 8) and 0xFF); out.write(b.size and 0xFF)
            out.write(b)
        }

        private class Reader(private val data: ByteArray) {
            private var pos = 0
            fun atEnd() = pos == data.size
            fun readByte(): Int { require(pos + 1 <= data.size); return data[pos++].toInt() and 0xFF }
            fun readLong(): Long { require(pos + 8 <= data.size); var v = 0L; repeat(8) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }; return v }
            fun readString(): String {
                require(pos + 4 <= data.size)
                var len = 0; repeat(4) { len = (len shl 8) or (data[pos++].toInt() and 0xFF) }
                require(len in 0..(data.size - pos))
                val s = String(data, pos, len, Charsets.UTF_8); pos += len; return s
            }
        }
    }
}
