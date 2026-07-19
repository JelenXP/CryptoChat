package com.jelenxp.cryptochat.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import com.jelenxp.cryptochat.ui.components.SegmentedControl
import com.jelenxp.cryptochat.ui.theme.LocalDesign

/**
 * Nastavení zobrazení (vzhled): motiv, akcent, hustota, styl a rychlost
 * animací. Vše se okamžitě projeví v celé appce přes [LocalDesign].
 *
 * Náhled animace není zvláštní prvek - celý obsah obrazovky se **přeanimuje
 * zvoleným přechodem**, kdykoli změníš styl nebo rychlost. Uvidíš tak přímo,
 * jak bude vypadat přepínání obrazovek (stejné přechody jako v navigaci appky).
 */
@Composable
fun DesignScreen(navController: NavController) {
    val design = LocalDesign.current
    val scrollState = rememberScrollState()

    CryptoScaffold(
        title = stringResource(R.string.settings_design_label),
        onBack = { navController.popBackStack() }
    ) { padding ->
        AnimatedContent(
            targetState = design.animStyle to design.animSpeed,
            transitionSpec = {
                val d = design.animSpeed.millis
                screenEnter(design.animStyle, d) togetherWith screenExit(design.animStyle, d)
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "designScreenPreview"
        ) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
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

                // Styl animace - změna rovnou přeanimuje celou obrazovku (náhled)
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

                // Rychlost animace - taky přeanimuje obrazovku
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

                Text(
                    text = stringResource(R.string.design_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

// Stejné přechody jako navigace appky (viz MainActivity) - náhled tak odpovídá
// realitě přepínání obrazovek.
private fun screenEnter(style: AnimStyle, d: Int): EnterTransition = when (style) {
    AnimStyle.SLIDE -> slideInHorizontally(tween(d)) { it / 6 } + fadeIn(tween(d))
    AnimStyle.FADE -> fadeIn(tween(d))
    AnimStyle.SCALE -> scaleIn(tween(d), initialScale = 0.965f) + fadeIn(tween(d))
    AnimStyle.NONE -> EnterTransition.None
}

private fun screenExit(style: AnimStyle, d: Int): ExitTransition = when (style) {
    AnimStyle.SLIDE -> slideOutHorizontally(tween(d)) { -it / 8 } + fadeOut(tween(d))
    AnimStyle.FADE -> fadeOut(tween(d))
    AnimStyle.SCALE -> scaleOut(tween(d), targetScale = 0.99f) + fadeOut(tween(d))
    AnimStyle.NONE -> ExitTransition.None
}
