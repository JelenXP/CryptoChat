package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.TorController
import com.jelenxp.cryptochat.data.ConnectionMode
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.components.SegmentedControl

/**
 * Nastavení serveru chatu. Záměrně minimalistické - jen dvě volby (výchozí /
 * vlastní) přes [SegmentedControl], stejně jako ostatní volby v appce. Spojení
 * se netestuje ručně: appka ho testuje sama po startu a stav ukazuje ikona
 * cloudu na hlavní obrazovce. Nastavení se ukládá průběžně (bez tlačítka Uložit
 * a bez toastů), jako ostatní přepínače v Nastavení.
 */
@Composable
fun RelaySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }

    var useCustom by remember { mutableStateOf(settings.isUsingCustomRelay()) }
    var customUrl by remember { mutableStateOf(settings.getRelayCustomUrl()) }
    var connMode by remember { mutableStateOf(settings.getConnectionMode()) }

    // Po přepnutí na .onion adresu (výchozí přes Tor i vlastní .onion) nastartuj
    // Tor, ať se stihne nabootovat, než se otestuje spojení. Čte efektivní adresu
    // z nastavení, takže respektuje i volbu Tor/Cloudflare u výchozího serveru
    // (u Cloudflare je adresa https → Tor se nespouští).
    fun ensureTorIfOnion() {
        if (settings.getRelayUrl().contains(".onion")) TorController.ensureStarted(context)
    }

    CryptoScaffold(
        title = stringResource(R.string.relay_title),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            InfoCard(
                icon = Icons.Default.CloudQueue,
                text = stringResource(R.string.relay_info)
            )

            SegmentedControl(
                options = listOf(
                    stringResource(R.string.relay_mode_default),
                    stringResource(R.string.relay_mode_custom)
                ),
                selectedIndex = if (useCustom) 1 else 0,
                onSelect = { index ->
                    useCustom = index == 1
                    settings.setUsingCustomRelay(useCustom)
                    ensureTorIfOnion()
                }
            )

            if (useCustom) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = {
                        customUrl = it
                        settings.setRelayCustomUrl(it)
                    },
                    label = { Text(stringResource(R.string.relay_custom_label)) },
                    placeholder = { Text("http://…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.relay_custom_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Způsob připojení k VÝCHOZÍMU serveru: Tor (soukromí) vs. Cloudflare
                // (rychlost). U vlastní adresy o transportu rozhoduje sama adresa,
                // takže se tenhle přepínač ukazuje jen tady.
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.conn_tor_title),
                        stringResource(R.string.conn_cf_title)
                    ),
                    selectedIndex = if (connMode == ConnectionMode.TOR) 0 else 1,
                    onSelect = { index ->
                        connMode = if (index == 0) ConnectionMode.TOR else ConnectionMode.CLOUDFLARE
                        settings.setConnectionMode(connMode)
                        ensureTorIfOnion()
                    }
                )
                Text(
                    text = stringResource(
                        if (connMode == ConnectionMode.TOR) R.string.relay_default_desc_tor
                        else R.string.relay_default_desc_cloudflare
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Záložní relaye (failover). Platí nad primárním (výchozím i vlastním);
            // když primární neodpoví, odeslání zkusí tyhle v pořadí a příjem je řídce
            // prohledává. Jeden na řádek.
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            var fallbacks by remember { mutableStateOf(settings.getRelayFallbackText()) }
            OutlinedTextField(
                value = fallbacks,
                onValueChange = {
                    fallbacks = it
                    settings.setRelayFallbackUrls(it)
                    ensureTorIfOnion()
                },
                label = { Text(stringResource(R.string.relay_fallback_label)) },
                placeholder = { Text("http://…\nhttp://…") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.relay_fallback_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
