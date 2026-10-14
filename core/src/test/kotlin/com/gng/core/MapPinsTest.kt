package com.gng.core

import java.time.Instant
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapPinsTest {
    private val now = Instant.parse("2026-10-13T08:00:00Z")
    private val library = LatLng(28.7511, 77.1180)
    private val here = LatLng(28.7502, 77.1172)

    private fun source(id: String, live: LatLng? = null, spot: LatLng? = null, minutesAgo: Long = 5) =
        MapSource(id, live, spot, spot?.let { "Library" }, now.minusSeconds(minutesAgo * 60))

    @Test
    fun liveBeatsSpot() {
        val pin = MapRules.pins(listOf(source("a", live = here, spot = library)), now).single()
        assertEquals(here, pin.point)
        assertTrue(pin.exact)
    }

    @Test
    fun spotModeIsDrawnOnTheSpotNotTheRealPosition() {
        val pin = MapRules.pins(listOf(source("a", spot = library)), now).single()
        assertEquals(library, pin.point)
        assertFalse(pin.exact)
        assertEquals("Library", pin.spotName)
    }

    @Test
    fun nobodyWithoutAPositionIsDrawn() {
        assertTrue(MapRules.pins(listOf(source("a")), now).isEmpty())
    }

    @Test
    fun oldCheckInsDropOffAndStaleOnesAreFlagged() {
        val pins = MapRules.pins(
            listOf(source("fresh", spot = library, minutesAgo = 10), source("stale", spot = library, minutesAgo = 120), source("gone", spot = library, minutesAgo = 7 * 60)),
            now,
        )
        assertEquals(listOf("fresh", "stale"), pins.map { it.userId })
        assertFalse(pins[0].stale)
        assertTrue(pins[1].stale)
        assertEquals("2h ago", pins[1].ago)
    }

    @Test
    fun aLonePinIsNotMoved() {
        val pins = MapRules.pins(listOf(source("a", spot = library)), now)
        assertEquals(0.0 to 0.0, MapRules.fanOut(pins)["a"])
    }

    @Test
    fun distantPinsAreNotMoved() {
        val pins = MapRules.pins(listOf(source("a", spot = library), source("b", live = here)), now)
        val offsets = MapRules.fanOut(pins)
        assertEquals(0.0 to 0.0, offsets["a"])
        assertEquals(0.0 to 0.0, offsets["b"])
    }

    @Test
    fun twoOnTheSameSpotSitSideBySide() {
        val pins = MapRules.pins(listOf(source("b", spot = library), source("a", spot = library)), now)
        val offsets = MapRules.fanOut(pins)
        val a = assertNotNull(offsets["a"])
        val b = assertNotNull(offsets["b"])
        assertEquals(0.0, a.second, 1e-9)
        assertEquals(-a.first, b.first, 1e-9)
        assertTrue(abs(a.first - b.first) >= 0.8)
    }

    @Test
    fun manyOnTheSameSpotNeverOverlap() {
        val pins = MapRules.pins((1..6).map { source("u$it", spot = library) }, now)
        val offsets = MapRules.fanOut(pins).values.toList()
        for (i in offsets.indices) for (j in i + 1 until offsets.size) {
            val d = hypot(offsets[i].first - offsets[j].first, offsets[i].second - offsets[j].second)
            assertTrue(d >= 0.8, "avatars $i and $j are only $d apart")
        }
    }

    @Test
    fun fanOutIsStableWhenOrderChanges() {
        val one = MapRules.fanOut(MapRules.pins(listOf(source("a", spot = library), source("b", spot = library), source("c", spot = library)), now))
        val two = MapRules.fanOut(MapRules.pins(listOf(source("c", spot = library), source("a", spot = library), source("b", spot = library)), now))
        assertEquals(one, two)
    }

    @Test
    fun boundsCoverAllPoints() {
        val bounds = assertNotNull(MapRules.bounds(listOf(library, here)))
        assertEquals(here.lat, bounds.south)
        assertEquals(library.lat, bounds.north)
        assertEquals(here.lng, bounds.west)
        assertEquals(library.lng, bounds.east)
        assertNull(MapRules.bounds(emptyList()))
    }
}
