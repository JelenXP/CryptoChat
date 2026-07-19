package com.jelenxp.cryptochat.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Rozbalí obalený Context (ContextWrapper vrstvy) a najde skutečnou Activity. */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Po dobu, co je tato composable na obrazovce, zamkne orientaci na výšku
 * (portrait) a při odchodu ji vrátí zpět.
 *
 * Používá se u obrazovek výměny klíče na dálku: jejich citlivý klíčový
 * materiál (soukromý klíč, odvozený sdílený klíč) se drží jen v paměti
 * (`remember`, NE `rememberSaveable`), aby se nikdy nezapsal do
 * savedInstanceState (ten může OS uložit i na disk). Bez saveable stavu by
 * ale rotace obrazovky rozbila rozdělanou výměnu - zamčení orientace tomu
 * zabrání.
 */
@Composable
fun LockPortraitWhileVisible() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
