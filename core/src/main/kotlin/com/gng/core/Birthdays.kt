package com.gng.core

import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit

data class Birthday(val userId: String, val name: String, val date: LocalDate)

data class UpcomingBirthday(val birthday: Birthday, val next: LocalDate, val daysAway: Int, val turning: Int) {
    val isToday get() = daysAway == 0
}

object Birthdays {
    /** Next occurrence on or after [today]. Feb 29 birthdays are celebrated on Feb 28 in non-leap years. */
    fun nextOccurrence(birthday: LocalDate, today: LocalDate): LocalDate {
        fun inYear(year: Int): LocalDate {
            val md = MonthDay.from(birthday)
            return if (md.isValidYear(year)) md.atYear(year) else LocalDate.of(year, 2, 28)
        }
        val thisYear = inYear(today.year)
        return if (thisYear.isBefore(today)) inYear(today.year + 1) else thisYear
    }

    fun upcoming(list: List<Birthday>, today: LocalDate, withinDays: Int = 60): List<UpcomingBirthday> =
        list.map {
            val next = nextOccurrence(it.date, today)
            UpcomingBirthday(it, next, ChronoUnit.DAYS.between(today, next).toInt(), next.year - it.date.year)
        }
            .filter { it.daysAway <= withinDays }
            .sortedBy { it.daysAway }

    fun label(b: UpcomingBirthday): String = when (b.daysAway) {
        0 -> "🎂 ${b.birthday.name} turns ${b.turning} TODAY!"
        1 -> "🎁 ${b.birthday.name}'s birthday is tomorrow"
        in 2..7 -> "🎈 ${b.birthday.name} in ${b.daysAway} days"
        else -> "📅 ${b.birthday.name} in ${b.daysAway} days"
    }
}
