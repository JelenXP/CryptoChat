package com.jelenxp.cryptochat.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.ContactRepository
import com.jelenxp.cryptochat.data.FeatureFlags
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.data.UpdateChecker
import com.jelenxp.cryptochat.data.UpdateNotifyPolicy
import com.jelenxp.cryptochat.data.UpdateRoutePolicy
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
import com.jelenxp.cryptochat.ui.util.localizedContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    /**
     * Poslední text trvalé notifikace - přepisujeme ji jen při skutečné změně.
     * `@Volatile`, protože `updateNotification()` teď volá i warmUp a poll smyčky
     * (různá vlákna IO dispatcheru), ne jen tik hlídače.
     */
    @Volatile private var lastNotificationText: String? = null

    /**
     * „Generace" konektivity - roste při každém návratu sítě (konec výpadku,
     * přepnutí Wi-Fi↔data). Poll smyčky na ni čekají BĚHEM backoffu (viz
     * [sleepBackoff]), aby po návratu sítě nezůstaly viset na dlouhé pauze a příjem
     * se chytl hned. `StateFlow`, ať jednu změnu zachytí všechny čekající smyčky.
     */
    private val networkGeneration = MutableStateFlow(0)

    /** Registrovaný callback změn sítě (odregistruje se v [onDestroy]). */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChatNotifications.ensureChannels(this)
        registerNetworkCallback()
    }

    /**
     * Zaregistruje sledování výchozí sítě. Když se síť VRÁTÍ (`onAvailable` - konec
     * výpadku, přepnutí Wi-Fi↔data), probudí poll smyčky z backoffu a popožene
     * hlídač, ať se příjem chytne okamžitě místo čekání na (až 5min) backoff.
     *
     * Záměrně řešíme jen NÁVRAT sítě: je bezpečný (nanejvýš probudí smyčku, která
     * pak zase selže a zpomalí). Výpadek explicitně neřešíme - to už dělá povinný
     * backoff; pauzovat smyčky na `onLost` by hrozilo, že se příjem omylem zastaví.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkGeneration.value = networkGeneration.value + 1
                wake.trySend(Unit)
                DiagnosticsLog.log(TAG, "síť dostupná, probouzím příjem")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            DiagnosticsLog.warn(TAG, "registrace síťového callbacku selhala (${e.javaClass.simpleName})")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Musí se zavolat hned (do 5 s), jinak ANR. Typ služby bere z manifestu.
        val startCtx = localizedContext(this, SettingsRepository(this).getLanguageTag())
        startForeground(
            ChatNotifications.SERVICE_NOTIFICATION_ID,
            ChatNotifications.buildServiceNotification(this, startCtx.getString(R.string.notif_connecting))
        )
        // Drž Tor naživu. ensureStarted dělá diskovou IO (getDir + stavba runtime),
        // a onStartCommand běží na HLAVNÍM vlákně - pustíme to proto mimo něj, ať
        // se služba nezdrží (riziko ANR). startSync() si na Tor stejně počká přes
        // awaitReady, takže na pořadí nezáleží.
        scope.launch(Dispatchers.IO) {
            try {
                // Spusť Tor, jen když je aspoň jedna efektivní adresa .onion
                // (getRelayUrls = primární + záložní). V Cloudflare módu se nespustí.
                val settings = SettingsRepository(this@TorForegroundService)
                if (TorManager.anyOnion(settings.getRelayUrls())) {
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
            // Úklid nedokončených přenosů běží VEDLE (vlastní launch), NE před
            // smyčkami: dešifruje celou historii všech kontaktů přes Keystore, a
            // kdyby blokoval, odložil by start VŠECH poll smyček (tedy první
            // příjem po startu) o dobu úklidu - u dlouhých historií znatelně.
            launch {
                runCatching {
                    val ctx = this@TorForegroundService
                    // Nedokončené přenosy (přerušené, se ztraceným manifestem…) by
                    // jinak ležely na disku navždy. Den je bezpečně za TTL relaye.
                    MediaTransfers.purgeStale(ctx, 24L * 60 * 60 * 1000)
                    // Přenosy, které se nikdy nedokončily, by jinak nechaly bublinu
                    // navždy ve stavu „přijímá se" - bez progressu a bez chyby.
                    val repo = ChatRepository(ctx)
                    ContactRepository(ctx).getContacts().forEach { c ->
                        repo.getMessages(c.id)
                            .filter { it.status == ChatMessage.Status.RECEIVING }
                            // Zaseklé pozná LOKÁLNÍ stav: purgeStale výše smazal
                            // tmp adresář (24 h od posledního doteku), takže když
                            // už neexistuje, přenos je definitivně mrtvý. Dřív se
                            // to řídilo časem ODESÍLATELE - rozjeté hodiny protějšku
                            // pak bublinu nechaly „přijímá se" napořád.
                            .filter { !MediaTransfers.hasPending(ctx, it.id) }
                            .forEach { m ->
                                repo.updateMedia(c.id, m.id, null, ChatMessage.Status.FAILED)
                                MediaTransfers.clearProgress(m.id)
                            }
                    }
                }
            }
            // Zahřej spojení jedním požadavkem (postaví onion okruh). Běží VEDLE,
            // ne před hlídačem: když se Tor teprve rozjíždí, jedna iterace warmUp
            // trvá i přes minutu - a dokud blokovala, nevznikla ANI JEDNA poll
            // smyčka, takže appka po startu několik minut vůbec nepřijímala zprávy.
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
        val settings = SettingsRepository(ctx)
        val relayUrl = settings.getRelayUrl()
        if (relayUrl.isBlank()) {
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
        // Srovnej běh Toru s potřebou. needsTor = aspoň jedna efektivní adresa (primární
        // i záložní) je .onion - pokrývá i .onion zálohu pod clearnet primárkou.
        val needsTor = TorManager.anyOnion(settings.getRelayUrls())
        when {
            // Přepnuto na clearnet (Cloudflare), ale Tor z dřívějška ještě běží -
            // ZASTAV ho (ať jsou kontakty nebo ne), jinak by dál žral baterii.
            !needsTor && TorController.isRunning -> {
                DiagnosticsLog.log(TAG, "clearnet režim, zastavuji běžící Tor")
                withContext(Dispatchers.IO) { TorController.stop() }
            }
            // Keepalive JEN když nic nepolluje. Běžící long-polly drží okruh teplý
            // samy - health request navíc by byl čistá spotřeba navíc.
            contacts.isEmpty() -> RelayStatus.refresh(ctx)
            // Tor mohl na pozadí spadnout (listener se zavřel / StartDaemon selhal)
            // a sám se nerozjede - poll smyčky volají jen `awaitReady`, ne
            // `ensureStarted`. Bez tohohle by doručování na pozadí tiše umřelo až do
            // otevření appky. `ensureStarted` je no-op, když runtime žije.
            needsTor && !TorController.isRunning -> {
                DiagnosticsLog.warn(TAG, "Tor na pozadí neběží, znovu ho spouštím")
                withContext(Dispatchers.IO) { TorController.ensureStarted(ctx) }
            }
        }
        updateNotification()
        maybeNotifyAboutUpdate()
    }

    /**
     * Jednou za [UPDATE_CHECK_INTERVAL_MS] se podívá, jestli nevyšla nová verze,
     * a když ano, pošle notifikaci.
     *
     * **Proč to nestojí baterii:** neprobouzí nic navíc - jede na tiku hlídače,
     * který běží tak jako tak, a na síť jde jen když od poslední kontroly uplynul
     * celý interval. Požadavek jde přes Tor (viz [UpdateChecker]); napřímo by
     * z reálné IP prozradil, že tenhle messenger na zařízení běží.
     *
     * Pravidla pro zobrazení jsou schválně stejná jako u kontroly při startu
     * (`MainActivity`): respektuje vypnutou kontrolu i pozastavené připomínání,
     * ať uživatel nedostane z pozadí to, co si v appce odklikl pryč.
     */
    private fun maybeNotifyAboutUpdate() {
        val ctx = this@TorForegroundService
        if (!FeatureFlags.UPDATE_CHECK_ENABLED) return
        val settings = SettingsRepository(ctx)
        if (!settings.isUpdateCheckEnabled()) return
        val now = System.currentTimeMillis()
        when (
            UpdateNotifyPolicy.decide(now, settings.getUpdateLastCheckAt(), UPDATE_CHECK_INTERVAL_MS)
        ) {
            UpdateNotifyPolicy.Decision.SKIP -> return
            // Čerstvá instalace: jen orazítkovat, na síť teď nechodit.
            UpdateNotifyPolicy.Decision.STAMP_ONLY -> {
                settings.setUpdateLastCheckAt(now)
                return
            }
            UpdateNotifyPolicy.Decision.CHECK -> Unit
        }
        // Razítko se dává PŘED požadavkem: díky tomu další tik hlídače uvidí
        // „kontrolováno teď" a nespustí druhou souběžnou kontrolu, i když tahle
        // ještě běží na pozadí.
        settings.setUpdateLastCheckAt(now)

        // VEDLE tiku, ne uvnitř. UpdateChecker čeká až 30 s na Tor a dalších
        // 20 s na odpověď; uvnitř tiku by o tuhle dobu odložil dorovnání poll
        // smyček, tedy zotavení příjmu zpráv.
        scope.launch {
            // Přes co (soukromí): Tor / clearnet napřímo / prázdná adresa = přeskočit,
            // ať se z reálné IP neposílá clearnet dotaz bez volby uživatele (viz UpdateRoutePolicy).
            val relayUrl = settings.getRelayUrl()
            val viaTor = when (UpdateRoutePolicy.route(relayUrl, TorManager.urlIsOnion(relayUrl))) {
                UpdateRoutePolicy.Route.SKIP -> return@launch
                UpdateRoutePolicy.Route.TOR -> true
                UpdateRoutePolicy.Route.DIRECT -> false
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.checkDetailed(currentVersionName(), viaTor) }
                    .getOrDefault(UpdateChecker.Result.Failed)
            }
            if (result is UpdateChecker.Result.Failed) {
                val failures = settings.getUpdateCheckFailures() + 1
                settings.setUpdateCheckFailures(failures)
                settings.setUpdateLastCheckAt(
                    UpdateNotifyPolicy.retryStamp(
                        now, UPDATE_CHECK_INTERVAL_MS, UPDATE_RETRY_AFTER_FAIL_MS, failures
                    )
                )
                return@launch
            }
            settings.setUpdateCheckFailures(0)
            val info = (result as? UpdateChecker.Result.UpdateAvailable)?.info ?: return@launch
            if (!UpdateNotifyPolicy.shouldNotify(
                    latestVersion = info.latestVersion,
                    important = info.important,
                    notifiedVersion = settings.getUpdateNotifiedVersion(),
                    dismissedVersion = settings.getUpdateDismissedVersion()
                )
            ) {
                return@launch
            }
            // Verze se značí jako oznámená AŽ po úspěšném zobrazení - jinak by
            // se při zakázaných notifikacích „spotřebovala" naprázdno a po
            // jejich povolení by o ní uživatel už nikdy nedostal vědět.
            if (ChatNotifications.notifyUpdate(ctx, info.latestVersion, info.important)) {
                settings.setUpdateNotifiedVersion(info.latestVersion)
                DiagnosticsLog.log(TAG, "upozornění na novou verzi odesláno")
            }
        }
    }

    /** Verze appky jako čistě číselný řetězec (viz `versionName` v build.gradle). */
    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * Přepíše text trvalé notifikace, ale jen když se opravdu změnil. Zbytečné
     * přepisování budí systémové UI (a tím i CPU) při každém tiku hlídače.
     */
    private fun updateNotification() {
        val ctx = localizedContext(this, SettingsRepository(this).getLanguageTag())
        val text = ctx.getString(
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
                // Přepiš trvalou notifikaci HNED („Připojuji…" → „Připojeno").
                // Bez tohohle by text visel na „Připojuji…" až do prvního tiku
                // hlídače (i 2 minuty), i když je spojení dávno navázané.
                updateNotification()
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
        // Kolik pollů po sobě selhalo. Po pár selháních srovnej indikátor na
        // „nedostupné" - jinak by zůstal na „Připojeno" z posledního úspěchu,
        // i když server dávno spí.
        var failStreak = 0
        // POZOR: `coroutineContext.isActive` (tenhle job), NE `scope.isActive`
        // (rodič). Když watchdogTick zruší jednotlivou smyčku (smazaný / znovu
        // spárovaný kontakt), rodičovský scope žije dál - se `scope.isActive` by
        // se zrušená smyčka po návratu blokujícího `poll` (bez suspend bodu na
        // úspěšné větvi) roztočila znovu a pollovala starou schránku napořád.
        while (coroutineContext.isActive) {
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
                    // Celá historie nepřečtených (ne jen poslední) do MessagingStyle.
                    val unseen = ChatNotificationLogic.unseenIncoming(
                        repo.getMessages(contact.id), repo.getUnreadCount(contact.id)
                    )
                    // Jméno čti ČERSTVÉ z repozitáře, ne ze snapshotu smyčky:
                    // přejmenování kontaktu smyčku nerestartuje (fingerprint hlídá
                    // jen klíč a roli), takže snapshot by v notifikaci ukázal staré
                    // jméno až do restartu.
                    val currentName = ContactRepository(ctx).getContacts()
                        .find { it.id == contact.id }?.name ?: contact.name
                    ChatNotifications.notifyMessage(ctx, contact.id, currentName, unseen)
                }
                if (result.failed) {
                    DiagnosticsLog.warn(TAG, "poll selhal, zpomaluji (backoff $backoff ms)")
                    if (result.reachable) {
                        // Server ODPOVĚDĚL, ale uložení selhalo (plný disk apod.) -
                        // spojení je v pořádku, indikátor nechat „připojeno". Zpomal
                        // ale stejně, ať se nehameruje disk.
                        failStreak = 0
                        RelayStatus.markConnected()
                    } else if (++failStreak >= UNREACHABLE_AFTER_FAILS) {
                        // Server nedostupný (uspaný notebook) - srovnej indikátor.
                        RelayStatus.markUnreachable()
                    }
                    updateNotification()   // promptně srovnej i text trvalé notifikace
                    backoff = sleepBackoff(backoff)
                } else {
                    backoff = BACKOFF_START_MS
                    failStreak = 0
                    // Poll prošel = server je dosažitelný. Srovnej indikátor i
                    // trvalou notifikaci na „připojeno", i když se health ve
                    // warmUpu nestihl (jinak by uvázly na „připojuji").
                    RelayStatus.markConnected()
                    updateNotification()   // „Připojuji…" → „Připojeno" hned po prvním úspěchu
                    // Outbox: relay právě odpověděl (dosažitelný), tak zkus (znovu)
                    // doručit odchozí zprávy, které uvázly (FAILED / staré SENDING).
                    // JEN po úspěšném pollu - přes výpadek relaye by opakování u
                    // ratchetu zbytečně pálilo msgNo. Čerstvé SENDING nechá být.
                    RelaySync.flushOutbox(ctx, contact)
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
                if (++failStreak >= UNREACHABLE_AFTER_FAILS) RelayStatus.markUnreachable()
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
        val startGen = networkGeneration.value
        // Spí max podle backoffu, ale probudí se HNED, jakmile se vrátí síť -
        // jinak by po výpadku zpráva čekala celý (až 5min) backoff, i když je síť
        // dávno zpět. `first { … }` se vrátí null jen když vyprší timeout.
        val wokenByNetwork = withTimeoutOrNull(current.coerceAtMost(max)) {
            networkGeneration.first { it != startGen }
        } != null
        // Návrat sítě = rozumný důvod zkusit hned od začátku, ne dál zdvojnásobovat.
        return if (wokenByNetwork) BACKOFF_START_MS else (current * 2).coerceAtMost(BACKOFF_MAX_SCREEN_OFF_MS)
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
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
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

        /**
         * Jak často smí služba na pozadí zjišťovat novou verzi. Čtyřikrát denně -
         * jde o jeden krátký požadavek po okruhu, který už stejně stojí, takže
         * proti long-pollům je to v šumu. **Nezkracuj pod hodinu bez měření.**
         */
        private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

        /**
         * Za jak dlouho zkusit znovu, když kontrola selhala. Nedostupný relay
         * (uspaný notebook) je běžný stav a bylo by hloupé kvůli němu propálit
         * celý interval - ale opakovat každý tik hlídače by zase pálilo baterii.
         */
        private const val UPDATE_RETRY_AFTER_FAIL_MS = 30L * 60 * 1000

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

        /** Po kolika pollech za sebou označit spojení za nedostupné (indikátor). */
        private const val UNREACHABLE_AFTER_FAILS = 2
    }
}
