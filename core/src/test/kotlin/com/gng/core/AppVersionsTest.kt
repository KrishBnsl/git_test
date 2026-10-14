package com.gng.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionsTest {
    @Test
    fun parsesTagsAndSuffixes() {
        assertEquals(listOf(0, 2, 0), AppVersions.parse("v0.2.0"))
        assertEquals(listOf(0, 1, 1), AppVersions.parse("0.1.01"))
        assertEquals(listOf(1, 2, 3), AppVersions.parse(" V1.2.3-beta+5 "))
        assertEquals(listOf(7), AppVersions.parse("7"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(AppVersions.parse(""))
        assertNull(AppVersions.parse("latest"))
        assertNull(AppVersions.parse("1..2"))
        assertNull(AppVersions.parse("1.x"))
    }

    @Test
    fun newerMeansNumericallyNewer() {
        assertTrue(AppVersions.isNewer("v0.2.0", "0.1.01"))
        assertTrue(AppVersions.isNewer("0.10.0", "0.9.0")) // not string order
        assertTrue(AppVersions.isNewer("1.0.1", "1.0"))
        assertTrue(AppVersions.isNewer("2", "1.9.9"))
    }

    @Test
    fun equalOrOlderIsNotNewer() {
        assertFalse(AppVersions.isNewer("0.1.01", "0.1.1"))
        assertFalse(AppVersions.isNewer("1.0", "1.0.0"))
        assertFalse(AppVersions.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun unparseableNeverTriggersAnUpdate() {
        assertFalse(AppVersions.isNewer("latest", "0.1.0"))
        assertFalse(AppVersions.isNewer("0.2.0", "dev"))
    }
}
