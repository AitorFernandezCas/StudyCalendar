package com.studycalendar.core

import java.time.*

data class HabitDay(val date: LocalDate, val startsAt: Instant, val endsAt: Instant)

object HabitClock {
  val zone: ZoneId = ZoneId.of("Europe/Madrid")

  // Match SystemClock: first repeated time; missing wall times advance to the
  // first valid minute, rather than Java's default shift by the DST gap.
  fun boundary(date: LocalDate, reset: LocalTime): Instant {
    var wall = date.atTime(reset)
    while (zone.rules.getValidOffsets(wall).isEmpty()) wall = wall.plusMinutes(1)
    return wall.toInstant(zone.rules.getValidOffsets(wall).first())
  }

  fun day(now: Instant, reset: LocalTime = LocalTime.MIDNIGHT): HabitDay {
    val calendarDate = now.atZone(zone).toLocalDate()
    val date = if (now < boundary(calendarDate, reset)) calendarDate.minusDays(1) else calendarDate
    return HabitDay(date, boundary(date, reset), boundary(date.plusDays(1), reset))
  }
}

data class ActivePeriod(val start: LocalDate, val end: LocalDate? = null) {
  fun includes(date: LocalDate) = date >= start && (end == null || date <= end)
}
data class Streak(val current: Int, val maximum: Int)

fun streaks(today: LocalDate, active: Boolean, periods: List<ActivePeriod>, completed: Set<LocalDate>): Streak {
  // Validated completions remain history even after the active period is closed.
  // Match routine_summaries: periods determine eligibility, not historical runs.
  val days = completed.filter { it <= today }.sorted()
  var run = 0
  var maximum = 0
  var previous: LocalDate? = null
  days.forEach { date ->
    run = if (previous?.plusDays(1) == date) run + 1 else 1
    maximum = maxOf(maximum, run)
    previous = date
  }
  val due = active && periods.any { it.includes(today) }
  val current = if (due && (previous == today || previous == today.minusDays(1))) run else 0
  return Streak(current, maximum)
}
