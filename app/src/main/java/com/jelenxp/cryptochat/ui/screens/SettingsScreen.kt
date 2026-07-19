package com.jelenxp.cryptochat.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.data.UpdateChecker
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.lock.isDeviceSecureAuthAvailable
import com.jelenxp.cryptochat.ui.lock.requestUnlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LANG_SYSTEM = "system"
private const val LANG_CZECH = "cs"
private const val LANG_ENGLISH = "en"

/** Odkaz na veřejný repozitář projektu (otevře se v prohlížeči). */
private const val GITHUB_URL = "https://github.com/JelenXP/CryptoChat"

/** Jak dlouho drží „pozastavit připomínání aktualizací" (30 dní). */
private const val UPDATE_SNOOZE_DURATION_MS = 30L * 24 * 60 * 60 * 1000

/** Stav ručně spuštěné kontroly aktualizací (zobrazuje se jako dialog). */
private sealed interface ManualCheckState {
    data object Hidden : ManualCheckState
    data object Checking : ManualCheckState
    data class Done(val result: UpdateChecker.Result) : ManualCheckState
}

private fun currentLanguageChoice(): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return LANG_SYSTEM
    return when (locales[0]?.language) {
        "cs" -> LANG_CZECH
        "en" -> LANG_ENGLISH
        else -> LANG_SYSTEM
    }
}

private fun applyLanguage(choice: String) {
    val localeList = when (choice) {
        LANG_CZECH -> LocaleListCompat.forLanguageTags("cs")
        LANG_ENGLISH -> LocaleListCompat.forLanguageTags("en")
        else -> LocaleListCompat.getEmptyLocaleList()
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    var selectedLanguage by remember { mutableStateOf(currentLanguageChoice()) }
    var appLockEnabled by remember { mutableStateOf(settingsRepository.isAppLockEnabled()) }
    var keyCopyAllowed by remember { mutableStateOf(settingsRepository.isKeyCopyAllowed()) }
    var updateCheckEnabled by remember { mutableStateOf(settingsRepository.isUpdateCheckEnabled()) }
    var updateSnoozeActive by remember {
        mutableStateOf(settingsRepository.getUpdateSnoozeUntil() > System.currentTimeMillis())
    }
    var fileLimitEnabled by remember { mutableStateOf(settingsRepository.isFileSizeLimitEnabled()) }

    val scope = rememberCoroutineScope()
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    var updateCheckState by remember { mutableStateOf<ManualCheckState>(ManualCheckState.Hidden) }

    // Otevře URL v prohlížeči; při chybě tiše nic (appka nespadne).
    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // Bez prohlížeče apod. - ignorovat.
        }
    }

    // Ručně spustí kontrolu aktualizací (bez vynucování při startu) a výsledek
    // ukáže v dialogu.
    fun runUpdateCheck() {
        updateCheckState = ManualCheckState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.checkDetailed(versionName) }
            updateCheckState = ManualCheckState.Done(result)
        }
    }

    val lockAvailable = remember { isDeviceSecureAuthAvailable(context) }

    val authTitle = stringResource(R.string.biometric_prompt_title)
    val noActivityError = stringResource(R.string.error_cannot_show_auth)
    val genericError = stringResource(R.string.error_auth_failed_generic)
    val lockUnavailable = stringResource(R.string.error_app_lock_unavailable)

    fun changeAppLock(target: Boolean) {
        if (target && !isDeviceSecureAuthAvailable(context)) {
            Toast.makeText(context, lockUnavailable, Toast.LENGTH_LONG).show()
            return
        }
        requestUnlock(
            context = context,
            title = authTitle,
            noActivityErrorMessage = noActivityError,
            genericErrorMessage = genericError,
            onSuccess = {
                appLockEnabled = target
                settingsRepository.setAppLockEnabled(target)
            },
            onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        )
    }

    CryptoScaffold(
        title = stringResource(R.string.settings_title),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1) JAZYK
            SectionHeader(stringResource(R.string.settings_section_language))
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    val options = listOf(
                        LANG_SYSTEM to stringResource(R.string.lang_system),
                        LANG_CZECH to stringResource(R.string.lang_czech),
                        LANG_ENGLISH to stringResource(R.string.lang_english)
                    )
                    options.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedLanguage == code,
                                    onClick = { selectedLanguage = code; applyLanguage(code) }
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLanguage == code,
                                onClick = { selectedLanguage = code; applyLanguage(code) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }

            // 2) ZABEZPEČENÍ
            SectionHeader(stringResource(R.string.settings_section_security))
            ToggleSettingCard(
                title = stringResource(R.string.settings_app_lock_label),
                description = if (lockAvailable) stringResource(R.string.settings_app_lock_description)
                else stringResource(R.string.settings_app_lock_unavailable),
                checked = appLockEnabled,
                enabled = lockAvailable,
                onCheckedChange = { changeAppLock(it) }
            )
            ToggleSettingCard(
                title = stringResource(R.string.settings_key_copy_label),
                description = stringResource(R.string.settings_key_copy_description),
                checked = keyCopyAllowed,
                onCheckedChange = {
                    keyCopyAllowed = it
                    settingsRepository.setKeyCopyAllowed(it)
                }
            )

            // 3) VZHLED (otevře samostatnou obrazovku)
            SectionHeader(stringResource(R.string.settings_section_appearance))
            AppCard(onClick = { navController.navigate("design") }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Palette, contentDescription = null)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_design_label), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.settings_design_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4) DATA (záloha kontaktů + kontrola aktualizací)
            SectionHeader(stringResource(R.string.settings_section_data))
            NavCard(
                icon = Icons.Default.SettingsBackupRestore,
                title = stringResource(R.string.settings_backup_label),
                description = stringResource(R.string.settings_backup_desc),
                onClick = { navController.navigate("backup") }
            )
            ToggleSettingCard(
                title = stringResource(R.string.settings_update_check_label),
                description = stringResource(R.string.settings_update_check_desc),
                checked = updateCheckEnabled,
                onCheckedChange = {
                    updateCheckEnabled = it
                    settingsRepository.setUpdateCheckEnabled(it)
                }
            )
            ToggleSettingCard(
                title = stringResource(R.string.settings_update_snooze_label),
                description = stringResource(R.string.settings_update_snooze_desc),
                checked = updateSnoozeActive,
                enabled = updateCheckEnabled,
                onCheckedChange = { on ->
                    updateSnoozeActive = on
                    if (on) {
                        settingsRepository.setUpdateSnooze(
                            System.currentTimeMillis() + UPDATE_SNOOZE_DURATION_MS
                        )
                    } else {
                        settingsRepository.clearUpdateSnooze()
                    }
                }
            )

            ToggleSettingCard(
                title = stringResource(R.string.settings_file_limit_label),
                description = stringResource(R.string.settings_file_limit_desc),
                checked = fileLimitEnabled,
                onCheckedChange = {
                    fileLimitEnabled = it
                    settingsRepository.setFileSizeLimitEnabled(it)
                }
            )

            // 5) O APLIKACI (verze, novinky, ruční kontrola aktualizací, GitHub)
            SectionHeader(stringResource(R.string.settings_section_about))
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Info, contentDescription = null)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.about_version, versionName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            NavCard(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                title = stringResource(R.string.about_changelog_label),
                description = stringResource(R.string.about_changelog_desc),
                onClick = { navController.navigate("changelog") }
            )
            NavCard(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.about_check_updates_label),
                description = stringResource(R.string.about_check_updates_desc),
                trailingIcon = null,
                onClick = { runUpdateCheck() }
            )
            NavCard(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                title = stringResource(R.string.about_github_label),
                description = stringResource(R.string.about_github_desc),
                trailingIcon = null,
                onClick = { openUrl(GITHUB_URL) }
            )
        }
    }

    UpdateCheckDialog(
        state = updateCheckState,
        currentVersion = versionName,
        onGetLatest = { url ->
            openUrl(url)
            updateCheckState = ManualCheckState.Hidden
        },
        onDismiss = { updateCheckState = ManualCheckState.Hidden }
    )
}

