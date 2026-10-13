package com.gng.core

import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TimetableTest {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private val monday = LocalDateTime.of(2026, 10, 12, 0, 0)

    private val me = listOf(
        ClassSlot(MONDAY, t(9), t(10), "DSA", "SPS-12"),
        ClassSlot(MONDAY, t(11), t(13), "OOPS Lab"),
    )
    private val friend = listOf(
        ClassSlot(MONDAY, t(10), t(11), "Maths"),
        ClassSlot(MONDAY, t(14), t(15), "DBMS"),
        ClassSlot(TUESDAY, t(9), t(18), "Workshop"),
    )

    @Test
    fun statusInClass() {
        val s = Timetable.status(me, monday.withHour(9).withMinute(30))
        assertIs<ClassStatus.InClass>(s)
        assertEquals("📖 in DSA till 10am", Timetable.label(s))
    }

    @Test
    fun statusFreeBetweenClasses() {
        assertEquals("🟢 free till 11am", Timetable.label(Timetable.status(me, monday.withHour(10).withMinute(15))))
    }

    @Test
    fun classEndIsExclusive() {
        assertIs<ClassStatus.Free>(Timetable.status(me, monday.withHour(10)))
    }

    @Test
    fun statusDone() {
        assertEquals(ClassStatus.DoneForToday, Timetable.status(me, monday.withHour(16)))
    }

    @Test
    fun commonWindowsMergeEveryone() {
        val windows = Timetable.commonFreeWindows(listOf(me, friend), MONDAY)
        assertEquals(listOf(t(13) to t(14), t(15) to t(18)), windows.map { it.start to it.end })
    }

    @Test
    fun shortGapsAreDropped() {
        val a = listOf(ClassSlot(MONDAY, t(9), t(12, 45), "X"), ClassSlot(MONDAY, t(13), t(18), "Y"))
        assertEquals(emptyList(), Timetable.commonFreeWindows(listOf(a), MONDAY))
    }

    @Test
    fun fullDayBusy() {
        assertEquals(emptyList(), Timetable.commonFreeWindows(listOf(friend), TUESDAY))
    }

    @Test
    fun rejectsBackwardsSlot() {
        assertFailsWith<IllegalArgumentException> { ClassSlot(MONDAY, t(10), t(9), "Oops") }
    }

    @Test
    fun formatsTimes() {
        assertEquals("12pm", t(12).hhmm())
        assertEquals("1:30pm", t(13, 30).hhmm())
        assertEquals("12:05am", t(0, 5).hhmm())
    }
}
