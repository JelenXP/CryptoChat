package com.jelenxp.cryptochat.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jelenxp.cryptochat.data.SettingsRepository

/**
 * Nastartuje [TorForegroundService] po restartu telefonu (a po aktualizaci
 * appky), aby chat běžel na pozadí a chodily notifikace i bez toho, aby uživatel
 * appku otevřel.
 *
 * Pozn.: „autostart" v nastavení telefonu (MIUI/HyperOS apod.) jen POVOLUJE, aby
 * systém appce doručil `BOOT_COMPLETED` - samotné spuštění musí udělat tenhle
 * přijímač. Start foreground service z `BOOT_COMPLETED` je na Androidu 12+
 * povolený (výjimka z omezení startu služeb z pozadí).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != QUICKBOOT_POWERON &&
            action != HTC_QUICKBOOT_POWERON
        ) return

        try {
            // Když není nastavený server chatu, není co spouštět.
            if (SettingsRepository(context).getRelayUrl().isBlank()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, TorForegroundService::class.java)
            )
            Log.i(TAG, "Po startu telefonu ($action) spouštím chat service")
        } catch (e: Exception) {
            Log.e(TAG, "Spuštění service po startu telefonu selhalo", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
