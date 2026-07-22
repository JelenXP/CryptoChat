package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log

/**
 * Ztlumení oznámení per kontakt. Ukládá se do SharedPreferences jako čas (epoch
 * ms), do kterého je kontakt ztlumený; speciální hodnota [INDEFINITE] znamená
 * „dokud to uživatel sám nezruší". Chybějící záznam = neztlumeno.
 *
 * Ztlumení potlačuje JEN notifikaci nové zprávy (viz gate v
 * [ChatNotifications.notifyMessage]) - zpráva se pořád přijme, uloží a počítá
 * jako nepřečtená. Je to čistě lokální nastavení, protějšek o něm neví.
 *
 * Rozhodnutí „je teď ztlumeno?" je vytažené do čisté [isMutedAt], aby šlo
 * otestovat bez Androidu (pravidlo projektu).
 */
object MuteStore {

    private const val TAG = "MuteStore"
    private const val PREFS = "crypto_chat_mute"

    /** „Ztlumeno, dokud to sám nezruším" - nikdy nevyprší časem. */
    const val INDEFINITE = Long.MAX_VALUE

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(contactId: String) = "mute_$contactId"

    /**
     * Do kdy je kontakt ztlumený (epoch ms), [INDEFINITE] pro „dokud nezruším",
     * nebo `null` když ztlumený není. Vrací uloženou hodnotu i když už čas prošel -
     * na „je teď ztlumeno" je [isMuted] / [isMutedAt].
     */
    fun mutedUntil(context: Context, contactId: String): Long? {
        val v = try {
            prefs(context).getLong(key(contactId), 0L)
        } catch (e: Exception) {
            0L
        }
        return if (v == 0L) null else v
    }

    /** Je kontakt v tuhle chvíli ztlumený? */
    fun isMuted(context: Context, contactId: String): Boolean =
        isMutedAt(mutedUntil(context, contactId), System.currentTimeMillis())

    /** Ztlumí kontakt do času [until] (nebo [INDEFINITE]). */
    fun mute(context: Context, contactId: String, until: Long) {
        try {
            prefs(context).edit().putLong(key(contactId), until).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Uložení ztlumení selhalo", e)
        }
    }

    /** Zruší ztlumení kontaktu. */
    fun unmute(context: Context, contactId: String) {
        try {
            prefs(context).edit().remove(key(contactId)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Zrušení ztlumení selhalo", e)
        }
    }

    /** Úklid při smazání kontaktu. */
    fun clear(context: Context, contactId: String) = unmute(context, contactId)
}

/**
 * Je `mutedUntil` (null / [MuteStore.INDEFINITE] / čas) v okamžiku `now`
 * ztlumené? Čistá funkce - testuje se bez Androidu.
 *
 * [MuteStore.INDEFINITE] == [Long.MAX_VALUE], takže `> now` platí vždy (kromě
 * teoretického `now == Long.MAX_VALUE`) - časovaná i trvalá varianta tak spadnou
 * pod jedno porovnání.
 */
internal fun isMutedAt(mutedUntil: Long?, now: Long): Boolean =
    mutedUntil != null && mutedUntil > now
