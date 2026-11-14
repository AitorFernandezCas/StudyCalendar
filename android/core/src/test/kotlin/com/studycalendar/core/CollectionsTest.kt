package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CollectionsTest {
  private val today = LocalDate.of(2026, 11, 13)

  @Test fun `project names are unique ignoring case and surrounding whitespace`() {
    val project = Project(name = "Curso")
    project.validate(listOf(project))
    assertThrows(IllegalArgumentException::class.java) { Project(name = " curso ").validate(listOf(project)) }
    assertThrows(IllegalArgumentException::class.java) { Project(name = " ").validate(emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { project.copy(color = "red").validate(emptyList()) }
  }

  @Test fun `pausing preserves completed history and reactivation after missed day breaks streak`() {
    val routine = Routine(title = "Leer", periods = listOf(ActivePeriod(today.minusDays(5))))
    val completed = setOf(today.minusDays(1), today)
    val paused = routine.activation(false, today)
    assertFalse(paused.due(today))
    assertEquals(today.minusDays(1), paused.periods.single().end)
    assertEquals(Streak(0, 2), streaks(today, paused.active, paused.periods, completed))
    val resumed = paused.activation(true, today.plusDays(2))
    assertEquals(routine.id, resumed.id)
    assertTrue(resumed.due(today.plusDays(2)))
    assertEquals(Streak(0, 2), streaks(today.plusDays(2), resumed.active, resumed.periods, completed))
    assertEquals(Streak(1, 2), streaks(today.plusDays(2), resumed.active, resumed.periods, completed + today.plusDays(2)))
  }

  @Test fun `future routines are not due and pausing future start removes unused period`() {
    val routine = Routine(title = "Leer", periods = listOf(ActivePeriod(today.plusDays(3))))
    assertFalse(routine.due(today))
    assertTrue(routine.due(today.plusDays(3)))
    val paused = routine.activation(false, today)
    assertTrue(paused.periods.isEmpty())
    assertTrue(paused.activation(true, today).due(today))
  }

  @Test fun `same day pause and resume retains completion and max can decrease after unmark`() {
    val routine = Routine(title = "Leer", periods = listOf(ActivePeriod(today)))
    val resumed = routine.activation(false, today).activation(true, today)
    assertEquals(Streak(1, 1), streaks(today, true, resumed.periods, setOf(today)))
    assertEquals(Streak(0, 0), streaks(today, true, resumed.periods, emptySet()))
  }
}
