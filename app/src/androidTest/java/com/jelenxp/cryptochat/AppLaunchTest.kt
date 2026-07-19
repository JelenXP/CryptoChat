package com.jelenxp.cryptochat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentovaný UI test (běží na emulátoru v CI, workflow `ui-tests.yml`).
 * Ověří, že se aplikace opravdu spustí a vykreslí hlavní obrazovku - odchytí
 * pády při startu (motiv, navigace, Compose strom), které unit testy nezachytí.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appSeSpustiAZobraziHlavniObrazovku() {
        // Název appky v horní liště je stejný ve všech jazycích - robustní kontrola.
        composeRule.onNodeWithText("CryptoChat").assertIsDisplayed()
    }
}
