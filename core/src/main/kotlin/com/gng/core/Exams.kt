package com.gng.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How worried the squad should be. The UI picks colours and icons for each level. */
enum class Urgency { Done, Today, Critical, Soon, Upcoming, Relaxed }

object Exams {
    fun daysLeft(examDate: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, examDate)

    fun urgency(daysLeft: Long): Urgency = when {
        daysLeft < 0 -> Urgency.Done
        daysLeft == 0L -> Urgency.Today
        daysLeft <= 2 -> Urgency.Critical
        daysLeft <= 7 -> Urgency.Soon
        daysLeft <= 14 -> Urgency.Upcoming
        else -> Urgency.Relaxed
    }

    fun countdownLabel(daysLeft: Long): String = when {
        daysLeft < 0 -> "done"
        daysLeft == 0L -> "today"
        daysLeft == 1L -> "tomorrow"
        else -> "in $daysLeft days"
    }
}
