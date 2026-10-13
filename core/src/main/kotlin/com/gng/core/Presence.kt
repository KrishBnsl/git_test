package com.gng.core

import java.time.Duration
import java.time.Instant

object Presence {
    /** Check-ins older than this are shown as "last seen" rather than "at". */
    val STALE_AFTER: Duration = Duration.ofMinutes(45)

    fun ago(then: Instant, now: Instant): String {
        val d = Duration.between(then, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            else -> "${d.toDays()}d ago"
        }
    }

    fun label(zoneName: String?, zoneEmoji: String?, updatedAt: Instant, now: Instant): String {
        val where = if (zoneName == null) "📍 off the map" else "${zoneEmoji ?: "📍"} $zoneName"
        val stale = Duration.between(updatedAt, now) > STALE_AFTER
        return if (stale) "last seen $where · ${ago(updatedAt, now)}" else "$where · ${ago(updatedAt, now)}"
    }

    fun isLive(liveUntil: Instant?, now: Instant): Boolean = liveUntil != null && liveUntil.isAfter(now)
}
