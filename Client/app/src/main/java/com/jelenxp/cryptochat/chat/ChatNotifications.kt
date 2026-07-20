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
import com.jelenxp.cryptochat.MainActivity
import com.jelenxp.cryptochat.R

/**
 * Notifikace chatu: trvalá (ongoing) pro běžící foreground service, který drží
 * Tor teplý, a jednotlivé notifikace nových zpráv. Obsah zpráv je jen lokální
 * (na zařízení uživatele) - na server ani do žádného pushe nic neteče.
 */
object ChatNotifications {

    const val CHANNEL_SERVICE = "relay_service"
    const val CHANNEL_MESSAGES = "chat_messages"
    const val CHANNEL_UPDATES = "app_updates"
    const val SERVICE_NOTIFICATION_ID = 1001
    private const val MESSAGE_NOTIFICATION_BASE = 2000
    private const val UPDATE_NOTIFICATION_ID = 3001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_messages_desc)
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
                context.getString(R.string.notif_channel_updates),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_updates_desc)
            }
        )
    }

    /** Trvalá notifikace běžícího service (nízká priorita, sbalitelná). */
    fun buildServiceNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

    /** Notifikace nové zprávy od kontaktu. Klepnutí otevře jeho konverzaci. */
    fun notifyMessage(context: Context, contactId: String, contactName: String, text: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, contactId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, contactId),
            pendingFlags()
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(contactName)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Obsah se na zamčené obrazovce skryje (jen "nová zpráva"); po odemčení je vidět.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(MESSAGE_NOTIFICATION_BASE + (contactId.hashCode() and 0xFFFF), notification)
        } catch (e: SecurityException) {
            // Povolení mezitím odebráno - ignorovat.
        }
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
        val title = context.getString(
            if (important) R.string.notif_update_title_important else R.string.notif_update_title
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_update_text, version))
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
