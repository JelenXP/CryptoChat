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
    const val SERVICE_NOTIFICATION_ID = 1001
    private const val MESSAGE_NOTIFICATION_BASE = 2000

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

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
}
