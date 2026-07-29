package com.jelenxp.cryptochat.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jelenxp.cryptochat.R
import kotlinx.coroutines.delay

/** Druhy kroků průvodce. */
private enum class StepKind { NOTIFICATIONS, BATTERY, AUTOSTART }

/**
 * Jeden krok průvodce: proč ho appka potřebuje + akce, která uživatele pošle do
 * příslušného systémového nastavení. `detectable` = umíme zjistit, jestli je
 * splněný (pak ukážeme fajfku a tlačítko „Další"); u autostartu to systém
 * neprozradí, tak jen otevřeme nastavení a necháme uživatele pokračovat.
 */
private class OnStep(
    val kind: StepKind,
    val icon: ImageVector,
    val titleRes: Int,
    val whyRes: Int,
    val actionRes: Int,
    val detectable: Boolean,
    val isSatisfied: (Context) -> Boolean
)

private fun buildSteps(): List<OnStep> {
    val steps = mutableListOf<OnStep>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        steps += OnStep(
            StepKind.NOTIFICATIONS, Icons.Default.Notifications,
            R.string.onb_notif_title, R.string.onb_notif_why, R.string.onb_allow,
            detectable = true, isSatisfied = { isNotifGranted(it) }
        )
    }
    steps += OnStep(
        StepKind.BATTERY, Icons.Default.BatteryChargingFull,
        R.string.onb_battery_title, R.string.onb_battery_why, R.string.onb_allow,
        detectable = true, isSatisfied = { isBatteryUnrestricted(it) }
    )
    // Autostart / běh na pozadí - univerzálně (best-effort podle výrobce, jinak
    // detail aplikace). Systém nedává zjistit stav, tak jen otevřeme nastavení.
    steps += OnStep(
        StepKind.AUTOSTART, Icons.Default.Autorenew,
        R.string.onb_autostart_title, R.string.onb_autostart_why, R.string.onb_open_settings,
        detectable = false, isSatisfied = { false }
    )
    return steps
}

/**
 * Průvodce povoleními pro běh na pozadí (první spuštění). Provede uživatele
 * krok za krokem: notifikace → vyjmutí z optimalizace baterie → autostart
 * (na Xiaomi/HyperOS). Bez těchto povolení systém spojení na pozadí zabíjí.
 */
@Composable
fun BackgroundOnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val steps = remember { buildSteps() }

    if (steps.isEmpty()) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    var index by rememberSaveable { mutableStateOf(0) }
    var resumeTick by remember { mutableStateOf(0) }
    var notifDenied by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        resumeTick++
        if (!granted) {
            notifDenied = true
            // Když systém dialog vůbec neukázal (oprávnění je trvale zamítnuté /
            // „příště se neptat"), request se hned vrátí jako zamítnutý bez akce.
            // Pak uživatele pošleme rovnou do nastavení, ať první klik něco udělá.
            val activity = context as? Activity
            if (activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.POST_NOTIFICATIONS
                )
            ) {
                openAppNotificationSettings(context)
            }
        }
    }

    // Po návratu ze systémového nastavení znovu přepočítej stavy.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val isLast = index >= steps.lastIndex

    // Stav (povoleno?) přepočítáváme po každém návratu do appky (resumeTick) i při
    // změně kroku - a párkrát to zopakujeme, protože systém stav někdy aktualizuje
    // až s malým zpožděním (typicky u vyjmutí z optimalizace baterie).
    var satisfied by remember(index) { mutableStateOf(step.isSatisfied(context)) }
    LaunchedEffect(resumeTick, index) {
        repeat(8) {
            val now = step.isSatisfied(context)
            if (now != satisfied) satisfied = now
            if (now) return@LaunchedEffect
            delay(350)
        }
    }

    fun advance() {
        if (isLast) onFinished() else index++
    }

    fun runAction() {
        when (step.kind) {
            StepKind.NOTIFICATIONS ->
                if (notifDenied) openAppNotificationSettings(context)
                else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            StepKind.BATTERY -> requestIgnoreBattery(context)
            StepKind.AUTOSTART -> openAutostart(context)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Počítadlo kroků + tečky.
            Text(
                text = stringResource(R.string.onb_step_counter, index + 1, steps.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (i <= index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // Min. výška = dostupná (vážená) oblast: krátký obsah kroku zůstane
                    // vycentrovaný, dlouhý (delší překlad „proč") jde doscrollovat místo
                    // aby ho `Arrangement.Center` ořízl mimo obrazovku.
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    step.icon,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(step.titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(step.whyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (step.detectable && satisfied) {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.onb_status_enabled),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            }

            // Tlačítka.
            if (step.detectable && satisfied) {
                Button(onClick = { advance() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (isLast) R.string.onb_finish else R.string.onb_next))
                }
            } else {
                Button(onClick = { runAction() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(step.actionRes))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { advance() }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            when {
                                !step.detectable && isLast -> R.string.onb_finish
                                !step.detectable -> R.string.onb_done_next
                                isLast -> R.string.onb_skip_finish
                                else -> R.string.onb_skip
                            }
                        )
                    )
                }
            }
        }
    }
}

// --- Pomocníci: stav a přesměrování do systémových nastavení ---

private fun isNotifGranted(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun isBatteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBattery(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: Exception) {
            openAppDetails(context)
        }
    }
}

private fun openAutostart(context: Context) {
    for (component in autostartComponents()) {
        try {
            context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Tahle komponenta na zařízení není - zkus další.
        }
    }
    // Žádná OEM obrazovka autostartu (např. čistý Android) - otevři detail aplikace,
    // kde je „Povolit aktivitu na pozadí" / „Bez omezení".
    openAppDetails(context)
}

/**
 * Známé obrazovky autostartu/správy pozadí napříč výrobci. Nejdřív ty
 * odpovídající výrobci zařízení, pak zbytek jako fallback; co na zařízení není,
 * `startActivity` vyhodí výjimku a přeskočí se.
 */
private fun autostartComponents(): List<ComponentName> {
    val byVendor = linkedMapOf(
        "xiaomi" to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        "samsung" to listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")
        ),
        "huawei" to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        ),
        "honor" to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        ),
        "oppo" to listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
        ),
        "realme" to listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        ),
        "vivo" to listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        ),
        "oneplus" to listOf(
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        ),
        "letv" to listOf(
            ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        ),
        "asus" to listOf(
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
        ),
        "meizu" to listOf(
            ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
        )
    )
    val id = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    val ordered = LinkedHashSet<ComponentName>()
    byVendor.forEach { (vendor, comps) -> if (id.contains(vendor)) ordered.addAll(comps) }
    byVendor.values.forEach { ordered.addAll(it) }  // zbytek jako fallback
    return ordered.toList()
}

private fun openAppNotificationSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        openAppDetails(context)
    }
}

private fun openAppDetails(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        // Bez nastavení nic - nespadneme.
    }
}
