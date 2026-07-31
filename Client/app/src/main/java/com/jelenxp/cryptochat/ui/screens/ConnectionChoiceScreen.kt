package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.ConnectionMode
import com.jelenxp.cryptochat.data.SettingsRepository

/**
 * Úvodní volba způsobu připojení k serveru: přes Tor (doporučeno, nejvyšší
 * soukromí) nebo napřímo přes Cloudflare (rychlé, šetří baterii, ale Cloudflare
 * i server vidí IP). Ukazuje se po startu appky, dokud ji uživatel nepotvrdí
 * (viz StartupGate v MainActivity). Celoobrazovkový překryv ve stylu
 * [ChangelogScreen].
 *
 * Důležité: zprávy jsou šifrované end-to-end u OBOU voleb - volba je jen
 * o metadatech (IP, „že appku používáš"), ne o obsahu zpráv. Tomu odpovídá
 * i úvodní text.
 *
 * @param onDone zavolá se po potvrzení volby; volající skryje překryv. Mód se
 *   uloží do [SettingsRepository]; efektivní adresa serveru se pak sama přepne
 *   ([SettingsRepository.getRelayUrl]) a zbytek appky se řídí `.onion` v adrese
 *   (u Cloudflare módu je adresa https, takže se Tor nikde nespustí).
 */
@Composable
fun ConnectionChoiceScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var selected by remember { mutableStateOf(settings.getConnectionMode()) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // Min. výška = výška obrazovky, ať krátký obsah zůstane vycentrovaný,
                    // ale při přerůstání normálně scrolluje (stejně jako ChangelogScreen).
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.conn_choice_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.conn_choice_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))
                OptionCard(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.conn_tor_title),
                    recommended = true,
                    subtitle = stringResource(R.string.conn_tor_subtitle),
                    pro = stringResource(R.string.conn_tor_pro),
                    con = stringResource(R.string.conn_tor_con),
                    selected = selected == ConnectionMode.TOR,
                    onClick = { selected = ConnectionMode.TOR }
                )
                Spacer(Modifier.height(12.dp))
                OptionCard(
                    icon = Icons.Filled.Bolt,
                    title = stringResource(R.string.conn_cf_title),
                    recommended = false,
                    subtitle = stringResource(R.string.conn_cf_subtitle),
                    pro = stringResource(R.string.conn_cf_pro),
                    con = stringResource(R.string.conn_cf_con),
                    selected = selected == ConnectionMode.CLOUDFLARE,
                    onClick = { selected = ConnectionMode.CLOUDFLARE }
                )

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        settings.setConnectionMode(selected)
                        settings.setConnectionChoiceMade(true)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.conn_choice_continue))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.conn_choice_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Jedna volitelná karta (Tor / Cloudflare): ikona, název (+ volitelný odznak
 * „Doporučeno"), podtitulek, jeden klad a jeden zápor. Vybraná karta má výrazný
 * (2 dp) obrys v accentu a zapnuté rádiové tlačítko. Celá karta je klikací.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionCard(
    icon: ImageVector,
    title: String,
    recommended: Boolean,
    subtitle: String,
    pro: String,
    con: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = border,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (recommended) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.conn_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RadioButton(selected = selected, onClick = null)
            }
            Spacer(Modifier.height(10.dp))
            ProConRow(
                icon = Icons.Filled.Check,
                text = pro,
                tint = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            ProConRow(
                icon = Icons.Filled.Remove,
                text = con,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Řádek klad/zápor: barevná ikona vlevo + text. */
@Composable
private fun ProConRow(icon: ImageVector, text: String, tint: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}
