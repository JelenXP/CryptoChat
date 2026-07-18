package com.jelenxp.cryptochat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
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
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.lock.isDeviceSecureAuthAvailable
import com.jelenxp.cryptochat.ui.lock.requestUnlock

private const val LANG_SYSTEM = "system"
private const val LANG_CZECH = "cs"
private const val LANG_ENGLISH = "en"

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
