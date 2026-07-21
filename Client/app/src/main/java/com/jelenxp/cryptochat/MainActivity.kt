package com.jelenxp.cryptochat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jelenxp.cryptochat.chat.TorForegroundService
import com.jelenxp.cryptochat.data.AnimStyle
import com.jelenxp.cryptochat.data.FeatureFlags
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.data.UpdateChecker
import com.jelenxp.cryptochat.data.UpdateStartupPolicy
import com.jelenxp.cryptochat.ui.lock.LockScreen
import com.jelenxp.cryptochat.ui.onboarding.BackgroundOnboardingScreen
import com.jelenxp.cryptochat.ui.util.AppLocale
import com.jelenxp.cryptochat.ui.util.LocalizedApp
import com.jelenxp.cryptochat.ui.screens.AcceptKeyScreen
import com.jelenxp.cryptochat.ui.screens.AddUserScreen
import com.jelenxp.cryptochat.ui.screens.BackupScreen
import com.jelenxp.cryptochat.ui.screens.BugReportScreen
import com.jelenxp.cryptochat.ui.screens.ChangelogScreen
import com.jelenxp.cryptochat.ui.screens.ChatScreen
import com.jelenxp.cryptochat.ui.screens.CreateKeyScreen
import com.jelenxp.cryptochat.ui.screens.DesignScreen
import com.jelenxp.cryptochat.ui.screens.MainScreen
import com.jelenxp.cryptochat.ui.screens.PairInviteScreen
import com.jelenxp.cryptochat.ui.screens.PairJoinScreen
import com.jelenxp.cryptochat.ui.screens.RelaySettingsScreen
import com.jelenxp.cryptochat.ui.screens.ReceiveFileScreen
import com.jelenxp.cryptochat.ui.screens.ReceiveScreen
import com.jelenxp.cryptochat.ui.screens.ReceiveSharedScreen
import com.jelenxp.cryptochat.ui.screens.RemoteCompleteScreen
import com.jelenxp.cryptochat.ui.screens.SendFileScreen
import com.jelenxp.cryptochat.ui.screens.RemoteInitScreen
import com.jelenxp.cryptochat.ui.screens.SendScreen
import com.jelenxp.cryptochat.ui.screens.SettingsScreen
import com.jelenxp.cryptochat.ui.screens.UpdateScreen
import com.jelenxp.cryptochat.ui.screens.UserDetailScreen
import com.jelenxp.cryptochat.ui.screens.VerifyContactScreen
import com.jelenxp.cryptochat.ui.theme.CryptoChatTheme
import com.jelenxp.cryptochat.ui.theme.DesignController
import com.jelenxp.cryptochat.ui.theme.LocalDesign
import com.jelenxp.cryptochat.ui.theme.LocalUiSpacing
import com.jelenxp.cryptochat.ui.theme.spacing
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // Text sdílený do appky přes „Sdílet do CryptoChat" (ACTION_SEND). Čte se
    // z intentu při startu i za běhu (onNewIntent); composable ho sleduje.
    private val sharedTextState = mutableStateOf<String?>(null)

    // Kontakt, jehož konverzaci má appka otevřít (klepnutí na notifikaci zprávy).
    private val openChatIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Blokování screenshotů/náhledu v přepínači aplikací - drží klíče a
        // dešifrované zprávy mimo snímky obrazovky.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        sharedTextState.value = extractSharedText(intent)
        openChatIdState.value = intent?.getStringExtra(EXTRA_OPEN_CHAT)

        // Jednorázová migrace jazyka ze STARÉHO AppCompat úložiště do našeho stavu
        // (jazyk teď přepínáme in-place, viz AppLocale - bez recreate, bez poblikání
        // a bez znovuvyžádání zámku). Kdo měl jazyk vynucený, přenese se; AppCompat
        // se pak vyčistí, aby base context řídil dál JEN náš přepínač. To je jediný
        // (jednorázový) recreate při první aktualizaci; další změny jazyka už ne.
        run {
            val settings = SettingsRepository(this)
            if (!settings.isLanguageMigrated()) {
                val existing = AppCompatDelegate.getApplicationLocales()
                settings.setLanguageTag(if (existing.isEmpty) "" else existing[0]?.language.orEmpty())
                settings.setLanguageMigrated(true)
                if (!existing.isEmpty) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                }
            }
            AppLocale.tag = settings.getLanguageTag()
        }

        setContent {
            val context = LocalContext.current
            val settingsRepository = remember { SettingsRepository(context) }
            val design = remember { DesignController(settingsRepository) }

            CryptoChatTheme(controller = design) {
                CompositionLocalProvider(
                    LocalDesign provides design,
                    LocalUiSpacing provides design.density.spacing()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // Při prvním spuštění průvodce povoleními pro běh na pozadí.
                        var onboarded by remember { mutableStateOf(settingsRepository.isOnboardingDone()) }
                        if (!onboarded) {
                            BackgroundOnboardingScreen(onFinished = {
                                settingsRepository.setOnboardingDone(true)
                                onboarded = true
                            })
                        } else {
                            // LocalizedApp prosadí zvolený jazyk (AppLocale) obalením
                            // Contextu - změna jazyka je pak jen rekompozice, žádný recreate.
                            LocalizedApp {
                                AppLockGate {
                                    StartupGate {
                                        CryptoChatApp(
                                            design = design,
                                            sharedText = sharedTextState.value,
                                            onSharedTextConsumed = { sharedTextState.value = null },
                                            openChatId = openChatIdState.value,
                                            onOpenChatConsumed = {
                                                openChatIdState.value = null
                                                // Vymaž extra i z intentu Aktivity. Jinak by ho
                                                // onCreate po recreate() (rotace, obnova procesu)
                                                // přečetl ZNOVU a po „zpět" by uživatele vrátil do
                                                // konverzace, ze které odešel.
                                                intent?.removeExtra(EXTRA_OPEN_CHAT)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedText(intent)?.let { sharedTextState.value = it }
        intent.getStringExtra(EXTRA_OPEN_CHAT)?.let { openChatIdState.value = it }
    }

    override fun onStart() {
        super.onStart()
        // Spusť/obnov foreground service (drží Tor teplý + zprávy na pozadí).
        // Z popředí (viditelná aktivita) to Android 12+ povolí; START_STICKY ho
        // pak drží i po odchodu appky na pozadí.
        try {
            if (SettingsRepository(this).getRelayUrl().isNotBlank()) {
                ContextCompat.startForegroundService(
                    this, Intent(this, TorForegroundService::class.java)
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Nepodařilo se spustit chat service", e)
        }
        // Záchranná síť: kdo v úvodním průvodci klikl „Přeskočit", nikdy o povolení
        // notifikací požádán nebyl - a bez něj mu na Androidu 13+ nepřijde ani jedna
        // (messenger by tak tiše nefungoval). Systém dialog ukáže jen jednou, pak
        // je volání neškodné no-op.
        try {
            maybeRequestNotificationPermission()
        } catch (e: Exception) {
            Log.w("MainActivity", "Žádost o povolení notifikací selhala", e)
        }
    }

    /** Na Androidu 13+ si vyžádá povolení notifikací (jinak by je systém skryl). */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat_id"
        private const val REQ_NOTIFICATIONS = 101
    }
}

/** Vytáhne z intentu text sdílený z jiné appky (ACTION_SEND text/plain), nebo null. */
private fun extractSharedText(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
    return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
}

private const val LOCK_GRACE_PERIOD_MS = 10_000L

@Composable
private fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    var needsUnlock by remember { mutableStateOf(settingsRepository.isAppLockEnabled()) }
    var backgroundedAt by remember { mutableStateOf(0L) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (settingsRepository.isAppLockEnabled() && !needsUnlock) {
                        backgroundedAt = System.currentTimeMillis()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (settingsRepository.isAppLockEnabled() && !needsUnlock && backgroundedAt != 0L) {
                        val elapsed = System.currentTimeMillis() - backgroundedAt
                        if (elapsed >= LOCK_GRACE_PERIOD_MS) needsUnlock = true
                        backgroundedAt = 0L
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Obsah appky je VŽDY složený; zámek se jen překryje přes něj (overlay).
    // Díky tomu se při zamčení nezahodí NavHost ani stav obrazovek - po
    // odemčení uživatel skončí přesně tam, kde byl (důležité u rozdělané
    // výměny klíče na dálku, kdy na pár sekund odejde z appky).
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        // Zámek se plynule objeví/zmizí (fade) - po odemčení „odtaje", ne tvrdý skok.
        AnimatedVisibility(
            visible = needsUnlock,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(220))
        ) {
            // Neprůhledný celoobrazovkový překryv, který navíc pohltí doteky,
            // aby nešlo omylem ovládat skrytý obsah pod zámkem.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            ) {
                LockScreen(onUnlocked = { needsUnlock = false })
            }
        }
    }
}

/** Jak dlouho po „Později" se stejná verze znovu nepřipomíná (týden). */
private const val UPDATE_REMIND_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Startovní upozornění (po odemčení). Nejdřív jednorázové „Novinky" po
 * aktualizaci ([ChangelogScreen]), pak kontrola nové verze na GitHub Releases
 * ([UpdateScreen]). Vždy jen jedno okno naráz.
 *
 * Update se ukáže, pokud: novější verze je důležitá (vždy), nebo je nejnovější
 * verze jiná než ta naposledy odložená ("při dalším updatu"), nebo od odložení
 * uplynul týden. Selhání kontroly (offline) nic neukáže.
 */
@Composable
private fun StartupGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val currentVersion = remember { currentVersionName(context) }
    val currentVersionCode = remember { currentVersionCode(context) }

    // Novinky: dřív viděná verze byla nižší než aktuální = právě se aktualizovalo.
    var showChangelog by remember {
        mutableStateOf(settings.getLastSeenVersionCode() in 1 until currentVersionCode)
    }
    LaunchedEffect(Unit) { settings.setLastSeenVersionCode(currentVersionCode) }

    // Kontrola nové verze na pozadí.
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var updateEligible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Feature prozatím vypnutá jedním vypínačem (kód zůstává; míří na repo
        // původní offline appky). Až budou vlastní veřejné releases, zapni ji.
        if (!FeatureFlags.UPDATE_CHECK_ENABLED) return@LaunchedEffect
        if (!settings.isUpdateCheckEnabled()) return@LaunchedEffect   // uživatel kontrolu vypnul
        val result = withContext(Dispatchers.IO) { UpdateChecker.check(currentVersion) }
            ?: return@LaunchedEffect
        // Rozhodnutí (pozastavení, zavření, připomínací interval) je v čisté
        // funkci vedle obrazovky - stejná pravidla jako UpdateNotifyPolicy na
        // pozadí, ale testovatelná a bez duplicitní logiky v composable.
        val decision = UpdateStartupPolicy.decide(
            important = result.important,
            latestVersion = result.latestVersion,
            dismissedVersion = settings.getUpdateDismissedVersion(),
            dismissedAt = settings.getUpdateDismissedAt(),
            snoozeUntil = settings.getUpdateSnoozeUntil(),
            snoozeImportantShown = settings.getUpdateSnoozeImportantShown(),
            remindIntervalMs = UPDATE_REMIND_INTERVAL_MS,
            now = System.currentTimeMillis()
        )
        if (decision.show) {
            if (decision.markSnoozeImportantShown) {
                settings.setUpdateSnoozeImportantShown(result.latestVersion)
            }
            updateInfo = result
            updateEligible = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        val info = updateInfo
        when {
            // Novinky mají přednost; teprve po jejich zavření se případně ukáže update.
            showChangelog -> BlockingOverlay {
                ChangelogScreen(version = currentVersion, onDismiss = { showChangelog = false })
            }
            updateEligible && info != null -> BlockingOverlay {
                UpdateScreen(
                    currentVersion = currentVersion,
                    latestVersion = info.latestVersion,
                    important = info.important,
                    onGetLatest = {
                        openUrl(context, info.latestUrl)
                        updateEligible = false
                    },
                    onLater = {
                        // Důležitou verzi nejde odložit - ukáže se zas po startu.
                        if (!info.important) {
                            settings.setUpdateDismissed(info.latestVersion, System.currentTimeMillis())
                        }
                        updateEligible = false
                    }
                )
            }
        }
    }
}

/** Celoobrazovkový překryv, který pohltí doteky (nejde ovládat obsah pod ním). */
@Composable
private fun BlockingOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) { content() }
}

/** Verze nainstalované appky (versionName, např. „2.3"). */
private fun currentVersionName(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

/** versionCode nainstalované appky (0 při chybě). */
private fun currentVersionCode(context: Context): Int =
    try {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        ).toInt()
    } catch (e: Exception) {
        0
    }

/** Otevře URL v prohlížeči; při chybě tiše nic (appka nespadne). */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // Bez prohlížeče apod. - ignorovat.
    }
}

// --- Přechody mezi obrazovkami řízené volbou v Nastavení → Vzhled ---

private fun enterFor(style: AnimStyle, d: Int): EnterTransition = when (style) {
    AnimStyle.SLIDE -> slideInHorizontally(tween(d)) { it / 6 } + fadeIn(tween(d))
    AnimStyle.FADE -> fadeIn(tween(d))
    AnimStyle.SCALE -> scaleIn(tween(d), initialScale = 0.965f) + fadeIn(tween(d))
    AnimStyle.NONE -> EnterTransition.None
}

private fun exitFor(style: AnimStyle, d: Int): ExitTransition = when (style) {
    AnimStyle.SLIDE -> slideOutHorizontally(tween(d)) { -it / 8 } + fadeOut(tween(d))
    AnimStyle.FADE -> fadeOut(tween(d))
    AnimStyle.SCALE -> scaleOut(tween(d), targetScale = 0.99f) + fadeOut(tween(d))
    AnimStyle.NONE -> ExitTransition.None
}

private fun popEnterFor(style: AnimStyle, d: Int): EnterTransition = when (style) {
    AnimStyle.SLIDE -> slideInHorizontally(tween(d)) { -it / 8 } + fadeIn(tween(d))
    AnimStyle.FADE -> fadeIn(tween(d))
    AnimStyle.SCALE -> scaleIn(tween(d), initialScale = 0.99f) + fadeIn(tween(d))
    AnimStyle.NONE -> EnterTransition.None
}

private fun popExitFor(style: AnimStyle, d: Int): ExitTransition = when (style) {
    AnimStyle.SLIDE -> slideOutHorizontally(tween(d)) { it / 6 } + fadeOut(tween(d))
    AnimStyle.FADE -> fadeOut(tween(d))
    AnimStyle.SCALE -> scaleOut(tween(d), targetScale = 0.965f) + fadeOut(tween(d))
    AnimStyle.NONE -> ExitTransition.None
}

@Composable
fun CryptoChatApp(
    design: DesignController,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    openChatId: String? = null,
    onOpenChatConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val viewModel: ContactsViewModel = viewModel()
    val style = design.animStyle
    val d = design.animSpeed.millis

    // Přišel text „Sdílet do CryptoChat"? Ulož ho a otevři obrazovku dešifrování.
    var pendingSharedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedText) {
        val text = sharedText ?: return@LaunchedEffect
        pendingSharedText = text
        onSharedTextConsumed()
        navController.navigate("receive_shared")
    }

    // Klepnutí na notifikaci nové zprávy → otevři přímo tu konverzaci.
    LaunchedEffect(openChatId) {
        val id = openChatId ?: return@LaunchedEffect
        onOpenChatConsumed()
        // `popUpTo("main")` sloupne případný jiný otevřený chat: kdyby se nová
        // konverzace jen naskládala NA něj, „zpět" by nevedlo na seznam, ale do
        // toho druhého (náhodně otevřeného) kontaktu. `launchSingleTop` pak
        // zabrání zdvojení při dvojím klepnutí na tutéž notifikaci.
        navController.navigate("chat/$id") {
            popUpTo("main")
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = "main",
        enterTransition = { enterFor(style, d) },
        exitTransition = { exitFor(style, d) },
        popEnterTransition = { popEnterFor(style, d) },
        popExitTransition = { popExitFor(style, d) }
    ) {
        composable("main") { MainScreen(navController, viewModel) }

        composable("add_user") { AddUserScreen(navController) }

        composable(
            route = "rekey/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            val existing = viewModel.getContact(id)
            AddUserScreen(
                navController = navController,
                contactId = id,
                presetName = existing?.name ?: ""
            )
        }

        composable("settings") { SettingsScreen(navController) }

        composable("design") { DesignScreen(navController) }

        composable("relay_settings") { RelaySettingsScreen(navController) }

        composable("bug_report") { BugReportScreen(navController) }

        composable(
            route = "chat/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            ChatScreen(id = id, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "pair_invite/{name}?contactId={contactId}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            val contactId = backStackEntry.arguments?.getString("contactId")?.let { Uri.decode(it) }
            PairInviteScreen(name = name, navController = navController, viewModel = viewModel, contactId = contactId)
        }

        composable(
            route = "pair_join/{name}?contactId={contactId}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            val contactId = backStackEntry.arguments?.getString("contactId")?.let { Uri.decode(it) }
            PairJoinScreen(name = name, navController = navController, viewModel = viewModel, contactId = contactId)
        }

        composable("backup") { BackupScreen(navController, viewModel) }

        composable("receive_shared") {
            ReceiveSharedScreen(
                cipherText = pendingSharedText ?: "",
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("changelog") {
            ChangelogScreen(
                version = currentVersionName(LocalContext.current),
                onDismiss = { navController.popBackStack() }
            )
        }

        composable(
            route = "create_key/{name}?contactId={contactId}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            val contactId = backStackEntry.arguments?.getString("contactId")?.let { Uri.decode(it) }
            CreateKeyScreen(name = name, navController = navController, viewModel = viewModel, contactId = contactId)
        }

        composable(
            route = "accept_key/{name}?contactId={contactId}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            val contactId = backStackEntry.arguments?.getString("contactId")?.let { Uri.decode(it) }
            AcceptKeyScreen(name = name, navController = navController, viewModel = viewModel, contactId = contactId)
        }

        composable(
            route = "remote_init/{name}?contactId={contactId}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            val contactId = backStackEntry.arguments?.getString("contactId")?.let { Uri.decode(it) }
            RemoteInitScreen(name = name, navController = navController, viewModel = viewModel, contactId = contactId)
        }

        composable(
            route = "remote_complete/{name}?contactId={contactId}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            val contactId = backStackEntry.arguments?.getString("contactId")?.let { Uri.decode(it) }
            RemoteCompleteScreen(name = name, navController = navController, viewModel = viewModel, contactId = contactId)
        }

        composable(
            route = "user_detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            UserDetailScreen(id = id, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "verify/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            VerifyContactScreen(id = id, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "send/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            SendScreen(id = id, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "receive/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            ReceiveScreen(id = id, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "send_file/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            SendFileScreen(id = id, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "receive_file/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            ReceiveFileScreen(id = id, navController = navController, viewModel = viewModel)
        }
    }
}
