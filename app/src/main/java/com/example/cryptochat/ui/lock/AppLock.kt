package com.example.cryptochat.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.cryptochat.R

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Context v Compose bývá zabalený (ContextWrapper vrstvy) - tahle funkce
 * bezpečně najde skutečnou FragmentActivity, kterou BiometricPrompt
 * potřebuje. Pokud se nenajde (teoreticky možné na neobvyklých
 * embeddingech), vrátí null místo nebezpečného force-castu, který by appku
 * shodil s ClassCastException.
 */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** True, pokud zařízení má nastavený PIN/vzor/heslo nebo biometriku - jinak zámek nejde zapnout. */
fun isDeviceSecureAuthAvailable(context: Context): Boolean {
    return try {
        val manager = BiometricManager.from(context)
        manager.canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        false
    }
}

/**
 * Zobrazí systémový dialog pro ověření (biometrika nebo PIN/vzor/heslo
 * jako záloha). Všechny chybové stavy (chybějící FragmentActivity, chyba
 * BiometricPrompt API na konkrétním zařízení) končí zavoláním [onError]
 * místo pádu appky.
 */
fun requestUnlock(
    context: Context,
    title: String,
    noActivityErrorMessage: String,
    genericErrorMessage: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val activity = context.findFragmentActivity()
    if (activity == null) {
        onError(noActivityErrorMessage)
        return
    }
    try {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Jeden neúspěšný pokus (např. špatný otisk) - dialog
                    // zůstává otevřený a čeká na další pokus, nic neděláme.
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()
        prompt.authenticate(promptInfo)
    } catch (e: Exception) {
        onError(e.message ?: genericErrorMessage)
    }
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.biometric_prompt_title)
    val noActivityError = stringResource(R.string.error_cannot_show_auth)
    val genericError = stringResource(R.string.error_auth_failed_generic)

    fun tryUnlock() {
        requestUnlock(
            context = context,
            title = title,
            noActivityErrorMessage = noActivityError,
            genericErrorMessage = genericError,
            onSuccess = {
                error = null
                onUnlocked()
            },
            onError = { message -> error = message }
        )
    }

    LaunchedEffect(Unit) { tryUnlock() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.lock_screen_title), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.lock_screen_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(onClick = { tryUnlock() }) { Text(stringResource(R.string.btn_unlock)) }
        }
    }
}
