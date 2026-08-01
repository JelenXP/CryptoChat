package com.jelenxp.cryptochat.chat

import java.io.ByteArrayOutputStream

/**
 * Seznam členů skupiny jako **podepsaný snapshot** (ne delta). Roster je jediný
 * zdroj pravdy o složení skupiny; podepisuje ho **admin** svým Ed25519 klíčem a
 * nese v sobě `gsCommit`, čímž kryptograficky sváže autoritu admina i s hodnotou
 * `GS` dané epochy (viz `GROUP_CHAT_PLAN.md`, nález crypto#1).
 *
 * Kanonická binární serializace ([encode]/[decode]) je zároveň:
 *  - to, co admin podepisuje ([sign]/[verify]) — takže podpis platí přes drát,
 *  - drátový/úložný formát rosteru.
 *
 * Přijetí rosteru na straně člena (viz roura) = [decode] ✓ + [validate] ✓ +
 * [verify] proti PINNUTÉMU adminovu klíči ✓ + `epoch > uložené` ✓.
 */
object GroupRoster {

    /** Strop velikosti skupiny. Vynucuje se při přidání i při [validate] příchozího rosteru. */
    const val MAX_GROUP_MEMBERS = 10

    /** Doménový label podpisu rosteru (oddělený od podpisu zpráv apod.). */
    const val LABEL = "CryptoChat/group/roster/v1"

    private const val FORMAT_VERSION = 1
    private const val ED25519_PUBLIC_KEY_BYTES = 32

    data class Member(
        val memberIdHex: String,
        val displayName: String,
        val ed25519PublicKeyBase64: String,
        val sealPublicKeyBase64: String,
    )

    data class Roster(
        val groupIdHex: String,
        val groupEpoch: Long,
        val name: String,
        val adminMemberIdHex: String,
        val gsCommit: String,
        val members: List<Member>,
    )

    /** Ed25519 veřejný klíč admina podle `adminMemberId`, nebo null když admin v rosteru není. */
    fun adminPublicKey(roster: Roster): String? =
        roster.members.firstOrNull { it.memberIdHex == roster.adminMemberIdHex }?.ed25519PublicKeyBase64

    // --- podpis / ověření ---

    /** Podepíše kanonickou serializaci rosteru adminovým soukromým Ed25519 klíčem. */
    fun sign(roster: Roster, adminPrivateKeyBase64: String): String =
        GroupIdentity.sign(adminPrivateKeyBase64, LABEL, encode(roster))

    /**
     * Ověří podpis rosteru proti **PINNUTÉMU** adminovu veřejnému klíči (získanému
     * při joinu po SAS-ověřeném 1:1 kanálu), NE proti klíči z rosteru samotného —
     * jinak by podvržený roster přinesl i podvrženého admina.
     */
    fun verify(roster: Roster, signatureBase64: String, trustedAdminPublicKeyBase64: String): Boolean =
        GroupIdentity.verify(trustedAdminPublicKeyBase64, LABEL, encode(roster), signatureBase64)

    /**
     * Sedí `gsCommit` rosteru s daným `GS`? Klient adoptuje `GS` (z 1:1 i z recovery)
     * jen když tohle vrátí true — tím nikdo nepodstrčí vlastní `GS` pod pravým rosterem.
     */
    fun matchesGsCommit(roster: Roster, sharedGroupKeyBase64: String): Boolean =
        GroupCrypto.gsCommit(sharedGroupKeyBase64, roster.groupIdHex, roster.groupEpoch) == roster.gsCommit

    // --- validace ---

