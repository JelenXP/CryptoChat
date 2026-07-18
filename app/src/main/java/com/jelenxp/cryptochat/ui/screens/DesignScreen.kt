package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.AccentColor
import com.jelenxp.cryptochat.data.AnimSpeed
import com.jelenxp.cryptochat.data.AnimStyle
import com.jelenxp.cryptochat.data.ThemeMode
import com.jelenxp.cryptochat.data.UiDensity
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.theme.LocalDesign

/**
 * Nastavení zobrazení (vzhled): motiv, akcent, hustota, styl a rychlost
 * animací. Vše se okamžitě projeví v celé appce přes [LocalDesign].
 */
@Composable
fun DesignScreen(navController: NavController) {
    val design = LocalDesign.current

    CryptoScaffold(
        title = stringResource(R.string.settings_design_label),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.design_display_settings).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Motiv
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubLabel(stringResource(R.string.design_theme_label))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        val options = listOf(
                            ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                            ThemeMode.LIGHT to stringResource(R.string.theme_light),
                            ThemeMode.DARK to stringResource(R.string.theme_dark)
                        )
                        options.forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = design.themeMode == mode,
                                        onClick = { design.setThemeMode(mode) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = design.themeMode == mode,
                                    onClick = { design.setThemeMode(mode) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label)
                            }
                        }
                    }
                }
            }

            // Akcent
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubLabel(stringResource(R.string.design_accent_label))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    AccentColor.entries.forEach { option ->
                        val selected = design.accent == option
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(option.color)
                                .then(
                                    if (selected) Modifier.border(
                                        BorderStroke(3.dp, MaterialTheme.colorScheme.onBackground),
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickable { design.setAccent(option) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Hustota
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubLabel(stringResource(R.string.design_density_label))
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.density_compact),
                        stringResource(R.string.density_comfortable)
                    ),
                    selectedIndex = if (design.density == UiDensity.COMFORTABLE) 1 else 0,
                    onSelect = { design.setDensity(if (it == 1) UiDensity.COMFORTABLE else UiDensity.COMPACT) }
                )
            }

            // Styl animace
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubLabel(stringResource(R.string.design_animation_label))
                val styles = listOf(AnimStyle.SLIDE, AnimStyle.FADE, AnimStyle.SCALE, AnimStyle.NONE)
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.anim_slide),
                        stringResource(R.string.anim_fade),
                        stringResource(R.string.anim_scale),
                        stringResource(R.string.anim_none)
                    ),
                    selectedIndex = styles.indexOf(design.animStyle).coerceAtLeast(0),
                    onSelect = { design.setAnimStyle(styles[it]) }
                )
            }

            // Rychlost animace
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubLabel(stringResource(R.string.design_speed_label))
                val speeds = listOf(AnimSpeed.FAST, AnimSpeed.NORMAL, AnimSpeed.SLOW)
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.speed_fast),
                        stringResource(R.string.speed_normal),
                        stringResource(R.string.speed_slow)
                    ),
                    selectedIndex = speeds.indexOf(design.animSpeed).coerceAtLeast(0),
                    onSelect = { design.setAnimSpeed(speeds[it]) }
                )
            }
        }
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

/** Jednoduchý přepínač z několika volev (segmented control). */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
