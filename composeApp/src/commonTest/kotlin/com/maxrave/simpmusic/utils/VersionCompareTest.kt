package com.maxrave.simpmusic.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La comparación de versiones del actualizador.
 *
 * Comparar como texto hace que "1.4.10" quede por debajo de "1.4.9", y el aviso
 * de versión nueva no saldría nunca. Por eso se comparan los números uno a uno.
 */
class VersionCompareTest {
    @Test
    fun tenIsNewerThanNine() {
        assertTrue(compareVersions("1.4.10", "1.4.9") > 0)
    }

    @Test
    fun nineIsOlderThanTen() {
        assertTrue(compareVersions("1.4.9", "1.4.10") < 0)
    }

    @Test
    fun sameVersionIsEqual() {
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertTrue(compareVersions("1.0.1", "1.0") > 0)
    }

    @Test
    fun majorNumberWins() {
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(compareVersions("1.99.99", "2.0.0") < 0)
    }

    @Test
    fun suffixIsIgnored() {
        assertEquals(0, compareVersions("1.4.2-hf", "1.4.2"))
        assertTrue(compareVersions("1.4.3-hf", "1.4.2") > 0)
    }

    @Test
    fun releaseTagIsNewerThanTheInstalledBuild() {
        // Lo que hace el actualizador: tag "v1.4.10" contra la 1.4.9 instalada.
        val release = "v1.4.10".removePrefix("v")
        assertTrue(compareVersions(release, "1.4.9") > 0)
        // Y no ofrece nada cuando ya está al día ni cuando va por delante.
        assertTrue(compareVersions("1.4.9", "1.4.9") <= 0)
        assertTrue(compareVersions("1.4.2", "1.4.9") <= 0)
    }
}
