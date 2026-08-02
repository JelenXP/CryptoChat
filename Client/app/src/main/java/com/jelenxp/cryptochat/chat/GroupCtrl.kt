package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import com.jelenxp.cryptochat.data.ContactRepository
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Příjem SKUPINOVÝCH řídicích zpráv doručených přes 1:1 kanál ([WireExt.TYPE_GROUP_CTRL]).
 * Drží celou logiku join handshake, aby zásah do ostře otestovaného [RelaySync] byl
 * minimální (jediná delegující větev). Tři subtypy:
 *  - INVITE  → ulož pozvánku pro UI kartu ([GroupInviteStore]),
 *  - PUBKEYS → admin: přidej člena a rozešli bundly (zatím jen zaznamená — auto-odpověď
 *    dodá odesílací cesta, viz `GROUP_CHAT_PLAN.md` fáze 6),
 *  - BUNDLE  → adoptuj klíč+roster ([GroupControl.applyBundle]) mými skupinovými klíči.
 */
object GroupCtrlReceiver {

    /** Vrací true = durabilně zpracováno (smí se ACKnout); false = zápis selhal, přijde znovu. */
    fun handle(context: Context, fromContactId: String, subtype: Int, bytes: ByteArray, storageCrypto: StorageCrypto): Boolean {
        return try {
            when (subtype) {
                // Pozvánka pro UI kartu. Poškozená (decode null) → ACK (zahodit); když se
                // ale uložení NEPOVEDE (plné prefs), vrať false → přijde znovu (nález v3.2 #4).
                WireExt.GROUP_CTRL_INVITE ->
                    GroupInvite.decode(bytes)?.let { GroupInviteStore.save(context, fromContactId, it) } ?: true
                // Admin dostal pubkeys nováčka → přidej člena a rozešli bundly VŠEM.
                WireExt.GROUP_CTRL_PUBKEYS -> handlePubkeys(context, fromContactId, bytes, storageCrypto)
                WireExt.GROUP_CTRL_BUNDLE -> handleBundle(context, bytes, storageCrypto)
                else -> true // neznámý subtype → zahodit
            }
        } catch (_: Exception) {
            true // nepřátelský vstup nesmí zaseknout dávku donekonečna
        }
    }

    /**
     * Admin: příchozí PUBKEYS nováčka → [GroupActions.onPubkeysReceived] (přidá člena
     * + rozešle balíky). Kontakty se čtou přes týž [storageCrypto] jako zbytek roury
     * (v testech FakeStorageCrypto). `false` = zápis skupiny selhal (přijde znovu).
     */
    private fun handlePubkeys(context: Context, fromContactId: String, bytes: ByteArray, storageCrypto: StorageCrypto): Boolean {
        val pubkeys = GroupPubkeys.decode(bytes) ?: return true
        val contacts = ContactRepository(context, storageCrypto).getContacts()
        val from = contacts.firstOrNull { it.id == fromContactId } ?: return true
        return GroupActions.onPubkeysReceived(context, from, pubkeys, contacts, storageCrypto)
    }

    /** Adoptuje bundle mými skupinovými klíči (stávající člen z [GroupStore], nováček z [GroupJoinStore]). */
    private fun handleBundle(context: Context, bytes: ByteArray, storageCrypto: StorageCrypto): Boolean {
        val bundle = GroupBundle.decode(bytes) ?: return true
        val store = GroupStore(context, storageCrypto)
        val existing = store.getGroup(bundle.payload.groupIdHex)
        val sign: GroupIdentity.SignKeyPair
        val seal: GroupIdentity.SealKeyPair
        if (existing != null) {
            sign = GroupIdentity.SignKeyPair(existing.mySignPublicKeyBase64, existing.mySignPrivateKeyBase64)
            seal = GroupIdentity.SealKeyPair(existing.mySealPublicKeyBase64, existing.mySealPrivateKeyBase64)
        } else {
            val pending = GroupJoinStore.get(context, bundle.payload.groupIdHex, storageCrypto) ?: return true // není pro mě / nepřipraven
            sign = pending.first
            seal = pending.second
        }
        return when (GroupControl.applyBundle(bundle.payload, bundle.rosterBytesBase64, bundle.rosterSigBase64, sign, seal, store)) {
            GroupControl.ApplyResult.FAILED -> false // zápis selhal → nepotvrzuj, přijde znovu
            GroupControl.ApplyResult.APPLIED -> {
                // Nováček: úklid pending join klíčů (už jsou v Group) a pozvánky.
                GroupJoinStore.remove(context, bundle.payload.groupIdHex, storageCrypto)
                true
            }
            else -> true // STALE/REMOVED/INVALID/NEEDS_KEY → smí se ACKnout (zahodit)
        }
    }
}

