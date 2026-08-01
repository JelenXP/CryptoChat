package com.jelenxp.cryptochat.chat

import android.content.Context

/**
 * Perzistentní čítač odesílacích `msgNo` na (skupinu, epochu). Určuje pozici
 * v odesílacím sender-key řetězu; roste o 1 při každé odeslané zprávě i doručence.
 * Musí přežít restart, jinak by se `msgNo` opakovalo. Reset na 0 při nové epoše
 * (nový `GS` = nový řetěz).
 *
 * Opakování `msgNo` (kdyby `commit` selhal) je bezpečné pro GCM (každý blob má
 * čerstvý IV), jen dva bloby sdílejí pozici v řetězu — příjemce oba dešifruje.
 */
object GroupSendCounter {

    private const val PREFS_NAME = "crypto_chat_prefs"
    private val lock = Any()

    /** Vrátí aktuální `msgNo` a rovnou ho posune (perzistentně). */
    fun next(context: Context, groupId: String, epoch: Long): Int = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "gsend_${groupId}_$epoch"
        val cur = prefs.getInt(key, 0)
        prefs.edit().putInt(key, cur + 1).commit()
        cur
    }
}
