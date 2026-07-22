package com.jelenxp.cryptochat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.MuteStore

private const val HOUR_MS = 3_600_000L

/**
 * Volby délky ztlumení. `durationMs == null` znamená [MuteStore.INDEFINITE]
 * („dokud to sám nezruším"). Sdílené mezi konverzací a detailem kontaktu.
 */
private val MUTE_OPTIONS: List<Pair<Int, Long?>> = listOf(
    R.string.mute_1h to HOUR_MS,
    R.string.mute_8h to 8 * HOUR_MS,
    R.string.mute_24h to 24 * HOUR_MS,
    R.string.mute_1week to 7 * 24 * HOUR_MS,
    R.string.mute_indefinite to null
)

/**
 * Dialog výběru délky ztlumení. [onPick] dostane čas (epoch ms), do kterého se
 * má ztlumit (nebo [MuteStore.INDEFINITE]); volající ho předá [MuteStore.mute].
 * Čas se počítá až v okamžiku klepnutí, ne v kompozici.
 */
@Composable
fun MuteDurationDialog(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mute_dialog_title)) },
        text = {
            Column {
                MUTE_OPTIONS.forEach { (labelRes, durationMs) ->
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val until = if (durationMs == null) MuteStore.INDEFINITE
                                else System.currentTimeMillis() + durationMs
                                onPick(until)
                            }
                            .padding(vertical = 14.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } }
    )
}
