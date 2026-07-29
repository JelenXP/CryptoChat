package com.jelenxp.cryptochat.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.jelenxp.cryptochat.MainActivity
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.util.localizedContext

/**
 * Notifikace chatu: trvalá (ongoing) pro běžící foreground service, který drží
 * Tor teplý, a jednotlivé notifikace nových zpráv. Obsah zpráv je jen lokální
 * (na zařízení uživatele) - na server ani do žádného pushe nic neteče.
 */
object ChatNotifications {

    // Kanál trvalé notifikace spojení. IMPORTANCE_MIN = bez ikony ve status baru
    // (jen v roztažené liště), aby se nepletla s ikonou nové zprávy. Nové id:
    // důležitost kanálu už po vytvoření nejde snížit, takže starý (LOW) se maže.
    const val CHANNEL_SERVICE = "relay_service_min"
    private const val CHANNEL_SERVICE_OLD = "relay_service"
    const val CHANNEL_MESSAGES = "chat_messages"
    const val CHANNEL_UPDATES = "app_updates"
    // Pásma ID notifikací žijí v čistém (jednotkově testovatelném) NotificationIds.
    const val SERVICE_NOTIFICATION_ID = NotificationIds.SERVICE
    private const val UPDATE_NOTIFICATION_ID = NotificationIds.UPDATE

