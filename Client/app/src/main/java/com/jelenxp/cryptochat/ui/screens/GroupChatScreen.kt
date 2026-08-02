package com.jelenxp.cryptochat.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.ActiveChat
import com.jelenxp.cryptochat.chat.ChatMediaStore
import com.jelenxp.cryptochat.chat.ChatNotifications
import com.jelenxp.cryptochat.chat.GroupChatMessage
import com.jelenxp.cryptochat.chat.GroupChatRepository
import com.jelenxp.cryptochat.chat.GroupMediaStore
import com.jelenxp.cryptochat.chat.GroupMemberNames
import com.jelenxp.cryptochat.chat.GroupStore
import com.jelenxp.cryptochat.chat.GroupSync
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.AboveAnchorPosition
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.IncognitoTextField
import com.jelenxp.cryptochat.ui.components.ReactionPicker
import com.jelenxp.cryptochat.ui.emoji.EmojiPickerSheet
import com.jelenxp.cryptochat.ui.theme.LocalDesign
import com.jelenxp.cryptochat.viewmodel.GroupsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GROUP_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Paleta rychlých reakcí (dlouhý stisk bubliny) + výchozí pro dvojklik. */
private val GROUP_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private const val GROUP_DEFAULT_REACTION = "👍"

/**
 * Skupinová konverzace — záměrně STEJNÁ jako 1:1 [ChatScreen]: stejné bubliny
 * (asymetrický roh, `accent.bubble`/`onBubble`, textové fajfky), oddělovače dnů,
 * prázdný stav, zprávy zarovnané dolů, vstupní lišta s přílohou fotky. Navíc jen
 * jméno odesílatele nad příchozí bublinou (LOKÁLNÍ jméno kontaktu má přednost) a
 * systémové řádky „X se připojil". Příjem obstarává služba; obrazovka přenačítá.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(groupId: String, navController: NavController, groupsViewModel: GroupsViewModel) {
    val ctx = LocalContext.current
    val groups by groupsViewModel.groups.collectAsState()
    val group = groups.find { it.groupId == groupId }

    if (group == null) {
        LaunchedEffect(groupId) { navController.popBackStack() }
        return
    }

    var messages by remember { mutableStateOf(emptyList<GroupChatMessage>()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) } // proti bliku „no messages yet" před 1. načtením
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var emojiPickerFor by remember { mutableStateOf<String?>(null) } // msgId, pro který je otevřený plný emoji picker
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val rows = remember(messages) { GroupChatRows.build(messages) }

    // Na popředí: potlač notifikaci a přenačítej historii + jména (příjem dělá služba).
    LaunchedEffect(groupId) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ActiveChat.currentGroupId = groupId
            ChatNotifications.cancelGroupMessage(ctx, groupId)
            try {
                while (true) {
                    val snapshot = withContext(Dispatchers.IO) {
                        val fresh = GroupStore(ctx).getGroup(groupId) ?: group
                        GroupChatRepository(ctx).getMessages(groupId) to GroupMemberNames.resolvedNames(ctx, fresh)
                    }
                    messages = snapshot.first
                    names = snapshot.second
                    loaded = true
                    delay(1500)
                }
            } finally {
                ActiveChat.currentGroupId = null
            }
        }
    }

    // Zprávy zarovnané dolů → drž se u dna, jakmile přibude řádek (jako 1:1).
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    fun reload() {
        scope.launch { messages = withContext(Dispatchers.IO) { GroupChatRepository(ctx).getMessages(groupId) } }
    }

    fun sendCurrent() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        scope.launch {
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) { GroupSync.sendText(ctx, group, text, url, System.currentTimeMillis()) }
            reload()
        }
    }

    // Reakce (emoji, prázdné = zrušení). OPTIMISTICKY hned v UI (než dojde na server),
    // reload smyčka pak stav dorovná; síť běží na pozadí.
    fun react(targetMsgId: String, emoji: String) {
        messages = messages.map { m ->
            if (m.msgIdHex != targetMsgId) m
            else m.copy(reactions = if (emoji.isEmpty()) m.reactions - group.myMemberId else m.reactions + (group.myMemberId to emoji))
        }
        scope.launch {
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) { GroupSync.sendReaction(ctx, group, targetMsgId, emoji, url, System.currentTimeMillis()) }
        }
    }

    fun sendImage(uri: Uri) {
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { ChatMediaStore.compress(ctx, uri) } ?: return@launch
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) {
                val path = GroupMediaStore.save(ctx, groupId, jpeg)
                GroupSync.sendImage(ctx, group, jpeg, path, url, System.currentTimeMillis())
            }
            reload()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendImage(uri)
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
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_open_detail)) },
                            onClick = { menuOpen = false; navController.navigate("group_detail/$groupId") }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_leave)) },
                            onClick = { menuOpen = false; showLeaveDialog = true }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (loaded && rows.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.group_empty_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
                    ) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is GroupChatRows.Row.Day -> GroupDayDivider(row.epochDay)
                                is GroupChatRows.Row.Msg -> {
                                    val m = row.message
                                    if (m.isSystem) {
                                        GroupSystemRow(m, myMemberId = group.myMemberId)
                                    } else {
                                        GroupMessageBubble(
                                            message = m,
                                            senderName = m.senderMemberIdHex?.let { names[it] },
                                            myMemberId = group.myMemberId,
                                            onReact = { emoji -> react(m.msgIdHex, emoji) },
                                            onMore = { emojiPickerFor = m.msgIdHex }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Vstupní lišta — jako 1:1: příloha (fotka) + incognito pole + odeslat.
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box {
                        IconButton(onClick = { attachMenu = true }) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.content_desc_attach))
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_gallery)) },
                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                                onClick = {
                                    attachMenu = false
                                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            )
                        }
                    }
                    IncognitoTextField(
                        value = input,
                        onValueChange = { input = it },
                        hint = stringResource(R.string.chat_input_hint),
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(onClick = { sendCurrent() }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.content_desc_send))
                    }
                }
            }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.group_leave)) },
            text = { Text(stringResource(R.string.group_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    groupsViewModel.deleteGroup(groupId)
                    navController.popBackStack()
                }) { Text(stringResource(R.string.group_leave)) }
            },
            dismissButton = { TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    // Plný emoji picker pro reakci (otevřený z „+" v paletě) — stejná komponenta jako 1:1.
    emojiPickerFor?.let { targetId ->
        EmojiPickerSheet(
            onPick = { emoji -> react(targetId, emoji); emojiPickerFor = null },
            onDismiss = { emojiPickerFor = null }
        )
    }
}

/**
 * Bublina skupinové zprávy — vizuálně SHODNÁ s 1:1 [ChatScreen] `MessageBubble`:
 * asymetrický roh (ocásek), moje = `accent.bubble`/`onBubble`, cizí =
 * `surfaceVariant`/`onSurfaceVariant`, čas + textové fajfky ✓/✓✓ v `textColor`@70 %.
 * Navíc jméno odesílatele nad příchozí bublinou ([senderName] = null u odchozích).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(message: GroupChatMessage, senderName: String?, myMemberId: String, onReact: (String) -> Unit, onMore: () -> Unit) {
    val outgoing = message.outgoing
    val accent = LocalDesign.current.accent
    val bubbleColor = if (outgoing) accent.bubble else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (outgoing) accent.onBubble else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp
    )
    val myReaction = message.reactions[myMemberId]
    var showPicker by remember { mutableStateOf(false) }

    Column {
        if (!outgoing && senderName != null) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 1.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
        ) {
            Box {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(shape)
                        .background(bubbleColor)
                        // Dvojklik = přepnout 👍, dlouhý stisk = výběr emoji (jako 1:1).
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showPicker = true },
                            onDoubleClick = { onReact(if (myReaction == GROUP_DEFAULT_REACTION) "" else GROUP_DEFAULT_REACTION) }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (message.kind == GroupChatMessage.Kind.IMAGE) {
                        GroupImage(message.mediaPath)
                    } else {
                        Text(message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            GROUP_TIME_FORMAT.format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                        if (outgoing) {
                            Text(
                                statusGlyph(message.status),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == GroupChatMessage.Status.FAILED)
                                    MaterialTheme.colorScheme.error else textColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                if (showPicker) {
                    Popup(
                        popupPositionProvider = remember(outgoing) { AboveAnchorPosition(alignEnd = outgoing) },
                        onDismissRequest = { showPicker = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        ReactionPicker(
                            emojis = GROUP_REACTIONS,
                            mine = myReaction,
                            onPick = { showPicker = false; onReact(if (myReaction == it) "" else it) },
                            onMore = { showPicker = false; onMore() }
                        )
                    }
                }
            }
        }
        if (message.reactions.isNotEmpty()) {
            GroupReactionChips(message.reactions, outgoing)
        }
    }
}

/** Čipy reakcí pod bublinou (emoji × počet), zarovnané na stranu bubliny. */
@Composable
private fun GroupReactionChips(reactions: Map<String, String>, outgoing: Boolean) {
    val counts = reactions.values.groupingBy { it }.eachCount()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            counts.forEach { (emoji, count) ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
                    Text(
                        if (count > 1) "$emoji $count" else emoji,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
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

/** Oddělovač dne — shodný s 1:1 `DayHeaderRow`. */
@Composable
private fun GroupDayDivider(epochDay: Long) {
    val today = remember { java.time.LocalDate.now().toEpochDay() }
    val label = when (ChatScreenLogic.dayLabel(epochDay, today)) {
        ChatScreenLogic.DayLabel.TODAY -> stringResource(R.string.chat_day_today)
        ChatScreenLogic.DayLabel.YESTERDAY -> stringResource(R.string.chat_day_yesterday)
        ChatScreenLogic.DayLabel.OLDER -> remember(epochDay) {
            java.time.LocalDate.ofEpochDay(epochDay).format(
                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Systémový řádek (kdo se připojil / byl odebrán) — vycentrovaná pilulka jako
 * oddělovač dne. „Připojili jste se" pro sebe, jinak rozřešené jméno.
 */
@Composable
private fun GroupSystemRow(message: GroupChatMessage, myMemberId: String) {
    val text = when {
        message.kind == GroupChatMessage.Kind.SYSTEM_JOIN && message.senderMemberIdHex == myMemberId ->
            stringResource(R.string.group_system_you_joined)
        message.kind == GroupChatMessage.Kind.SYSTEM_JOIN ->
            stringResource(R.string.group_system_joined, message.text)
        else -> stringResource(R.string.group_system_left, message.text)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/** Zobrazení fotky ve skupinové bublině (dekódování mimo hlavní vlákno). */
@Composable
private fun GroupImage(path: String?) {
    val bitmap by androidx.compose.runtime.produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.sizeIn(maxWidth = 260.dp, maxHeight = 360.dp))
    } else {
        Text(stringResource(R.string.notif_photo), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
