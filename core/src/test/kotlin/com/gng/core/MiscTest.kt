package com.gng.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiscTest {
    @Test
    fun inviteCodes() {
        assertEquals("ABC123", InviteCodes.normalize(" abc-123 "))
        assertEquals("ABC123", InviteCodes.normalize("gng:abc 123"))
        assertNull(InviteCodes.normalize("abc12"))
    }

    @Test
    fun examPanic() {
        val today = LocalDate.of(2026, 10, 13)
        val left = Exams.daysLeft(LocalDate.of(2026, 10, 15), today)
        assertEquals(2, left)
        assertEquals(Urgency.Critical, Exams.urgency(left))
        assertEquals("in 2 days", Exams.countdownLabel(left))
        assertEquals(Urgency.Done, Exams.urgency(-1))
        assertEquals(Urgency.Today, Exams.urgency(0))
        assertEquals(Urgency.Soon, Exams.urgency(7))
        assertEquals(Urgency.Relaxed, Exams.urgency(15))
    }

    @Test
    fun presence() {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        assertEquals(PresenceStatus("Mic Mac", "5m ago", false), Presence.describe("Mic Mac", now.minusSeconds(300), now))
        assertEquals(PresenceStatus("Library", "2h ago", true), Presence.describe("Library", now.minusSeconds(7200), now))
        assertEquals(PresenceStatus(null, "just now", false), Presence.describe(null, now, now))
        assertEquals(false, Presence.isLive(now.minusSeconds(1), now))
    }
}