    /**
     * Context s jazykem zvoleným uživatelem. Notifikace vznikají mimo Compose,
     * takže se na ně neuplatní `LocalContext` z [com.jelenxp.cryptochat.ui.util.LocalizedApp]
     * - musí si jazyk prosadit samy podle uloženého tagu.
     */
    private fun local(context: Context): Context =
        localizedContext(context, SettingsRepository(context).getLanguageTag())

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctx = local(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Starý kanál spojení (IMPORTANCE_LOW) měl ikonu ve status baru - smaž ho,
        // ať se nepere s novým MIN kanálem a nekouká zbytečně ve výpisu kanálů.
        runCatching { nm.deleteNotificationChannel(CHANNEL_SERVICE_OLD) }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                ctx.getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = ctx.getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                ctx.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.notif_channel_messages_desc)
            }
        )
        // Aktualizace mají vlastní kanál, ať jdou vypnout zvlášť od zpráv.
        // IMPORTANCE_LOW = bez zvuku; není to nic, kvůli čemu má telefon zvonit.
        // POZOR: důležitost kanálu jde po prvním vytvoření už jen ZVÝŠIT (a to
        // jen uživatelem), takže tuhle hodnotu nelze později opravit - musí být
        // správně napoprvé.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                ctx.getString(R.string.notif_channel_updates),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.notif_channel_updates_desc)
            }
        )
    }

    /** Trvalá notifikace běžícího service (minimální priorita, bez ikony ve status baru). */
    fun buildServiceNotification(context: Context, text: String): Notification {
        val ctx = local(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            // Mrak (spojení), NE bublina se zámkem - ta patří jen novým zprávám,
            // aby šla nová zpráva ve status baru spolehlivě poznat.
            .setSmallIcon(R.drawable.ic_stat_connection)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    /** Aktualizuje text trvalé notifikace (Připojuji… / Připojeno). */
    fun updateService(context: Context, text: String) {
        try {
            NotificationManagerCompat.from(context)
                .notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(context, text))
        } catch (e: SecurityException) {
            // Bez povolení notifikací neukážeme nic - service ale běží dál.
        }
    }

    /**
     * Notifikace nových zpráv od kontaktu jako KONVERZACE (MessagingStyle): ukáže
     * celou historii nepřečtených [unseen], ne jen poslední zprávu. Přidá tlačítka
     * Odpovědět (inline RemoteInput → normální zpráva) a To se mi líbí (👍 na
     * poslední zprávu, když na ni jde reakci navěsit). Klepnutí na tělo otevře
     * konverzaci; obě tlačítka obsluhuje [ChatNotificationReceiver].
     */
    fun notifyMessage(
        context: Context,
        contactId: String,
        contactName: String,
        unseen: List<ChatMessage>
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled() || unseen.isEmpty()) return
        // Ztlumený kontakt: zprávu jsme přijali a započítali jako nepřečtenou,
        // jen se pro ni nezobrazí notifikace (dokud ztlumení trvá).
        if (MuteStore.isMuted(context, contactId)) return
        val ctx = local(context)
        val open = PendingIntent.getActivity(
            context, contactId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, contactId),
            pendingFlags()
        )

        // MessagingStyle = konverzace: každá nepřečtená zpráva jako vlastní řádek.
        val them = Person.Builder().setName(contactName).build()
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName(ctx.getString(R.string.notif_you)).build()
        )
        val photo = ctx.getString(R.string.notif_photo)
        val file = ctx.getString(R.string.notif_file)
        val fallback = ctx.getString(R.string.notif_new_message)
        unseen.forEach { m ->
            style.addMessage(ChatNotificationLogic.lineText(m, photo, file, fallback), m.timestamp, them)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setStyle(style)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Obsah se na zamčené obrazovce skryje; po odemčení je vidět.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(replyAction(ctx, contactId))

        // „To se mi líbí" jen když je na co reakci navěsit (poslední zpráva má wireRef).
        if (ChatNotificationLogic.likeTarget(unseen) != null) {
            builder.addAction(likeAction(ctx, contactId))
        }

        try {
            nm.notify(messageNotificationId(contactId), builder.build())
        } catch (e: SecurityException) {
            // Povolení mezitím odebráno - ignorovat.
        }
    }

    /**
     * Notifikace, že protějšek [contactName] reagoval [emoji] na NAŠI zprávu
     * [target]. Ukáže, na KTEROU zprávu (v uvozovkách; dlouhá se uřízne „…").
     * Volá se jen pro reakci na naši odchozí zprávu (viz [RelaySync]).
     *
     * Respektuje ztlumení kontaktu. Obsah je jen lokální (na zařízení) - na server
     * ani do žádného pushe nic neteče. Vlastní ID pásmo, takže nepřepíše notifikaci
     * nepřečtených zpráv; per-kontakt, takže novější reakce nahradí starší.
     */
    fun notifyReaction(
        context: Context,
        contactId: String,
        contactName: String,
        emoji: String,
        target: ChatMessage
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        // Ztlumený kontakt: reakci zpracujeme (přilepí se ke zprávě), ale
        // notifikaci nezobrazíme - stejné pravidlo jako u zpráv.
        if (MuteStore.isMuted(context, contactId)) return
        val ctx = local(context)
        val display = ChatNotificationLogic.lineText(
            target,
            ctx.getString(R.string.notif_photo),
            ctx.getString(R.string.notif_file),
            ctx.getString(R.string.notif_new_message)
        )
        val snippet = ChatNotificationLogic.reactionSnippet(display)
        val open = PendingIntent.getActivity(
            context, contactId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, contactId),
            pendingFlags()
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(contactName)
            .setContentText(ctx.getString(R.string.notif_reaction, emoji, snippet))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Obsah se na zamčené obrazovce skryje; po odemčení je vidět (jako zprávy).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
        try {
            nm.notify(reactionNotificationId(contactId), builder.build())
        } catch (e: SecurityException) {
            // Povolení mezitím odebráno - ignorovat.
        }
    }

    /** Stabilní ID notifikace o reakci (per-kontakt, vlastní pásmo). */
    private fun reactionNotificationId(contactId: String): Int =
        NotificationIds.reaction(contactId)

    /** Akce „Odpovědět" s inline RemoteInput - odešle NORMÁLNÍ zprávu. */
    private fun replyAction(context: Context, contactId: String): NotificationCompat.Action {
        val intent = Intent(context, ChatNotificationReceiver::class.java)
            .setAction(ChatNotificationReceiver.ACTION_REPLY)
            .putExtra(ChatNotificationReceiver.EXTRA_CONTACT_ID, contactId)
        // RemoteInput vyžaduje MUTABLE PendingIntent (systém do něj vloží text);
        // proto NE pendingFlags() (to je IMMUTABLE). Vlastní requestCode, ať se
        // nepere s obsahovým intentem ani s akcí Like.
        val pi = PendingIntent.getBroadcast(
            context, ("reply:$contactId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )
        val remoteInput = RemoteInput.Builder(ChatNotificationReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        return NotificationCompat.Action.Builder(
            0, context.getString(R.string.notif_action_reply), pi
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    /** Akce „To se mi líbí" - 👍 na poslední příchozí zprávu. */
    private fun likeAction(context: Context, contactId: String): NotificationCompat.Action {
        val intent = Intent(context, ChatNotificationReceiver::class.java)
            .setAction(ChatNotificationReceiver.ACTION_LIKE)
            .putExtra(ChatNotificationReceiver.EXTRA_CONTACT_ID, contactId)
        val pi = PendingIntent.getBroadcast(
            context, ("like:$contactId").hashCode(), intent, pendingFlags()
        )
        return NotificationCompat.Action.Builder(
            0, context.getString(R.string.notif_action_like), pi
        ).build()
    }

    /** MUTABLE flag pro RemoteInput; na < S se mutabilita řeší absencí IMMUTABLE. */
    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    /**
     * Stabilní ID notifikace zpráv daného kontaktu. Per-KONTAKT (ne per-zpráva),
     * takže novější zpráva přepíše starší. Jediný zdroj toho ID: [notifyMessage] i
     * [cancelMessage] ho MUSÍ počítat odsud, jinak by se notifikace nedala zrušit.
     */
    fun messageNotificationId(contactId: String): Int =
        NotificationIds.message(contactId)

    /**
     * Zruší notifikaci zpráv daného kontaktu. Volá se při otevření jeho konverzace:
     * uživatel obsah právě čte, takže notifikace o něm nemá dál viset v liště.
     */
    fun cancelMessage(context: Context, contactId: String) {
        NotificationManagerCompat.from(context).cancel(messageNotificationId(contactId))
    }

    /**
     * Notifikace o nové verzi aplikace. Klepnutí otevře appku, která pak ukáže
     * obrazovku s aktualizací (stejná logika jako při startu).
     *
     * Nesmí obsahovat nic o kontaktech ani zprávách - je to jen číslo verze.
     */
    fun notifyUpdate(context: Context, version: String, important: Boolean): Boolean {
        val nm = NotificationManagerCompat.from(context)
        // Vrací úspěch, aby si volající NEPOZNAMENAL verzi jako oznámenou, když
        // se notifikace vůbec neukázala - jinak by ji po povolení oznámení už
        // nikdy nedostal.
        if (!nm.areNotificationsEnabled()) return false
        val open = PendingIntent.getActivity(
            context, UPDATE_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        val ctx = local(context)
        val title = ctx.getString(
            if (important) R.string.notif_update_title_important else R.string.notif_update_title
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.notif_update_text, version))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Na zamčené obrazovce jen obecný text. Appka jinak drží FLAG_SECURE
            // a skrývá obsah zpráv - bylo by nedůsledné, aby zrovna tahle
            // notifikace komukoli u telefonu prozradila, že tenhle messenger
            // na zařízení je.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .build()
        return try {
            nm.notify(UPDATE_NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // Povolení mezitím odebráno - ignorovat.
            false
        }
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
}
