package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.RelayTelemetry
import com.jelenxp.cryptochat.chat.TorManager
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostika spojení: zdravotní metriky relaye (RTT, úspěšnost, selhání po sobě),
 * stav bootstrapu Toru a posledních pár řádků diagnostického logu. Slouží k
 * odlišení „server spí" od „Tor se pomalu staví" a k bezpečnému ladění intervalů.
 *
 * Všechno je **zredigované** - jen agregovaná čísla a typy chyb, log přes
 * [DiagnosticsLog.redact] (žádná .onion adresa, žádné ID schránky, žádný obsah).
 */
@Composable
fun DiagnosticsScreen(navController: NavController) {
    val context = LocalContext.current

    // Živě: přepočítávej co 1,5 s, dokud je obrazovka otevřená.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1500); tick++ }
    }
    val state = remember(tick) { RelayTelemetry.snapshot() }
    val bootstrap = remember(tick) { TorManager.bootstrapPercent }
    val bootstrapped = remember(tick) { TorManager.bootstrapped }
    val logLines = remember(tick) { DiagnosticsLog.dumpLines().takeLast(40).map { DiagnosticsLog.redact(it) } }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    fun ago(ts: Long): String =
        if (ts == 0L) "—" else timeFmt.format(Date(ts))
    fun ms(v: Long): String = if (v < 0) "—" else "$v ms"

    CryptoScaffold(
        title = stringResource(R.string.diag_title),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(text = stringResource(R.string.diag_info))

            Section(stringResource(R.string.diag_tor)) {
                Metric(stringResource(R.string.diag_bootstrap), if (bootstrapped) "100 % ✓" else "$bootstrap %")
            }

            val total = state.requests
            val rate = if (total == 0) "—" else "${state.successes * 100 / total} %"
            Section(stringResource(R.string.diag_relay)) {
                Metric(stringResource(R.string.diag_requests), total.toString())
                Metric(stringResource(R.string.diag_success_rate), rate)
                Metric(stringResource(R.string.diag_avg_rtt), ms(state.avgRttMs))
                Metric(stringResource(R.string.diag_last_rtt), ms(state.lastRttMs))
                Metric(stringResource(R.string.diag_consecutive_fail), state.consecutiveFailures.toString())
                Metric(stringResource(R.string.diag_last_error), state.lastErrorType ?: "—")
                Metric(stringResource(R.string.diag_last_success), ago(state.lastSuccessAt))
            }

            OutlinedButton(onClick = { RelayTelemetry.reset() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diag_reset))
            }

            Text(
                stringResource(R.string.diag_recent_log),
                style = MaterialTheme.typography.titleSmall
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (logLines.isEmpty()) stringResource(R.string.diag_none)
                    else logLines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
