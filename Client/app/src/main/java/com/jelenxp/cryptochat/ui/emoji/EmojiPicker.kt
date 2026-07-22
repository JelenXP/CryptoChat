package com.jelenxp.cryptochat.ui.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Položka v ploché mřížce pickeru: nadpis sekce, nebo jedno emoji. */
private sealed interface EmojiItem {
    data class Header(val titleRes: Int) : EmojiItem
    data class Emoji(val value: String) : EmojiItem
}

private const val COLUMNS = 8

/**
 * Plný emoji picker jako spodní panel (bottom sheet): nahoře lišta kategorií
 * (klepnutí skočí na sekci a zvýrazní aktivní), pod ní mřížka emoji seskupená
 * do sekcí. Klepnutí na emoji zavolá [onPick]. Data jsou v [EmojiData].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        EmojiPickerContent(onPick = onPick)
    }
}

@Composable
private fun EmojiPickerContent(onPick: (String) -> Unit) {
    val categories = EmojiData.CATEGORIES
    val items = remember(categories) { flatten(categories) }
    val headerIndices = remember(categories) { emojiHeaderIndices(categories) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Aktivní kategorie podle prvního viditelného prvku (pro zvýraznění v liště).
    val active by remember {
        derivedStateOf { emojiCategoryForIndex(gridState.firstVisibleItemIndex, headerIndices) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp, max = 460.dp)
            .navigationBarsPadding()
    ) {
        // Lišta kategorií - skok na sekci.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            categories.forEachIndexed { i, cat ->
                val selected = i == active
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .then(
                            if (selected) Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            ) else Modifier
                        )
                        .clickable { scope.launch { gridState.animateScrollToItem(headerIndices[i]) } }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(cat.tab, fontSize = 20.sp)
                }
            }
        }
        HorizontalDivider()
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            items(
                items,
                span = { item -> if (item is EmojiItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
            ) { item ->
                when (item) {
                    is EmojiItem.Header -> Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 10.dp, bottom = 4.dp)
                    )
                    is EmojiItem.Emoji -> Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(item.value) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(item.value, fontSize = 26.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Ploché pořadí: za každou hlavičkou její emoji. */
private fun flatten(cats: List<EmojiData.Category>): List<EmojiItem> = buildList {
    cats.forEach { c ->
        add(EmojiItem.Header(c.titleRes))
        c.emojis.forEach { add(EmojiItem.Emoji(it)) }
    }
}

/**
 * Index hlavičky každé kategorie v ploché listině z [flatten]. `internal` kvůli
 * testu (netriviální aritmetika nad indexy - pravidlo 2).
 */
internal fun emojiHeaderIndices(cats: List<EmojiData.Category>): List<Int> {
    val res = ArrayList<Int>(cats.size)
    var idx = 0
    cats.forEach { c -> res.add(idx); idx += 1 + c.emojis.size }
    return res
}

/**
 * Poslední kategorie, jejíž hlavička je ještě nad [firstVisible] prvkem (pro
 * zvýraznění aktivní v liště). `internal` kvůli testu na off-by-one/hranice.
 */
internal fun emojiCategoryForIndex(firstVisible: Int, headerIndices: List<Int>): Int {
    var cat = 0
    for (i in headerIndices.indices) if (headerIndices[i] <= firstVisible) cat = i
    return cat
}
