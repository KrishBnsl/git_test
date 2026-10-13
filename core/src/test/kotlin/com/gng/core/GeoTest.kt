package com.gng.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {
    private val campus = CampusZone("campus", "DTU", "school", LatLng(28.7500, 77.1170), 900)
    private val canteen = CampusZone("canteen", "Mic Mac", "restaurant", LatLng(28.7502, 77.1172), 40)

    @Test
    fun distanceIsRoughlyRight() {
        // ~111 m per 0.001 degree of latitude
        val d = Geo.distanceMeters(LatLng(28.75, 77.117), LatLng(28.751, 77.117))
        assertTrue(d in 105.0..117.0, "got $d")
    }

    @Test
    fun smallerNearbyZoneWins() {
        assertEquals("canteen", Geo.resolveZone(LatLng(28.75021, 77.11721), listOf(campus, canteen))?.id)
    }

    @Test
    fun fallsBackToBigZone() {
        assertEquals("campus", Geo.resolveZone(LatLng(28.7530, 77.1170), listOf(campus, canteen))?.id)
    }

    @Test
    fun outsideEverything() {
        assertNull(Geo.resolveZone(LatLng(28.6139, 77.2090), listOf(campus, canteen)))
    }
}
