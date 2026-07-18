package com.jelenxp.cryptochat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContentTransitionScope
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
import com.jelenxp.cryptochat.data.AnimStyle
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.data.UpdateChecker
import com.jelenxp.cryptochat.ui.lock.LockScreen
import com.jelenxp.cryptochat.ui.screens.AcceptKeyScreen
import com.jelenxp.cryptochat.ui.screens.AddUserScreen
import com.jelenxp.cryptochat.ui.screens.BackupScreen
import com.jelenxp.cryptochat.ui.screens.ChangelogScreen
import com.jelenxp.cryptochat.ui.screens.CreateKeyScreen
import com.jelenxp.cryptochat.ui.screens.DesignScreen
import com.jelenxp.cryptochat.ui.screens.MainScreen
import com.jelenxp.cryptochat.ui.screens.ReceiveScreen
import com.jelenxp.cryptochat.ui.screens.RemoteCompleteScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
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
                        AppLockGate { StartupGate { CryptoChatApp(design) } }
                    }
                }
            }
        }
    }
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
        if (needsUnlock) {
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
        if (!settings.isUpdateCheckEnabled()) return@LaunchedEffect   // uživatel kontrolu vypnul
        val result = withContext(Dispatchers.IO) { UpdateChecker.check(currentVersion) }
            ?: return@LaunchedEffect
        val shouldShow = when {
            result.important -> true
            result.latestVersion != settings.getUpdateDismissedVersion() -> true
            else -> System.currentTimeMillis() - settings.getUpdateDismissedAt() >= UPDATE_REMIND_INTERVAL_MS
        }
        if (shouldShow) {
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
fun CryptoChatApp(design: DesignController) {
    val navController = rememberNavController()
    val viewModel: ContactsViewModel = viewModel()
    val style = design.animStyle
    val d = design.animSpeed.millis

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

        composable("settings") { SettingsScreen(navController) }

        composable("design") { DesignScreen(navController) }

        composable("backup") { BackupScreen(navController, viewModel) }

        composable(
            route = "create_key/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType })
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            CreateKeyScreen(name = name, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "accept_key/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType })
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            AcceptKeyScreen(name = name, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "remote_init/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType })
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            RemoteInitScreen(name = name, navController = navController, viewModel = viewModel)
        }

        composable(
            route = "remote_complete/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType })
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            RemoteCompleteScreen(name = name, navController = navController, viewModel = viewModel)
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
    }
}
