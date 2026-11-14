package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReminderTest {
  @Test fun `recorded action retains day and reset when delivered days later`() {
    val now = Instant.parse("2026-11-13T01:00:00Z")
    val result = recordHabitAction("owner", "habit", now, LocalTime.of(3, 0), true,
      listOf(ActivePeriod(LocalDate.of(2026, 11, 1))), true) as HabitActionResult.Recorded
    assertEquals(LocalDate.of(2026, 11, 12), result.action.day)
    assertEquals(LocalTime.of(3, 0), result.action.resetTime)
    assertEquals(now, result.action.recordedAt)
    // Processing never recomputes the stored action's day using a later reset.
    assertNotEquals(result.action.day, HabitClock.day(now.plusSeconds(3 * 86400), LocalTime.MIDNIGHT).date)
  }
  @Test fun `old habit notice opens current day without recording either completion`() {
    val result = recordHabitAction("owner", "habit", Instant.parse("2026-11-13T12:00:00Z"), LocalTime.MIDNIGHT,
      true, listOf(ActivePeriod(LocalDate.of(2026, 11, 1))), true, LocalDate.of(2026, 11, 12))
    assertEquals(HabitActionResult.OpenCurrentDay, result)
  }
  @Test fun `inactive and future habits cannot be recorded`() {
    val now = Instant.parse("2026-11-13T12:00:00Z")
    assertThrows(IllegalArgumentException::class.java) { recordHabitAction("owner", "habit", now, LocalTime.MIDNIGHT, false, emptyList(), true) }
    assertThrows(IllegalArgumentException::class.java) { recordHabitAction("owner", "habit", now, LocalTime.MIDNIGHT, true, listOf(ActivePeriod(LocalDate.of(2027, 1, 1))), true) }
  }
  @Test fun `relative reminder follows task and snooze does not mutate schedule`() {
    val task = Task(title = "Estudiar", date = LocalDate.of(2026, 11, 13), start = LocalTime.of(18, 0), end = LocalTime.of(19, 0), allDay = false)
    val rule = ReminderRule.BeforeStart()
    assertEquals(Instant.parse("2026-11-13T16:50:00Z"), taskReminder(task, rule))
    assertEquals(Instant.parse("2026-11-13T17:50:00Z"), taskReminder(task.copy(start = LocalTime.of(19, 0), end = LocalTime.of(20, 0)), rule))
    assertNull(taskReminder(task.copy(completed = true), rule))
    assertEquals(Instant.parse("2026-11-13T17:00:00Z"), postponeReminder(taskReminder(task, rule)!!))
    assertEquals(LocalTime.of(18, 0), task.start)
  }
  @Test fun `all day requires chosen time and permissions have explicit fallback`() {
    val task = Task(title = "Leer", date = LocalDate.of(2026, 11, 13))
    assertThrows(IllegalArgumentException::class.java) { taskReminder(task, ReminderRule.BeforeStart()) }
    assertEquals(Instant.parse("2026-11-13T08:00:00Z"), taskReminder(task, ReminderRule.AtTime(LocalTime.of(9, 0))))
    assertEquals(ReminderCapability.DISABLED, reminderCapability(false, true))
    assertEquals(ReminderCapability.APPROXIMATE, reminderCapability(true, false))
    assertEquals(ReminderCapability.PRECISE, reminderCapability(true, true))
  }
}
