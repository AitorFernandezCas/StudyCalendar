package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*

class GoalsTest {
  @Test fun calendarPeriodsCrossYearsAndLeapDays() {
    assertEquals(LocalDate.parse("2026-12-28"), GoalPeriod.WEEK.start(LocalDate.parse("2027-01-01")))
    assertEquals(LocalDate.parse("2024-02-29"), GoalPeriod.MONTH.end(LocalDate.parse("2024-02-01")))
    assertEquals(LocalDate.parse("2026-12-31"), GoalPeriod.YEAR.end(LocalDate.parse("2026-01-01")))
  }
  @Test fun reviewsSuppressOnlyTheirGoalAndWindow() {
    val now = Instant.parse("2026-11-23T20:00:00Z")
    val goal = Goal("a", "Leer", GoalPeriod.WEEK, LocalDate.parse("2026-11-23"), BigDecimal.TEN)
    val second = goal.copy(id = "b")
    val schedule = GoalSchedule("s", GoalPeriod.WEEK, enabled = true)
    val review = GoalReview("r", "a", now, BigDecimal.ONE)
    assertEquals(listOf(second), GoalReminders.due(listOf(goal, second), listOf(review), listOf(schedule), now))
    assertEquals(2, GoalReminders.due(listOf(goal, second), listOf(review), listOf(schedule), now.plusSeconds(86400)).size)
    assertTrue(GoalReminders.due(listOf(goal.copy(achieved = true)), emptyList(), listOf(schedule), now).isEmpty())
    assertTrue(GoalReminders.due(listOf(goal), emptyList(), listOf(schedule), now.plusSeconds(7 * 86400)).isEmpty())
  }
  @Test fun schedulesFollowMadridAndShortMonths() {
    val schedule = GoalSchedule("s", GoalPeriod.YEAR, true, ReviewFrequency.MONTHLY)
    assertEquals(Instant.parse("2024-02-29T19:00:00Z"), GoalReminders.next(listOf(schedule), Instant.parse("2024-02-01T00:00:00Z")))
    val daily = GoalSchedule("s", GoalPeriod.WEEK, true, time = LocalTime.of(20, 0))
    assertEquals(Instant.parse("2026-03-29T18:00:00Z"), GoalReminders.next(listOf(daily), Instant.parse("2026-03-28T20:00:00Z")))
  }
}
