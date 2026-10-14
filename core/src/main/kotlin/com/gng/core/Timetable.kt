package com.gng.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

data class ClassSlot(
    val day: DayOfWeek,
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val room: String? = null,
) {
    init {
        require(end.isAfter(start)) { "Class must end after it starts" }
    }

    fun contains(time: LocalTime) = !time.isBefore(start) && time.isBefore(end)
}

data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    val minutes: Long get() = java.time.Duration.between(start, end).toMinutes()
}

sealed interface ClassStatus {
    data class InClass(val slot: ClassSlot) : ClassStatus
    data class Free(val nextClass: ClassSlot) : ClassStatus
    data object DoneForToday : ClassStatus
}

object Timetable {
    fun status(slots: List<ClassSlot>, now: LocalDateTime): ClassStatus {
        val today = slots.filter { it.day == now.dayOfWeek }.sortedBy { it.start }
        val time = now.toLocalTime()
        today.firstOrNull { it.contains(time) }?.let { return ClassStatus.InClass(it) }
        val next = today.firstOrNull { it.start.isAfter(time) }
        return if (next == null) ClassStatus.DoneForToday else ClassStatus.Free(next)
    }

    /**
     * Windows on [day] between [from] and [to] where nobody in [people] has a class
     * and that last at least [minMinutes]. Powers "when can we all grab lunch?".
     */
    fun commonFreeWindows(
        people: List<List<ClassSlot>>,
        day: DayOfWeek,
        from: LocalTime = LocalTime.of(9, 0),
        to: LocalTime = LocalTime.of(18, 0),
        minMinutes: Long = 30,
    ): List<TimeWindow> {
        val busy = people.flatten()
            .filter { it.day == day && it.end.isAfter(from) && it.start.isBefore(to) }
            .sortedBy { it.start }

        val windows = mutableListOf<TimeWindow>()
        var cursor = from
        for (slot in busy) {
            if (slot.start.isAfter(cursor)) windows += TimeWindow(cursor, slot.start)
            if (slot.end.isAfter(cursor)) cursor = slot.end
        }
        if (to.isAfter(cursor)) windows += TimeWindow(cursor, to)
        return windows.filter { it.minutes >= minMinutes }
    }
}

fun LocalTime.hhmm(): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "am" else "pm"
    return if (minute == 0) "$h$suffix" else "$h:%02d$suffix".format(minute)
}
