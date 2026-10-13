package com.gng.core

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/** What we know about where one person is. Either field may be missing. */
data class MapSource(
    val userId: String,
    /** Exact position, only present while the person is sharing live. */
    val live: LatLng?,
    /** Centre of the spot they are checked in at (Library, Mic Mac...). */
    val spot: LatLng?,
    val spotName: String?,
    val updatedAt: Instant,
)

/** One avatar to draw on the map. */
data class MapPin(
    val userId: String,
    val point: LatLng,
    /** True for a live position; false when the avatar sits on a pinned spot. */
    val exact: Boolean,
    val spotName: String?,
    val ago: String,
    val stale: Boolean,
)

data class MapBounds(val south: Double, val west: Double, val north: Double, val east: Double)

object MapRules {
    /** People who have not checked in for this long drop off the map. */
    val HIDE_AFTER: Duration = Duration.ofHours(6)

    /**
     * Live position beats spot position. Someone in spot mode is drawn on their spot, never at
     * their real position. Nobody with neither, or with an old check-in, is drawn.
     */
    fun pins(sources: List<MapSource>, now: Instant): List<MapPin> = sources.mapNotNull { s ->
        val age = Duration.between(s.updatedAt, now)
        if (age > HIDE_AFTER) return@mapNotNull null
        val (point, exact) = when {
            s.live != null -> s.live to true
            s.spot != null -> s.spot to false
            else -> return@mapNotNull null
        }
        MapPin(s.userId, point, exact, s.spotName, Presence.ago(s.updatedAt, now), stale = age > Presence.STALE_AFTER)
    }

    /**
     * Pins within a few metres of each other would hide one another, so they are spread on a ring.
     * Offsets are in avatar widths, x to the right and y downwards, keyed by user id. A lone pin
     * gets (0, 0).
     */
    fun fanOut(pins: List<MapPin>): Map<String, Pair<Double, Double>> {
        val result = mutableMapOf<String, Pair<Double, Double>>()
        pins.groupBy { clusterKey(it.point) }.values.forEach { cluster ->
            val sorted = cluster.sortedBy { it.userId } // stable, so avatars don't swap places on refresh
            val n = sorted.size
            if (n == 1) {
                result[sorted[0].userId] = 0.0 to 0.0
                return@forEach
            }
            val radius = 0.425 / sin(PI / n) // neighbours end up 0.85 avatar widths apart
            val start = if (n == 2) 0.0 else -PI / 2
            sorted.forEachIndexed { i, pin ->
                val angle = start + 2 * PI * i / n
                result[pin.userId] = radius * cos(angle) to radius * sin(angle)
            }
        }
        return result
    }

    fun bounds(points: List<LatLng>): MapBounds? {
        if (points.isEmpty()) return null
        return MapBounds(
            south = points.minOf { it.lat }, west = points.minOf { it.lng },
            north = points.maxOf { it.lat }, east = points.maxOf { it.lng },
        )
    }

    /** Roughly 11 m grid. */
    private fun clusterKey(p: LatLng) = (p.lat * 10_000).roundToLong() to (p.lng * 10_000).roundToLong()
}
