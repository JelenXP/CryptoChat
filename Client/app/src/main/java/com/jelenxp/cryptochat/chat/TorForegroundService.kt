package com.jelenxp.cryptochat.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.ContactRepository
import com.jelenxp.cryptochat.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Foreground service, který drží zabudovaný Tor „teplý" a na pozadí vyzvedává
 * zprávy pro všechny kontakty (long-poll), takže:
 *  - první (pomalé) navázání onion okruhu se platí jen jednou; další otevření
 *    appky je pak skoro okamžité,
 *  - nové zprávy chodí jako notifikace i když appka není v popředí.
 *
 * Tohle je cesta k „normálnímu messengeru" bez ztráty soukromí: nepoužíváme
 * žádný push (FCM/Google), který by prozradil, kdo komu píše - spojení držíme
 * sami přes Tor. Trvalá notifikace je cena za to, že server ani nikdo jiný
 * nezná metadata (stejný přístup jako Briar).
 */
class TorForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    @Volatile private var syncStarted = false

    /** Poslední text trvalé notifikace - přepisujeme ji jen při skutečné změně. */
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChatNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Musí se zavolat hned (do 5 s), jinak ANR. Typ služby bere z manifestu.
        startForeground(
            ChatNotifications.SERVICE_NOTIFICATION_ID,
            ChatNotifications.buildServiceNotification(this, getString(R.string.notif_connecting))
        )
        // Drž Tor naživu.
        try {
            if (SettingsRepository(this).getRelayUrl().contains(".onion")) {
                TorController.ensureStarted(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tor se nepodařilo nastartovat ze service", e)
        }
        startSync()
        return START_STICKY
    }

    /** Spustí (jednou) hlídače, který pro každý kontakt s klíčem drží poll smyčku. */
    private fun startSync() {
        if (syncStarted) return
        syncStarted = true
        scope.launch {
            // Nejdřív ZAHŘEJ spojení jedním požadavkem (postaví onion okruh) a
            // teprve pak spusť pollování kontaktů. Jinak by health check i všechny
            // polly cold-connectily naráz a konkurovaly si o stavbu prvního okruhu
            // (nestabilní / pomalé první připojení).
            runCatching { warmUp() }
            while (isActive) {
                // Celý tik hlídače je pod try/catch: jediná nechycená výjimka by
                // zabila tuhle korutinu a appka by až do restartu procesu přestala
                // přijímat zprávy (`syncStarted` už je true, nikdo ji nenastartuje).
                try {
                    watchdogTick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Tik hlídače selhal", e)
                }
                delay(WATCHDOG_INTERVAL_MS)  // přenačtení kontaktů + případný keepalive
            }
        }
    }

    /**
     * Jeden tik hlídače: přenačte kontakty, dorovná poll smyčky (spustí chybějící,
     * zruší ty po smazaných kontaktech) a aktualizuje notifikaci.
     */
    private suspend fun watchdogTick() {
        val ctx = this@TorForegroundService
        if (SettingsRepository(ctx).getRelayUrl().isBlank()) return

        val contacts = ContactRepository(ctx).getContacts().filter { it.keyBase64 != null }
        val liveIds = contacts.map { it.id }.toSet()

        // Smazaný kontakt = zruš jeho smyčku, ať zbytečně nepollovává cizí schránku.
        jobs.keys.toList().forEach { id ->
            if (id !in liveIds) jobs.remove(id)?.cancel()
        }
        for (contact in contacts) {
            if (jobs[contact.id]?.isActive != true) {
                jobs[contact.id] = scope.launch { syncLoop(contact) }
            }
        }
        // Keepalive JEN když nic nepolluje. Běžící long-polly drží okruh
        // teplý samy - health request navíc by byl čistá spotřeba navíc.
        if (contacts.isEmpty()) RelayStatus.refresh(ctx)
        updateNotification()
    }

    /**
     * Přepíše text trvalé notifikace, ale jen když se opravdu změnil. Zbytečné
     * přepisování budí systémové UI (a tím i CPU) při každém tiku hlídače.
     */
    private fun updateNotification() {
        val text = getString(
            if (RelayStatus.state == RelayConn.CONNECTED) R.string.notif_connected
            else R.string.notif_connecting
        )
        if (text == lastNotificationText) return
        lastNotificationText = text
        ChatNotifications.updateService(this, text)
    }

    /**
     * Zahřeje spojení: opakovaně zkusí health (přes [RelayStatus], takže se
     * aktualizuje i ikona cloudu), dokud není CONNECTED. Tím se postaví onion
     * okruh JEDNOU a serializovaně - navazující polly kontaktů ho pak jen sdílejí
     * (rychlé). [RelayStatus.refresh] má vlastní pojistku proti souběhu, takže
     * se nepere se stejným voláním z hlavní obrazovky.
     */
    private suspend fun warmUp() {
        val ctx = this@TorForegroundService
        val deadline = System.currentTimeMillis() + WARMUP_BUDGET_MS
        var backoff = 1_500L
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            if (SettingsRepository(ctx).getRelayUrl().isBlank()) return
            RelayStatus.refresh(ctx)          // blokuje než health doběhne; no-op při souběhu
            if (RelayStatus.state == RelayConn.CONNECTED) return
            // Rostoucí odstup: když server neběží (uspaný notebook), nemá smysl
            // pořád dokola stavět okruhy - to je nejdražší věc, co appka umí.
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
        // Nepodařilo se zahřát - poll smyčky to zkusí dál s vlastním backoffem.
    }

    /** Nekonečná long-poll smyčka pro jeden kontakt: čeká na zprávy a notifikuje. */
    private suspend fun syncLoop(contact: Contact) {
        val ctx = this@TorForegroundService
        val repo = ChatRepository(ctx)
        var backoff = BACKOFF_START_MS
        while (scope.isActive) {
            // Otevřená konverzace si pollovává sama (ChatScreen) - nepouštěj na
            // stejnou schránku druhé spojení, byla by to dvojnásobná spotřeba.
            if (ActiveChat.currentId == contact.id) {
                delay(2000)
                continue
            }
            try {
                // long-poll: drží se, dokud nedorazí zpráva (nebo ~60 s)
                val result = RelaySync.poll(ctx, contact)
                if (result.received > 0) {
                    val lastIncoming = repo.getMessages(contact.id).lastOrNull { !it.outgoing }
                    ChatNotifications.notifyMessage(
                        ctx,
                        contact.id,
                        contact.name,
                        lastIncoming?.text ?: getString(R.string.notif_new_message)
                    )
                }
                if (result.failed) {
                    // Server nedostupný - zpomaluj, ať se donekonečna nestaví okruhy.
                    backoff = sleepBackoff(backoff)
                } else {
                    backoff = BACKOFF_START_MS
                }
            } catch (e: CancellationException) {
                throw e                   // zrušení smyčky se musí šířit, ne spolknout
            } catch (e: Exception) {
                backoff = sleepBackoff(backoff)
            }
        }
    }

    /**
     * Počká podle aktuálního backoffu a vrátí ten příští (dvojnásobek). Strop se
     * čte AŽ TEĎ, ne při zdvojnásobení - takže když uživatel mezitím rozsvítí
     * obrazovku, dlouhé čekání se zkrátí na kratší strop a zpráva dorazí rychle.
     */
    private suspend fun sleepBackoff(current: Long): Long {
        val max = maxBackoffMs()
        delay(current.coerceAtMost(max))
        return (current * 2).coerceAtMost(BACKOFF_MAX_SCREEN_OFF_MS)
    }

    /**
     * Strop backoffu. Při zhasnuté obrazovce ho pustíme výš - uživatel stejně
     * zprávu hned nečte a zbytečné pokusy o spojení jsou v tu chvíli nejdražší.
     */
    private fun maxBackoffMs(): Long {
        val interactive = try {
            (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isInteractive ?: true
        } catch (e: Exception) {
            true
        }
        return if (interactive) BACKOFF_MAX_SCREEN_ON_MS else BACKOFF_MAX_SCREEN_OFF_MS
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Uživatel smázl appku z posledních (recents). Zkus se za chvíli znovu
        // spustit, ať spojení na pozadí přežije. Funguje to, když je appka vyjmutá
        // z optimalizace baterie (jinak ji systém stejně zabije - viz onboarding).
        try {
            val restart = Intent(applicationContext, TorForegroundService::class.java)
            val flags = PendingIntent.FLAG_ONE_SHOT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            // getForegroundService = správný způsob spuštění FGS z pozadí (API 26+).
            val pending = PendingIntent.getForegroundService(this, 1, restart, flags)
            (getSystemService(ALARM_SERVICE) as? AlarmManager)
                ?.set(AlarmManager.RTC, System.currentTimeMillis() + 2000, pending)
        } catch (e: Exception) {
            Log.w(TAG, "Restart po odebrání z recents selhal", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "TorForegroundService"

        /**
         * Jak často hlídač přenačte kontakty (a případně udrží okruh teplý).
         * Každý tik čte SharedPreferences a dešifruje kontakty přes Keystore, takže
         * krátký interval stojí CPU zbytečně - poll smyčky si běží samy.
         */
        private const val WATCHDOG_INTERVAL_MS = 120_000L

        /** Kolik času nejvýš strávit zahříváním spojení, než to necháme na poll smyčkách. */
        private const val WARMUP_BUDGET_MS = 3L * 60 * 1000

        /** První pauza po neúspěšném pollu; dál se zdvojnásobuje. */
        private const val BACKOFF_START_MS = 3_000L

        /** Strop backoffu při rozsvícené obrazovce (uživatel čeká na zprávu). */
        private const val BACKOFF_MAX_SCREEN_ON_MS = 60_000L

        /** Strop backoffu při zhasnuté obrazovce (šetříme nejvíc). */
        private const val BACKOFF_MAX_SCREEN_OFF_MS = 5L * 60 * 1000
    }
}
