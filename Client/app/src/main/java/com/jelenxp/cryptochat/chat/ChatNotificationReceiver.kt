package com.jelenxp.cryptochat.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.jelenxp.cryptochat.data.ContactRepository
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Zpracuje tlačítka z notifikace nové zprávy (viz [ChatNotifications.notifyMessage]):
 *  - **Odpovědět** ([ACTION_REPLY]) - text z inline RemoteInput odešle jako
 *    NORMÁLNÍ zprávu (ne jako in-app funkci „odpověď"), stejnou cestou jako
 *    obrazovka konverzace: [RelaySync.enqueue] + [RelaySync.deliver].
 *  - **To se mi líbí** ([ACTION_LIKE]) - navěsí 👍 na POSLEDNÍ příchozí zprávu
 *    přes [RelaySync.sendReaction].
 *
 * Síť (Tor) běží mimo hlavní vlákno; [goAsync] drží proces naživu, dokud odeslání
 * nedoběhne. Odeslaná zpráva je díky `enqueue` už uložená, takže se neztratí ani
 * když doručení selže - dorazí při dalším pokusu. Po akci se konverzace označí za
 * přečtenou a notifikace zmizí.
 */
class ChatNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: return
        val action = intent.action ?: return
        // Text z inline odpovědi vytáhni TEĎ (v onReceive), ne až v korutině -
        // results z intentu se musí přečíst dřív, než se intent recykluje.
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim()

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contact = ContactRepository(appContext).getContacts()
                    .find { it.id == contactId } ?: return@launch
                when (action) {
                    ACTION_REPLY -> if (!replyText.isNullOrEmpty()) {
                        // replyToWireId = null → obyčejná zpráva, ne in-app odpověď.
                        val msg = RelaySync.enqueue(appContext, contact, replyText, null)
                        RelaySync.deliver(appContext, contact, msg)
                    }
                    ACTION_LIKE -> {
                        val ref = ChatRepository(appContext).getMessages(contactId)
                            .lastOrNull { !it.outgoing && it.wireRef != null }?.wireRef
                        if (ref != null) RelaySync.sendReaction(appContext, contact, ref, LIKE_EMOJI)
                    }
                }
                // Uživatel na zprávu zareagoval - je přečtená a notifikace může pryč.
                ChatRepository(appContext).markRead(contactId)
                ChatNotifications.cancelMessage(appContext, contactId)
            } catch (e: Exception) {
                DiagnosticsLog.warn(TAG, "akce notifikace selhala (${e.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.jelenxp.cryptochat.action.NOTIF_REPLY"
        const val ACTION_LIKE = "com.jelenxp.cryptochat.action.NOTIF_LIKE"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val KEY_REPLY = "key_reply_text"
        const val LIKE_EMOJI = "👍"
        private const val TAG = "ChatNotifyReceiver"
    }
}