/** Pozvánka do skupiny (pro UI kartu v 1:1 chatu). Malá, jde tělem 1:1 zprávy. */
data class GroupInvite(
    val groupIdHex: String,
    val name: String,
    val memberCount: Int,
    val adminPublicKeyBase64: String,
) {
    companion object {
        fun encode(i: GroupInvite): ByteArray {
            val o = ByteArrayOutputStream()
            putString(o, i.groupIdHex); putString(o, i.name); putInt(o, i.memberCount); putString(o, i.adminPublicKeyBase64)
            return o.toByteArray()
        }
        fun decode(b: ByteArray): GroupInvite? = try {
            val r = R(b); val g = r.s(); val n = r.s(); val c = r.i(); val a = r.s()
            if (!r.end() || g.isEmpty() || a.isEmpty() || c < 0) null else GroupInvite(g, n, c, a)
        } catch (_: Exception) { null }
    }
}

/** Skupinové pubkeys nováčka (admin z nich sestaví roster). Malé, jde tělem 1:1 zprávy. */
data class GroupPubkeys(
    val groupIdHex: String,
    val displayName: String,
    val ed25519PublicKeyBase64: String,
    val sealPublicKeyBase64: String,
) {
    companion object {
        fun encode(p: GroupPubkeys): ByteArray {
            val o = ByteArrayOutputStream()
            putString(o, p.groupIdHex); putString(o, p.displayName); putString(o, p.ed25519PublicKeyBase64); putString(o, p.sealPublicKeyBase64)
            return o.toByteArray()
        }
        fun decode(b: ByteArray): GroupPubkeys? = try {
            val r = R(b); val g = r.s(); val n = r.s(); val e = r.s(); val s = r.s()
            if (!r.end() || g.isEmpty() || e.isEmpty() || s.isEmpty()) null else GroupPubkeys(g, n, e, s)
        } catch (_: Exception) { null }
    }
}

/** Čekající pozvánky (metadata) per 1:1 kontakt — pro kartu v jeho chatu. Prosté prefs. */
object GroupInviteStore {
    private const val PREFS = "crypto_chat_prefs"
    private val lock = Any()
    private fun key(contactId: String) = "ginv_$contactId"

    fun save(context: Context, contactId: String, inv: GroupInvite): Boolean = synchronized(lock) {
        try {
            val o = JSONObject().put("g", inv.groupIdHex).put("n", inv.name).put("c", inv.memberCount).put("a", inv.adminPublicKeyBase64)
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(contactId), o.toString()).commit()
        } catch (_: Exception) { false }
    }

    fun get(context: Context, contactId: String): GroupInvite? = synchronized(lock) {
        try {
            val s = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(contactId), null) ?: return null
            val o = JSONObject(s)
            GroupInvite(o.optString("g"), o.optString("n"), o.optInt("c"), o.optString("a")).takeIf { it.groupIdHex.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    fun remove(context: Context, contactId: String) = synchronized(lock) {
        try { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(contactId)).commit() } catch (_: Exception) {}
    }
}

/** Čekající skupinové klíče nováčka (TAJNÉ) per groupId — po „Přijmout" do adopce bundlu. */
object GroupJoinStore {
    private const val PREFS = "crypto_chat_prefs"
    private val lock = Any()
    private fun key(groupId: String) = "gjoin_$groupId"

    fun save(context: Context, groupId: String, sign: GroupIdentity.SignKeyPair, seal: GroupIdentity.SealKeyPair, crypto: StorageCrypto = KeystoreStorageCrypto): Boolean = synchronized(lock) {
        try {
            val o = JSONObject().put("sp", sign.privateKeyBase64).put("su", sign.publicKeyBase64).put("kp", seal.privateKeyBase64).put("ku", seal.publicKeyBase64)
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(groupId), crypto.encrypt(o.toString())).commit()
        } catch (_: Exception) { false }
    }

    fun get(context: Context, groupId: String, crypto: StorageCrypto = KeystoreStorageCrypto): Pair<GroupIdentity.SignKeyPair, GroupIdentity.SealKeyPair>? = synchronized(lock) {
        try {
            val enc = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(groupId), null) ?: return null
            val o = JSONObject(crypto.decrypt(enc) ?: return null)
            GroupIdentity.SignKeyPair(o.optString("su"), o.optString("sp")) to GroupIdentity.SealKeyPair(o.optString("ku"), o.optString("kp"))
        } catch (_: Exception) { null }
    }

    fun remove(context: Context, groupId: String, crypto: StorageCrypto = KeystoreStorageCrypto) = synchronized(lock) {
        try { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(groupId)).commit() } catch (_: Exception) {}
    }
}

// --- sdílené serializační helpery (length-prefixed, jako ostatní group kodeky) ---

private fun putInt(o: ByteArrayOutputStream, v: Int) {
    o.write((v ushr 24) and 0xFF); o.write((v ushr 16) and 0xFF); o.write((v ushr 8) and 0xFF); o.write(v and 0xFF)
}
private fun putString(o: ByteArrayOutputStream, s: String) {
    val b = s.toByteArray(Charsets.UTF_8); putInt(o, b.size); o.write(b)
}
private class R(private val d: ByteArray) {
    private var p = 0
    fun end() = p == d.size
    fun i(): Int { require(p + 4 <= d.size); var v = 0; repeat(4) { v = (v shl 8) or (d[p++].toInt() and 0xFF) }; return v }
    fun s(): String { val n = i(); require(n in 0..(d.size - p)); val s = String(d, p, n, Charsets.UTF_8); p += n; return s }
}
