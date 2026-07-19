package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel

/**
 * Ověření otisku sdíleného klíče kontaktu. Obě strany vidí stejný krátký kód
 * (spočítaný z jejich klíče) - když se shoduje, mají opravdu tentýž klíč a
 * nikdo ho po výměně nepodvrhl. Porovnávat se má JINÝM kanálem, než kterým
 * proběhla výměna.
 */
@Composable
fun VerifyContactScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val contact = viewModel.getContact(id)
    val key = contact?.keyBase64

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

            InfoCard(text = stringResource(R.string.verify_hint))

            Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_done))
            }
        }
    }
}
