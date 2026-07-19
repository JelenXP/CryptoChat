@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jelenxp.cryptochat.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.crypto.FileStreamCipher
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.components.SegmentedControl
import com.jelenxp.cryptochat.ui.theme.MonoStyle
import com.jelenxp.cryptochat.ui.util.AvatarStore
import com.jelenxp.cryptochat.ui.util.FileTransfer
import com.jelenxp.cryptochat.ui.util.copyToClipboard
import com.jelenxp.cryptochat.ui.util.shareText
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val ENC_MIME = "application/octet-stream"
private const val PREVIEW_MAX_PX = 1280

private enum class Dir { ENCRYPT, DECRYPT }
private enum class Kind { TEXT, FILE }

/** Dešifrovaný/zašifrovaný soubor připravený k uložení/sdílení. */
private class FileOutcome(
    val file: File,
    val name: String,
    val saveMime: String,
    val preview: ImageBitmap?,
    val encrypted: Boolean
)

/**
 * Detail kontaktu jako jedna obrazovka: přepínač Zašifrovat/Dešifrovat + chipy
 * Text/Soubor, obsah se mění podle volby (text přes [CryptoManager], soubory
 * rámcově a streamově přes [FileStreamCipher]). Nahoře profil, dole otisk klíče,
 * v přetékajícím menu úprava profilu / ověření / obnova klíče / smazání.
 */
