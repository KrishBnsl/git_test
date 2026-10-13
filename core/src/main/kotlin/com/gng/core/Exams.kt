package com.gng.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Exams {
    fun daysLeft(examDate: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, examDate)

    /** How worried the squad should be, as fire emojis. */
    fun panicMeter(daysLeft: Long): String = when {
        daysLeft < 0 -> "✅"
        daysLeft == 0L -> "💀"
        daysLeft <= 2 -> "🔥🔥🔥"
        daysLeft <= 7 -> "🔥🔥"
        daysLeft <= 14 -> "🔥"
        else -> "😌"
    }

    fun countdownLabel(daysLeft: Long): String = when {
        daysLeft < 0 -> "done"
        daysLeft == 0L -> "today"
        daysLeft == 1L -> "tomorrow"
        else -> "in $daysLeft days"
    }
}
