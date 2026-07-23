package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.data.TrustState
import com.jelenxp.cryptochat.data.TrustStore
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ověření otisku sdíleného klíče kontaktu. Obě strany vidí stejný krátký kód
 * (spočítaný z jejich klíče) - když se shoduje, mají opravdu tentýž klíč a
 * nikdo ho po výměně nepodvrhl. Porovnávat se má JINÝM kanálem, než kterým
 * proběhla výměna.
 *
 * Po potvrzení se otisk uloží ([TrustStore]) a když se pak změní (podvržený /
 * obnovený klíč), appka to pozná ([TrustState]) a upozorní znovu ověřit.
 */
@Composable
fun VerifyContactScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val contact = viewModel.getContact(id)
    val key = contact?.keyBase64
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trustStore = remember { TrustStore(context) }

    // Naposledy ověřený otisk (mimo main - dešifruje se Keystorem). `saved` se
    // po uložení přepne lokálně, ať se stav hned překreslí bez dalšího čtení.
    var storedFingerprint by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        storedFingerprint = withContext(Dispatchers.IO) { trustStore.verifiedFingerprint(id) }
    }

    CryptoScaffold(
        title = stringResource(R.string.verify_title),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (key == null) {
                InfoCard(text = stringResource(R.string.user_no_key))
                return@Column
            }

            val fingerprint = remember(key) { CryptoManager.fingerprint(key) }
            val level = TrustState.evaluate(storedFingerprint, fingerprint)

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(36.dp))
                }
            }

            Text(
                text = stringResource(R.string.verify_intro, contact.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Zvýrazněný otisk - velké monospace písmo, ať se dobře čte nahlas.
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = fingerprint,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 12.dp)
                )
            }

            // Stav důvěry: ověřeno (zeleně), nebo změněný otisk (varování).
            when (level) {
                TrustState.Level.VERIFIED -> StatusBanner(
                    icon = Icons.Default.CheckCircle,
                    text = stringResource(R.string.verify_status_verified),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer
                )
                TrustState.Level.CHANGED -> StatusBanner(
                    icon = Icons.Default.Warning,
                    text = stringResource(R.string.verify_status_changed),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer
                )
                TrustState.Level.UNVERIFIED -> {}
            }

            InfoCard(text = stringResource(R.string.verify_hint))

            // Označit jako ověřené (uloží aktuální otisk). Když už sedí, tlačítko
            // není potřeba - stačí zavřít.
            if (level != TrustState.Level.VERIFIED) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { trustStore.setVerified(id, fingerprint) }
                            storedFingerprint = fingerprint
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.verify_mark_verified))
                }
            }
            OutlinedButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_done))
            }
        }
    }
}

/** Malý stavový banner (ověřeno / změněno) pod otiskem. */
@Composable
private fun StatusBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color
) {
    Surface(shape = MaterialTheme.shapes.medium, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
