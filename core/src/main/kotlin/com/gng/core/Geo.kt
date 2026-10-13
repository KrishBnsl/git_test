package com.gng.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** A named circle on campus ("Library", "Mic Mac", "Hostel") that a check-in snaps to. */
data class CampusZone(
    val id: String,
    val name: String,
    val icon: String,
    val center: LatLng,
    val radiusMeters: Int,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Returns the zone the point falls in. When zones overlap the most specific (smallest) one wins,
     * so a "Mic Mac" zone inside the big "Campus" zone takes priority; ties go to the closer centre.
     */
    fun resolveZone(point: LatLng, zones: List<CampusZone>): CampusZone? =
        zones
            .map { it to distanceMeters(point, it.center) }
            .filter { (zone, d) -> d <= zone.radiusMeters }
            .minWithOrNull(compareBy({ it.first.radiusMeters }, { it.second }))
            ?.first

    fun prettyDistance(meters: Double): String = when {
        meters < 50 -> "right here"
        meters < 1000 -> "${(meters / 10).toInt() * 10} m away"
        else -> "%.1f km away".format(meters / 1000)
    }
}
