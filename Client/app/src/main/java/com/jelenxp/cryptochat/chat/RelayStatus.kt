package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jelenxp.cryptochat.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Stav připojení k serveru chatu (pro indikátor u ikony cloudu). */
enum class RelayConn { UNKNOWN, CONNECTING, CONNECTED, FAILED }

/**
 * Globální (pozorovatelný) stav dostupnosti serveru chatu. Appka ho testuje
 * sama od sebe po startu / návratu do popředí; UI (ikona cloudu na hlavní
 * obrazovce) podle něj ukazuje kolečko / fajfku. Bez toastů - jen tichý indikátor.
 */
object RelayStatus {

    private const val TAG = "RelayStatus"

    var state: RelayConn by mutableStateOf(RelayConn.UNKNOWN)
        private set

    // Aby neběželo víc testů spojení naráz (zbytečné dotazy přes Tor), pustí se
    // vždy jen jeden; další volání během něj se přeskočí.
    private val inFlight = AtomicBoolean(false)

    /**
     * Otestuje dostupnost aktuálně nastaveného serveru chatu a aktualizuje
     * [state]. Prázdná adresa = server vypnutý ([RelayConn.UNKNOWN]). Volej
     * z korutiny na hlavním vlákně (I/O si přepne sama). Nikdy nevyhodí výjimku.
     */
    suspend fun refresh(context: Context) {
        if (!inFlight.compareAndSet(false, true)) return
        try {
            doRefresh(context)
        } catch (e: CancellationException) {
            throw e                       // zrušení korutiny musí projít dál
        } catch (e: Exception) {
            // Nesmí uniknout ven: volá se i z foreground service, kde by nechycená
            // výjimka (typicky ze startu Toru) shodila celou synchronizační
            // korutinu - a appka by pak už nikdy nepollovala, jen svítila „připojuji".
            Log.w(TAG, "Test spojení selhal", e)
            state = RelayConn.FAILED
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun doRefresh(context: Context) {
        val url = SettingsRepository(context).getRelayUrl()
        if (url.isBlank()) {
            state = RelayConn.UNKNOWN
            return
        }
        // Nepřepínej zpět na „připojuji", když už jednou připojeno - ať indikátor
        // zbytečně nebliká při každém návratu do appky.
        if (state != RelayConn.CONNECTED) state = RelayConn.CONNECTING
        // ensureStarted dělá diskovou IO - mimo hlavní vlákno (doRefresh se volá z UI).
        if (url.contains(".onion")) withContext(Dispatchers.IO) { TorController.ensureStarted(context) }
        val ok = withContext(Dispatchers.IO) { RelayClient.health(url) }
        state = if (ok) RelayConn.CONNECTED else RelayConn.FAILED
    }
}
