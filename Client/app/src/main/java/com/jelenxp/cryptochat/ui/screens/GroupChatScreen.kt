package com.jelenxp.cryptochat.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.ActiveChat
import com.jelenxp.cryptochat.chat.GroupChatMessage
import com.jelenxp.cryptochat.chat.GroupChatRepository
import com.jelenxp.cryptochat.chat.GroupSync
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.IncognitoTextField
import com.jelenxp.cryptochat.ui.theme.LocalDesign
import com.jelenxp.cryptochat.viewmodel.GroupsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Skupinová konverzace — tenký sourozenec [ChatScreen]. Záměrně vypadá STEJNĚ jako
 * 1:1 (stejné barvy bublin z [LocalDesign], stejné textové fajfky ✓/✓✓ v `onBubble`),
 * jen přidává jméno odesílatele nad příchozí bublinou. Příjem obstarává služba na
 * pozadí (píše do [GroupChatRepository]); obrazovka jen periodicky přenačítá historii
 * a při otevření potlačí notifikaci ([ActiveChat.currentGroupId]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(groupId: String, navController: NavController, groupsViewModel: GroupsViewModel) {
    val ctx = LocalContext.current
    val groups by groupsViewModel.groups.collectAsState()
    val group = groups.find { it.groupId == groupId }

    // Skupina zmizela (opuštěna/smazána z jiného místa) → zpět na seznam.
    if (group == null) {
        androidx.compose.runtime.LaunchedEffect(groupId) { navController.popBackStack() }
        return
    }

    var messages by remember { mutableStateOf(emptyList<GroupChatMessage>()) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Jména odesílatelů z rosteru (memberId → zobrazované jméno).
    val names = remember(group.rosterBytesBase64) { group.members().associate { it.memberIdHex to it.displayName } }

    // Na popředí: potlač notifikaci téhle skupiny a přenačítej historii (příjem dělá služba).
    androidx.compose.runtime.LaunchedEffect(groupId) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ActiveChat.currentGroupId = groupId
            try {
                while (true) {
                    messages = withContext(Dispatchers.IO) { GroupChatRepository(ctx).getMessages(groupId) }
                    kotlinx.coroutines.delay(1500)
                }
            } finally {
                ActiveChat.currentGroupId = null
            }
        }
    }

    // Auto-scroll na konec při přírůstku zpráv.
    androidx.compose.runtime.LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendCurrent() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        scope.launch {
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) {
                GroupSync.sendText(ctx, group, text, url, System.currentTimeMillis())
                messages = GroupChatRepository(ctx).getMessages(groupId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { navController.navigate("group_detail/$groupId") }
                    ) {
                        ContactAvatar(name = group.name, avatarPath = group.avatarPath, size = 32.dp)
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.group_member_count, group.members().size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IncognitoTextField(
                        value = input,
                        onValueChange = { input = it },
                        hint = stringResource(R.string.chat_input_hint),
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = { sendCurrent() },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.content_desc_send))
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(messages, key = { it.msgIdHex }) { m ->
                GroupMessageBubble(m, senderName = m.senderMemberIdHex?.let { names[it] })
            }
        }
    }
}

/**
 * Bublina skupinové zprávy. Vizuálně shodná s 1:1 [MessageBubble] (viz ChatScreen):
 * moje = `accent.bubble`/`accent.onBubble`, cizí = `surfaceVariant`/`onSurfaceVariant`;
 * fajfky ✓/✓✓ TEXTOVĚ v `textColor`@70 % (NE accent). Navíc jméno odesílatele nad
 * příchozí bublinou ([senderName] = null u odchozích).
 */
@Composable
private fun GroupMessageBubble(message: GroupChatMessage, senderName: String?) {
    val outgoing = message.outgoing
    val accent = LocalDesign.current.accent
    val bubbleColor = if (outgoing) accent.bubble else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (outgoing) accent.onBubble else MaterialTheme.colorScheme.onSurfaceVariant
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
            if (!outgoing && senderName != null) {
                Text(
                    senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, bottom = 1.dp)
                )
            }
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.sizeIn(maxWidth = 300.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (message.kind == GroupChatMessage.Kind.IMAGE) {
                        GroupImage(message.mediaPath)
                    } else {
                        Text(message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Text(
                            timeFmt.format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                        if (outgoing) {
                            Text(
                                statusGlyph(message.status),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == GroupChatMessage.Status.FAILED)
                                    MaterialTheme.colorScheme.error else textColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Textová fajfka jako v 1:1 `StatusGlyph`: SENT „✓", DELIVERED „✓✓", FAILED „!". */
private fun statusGlyph(status: GroupChatMessage.Status): String = when (status) {
    GroupChatMessage.Status.SENDING -> "…"
    GroupChatMessage.Status.SENT -> "✓"
    GroupChatMessage.Status.DELIVERED -> "✓✓"
    GroupChatMessage.Status.FAILED -> "!"
}

/** Jednoduché zobrazení přijaté fotky (dekódování mimo hlavní vlákno). */
@Composable
private fun GroupImage(path: String?) {
    val ctx = LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.sizeIn(maxWidth = 260.dp, maxHeight = 360.dp))
    } else {
        Box(modifier = Modifier.width(160.dp).padding(4.dp)) {
            Text(stringResource(R.string.notif_photo), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
