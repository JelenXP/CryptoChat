package com.jelenxp.cryptochat.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import java.io.File

/**
 * Kruhový avatar kontaktu: pokud má nastavenou fotku ([avatarPath] ukazuje na
 * existující soubor), zobrazí ji oříznutou do kruhu; jinak ukáže první písmeno
 * jména na barevném podkladu (jako dřív). Chybějící/poškozený soubor tiše spadne
 * zpět na iniciálu.
 */
@Composable
fun ContactAvatar(
    name: String,
    avatarPath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    val bitmap = remember(avatarPath) {
        avatarPath?.let { path ->
            try {
                if (File(path).exists()) BitmapFactory.decodeFile(path) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.size(size)
    ) {
        // Při změně fotky (nebo přechodu fotka↔iniciála) se obsah plynule prolne.
        Crossfade(
            targetState = bitmap,
            animationSpec = tween(220),
            label = "avatar",
            modifier = Modifier.fillMaxSize()
        ) { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = name.trim().firstOrNull()?.uppercase() ?: "?", style = textStyle)
                }
            }
        }
    }
}