@Composable
fun UserDetailScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contact = viewModel.getContact(id)
    val key = contact?.keyBase64
    val secretKey = remember(key) { key?.let { runCatching { CryptoManager.keyFromBase64(it) }.getOrNull() } }
    val settings = remember { SettingsRepository(context) }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { FileTransfer.clearTemp(context) }

    // --- Stav dialogů / menu ---
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showAvatarSheet by remember { mutableStateOf(false) }
    var editedName by remember(contact?.name) { mutableStateOf(contact?.name ?: "") }

    // --- Stav šifrování/dešifrování ---
    var dir by remember { mutableStateOf(Dir.ENCRYPT) }
    var kind by remember { mutableStateOf(Kind.TEXT) }
    var draft by remember { mutableStateOf("") }
    var textOut by remember { mutableStateOf<String?>(null) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf("") }
    var pickedMime by remember { mutableStateOf<String?>(null) }
    var pickedSize by remember { mutableStateOf<Long?>(null) }
    var outcome by remember { mutableStateOf<FileOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var indeterminate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val errorSaveFailed = stringResource(R.string.error_save_failed)
    val errorDeleteFailed = stringResource(R.string.error_delete_failed)
    val errorCameraPermission = stringResource(R.string.error_camera_permission)
    val errEncText = stringResource(R.string.error_encrypt_failed)
    val errDecText = stringResource(R.string.error_decrypt_failed)
    val errFileEnc = stringResource(R.string.error_file_encrypt_failed)
    val errFileDec = stringResource(R.string.error_file_decrypt_failed)
    val errTooLarge = stringResource(R.string.error_file_too_large)
    val toastSaved = stringResource(R.string.toast_file_saved)
    val toastCopied = stringResource(R.string.toast_copied)
    val shareTextChooser = stringResource(R.string.share_encrypted_chooser)
    val shareEncFileChooser = stringResource(R.string.share_encrypted_file_chooser)
    val shareDecFileChooser = stringResource(R.string.share_decrypted_file_chooser)

    fun resetIO() {
        draft = ""; textOut = null; error = null
        pickedUri = null; pickedName = ""; pickedMime = null; pickedSize = null
        outcome = null; busy = false; progress = 0f; indeterminate = false
    }

    fun maxBytes(): Long =
        if (settings.isFileSizeLimitEnabled()) FileTransfer.DEFAULT_LIMIT_BYTES else Long.MAX_VALUE

    // --- Fotka profilu ---
    fun applyAvatar(uri: Uri) {
        scope.launch {
            val path = withContext(Dispatchers.IO) { AvatarStore.saveAvatar(context, id, uri) }
            if (path != null) viewModel.getContact(id)?.let { viewModel.addOrUpdateContact(it.copy(avatarPath = path)) }
            else Toast.makeText(context, errorSaveFailed, Toast.LENGTH_LONG).show()
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { applyAvatar(it) }
    }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraTempUri?.let { applyAvatar(it) }
    }
    fun launchCamera() {
        val uri = AvatarStore.newCameraOutputUri(context)
        cameraTempUri = uri
        if (uri != null) cameraLauncher.launch(uri)
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(context, errorCameraPermission, Toast.LENGTH_LONG).show()
    }
    fun onTakePhoto() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera() else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }
    fun removeAvatar() {
        scope.launch {
            withContext(Dispatchers.IO) { AvatarStore.deleteAvatars(context, id) }
            viewModel.getContact(id)?.let { viewModel.addOrUpdateContact(it.copy(avatarPath = null)) }
        }
    }

    // --- Výběr / uložení souborů ---
    val pickContract = remember { ActivityResultContracts.GetContent() }
    val saveContract = remember(outcome?.saveMime) {
        ActivityResultContracts.CreateDocument(outcome?.saveMime ?: ENC_MIME)
    }
    val pickLauncher = rememberLauncherForActivityResult(pickContract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val size = FileTransfer.fileSize(context, uri)
        if (size != null && size > maxBytes()) { error = errTooLarge; return@rememberLauncherForActivityResult }
        pickedUri = uri
        pickedName = FileTransfer.displayName(context, uri) ?: "soubor"
        pickedMime = FileTransfer.mimeType(context, uri)
        pickedSize = size
        outcome = null; error = null
    }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        val o = outcome
        if (uri == null || o == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { FileTransfer.copyToUri(context, o.file, uri) }
            Toast.makeText(context, if (ok) toastSaved else errorSaveFailed, Toast.LENGTH_LONG).show()
        }
    }

    // --- Akce ---
    fun runText() {
        val sk = secretKey ?: return
        val input = draft.trim()
        if (input.isEmpty()) return
        // Ihned schovej klávesnici (uživatel právě odeslal akci).
        focusManager.clearFocus()
        error = null
        try {
            textOut = if (dir == Dir.ENCRYPT) CryptoManager.encrypt(input, sk) else CryptoManager.decrypt(input, sk)
        } catch (e: Exception) {
            textOut = null
            error = if (dir == Dir.ENCRYPT) errEncText else errDecText
        }
    }

    fun runFile() {
        val sk = secretKey ?: return
        val uri = pickedUri ?: return
        val encrypt = dir == Dir.ENCRYPT
        val name = pickedName
        val mime = pickedMime
        val size = pickedSize
        val max = maxBytes()
        error = null; outcome = null; progress = 0f; indeterminate = (size == null || size <= 0); busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                val temp = FileTransfer.newTempFile(context, if (encrypt) "$name.enc" else "cryptochat_decrypted.tmp")
                try {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        temp.outputStream().use { out ->
                            if (encrypt) {
                                FileStreamCipher.encrypt(input, out, sk, name, mime, max) { done ->
                                    if (size != null && size > 0) progress = (done.toFloat() / size).coerceIn(0f, 1f)
                                }
                                FileOutcome(temp, "$name.enc", ENC_MIME, null, true)
                            } else {
                                val meta = FileStreamCipher.decrypt(input, out, sk, max) { done ->
                                    if (size != null && size > 0) progress = (done.toFloat() / size).coerceIn(0f, 1f)
                                }
                                val preview = if (isImage(meta.mime, meta.name)) decodePreview(temp)?.asImageBitmap() else null
                                FileOutcome(temp, meta.name, meta.mime ?: ENC_MIME, preview, false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    temp.delete()
                    null
                }
            }
            busy = false
            if (res != null) outcome = res else error = if (encrypt) errFileEnc else errFileDec
        }
    }

    CryptoScaffold(
        title = contact?.name ?: stringResource(R.string.user_fallback_title),
        onBack = { navController.popBackStack() },
        actions = {
            if (contact != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_edit_profile)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; editedName = contact.name; showEditDialog = true }
                        )
                        if (contact.keyBase64 != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.btn_verify_key)) },
                                leadingIcon = { Icon(Icons.Default.VerifiedUser, null) },
                                onClick = { menuOpen = false; navController.navigate("verify/$id") }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.btn_rekey)) },
                                leadingIcon = { Icon(Icons.Default.Autorenew, null) },
                                onClick = { menuOpen = false; navController.navigate("rekey/$id") }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.btn_delete_contact), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; showDeleteDialog = true }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (contact == null) {
                InfoCard(text = stringResource(R.string.user_not_found))
                return@Column
            }

            ProfileHeader(
                name = contact.name,
                avatarPath = contact.avatarPath,
                hasKey = secretKey != null,
                onEdit = { editedName = contact.name; showEditDialog = true }
            )

            if (secretKey == null) {
                InfoCard(text = stringResource(R.string.user_no_key))
                Button(onClick = { navController.navigate("rekey/$id") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Autorenew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_rekey))
                }
                return@Column
            }

            // Přepínač Zašifrovat / Dešifrovat (klouzavá pilulka)
            SegmentedControl(
                options = listOf(stringResource(R.string.btn_encrypt), stringResource(R.string.btn_decrypt)),
                selectedIndex = if (dir == Dir.ENCRYPT) 0 else 1,
                onSelect = { idx ->
                    val nd = if (idx == 0) Dir.ENCRYPT else Dir.DECRYPT
                    if (dir != nd) { dir = nd; resetIO() }
                }
            )

            // Chipy Text / Soubor
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    icon = Icons.Default.Edit,
                    label = stringResource(R.string.chip_text),
                    selected = kind == Kind.TEXT,
                    onClick = { if (kind != Kind.TEXT) { kind = Kind.TEXT; resetIO() } }
                )
                Chip(
                    icon = Icons.Default.Description,
                    label = stringResource(R.string.chip_file),
                    selected = kind == Kind.FILE,
                    onClick = { if (kind != Kind.FILE) { kind = Kind.FILE; resetIO() } }
                )
            }

            // Obsah přejíždí do strany podle pozice přepínačů: Zašifrovat/Text
            // vlevo, Dešifrovat/Soubor vpravo - přepnutí doprava slide zprava,
            // doleva zleva (viz index níže).
            AnimatedContent(
                targetState = dir to kind,
                transitionSpec = {
                    val fromIdx = initialState.first.ordinal * 2 + initialState.second.ordinal
                    val toIdx = targetState.first.ordinal * 2 + targetState.second.ordinal
                    val forward = toIdx >= fromIdx
                    val d = 260
                    (slideInHorizontally(tween(d)) { w -> if (forward) w else -w } + fadeIn(tween(d)))
                        .togetherWith(slideOutHorizontally(tween(d)) { w -> if (forward) -w else w } + fadeOut(tween(d)))
                },
                label = "modeSlide"
            ) { state ->
                val (curDir, curKind) = state
                val encrypting = curDir == Dir.ENCRYPT
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (curKind == Kind.TEXT) {
                        // ---- Textový režim ----
                        FieldLabel(
                            stringResource(
                                if (encrypting) R.string.detail_msg_for else R.string.detail_enc_text_from,
                                contact.name
                            )
                        )
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it; error = null; if (textOut != null) textOut = null },
                            placeholder = {
                                Text(stringResource(if (encrypting) R.string.detail_msg_placeholder else R.string.detail_paste_placeholder))
                            },
                            textStyle = if (encrypting) LocalTextStyle.current else MonoStyle,
                            // Velká písmena necháme na klávesnici (Gboard apod.) - jen u psaní
                            // zprávy; u vkládání šifrovaného textu ne (je to Base64).
                            keyboardOptions = KeyboardOptions(
                                capitalization = if (encrypting) KeyboardCapitalization.Sentences else KeyboardCapitalization.None
                            ),
                            // Nenápadné „vložit ze schránky" v obou režimech (jednotný vzhled).
                            trailingIcon = {
                                IconButton(onClick = {
                                    clipboard.getText()?.text?.let { draft = it; error = null; textOut = null }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.action_paste))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 118.dp)
                        )
                        ActionButton(
                            label = stringResource(if (encrypting) R.string.action_enc_text else R.string.action_dec_text),
                            icon = if (encrypting) Icons.Default.Lock else Icons.Default.LockOpen,
                            enabled = draft.isNotBlank(),
                            onClick = { runText() }
                        )
                        error?.let { Appear { ErrorCard(it) } }
                        textOut?.let { out ->
                            Appear {
                                ResultTextCard(
                                    label = stringResource(if (encrypting) R.string.detail_out_encrypted else R.string.detail_out_original),
                                    value = out,
                                    mono = encrypting,
                                    onCopy = { context.copyToClipboard("cryptochat", out, toastCopied) },
                                    onShare = if (encrypting) ({ context.shareText(out, shareTextChooser) }) else null
                                )
                            }
                        }
                    } else {
                        // ---- Souborový režim ----
                        FieldLabel(
                            stringResource(
                                if (encrypting) R.string.detail_file_for else R.string.detail_enc_file_from,
                                contact.name
                            )
                        )
                        if (pickedUri == null) {
                            DropZone(
                                title = stringResource(if (encrypting) R.string.detail_pick_enc else R.string.detail_pick_dec),
                                hint = stringResource(if (encrypting) R.string.detail_pick_enc_hint else R.string.detail_pick_dec_hint),
                                onClick = { error = null; pickLauncher.launch("*/*") }
                            )
                        } else {
                            PickedFileCard(
                                name = pickedName,
                                size = pickedSize,
                                onClear = { pickedUri = null; outcome = null; error = null },
                                onReplace = { error = null; pickLauncher.launch("*/*") }
                            )
                            if (!busy && outcome == null) {
                                ActionButton(
                                    label = stringResource(if (encrypting) R.string.action_enc_file else R.string.action_dec_file),
                                    icon = if (encrypting) Icons.Default.Lock else Icons.Default.LockOpen,
                                    enabled = true,
                                    onClick = { runFile() }
                                )
                            }
                        }
                        if (busy) {
                            ProgressRow(
                                label = stringResource(if (encrypting) R.string.file_encrypting else R.string.file_decrypting),
                                progress = progress,
                                indeterminate = indeterminate
                            )
                        }
                        error?.let { Appear { ErrorCard(it) } }
                        outcome?.let { o ->
                            Appear {
                                FileResultCard(
                                    title = stringResource(if (o.encrypted) R.string.file_encrypted_ready else R.string.file_decrypted_ready),
                                    name = o.name,
                                    preview = o.preview,
                                    onSave = { saveLauncher.launch(o.name) },
                                    onShare = {
                                        FileTransfer.shareFile(
                                            context, o.file, o.saveMime,
                                            if (o.encrypted) shareEncFileChooser else shareDecFileChooser
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            key?.let { VerifyLine(fingerprint = remember(it) { CryptoManager.fingerprint(it) }) { navController.navigate("verify/$id") } }
        }
    }

    // --- Dialogy ---
    if (showEditDialog && contact != null) {
        EditProfileDialog(
            name = editedName,
            avatarPath = contact.avatarPath,
            hasPhoto = contact.avatarPath != null,
            onName = { editedName = it },
            onChangePhoto = { showAvatarSheet = true },
            onRemovePhoto = { removeAvatar() },
            onDismiss = { showEditDialog = false },
            onSave = {
                val trimmed = editedName.trim()
                if (trimmed.isNotEmpty()) {
                    val ok = viewModel.addOrUpdateContact(contact.copy(name = trimmed))
                    showEditDialog = false
                    if (!ok) Toast.makeText(context, errorSaveFailed, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showAvatarSheet && contact != null) {
        AlertDialog(
            onDismissRequest = { showAvatarSheet = false },
            title = { Text(stringResource(R.string.avatar_dialog_title)) },
            text = {
                Column {
                    OptionRow(Icons.Default.PhotoLibrary, stringResource(R.string.avatar_pick_gallery)) {
                        showAvatarSheet = false
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    OptionRow(Icons.Default.PhotoCamera, stringResource(R.string.avatar_take_photo)) {
                        showAvatarSheet = false; onTakePhoto()
                    }
                    if (contact.avatarPath != null) {
                        OptionRow(Icons.Default.Delete, stringResource(R.string.avatar_remove), MaterialTheme.colorScheme.error) {
                            showAvatarSheet = false; removeAvatar()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAvatarSheet = false }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }

    if (showDeleteDialog && contact != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_text, contact.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = viewModel.deleteContact(id)
                    showDeleteDialog = false
                    if (ok) {
                        AvatarStore.deleteAvatars(context, id)
                        navController.popBackStack("main", inclusive = false)
                    } else Toast.makeText(context, errorDeleteFailed, Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.btn_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}

// ---------- Skládané prvky ----------

@Composable
private fun ProfileHeader(name: String, avatarPath: String?, hasKey: Boolean, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(modifier = Modifier.clickable(onClick = onEdit)) {
            ContactAvatar(name = name, avatarPath = avatarPath, size = 60.dp, textStyle = MaterialTheme.typography.headlineSmall)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.background),
                modifier = Modifier.size(24.dp).align(Alignment.BottomEnd)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(13.dp))
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(
                    if (hasKey) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(if (hasKey) R.string.e2e_label else R.string.key_not_set),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        tween(220), label = "chipBg"
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(220), label = "chipFg"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        contentColor = fg,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Button(
        onClick = {
            view.performHapticFeedback(
                HapticFeedbackConstants.CONTEXT_CLICK,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            onClick()
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Obalí obsah jemným nájezdem (fade + malý posun zdola) při jeho objevení. */
@Composable
private fun Appear(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 },
        exit = fadeOut(tween(120))
    ) { content() }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun ResultTextCard(label: String, value: String, mono: Boolean, onCopy: () -> Unit, onShare: (() -> Unit)?) {
    // Po zkopírování se ikona na chvíli změní na ✓ (drobný feedback).
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(
                value,
                style = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(14.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onCopy(); copied = true }, modifier = Modifier.weight(1f)) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (copied) R.string.toast_copied else R.string.content_desc_copy))
            }
            if (onShare != null) {
                FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_share_file))
                }
            }
        }
    }
}

@Composable
private fun DropZone(title: String, hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 26.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.UploadFile, contentDescription = null) }
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickedFileCard(name: String, size: Long?, onClear: () -> Unit, onReplace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, contentDescription = null) }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(humanSize(size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.btn_cancel)) }
            }
        }
        TextButton(onClick = onReplace) { Text(stringResource(R.string.detail_pick_other)) }
    }
}

@Composable
private fun FileResultCard(title: String, name: String, preview: ImageBitmap?, onSave: () -> Unit, onShare: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            preview?.let {
                Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp))
            }
            Text(name, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_save_file))
                }
                FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_share_file))
                }
            }
        }
    }
}

