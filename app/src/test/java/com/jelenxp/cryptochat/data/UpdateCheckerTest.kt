package com.jelenxp.cryptochat.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Testy porovnávání verzí (čistý JVM). */
class UpdateCheckerTest {

    @Test
    fun novejsi_verze() {
        assertTrue(UpdateChecker.compareVersions("2.2", "2.1") > 0)
        assertTrue(UpdateChecker.compareVersions("2.10", "2.9") > 0) // numericky, ne textově
        assertTrue(UpdateChecker.compareVersions("3.0", "2.9") > 0)
        assertTrue(UpdateChecker.compareVersions("2.1.1", "2.1") > 0)
    }

    @Test
    fun starsi_verze() {
        assertTrue(UpdateChecker.compareVersions("2.0", "2.1") < 0)
        assertTrue(UpdateChecker.compareVersions("2.9", "2.10") < 0)
    }

    @Test
    fun stejne_verze() {
        assertTrue(UpdateChecker.compareVersions("2.1", "2.1") == 0)
        assertTrue(UpdateChecker.compareVersions("2.1", "2.1.0") == 0) // chybějící složka = 0
    }
}
