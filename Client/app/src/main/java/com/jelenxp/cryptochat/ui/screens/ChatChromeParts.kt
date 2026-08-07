package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.jelenxp.cryptochat.R

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
