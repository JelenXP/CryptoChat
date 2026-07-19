package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.chat.ChatMessage
import com.jelenxp.cryptochat.chat.ChatRepository
import com.jelenxp.cryptochat.chat.RelaySync
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Konverzace s jedním kontaktem (chat přes relay). Historie je lokální a
 * šifrovaná ([ChatRepository]); nové zprávy chodí přes „slepou schránku".
 * Obrazovka pravidelně dotazuje server na nové zprávy, dokud je otevřená.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val repo = remember { ChatRepository(context) }
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val contact = remember(id) { viewModel.getContact(id) }
    var messages by remember { mutableStateOf(repo.getMessages(id)) }
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val relayUrl = settings.getRelayUrl()
    val hasKey = contact?.keyBase64 != null
    val canChat = hasKey && relayUrl.isNotBlank()

    val listState = rememberLazyListState()

    // Pravidelné vyzvedávání nových zpráv, dokud je obrazovka aktivní.
    LaunchedEffect(id, canChat) {
        if (contact == null || !canChat) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { RelaySync.poll(context, contact) }
            messages = repo.getMessages(id)
            delay(4000)
        }
    }

    // Automatické odrolování na poslední zprávu.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendCurrent() {
        val text = input.trim()
        if (text.isEmpty() || contact == null) return
        input = ""
        scope.launch {
            val msg = withContext(Dispatchers.IO) { RelaySync.enqueue(context, contact, text) }
            messages = repo.getMessages(id)
            withContext(Dispatchers.IO) { RelaySync.deliver(context, contact, msg) }
            messages = repo.getMessages(id)
        }
    }

    fun retry(message: ChatMessage) {
        if (contact == null) return
        scope.launch {
            repo.updateStatus(id, message.id, ChatMessage.Status.SENDING)
            messages = repo.getMessages(id)
            withContext(Dispatchers.IO) { RelaySync.deliver(context, contact, message) }
            messages = repo.getMessages(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (contact != null) {
                            ContactAvatar(name = contact.name, avatarPath = contact.avatarPath, size = 32.dp)
                        }
                        Text(contact?.name ?: "Konverzace", maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Více")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Detail a klíč") },
                            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                            onClick = { menuOpen = false; navController.navigate("user_detail/$id") }
                        )
                        DropdownMenuItem(
                            text = { Text("Server chatu") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { menuOpen = false; navController.navigate("relay_settings") }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Upozornění, když chat ještě nejde používat.
            if (!hasKey) {
                ChatNotice("Tento kontakt zatím nemá klíč. Nejdřív ho spáruj (Detail a klíč).")
            } else if (relayUrl.isBlank()) {
                ChatNotice("Není nastavený server chatu. Nastav ho v menu (⋮ → Server chatu).")
            }

            if (messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Zatím žádné zprávy.\nNapiš první.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageBubble(message = m, onRetry = { retry(m) })
                    }
                }
            }

            // Vstupní řádek.
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Zpráva…") },
                        enabled = canChat,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = { sendCurrent() },
                        enabled = canChat && input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Odeslat")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun MessageBubble(message: ChatMessage, onRetry: () -> Unit) {
    val outgoing = message.outgoing
    val bubbleColor = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bubbleColor)
                .then(if (message.status == ChatMessage.Status.FAILED) Modifier.clickable { onRetry() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    TIME_FORMAT.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
                if (outgoing) StatusGlyph(message.status, textColor)
            }
            if (message.status == ChatMessage.Status.FAILED) {
                Text(
                    "Nedoručeno — klepni pro nový pokus",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusGlyph(status: ChatMessage.Status, tint: Color) {
    when (status) {
        ChatMessage.Status.SENDING -> Icon(
            Icons.Default.Schedule, contentDescription = "Odesílám",
            modifier = Modifier.size(14.dp), tint = tint.copy(alpha = 0.7f)
        )
        ChatMessage.Status.SENT -> Text("✓", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f))
        ChatMessage.Status.FAILED -> Icon(
            Icons.Default.ErrorOutline, contentDescription = "Nedoručeno",
            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error
        )
        ChatMessage.Status.RECEIVED -> {}
    }
}
