package com.example.cryptochat.ui.qr

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Sken QR kódu se má vždy zobrazit na výšku (portrait). Výchozí
 * CaptureActivity z knihovny zxing-android-embedded se jinak podle senzoru
 * umí otočit na šířku (landscape) - tahle podtřída je v AndroidManifest.xml
 * zamčená na portrait (`android:screenOrientation="portrait"`).
 */
class PortraitCaptureActivity : CaptureActivity()

/**
 * Jednotné nastavení skeneru QR kódu, sdílené všemi obrazovkami (dřív bylo
 * nakopírované na několika místech): jen formát QR, bez pípnutí a vždy na
 * výšku přes [PortraitCaptureActivity].
 *
 * `setOrientationLocked(false)` schválně nechává řízení orientace na
 * manifestu (portrait) - kdyby zůstalo `true`, knihovna by si orientaci
 * zamkla podle senzoru v okamžiku spuštění, což je právě příčina nechtěného
 * otočení na šířku.
 */
fun buildQrScanOptions(prompt: String): ScanOptions =
    ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt(prompt)
        setBeepEnabled(false)
        setOrientationLocked(false)
        setCaptureActivity(PortraitCaptureActivity::class.java)
    }
