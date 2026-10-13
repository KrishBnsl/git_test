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
        assertEquals("🔥🔥🔥", Exams.panicMeter(left))
        assertEquals("in 2 days", Exams.countdownLabel(left))
        assertEquals("✅", Exams.panicMeter(-1))
    }

    @Test
    fun presence() {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        assertEquals("🍟 Mic Mac · 5m ago", Presence.label("Mic Mac", "🍟", now.minusSeconds(300), now))
        assertEquals("last seen 📚 Library · 2h ago", Presence.label("Library", "📚", now.minusSeconds(7200), now))
        assertEquals(false, Presence.isLive(now.minusSeconds(1), now))
    }
}