@Composable
private fun VerifyLine(fingerprint: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.detail_verify_code, fingerprint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EditProfileDialog(
    name: String,
    avatarPath: String?,
    hasPhoto: Boolean,
    onName: (String) -> Unit,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_edit_profile)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.clickable(onClick = onChangePhoto)) {
                    ContactAvatar(name = name.ifBlank { "?" }, avatarPath = avatarPath, size = 88.dp, textStyle = MaterialTheme.typography.headlineMedium)
                    Surface(
                        shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                        modifier = Modifier.size(30.dp).align(Alignment.BottomEnd)
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(16.dp)) } }
                }
                if (hasPhoto) {
                    TextButton(onClick = onRemovePhoto) { Text(stringResource(R.string.avatar_remove), color = MaterialTheme.colorScheme.error) }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.label_new_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = name.isNotBlank()) { Text(stringResource(R.string.btn_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } }
    )
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

// ---------- Pomocné ----------

private fun humanSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return ""
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f kB", bytes / 1024.0)
        else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun isImage(mime: String?, name: String): Boolean {
    if (mime != null && mime.startsWith("image/")) return true
    val lower = name.lowercase()
    return listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { lower.endsWith(it) }
}

private fun decodePreview(file: File): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= PREVIEW_MAX_PX && h / 2 >= PREVIEW_MAX_PX) { w /= 2; h /= 2; sample *= 2 }
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}
