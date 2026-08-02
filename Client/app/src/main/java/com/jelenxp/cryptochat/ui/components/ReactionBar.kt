package com.jelenxp.cryptochat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.jelenxp.cryptochat.R

/**
 * Sdílený vodorovný pruh rychlých reakcí (stejný v 1:1 i ve skupině — kvůli
 * vizuální paritě). Vybraná reakce je zvýrazněná; „+" na konci otevře plný emoji
 * picker ([onMore]). Emoji paletu předává volající, ať komponenta nezávisí na
 * konkrétní obrazovce.
 */
@Composable
fun ReactionPicker(emojis: List<String>, mine: String?, onPick: (String) -> Unit, onMore: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            emojis.forEach { emoji ->
                val isMine = mine == emoji
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .then(if (isMine) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)) else Modifier)
                        .clickable { onPick(emoji) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onMore() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.reaction_more),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Umístí Popup těsně NAD kotvu (bublinu), zarovnaný k její straně (stejné jako 1:1). */
class AboveAnchorPosition(private val alignEnd: Boolean) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        val rawX = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(rawX.coerceIn(0, maxX), y)
    }
}
