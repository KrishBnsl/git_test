package com.gng.core

import java.time.Duration
import java.time.Instant

/** What to show for a person: where they are (if known), how long ago, and whether that is stale. */
data class PresenceStatus(val spotName: String?, val ago: String, val stale: Boolean)

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

    fun describe(zoneName: String?, updatedAt: Instant, now: Instant): PresenceStatus =
        PresenceStatus(zoneName, ago(updatedAt, now), Duration.between(updatedAt, now) > STALE_AFTER)

    fun isLive(liveUntil: Instant?, now: Instant): Boolean = liveUntil != null && liveUntil.isAfter(now)
}
