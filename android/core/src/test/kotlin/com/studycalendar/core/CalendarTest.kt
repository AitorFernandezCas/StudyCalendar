package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CalendarTest {
  private val day = LocalDate.of(2026, 11, 16)
  private fun task(start: String, end: String) = Task(title = "Trabajo", date = day, start = LocalTime.parse(start), end = LocalTime.parse(end), allDay = false)
  @Test fun lateCreationKeepsTheSelectedStart() {
    val draft = CalendarLayout.draft(day, 1425)
    assertEquals(LocalTime.of(23, 45), draft.start)
    assertEquals(LocalTime.of(23, 59), draft.end)
    assertFalse(draft.allDay)
    assertTrue(CalendarLayout.draft(day, null).allDay)
  }
  @Test fun weekAndMonthCrossYearBoundaries() {
    assertEquals(LocalDate.of(2025, 12, 29), CalendarLayout.week(LocalDate.of(2026, 1, 1), false))
    assertEquals(LocalDate.of(2025, 12, 28), CalendarLayout.week(LocalDate.of(2026, 1, 1), true))
    val days = CalendarLayout.month(LocalDate.of(2026, 2, 1), false)
    assertEquals(42, days.size)
    assertEquals(28, days.count { it.monthValue == 2 })
    assertEquals(days.first().plusDays(41), days.last())
  }
  @Test fun lateMovePreservesDurationAndOtherFields() {
    val original = task("09:00", "11:00").copy(completed = true, categoryId = "category", projectId = "project", projectTask = true)
    val moved = CalendarLayout.move(original, day.plusDays(1), 1430)!!
    assertEquals(LocalTime.of(21, 45), moved.start)
    assertEquals(LocalTime.of(23, 45), moved.end)
    assertEquals(original.copy(date = moved.date, start = moved.start, end = moved.end), moved)
  }
  @Test fun conversionsAndMonthMovement() {
    val original = task("09:00", "09:30")
    val allDay = CalendarLayout.move(original, day, null, true)!!
    assertTrue(allDay.allDay)
    assertEquals(original.start, allDay.start)
    val timed = CalendarLayout.move(allDay, day, 611)!!
    assertEquals(LocalTime.of(10, 15), timed.start)
    assertEquals(LocalTime.of(11, 15), timed.end)
    assertFalse(timed.allDay)
    assertEquals(original.copy(date = day.plusDays(1)), CalendarLayout.move(original, day.plusDays(1), null))
  }
  @Test fun narrowRangeAndBoundaryVisibility() {
    val settings = CalendarSettings(480, 1200)
    assertFalse(CalendarLayout.visible(task("07:00", "08:00"), settings))
    assertTrue(CalendarLayout.visible(task("07:30", "08:15"), settings))
    assertFalse(CalendarLayout.visible(task("20:00", "21:00"), settings))
    assertTrue(CalendarLayout.visible(task("22:00", "23:00").copy(allDay = true), settings))
  }
  @Test fun overlappingGroupsUseIndependentColumns() {
    val a = task("09:00", "10:00")
    val b = task("09:30", "11:00")
    val c = task("10:00", "10:30")
    val d = task("11:00", "12:00")
    val lanes = CalendarLayout.lanes(listOf(d, c, b, a)).associateBy { it.task.id }
    assertEquals(2, lanes[a.id]!!.columns)
    assertNotEquals(lanes[a.id]!!.column, lanes[b.id]!!.column)
    assertEquals(lanes[a.id]!!.column, lanes[c.id]!!.column)
    assertEquals(1, lanes[d.id]!!.columns)
  }
  @Test fun settingsAllowMidnightOnlyAsRangeEnd() {
    assertEquals(1440, CalendarLayout.parse("24:00"))
    assertEquals(CalendarSettings(), CalendarSettings(0, 1440, false))
    listOf("25:00", "12:60", "1:00", "24:01").forEach {
      try { CalendarLayout.parse(it); fail(it) } catch (_: IllegalArgumentException) { }
    }
    try { CalendarSettings(600, 500); fail() } catch (_: IllegalArgumentException) { }
  }
}