    /**
     * Strukturální validace nezávislá na podpisu: neprázdná pole, velikost 1..MAX,
     * unikátní `memberId` i klíče, admin je členem, epoch ≥ 0. Testuje se i na
     * „nedosažitelný" vstup (roster > 10) — až někdo posune strop, chytí se to tady.
     */
    fun validate(roster: Roster): Boolean {
        if (roster.groupIdHex.isEmpty() || roster.gsCommit.isEmpty() || roster.adminMemberIdHex.isEmpty()) return false
        if (roster.groupEpoch < 0) return false
        if (roster.members.isEmpty() || roster.members.size > MAX_GROUP_MEMBERS) return false

        val ids = HashSet<String>()
        val edKeys = HashSet<String>()
        val sealKeys = HashSet<String>()
        for (m in roster.members) {
            if (m.memberIdHex.isEmpty()) return false
            // Klíče se dekódují a validují — neplatný Base64 nebo špatná délka
            // Ed25519 = poškozený/škodlivý roster.
            val ed = decodeOrNull(m.ed25519PublicKeyBase64) ?: return false
            val seal = decodeOrNull(m.sealPublicKeyBase64) ?: return false
            if (ed.size != ED25519_PUBLIC_KEY_BYTES || seal.isEmpty()) return false
            if (!ids.add(m.memberIdHex)) return false
            // Dedup nad DEKÓDOVANÝMI bajty, ne base64 řetězcem — dvě různé base64
            // reprezentace téhož klíče nesmí projít jako „unikátní" (rozbilo by to
            // atribuci odesílatele / jedinečnost seal klíče).
            if (!edKeys.add(GroupCrypto.bytesToHex(ed))) return false
            if (!sealKeys.add(GroupCrypto.bytesToHex(seal))) return false
        }
        return ids.contains(roster.adminMemberIdHex)
    }

    private fun decodeOrNull(base64: String): ByteArray? =
        try { com.jelenxp.cryptochat.crypto.Base64Util.decode(base64) } catch (_: Exception) { null }

    // --- kanonická serializace (deterministická) ---

    /**
     * Deterministická binární serializace — vstup pro podpis i drát/úložiště.
     * Pořadí členů se NEřadí: kanonickým artefaktem je **přijatý byte-obraz**
     * (co admin podepsal), který roura ukládá a nad kterým počítá roster-hash
     * (§1.3/crypto#14). Nikdy neporovnávej roster-hash nad RE-serializovaným
     * objektem — hashuj přijaté bajty, ať pořadí členů nespustí falešné varování.
     */
    fun encode(roster: Roster): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FORMAT_VERSION)
        putString(out, roster.groupIdHex)
        putLong(out, roster.groupEpoch)
        putString(out, roster.name)
        putString(out, roster.adminMemberIdHex)
        putString(out, roster.gsCommit)
        putInt(out, roster.members.size)
        for (m in roster.members) {
            putString(out, m.memberIdHex)
            putString(out, m.displayName)
            putString(out, m.ed25519PublicKeyBase64)
            putString(out, m.sealPublicKeyBase64)
        }
        return out.toByteArray()
    }

    /** Rozparsuje [encode]. Vrací null na jakýkoli poškozený/useknutý vstup (nevyhazuje). */
    fun decode(bytes: ByteArray): Roster? {
        return try {
            val r = Reader(bytes)
            if (r.readByte() != FORMAT_VERSION) return null
            val groupId = r.readString()
            val epoch = r.readLong()
            val name = r.readString()
            val adminId = r.readString()
            val gsCommit = r.readString()
            val count = r.readInt()
            // Strop čteme hned, ať přerostlý vstup nealokuje N položek.
            if (count < 0 || count > MAX_GROUP_MEMBERS) return null
            val members = ArrayList<Member>(count)
            repeat(count) {
                members.add(
                    Member(
                        memberIdHex = r.readString(),
                        displayName = r.readString(),
                        ed25519PublicKeyBase64 = r.readString(),
                        sealPublicKeyBase64 = r.readString(),
                    )
                )
            }
            if (!r.atEnd()) return null
            Roster(groupId, epoch, name, adminId, gsCommit, members)
        } catch (_: Exception) {
            null
        }
    }

    private fun putInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun putLong(out: ByteArrayOutputStream, v: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((v ushr shift) and 0xFF).toInt())
    }

    private fun putString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        putInt(out, b.size)
        out.write(b)
    }

    /** Kurzor nad bajty s kontrolou mezí — čtení za konec vyhodí (chytí [decode]). */
    private class Reader(private val data: ByteArray) {
        private var pos = 0
        fun atEnd(): Boolean = pos == data.size
        fun readByte(): Int {
            require(pos + 1 <= data.size)
            return data[pos++].toInt() and 0xFF
        }
        fun readInt(): Int {
            require(pos + 4 <= data.size)
            var v = 0
            repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xFF) }
            return v
        }
        fun readLong(): Long {
            require(pos + 8 <= data.size)
            var v = 0L
            repeat(8) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }
            return v
        }
        fun readString(): String {
            val len = readInt()
            require(len in 0..(data.size - pos))
            val s = String(data, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
    }
}