/**
 * Dialog s výsledkem ručně spuštěné kontroly aktualizací: probíhá / je novější
 * verze / jste aktuální / se to nepovedlo. Nevynucuje nic - jen informuje.
 */
@Composable
private fun UpdateCheckDialog(
    state: ManualCheckState,
    currentVersion: String,
    onGetLatest: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        ManualCheckState.Hidden -> Unit

        ManualCheckState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_check_dialog_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.update_checking))
                }
            },
            confirmButton = {}
        )

        is ManualCheckState.Done -> when (val result = state.result) {
            is UpdateChecker.Result.UpdateAvailable -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.update_versions,
                                currentVersion,
                                result.info.latestVersion
                            )
                        )
                        if (result.info.important) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.update_important_warning),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onGetLatest(result.info.latestUrl) }) {
                        Text(stringResource(R.string.btn_update_get))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
                }
            )

            UpdateChecker.Result.UpToDate -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_check_dialog_title)) },
                text = { Text(stringResource(R.string.update_up_to_date, currentVersion)) },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
                }
            )

            UpdateChecker.Result.Failed -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_check_dialog_title)) },
                text = { Text(stringResource(R.string.update_check_failed)) },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
                }
            )
        }
    }
}

@Composable
private fun NavCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight
) {
    AppCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trailingIcon != null) {
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun ToggleSettingCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val descColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = titleColor)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = descColor)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}
