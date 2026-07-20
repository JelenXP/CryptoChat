package com.jelenxp.cryptochat.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.ContactRepository
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Otisk (klíč + role) kontaktu, se kterým běží jeho smyčka - viz watchdogTick. */
    private val fingerprints = HashMap<String, String>()
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
        // Drž Tor naživu. ensureStarted dělá diskovou IO (getDir + stavba runtime),
        // a onStartCommand běží na HLAVNÍM vlákně - pustíme to proto mimo něj, ať
        // se služba nezdrží (riziko ANR). startSync() si na Tor stejně počká přes
        // awaitReady, takže na pořadí nezáleží.
        scope.launch(Dispatchers.IO) {
            try {
                if (SettingsRepository(this@TorForegroundService).getRelayUrl().contains(".onion")) {
                    TorController.ensureStarted(this@TorForegroundService)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tor se nepodařilo nastartovat ze service", e)
                DiagnosticsLog.error(TAG, "Tor se nepodařilo nastartovat ze služby (${e.javaClass.simpleName})")
            }
        }
        startSync()
        return START_STICKY
    }

    /** Spustí (jednou) hlídače, který pro každý kontakt s klíčem drží poll smyčku. */
    private fun startSync() {
        if (syncStarted) return
        syncStarted = true
        DiagnosticsLog.log(TAG, "služba na pozadí spuštěna, startuji synchronizaci")
        scope.launch {
            // Nejdřív ZAHŘEJ spojení jedním požadavkem (postaví onion okruh) a
            // teprve pak spusť pollování kontaktů. Jinak by health check i všechny
            // polly cold-connectily naráz a konkurovaly si o stavbu prvního okruhu
            // (nestabilní / pomalé první připojení).
            // Nedokončené přenosy z dřívějška (přerušené, se ztraceným manifestem…)
            // jinak leží na disku navždy. Den je bezpečně za TTL relaye.
            runCatching {
                val ctx = this@TorForegroundService
                MediaTransfers.purgeStale(ctx, 24L * 60 * 60 * 1000)
                // Přenosy, které se nikdy nedokončily, by jinak nechaly bublinu
                // navždy ve stavu „přijímá se" - bez progressu a bez chyby.
                val repo = ChatRepository(ctx)
                ContactRepository(ctx).getContacts().forEach { c ->
                    repo.getMessages(c.id)
                        .filter { it.status == ChatMessage.Status.RECEIVING }
                        .filter { System.currentTimeMillis() - it.timestamp > 24L * 60 * 60 * 1000 }
                        .forEach { m ->
                            repo.updateMedia(c.id, m.id, null, ChatMessage.Status.FAILED)
                            MediaTransfers.clearProgress(m.id)
                        }
                }
            }
            // Zahřívání běží VEDLE, ne před hlídačem. Když se Tor teprve
            // rozjíždí, jedna iterace warmUp trvá i přes minutu - a dokud
            // blokovala, nevznikla ANI JEDNA poll smyčka, takže appka po startu
            // několik minut vůbec nepřijímala zprávy.
            launch { runCatching { warmUp() } }
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
                    DiagnosticsLog.warn(TAG, "tik hlídače selhal (${e.javaClass.simpleName})")
                }
                // Čekej na další tik, ale nech se probudit dřív (nový kontakt,
                // změna serveru) - jinak by čerstvě spárovaný kontakt čekal na
                // svou poll smyčku až dvě minuty.
                withTimeoutOrNull(WATCHDOG_INTERVAL_MS) { wake.receive() }
            }
        }
    }

    /**
     * Jeden tik hlídače: přenačte kontakty, dorovná poll smyčky (spustí chybějící,
     * zruší ty po smazaných kontaktech) a aktualizuje notifikaci.
     */
    private suspend fun watchdogTick() {
        val ctx = this@TorForegroundService
        if (SettingsRepository(ctx).getRelayUrl().isBlank()) {
            // Chat vypnutý - zastav všechny smyčky, ať netočí naprázdno.
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            return
        }

        val contacts = ContactRepository(ctx).getContacts().filter { it.keyBase64 != null }
        val liveIds = contacts.map { it.id }.toSet()

        // Smazaný kontakt = zruš jeho smyčku, ať zbytečně nepollovává cizí schránku.
        jobs.keys.toList().forEach { id ->
            if (id !in liveIds) {
                jobs.remove(id)?.cancel()
                fingerprints.remove(id)
            }
        }
        for (contact in contacts) {
            // Smyčka drží kontakt jako snapshot. Když se změní klíč nebo role při
            // párování, musí se restartovat - jinak by navždy pollovala starou
            // schránku (starý klíč / opačný směr) a zprávy by tiše nedorazily.
            val fingerprint = "${contact.keyBase64}|${contact.initiator}"
            if (jobs[contact.id]?.isActive != true || fingerprints[contact.id] != fingerprint) {
                jobs.remove(contact.id)?.cancel()
                fingerprints[contact.id] = fingerprint
                jobs[contact.id] = scope.launch { syncLoop(contact) }
                // Bez jména i bez ID kontaktu - stačí, že se smyčka (znovu) rozjela.
                DiagnosticsLog.log(TAG, "spuštěna poll smyčka kontaktu")
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
            if (RelayStatus.state == RelayConn.CONNECTED) {
                DiagnosticsLog.log(TAG, "spojení zahřáto (relay dostupný)")
                return
            }
            // Rostoucí odstup: když server neběží (uspaný notebook), nemá smysl
            // pořád dokola stavět okruhy - to je nejdražší věc, co appka umí.
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
        // Nepodařilo se zahřát - poll smyčky to zkusí dál s vlastním backoffem.
        DiagnosticsLog.warn(TAG, "zahřátí spojení se nepovedlo, pokračují poll smyčky")
    }

    /** Nekonečná long-poll smyčka pro jeden kontakt: čeká na zprávy a notifikuje. */
    private suspend fun syncLoop(contact: Contact) {
        val ctx = this@TorForegroundService
        val repo = ChatRepository(ctx)
        var backoff = BACKOFF_START_MS
        while (scope.isActive) {
            // Prázdná adresa = chat vypnutý. Bez téhle pojistky by se `poll()`
            // vracel okamžitě a smyčka by se roztočila na plné CPU.
            if (SettingsRepository(ctx).getRelayUrl().isBlank()) {
                delay(30_000)
                continue
            }
            try {
                // long-poll: drží se, dokud nedorazí zpráva (nebo ~60 s)
                val pollStart = System.currentTimeMillis()
                val result = RelaySync.poll(ctx, contact)
                // Notifikaci NE pro konverzaci, kterou má uživatel otevřenou -
                // tu si zprávu zobrazí sama. Kontroluje se AŽ TEĎ, protože poll
                // mohl běžet ještě z doby, než uživatel chat otevřel.
                if (result.received > 0 && ActiveChat.currentId != contact.id) {
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
                    DiagnosticsLog.warn(TAG, "poll selhal, zpomaluji (backoff $backoff ms)")
                    backoff = sleepBackoff(backoff)
                } else {
                    backoff = BACKOFF_START_MS
                    // Pojistka proti serveru, který nectí long-poll (vrátí se hned):
                    // bez podlahy by se smyčka roztočila na 100 % CPU a stavěla okruh
                    // za okruhem. Výchozí relay drží ~60 s, takže se sem nedostane.
                    val elapsed = System.currentTimeMillis() - pollStart
                    if (elapsed < MIN_POLL_INTERVAL_MS) delay(MIN_POLL_INTERVAL_MS - elapsed)
                }
            } catch (e: CancellationException) {
                throw e                   // zrušení smyčky se musí šířit, ne spolknout
            } catch (e: Exception) {
                DiagnosticsLog.error(
                    TAG,
                    "poll smyčka vyhodila výjimku (${e.javaClass.simpleName}), zpomaluji"
                )
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
            // Restart z alarmu NENÍ na Androidu 12+ výjimka z omezení startu
            // foreground service. Bez vyjmutí z optimalizace baterie by systém
            // při spuštění vyhodil ForegroundServiceStartNotAllowedException -
            // a to už mimo tenhle try, takže by to appku shodilo.
            val power = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            val exempt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                power?.isIgnoringBatteryOptimizations(packageName) == true
            if (!exempt) {
                Log.i(TAG, "Restart po odebrání z recents přeskočen (chybí výjimka z optimalizace baterie)")
                super.onTaskRemoved(rootIntent)
                return
            }
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
         * Probouzí hlídače. CONFLATED: víc žádostí za sebou splyne v jednu,
         * takže se hlídač nerozjede zbytečně vícekrát.
         */
        private val wake = Channel<Unit>(Channel.CONFLATED)

        /**
         * Zajistí, že služba běží, a popožene hlídače, ať hned přenačte kontakty
         * a rozjede jejich poll smyčky.
         *
         * Volá se při otevření konverzace: služba je totiž JEDINÝ příjemce zpráv
         * (obrazovka sama nepollovává), takže kdyby zrovna neběžela, nedorazilo
         * by nic. Zároveň tím odpadá čekání na pravidelný tik hlídače u čerstvě
         * spárovaného kontaktu.
         */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, TorForegroundService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Službu se nepodařilo spustit", e)
            }
            wake.trySend(Unit)
        }

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

        /**
         * Minimální rozestup mezi úspěšnými polly, když se poll vrátí podezřele
         * rychle. Výchozí relay drží long-poll ~60 s, takže se tahle podlaha
         * neuplatní; chrání jen před vlastním serverem, který `?wait=` ignoruje a
         * odpovídá hned - bez ní by se smyčka roztočila na 100 % CPU.
         */
        private const val MIN_POLL_INTERVAL_MS = 3_000L

        /** Strop backoffu při rozsvícené obrazovce (uživatel čeká na zprávu). */
        private const val BACKOFF_MAX_SCREEN_ON_MS = 60_000L

        /** Strop backoffu při zhasnuté obrazovce (šetříme nejvíc). */
        private const val BACKOFF_MAX_SCREEN_OFF_MS = 5L * 60 * 1000
    }
}
