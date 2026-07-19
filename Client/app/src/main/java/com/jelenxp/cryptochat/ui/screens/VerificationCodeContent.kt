package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.components.InfoCard

/**
 * Poslední krok výměny klíče na dálku: obě strany vidí krátký kód odvozený
 * ze sdíleného tajemství a mají si ho navzájem potvrdit JINÝM kanálem, než
 * kterým proběhla samotná výměna (nahlas po telefonu, SMS, ...). Pokud
 * sedí, žádný útočník výměnu "uprostřed" nepodvrhl. Kontakt se uloží až
 * po tomto potvrzení.
 */
@Composable
fun VerificationCodeContent(
    verificationCode: String,
    contactName: String,
    onConfirmed: () -> Unit,
    onCancel: () -> Unit
) {
    val code = verificationCode

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.VerifiedUser,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.verification_code_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            stringResource(R.string.verification_code_instructions, contactName),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Zvýrazněný kód v kartě - velké monospace písmo, ať se dobře čte nahlas.
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = code,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            )
        }

        InfoCard(
            text = stringResource(R.string.verification_code_warning),
            icon = Icons.Default.Warning,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )

        Button(onClick = onConfirmed, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_codes_match))
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_codes_dont_match))
        }
    }
}
