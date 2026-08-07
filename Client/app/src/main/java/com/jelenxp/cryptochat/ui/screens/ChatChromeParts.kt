package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.components.IncognitoTextField

/**
 * Sdílené „chrome" prvky proudu zpráv (lišty a dialogy) — STEJNÉ v 1:1 [ChatScreen]
 * i skupinovém [GroupChatScreen]. Berou jen primitiva a callbacky, žádnou závislost
 * na modelu zprávy, takže rozhodnutí „co je vybráno / co jde smazat pro všechny"
 * zůstává u volajícího (kde se pravidla 1:1 a skupiny liší), ale VZHLED se edituje
 * na jednom místě.
 */

/**
 * Akční lišta výběrového režimu: zavřít, (odpovědět), (upravit), (kopírovat), smazat.
 * Akce se ukazují podle příznaků — odpověď/úprava dávají smysl jen u jedné zprávy,
 * kopírovat/smazat i u víc. Barevné pozadí ([secondaryContainer]) odlišuje výběrový
 * režim od běžné lišty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    canReply: Boolean,
    canEdit: Boolean,
    canCopy: Boolean,
    onClose: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.chat_selection_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_clear_selection))
            }
        },
        actions = {
            if (canReply) IconButton(onClick = onReply) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.chat_action_reply))
            }
            if (canEdit) IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.chat_action_edit))
            }
            if (canCopy) IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.chat_action_copy))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.chat_action_delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}

/**
 * Potvrzení mazání zpráv. Když jsou VŠECHNY vybrané moje ([canDeleteForEveryone]),
 * nabídne „smazat pro všechny" i „smazat u mě"; jinak jen „smazat u mě". Volající
 * si po volbě uklidí výběr sám (callbacky nic neruší).
 */
@Composable
internal fun DeleteMessagesDialog(
    canDeleteForEveryone: Boolean,
    onDeleteForEveryone: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_title)) },
        text = {
            Text(stringResource(if (canDeleteForEveryone) R.string.chat_delete_choose_body else R.string.chat_delete_body))
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canDeleteForEveryone) {
                    TextButton(onClick = onDeleteForEveryone) {
                        Text(stringResource(R.string.chat_delete_for_everyone))
                    }
                }
                TextButton(onClick = onDeleteForMe) {
                    Text(stringResource(R.string.chat_action_delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * Náhled zprávy, na kterou se odpovídá (nad vstupním polem) — barevný pruh vlevo,
 * autor a zkrácený text; křížek zruší. Volající předá už rozřešeného [author]
 * („Vy" / jméno protějšku či člena) a [summary] (u fotky/souboru zkrácený popis).
 */
@Composable
internal fun ReplyPreviewBar(author: String, summary: String, onCancel: () -> Unit) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_cancel_reply))
            }
        }
    }
}

/** Pruh nad vstupem v režimu úpravy: tužka, „Upravit zprávu" a náhled textu. */
@Composable
internal fun EditPreviewBar(summary: String, onCancel: () -> Unit) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.chat_editing_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_cancel_edit))
            }
        }
    }
}

/**
 * Vstupní řádek zprávy — STEJNÝ rám v 1:1 i skupině: (volitelná náběžná ikona pro
 * přílohu přes [leading]) + incognito textové pole + tlačítko odeslat. Obsah menu
 * příloh se u appek liší (1:1 foto/galerie/soubor, skupina zatím jen galerie), tak
 * ho dodá volající jako slot; rám, pole i odeslání jsou na jednom místě.
 */
@Composable
internal fun MessageComposerRow(
    input: String,
    onInputChange: (String) -> Unit,
    hint: String,
    inputEnabled: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (leading != null) leading()
            // Incognito vstup: platformní EditText s vypnutým učením klávesnice
            // (viz IncognitoTextField) - napsané zprávy se neukládají do slovníku.
            IncognitoTextField(
                value = input,
                onValueChange = onInputChange,
                hint = hint,
                enabled = inputEnabled,
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(onClick = onSend, enabled = sendEnabled) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.content_desc_send))
            }
        }
    }
}
