package com.gng.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BirthdaysTest {
    @Test
    fun laterThisYear() {
        assertEquals(LocalDate.of(2026, 12, 1), Birthdays.nextOccurrence(LocalDate.of(2005, 12, 1), LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun alreadyPassedRollsToNextYear() {
        assertEquals(LocalDate.of(2027, 3, 5), Birthdays.nextOccurrence(LocalDate.of(2005, 3, 5), LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun leapDayInNonLeapYear() {
        assertEquals(LocalDate.of(2027, 2, 28), Birthdays.nextOccurrence(LocalDate.of(2004, 2, 29), LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun upcomingSortsAndComputesAge() {
        val today = LocalDate.of(2026, 10, 13)
        val list = listOf(
            Birthday("a", "Aarav", LocalDate.of(2005, 11, 1)),
            Birthday("b", "Bhavya", LocalDate.of(2006, 10, 13)),
            Birthday("c", "Chirag", LocalDate.of(2005, 5, 1)),
        )
        val up = Birthdays.upcoming(list, today, withinDays = 30)
        assertEquals(listOf("b", "a"), up.map { it.birthday.userId })
        assertEquals(true, up[0].isToday)
        assertEquals(20, up[0].turning)
        assertEquals("🎂 Bhavya turns 20 TODAY!", Birthdays.label(up[0]))
    }
}
