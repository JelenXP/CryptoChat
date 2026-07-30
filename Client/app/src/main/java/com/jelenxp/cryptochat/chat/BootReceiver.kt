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
        // JEN chráněné (protected) broadcasty, které umí poslat výhradně systém.
        // QUICKBOOT_POWERON (ani HTC varianta) chráněné NEJSOU: přijímač je
        // exported, takže cizí appka je může doručit EXPLICITNÍM intentem (ten
        // obchází intent-filter úplně) a nastartovat Tor FGS - žrout baterie a
        // prozrazení, že messenger na zařízení běží. Kód je jediná skutečná brána,
        // takže guard musí QUICKBOOT odmítnout, ne jen jeho odebrání z filtru
        // (re-audit #4). BOOT_COMPLETED / MY_PACKAGE_REPLACED chráněné jsou a stačí.
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
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
    }
}
