package com.example.cryptochat

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.cryptochat.data.SettingsRepository
import com.example.cryptochat.ui.lock.LockScreen
import com.example.cryptochat.ui.theme.CryptoChatTheme
import com.example.cryptochat.ui.screens.AcceptKeyScreen
import com.example.cryptochat.ui.screens.AddUserScreen
import com.example.cryptochat.ui.screens.CreateKeyScreen
import com.example.cryptochat.ui.screens.MainScreen
import com.example.cryptochat.ui.screens.ReceiveScreen
import com.example.cryptochat.ui.screens.RemoteCompleteScreen
import com.example.cryptochat.ui.screens.RemoteInitScreen
import com.example.cryptochat.ui.screens.SendScreen
import com.example.cryptochat.ui.screens.SettingsScreen
import com.example.cryptochat.ui.screens.UserDetailScreen
import com.example.cryptochat.viewmodel.ContactsViewModel

// AppCompatActivity (místo prostého ComponentActivity) je potřeba kvůli
// AppCompatDelegate.setApplicationLocales() - mechanismu pro přepínání
// jazyka appky nezávisle na systémovém jazyce (viz SettingsScreen.kt).
// Jetpack Compose (setContent { ... }) funguje na AppCompatActivity úplně
// stejně, protože dědí z ComponentActivity.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE: obsah appky (klíče, QR kódy, dešifrované zprávy) se
        // nedostane do screenshotů ani do náhledu v přepínači aplikací a
        // nedá se nahrát obrazovka. U appky na E2E šifrování je to žádoucí -
        // citlivá data nemají opustit obrazovku jinak než záměrným zkopírováním.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            CryptoChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppLockGate { CryptoChatApp() }
                }
            }
        }
    }
}

/** Doba, po kterou po odchodu do pozadí zůstane appka odemčená (grace period). */
private const val LOCK_GRACE_PERIOD_MS = 10_000L

/**
 * Pokud je v Nastavení zapnutý zámek appky, zobrazí [LockScreen] místo
 * skutečného obsahu. Zamkne se při studeném startu a při návratu z pozadí -
 * ale jen když byla appka na pozadí déle než [LOCK_GRACE_PERIOD_MS] (10 s).
 * Kratší přepnutí (přečtení notifikace, rychlý sken QR, přepnutí do jiné appky
 * a hned zpět) tak nevyžaduje opakované ověření. Pokud je zámek vypnutý,
 * žádnou překážku nepřidává.
 */
@Composable
private fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    // Při studeném startu se zamkne hned (žádná grace perioda).
    var needsUnlock by remember { mutableStateOf(settingsRepository.isAppLockEnabled()) }
    // Čas odchodu do pozadí; 0 = appka nebyla uspaná (nebo už je zamčená).
    var backgroundedAt by remember { mutableStateOf(0L) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // Zaznamenat čas odchodu, jen když je co uzamykat.
                    if (settingsRepository.isAppLockEnabled() && !needsUnlock) {
                        backgroundedAt = System.currentTimeMillis()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    // Po návratu zamknout jen při překročení grace periody.
                    if (settingsRepository.isAppLockEnabled() && !needsUnlock && backgroundedAt != 0L) {
                        val elapsed = System.currentTimeMillis() - backgroundedAt
                        if (elapsed >= LOCK_GRACE_PERIOD_MS) {
                            needsUnlock = true
                        }
                        backgroundedAt = 0L
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (needsUnlock) {
        LockScreen(onUnlocked = { needsUnlock = false })
    } else {
        content()
    }
}

@Composable
fun CryptoChatApp() {
    val navController = rememberNavController()
    val viewModel: ContactsViewModel = viewModel()

    NavHost(navController = navController, startDestination = "main") {

        composable("main") {
            MainScreen(navController, viewModel)
        }

        composable("add_user") {
            AddUserScreen(navController)
        }

        composable("settings") {
            SettingsScreen(navController)
        }

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
