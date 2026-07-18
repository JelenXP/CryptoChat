package com.example.cryptochat.ui.qr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bílá karta okolo QR kódu. QR se generuje černobílý, takže na tmavém pozadí
 * (tmavý motiv) by splýval - proto ho vždy rámujeme bílou plochou s odsazením,
 * aby zůstal dobře čitelný a naskenovatelný.
 */
@Composable
fun QrCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Color.White,
        modifier = Modifier.padding(4.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
