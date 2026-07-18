package com.jelenxp.cryptochat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.theme.MonoStyle

/**
 * Jednotný rámec obrazovky (top bar + volitelné tlačítko zpět, akce a FAB).
 * Sjednocuje vzhled všech obrazovek a doplňuje tlačítko zpět tam, kde dřív
 * chybělo (dalo se odejít jen systémovým gestem).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.content_desc_back)
                            )
                        }
                    }
                },
                actions = actions
            )
        },
        floatingActionButton = floatingActionButton,
        content = content
    )
}

/**
 * Plochá minimalistická karta - místo stínu jen jemný obrys (outlineVariant)
 * na barvě podkladu. Sjednocuje vzhled všech karet v appce a drží klidný,
 * nerušivý dojem. `onClick` je volitelný (klikací varianta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    }
}

/**
 * Informační/instrukční karta s volitelnou ikonou. Používá se pro vysvětlující
 * texty na začátku obrazovek, aby nesplývaly s ovládacími prvky.
 */
@Composable
fun InfoCard(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Read-only pole s dlouhým textem (Base64 klíč / zašifrovaná zpráva). Pokud je
 * `onCopy` zadané, ukáže se přímo v poli tlačítko kopírovat; pokud je `null`,
 * pole je čistě jen ke čtení bez tlačítka (např. když je kopírování klíče
 * v nastavení vypnuté) - vypadá pak stejně upraveně, jen bez ikony.
 */
@Composable
fun CopyableField(
    label: String,
    value: String,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        textStyle = MonoStyle,
        trailingIcon = if (onCopy != null) {
            {
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.content_desc_copy)
                    )
                }
            }
        } else null,
        modifier = modifier
            .fillMaxWidth()
            .then(if (minHeight > 0.dp) Modifier.heightIn(min = minHeight) else Modifier)
    )
}
