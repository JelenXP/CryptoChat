package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.AppChangelog

/**
 * Celoobrazovkové „Novinky". Ukáže se dvěma způsoby:
 *  - jednorázově po aktualizaci (jen verze novější než naposledy viděná),
 *  - z Nastavení → Novinky (posledních pět verzí).
 * Text novinek je součástí appky ([AppChangelog]), takže funguje offline.
 *
 * @param entries verze k zobrazení, NEJNOVĚJŠÍ první. Prázdný seznam se nekreslí
 *   (volající ho nemá zobrazovat).
 */
@Composable
fun ChangelogScreen(entries: List<AppChangelog.Entry>, onDismiss: () -> Unit) {
    if (entries.isEmpty()) return
    // Jedna verze = konkrétní nadpis „Novinky ve verzi X"; víc verzí = obecné „Novinky".
    val title = if (entries.size == 1) {
        stringResource(R.string.changelog_title, entries.first().version)
    } else {
        stringResource(R.string.changelog_title_multi)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.ListAlt,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))
            // Každá verze ve vlastní kartě (text sám sebe uvozuje „Verze X — …").
            entries.forEachIndexed { index, entry ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(entry.bodyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_changelog_ok))
            }
        }
    }
}
